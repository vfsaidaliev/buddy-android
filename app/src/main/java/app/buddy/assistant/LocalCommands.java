package app.buddy.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Does the voice commands CommandParser understood, on the phone itself, without Claude and without a network.
 * Calling, SMS and the camera ask on Buddy's card first; the rest the user asked for out loud, so it's done at once.
 * run() says false only when it can't be done here after all (an app that isn't on the phone): then it's the agent's.
 */
final class LocalCommands {
    private static final Handler main = new Handler(Looper.getMainLooper());

    private LocalCommands() {
    }

    private static String t(VoiceCommand.Host h, int id, Object... args) {
        return VoiceCommand.t(h.activity(), h.lang(), id, args);
    }

    private static void bg(Runnable r) {
        new Thread(r, "voice-local").start();
    }

    private static void sayOnMain(VoiceCommand.Host h, String s) {
        main.post(() -> h.say(s));
    }

    static boolean run(VoiceCommand.Host h, Command c) {
        Activity a = h.activity();
        switch (c.type) {
            case CALL: call(h, c.target); return true;
            case SMS: sms(h, c.target, c.text); return true;
            case ALARM: alarm(h, c.hour, c.minute); return true;
            case TIMER: timer(h, c.seconds); return true;
            case REMINDER: reminder(h, c); return true;
            case FLASHLIGHT: {
                String r = DeviceTools.torch(a, c.on);
                h.say(r.startsWith("The flashlight is") ? t(h, c.on ? R.string.vc_torch_on : R.string.vc_torch_off)
                        : t(h, R.string.vc_torch_failed));
                return true;
            }
            case VOLUME: volume(h, c); return true;
            case RINGER: ringer(h, c.ringer); return true;
            case BRIGHTNESS: brightness(h, c); return true;
            case OPEN_APP: return openApp(h, c.target);
            case PHOTO: photo(h, c.front); return true;
            case WIFI:
            case BLUETOOTH:
            case MOBILE_DATA: radio(h, c.type, c.on); return true;
            case TIME:
                h.say(t(h, R.string.vc_time, DateFormat.getTimeInstance(DateFormat.SHORT, locale(h)).format(new Date())));
                return true;
            case DATE:
                h.say(t(h, R.string.vc_date, DateFormat.getDateInstance(DateFormat.FULL, locale(h)).format(new Date())));
                return true;
            case BATTERY: {
                BatteryManager bm = a.getSystemService(BatteryManager.class);
                int pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                h.say(t(h, R.string.vc_battery, pct) + (bm.isCharging() ? " " + t(h, R.string.vc_charging) : ""));
                return true;
            }
            default: // the translator comes with 9.3
                return Translator.run(h, c);
        }
    }

    static Locale locale(VoiceCommand.Host h) {
        return Locale.forLanguageTag(h.lang());
    }

    // ----------------------------------------------------------- contacts

    interface Picked {
        void got(String name, String number);
    }

    /** {name → numbers} of every contact with a phone number. */
    static Map<String, List<String>> contacts(Context c) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        try (Cursor cur = c.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER},
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {
            while (cur != null && cur.moveToNext()) {
                String name = cur.getString(0), num = cur.getString(1);
                if (name == null || num == null) continue;
                List<String> l = out.get(name);
                if (l == null) out.put(name, l = new ArrayList<>());
                String n = num.replaceAll("[^0-9+]", "");
                boolean have = false;
                for (String x : l) have |= x.replaceAll("[^0-9+]", "").equals(n);
                if (!have) l.add(num);
            }
        }
        return out;
    }

    /** A spoken name or number to one number: a choice when several contacts or numbers fit. */
    static void resolve(VoiceCommand.Host h, String target, Picked p) {
        Activity a = h.activity();
        if (target.matches("[+0-9]{3,}")) {
            p.got(null, target);
            return;
        }
        if (a.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            h.say(t(h, R.string.vc_no_contacts_access));
            return;
        }
        Map<String, List<String>> all = contacts(a);
        List<String> names = new ArrayList<>(all.keySet());
        List<Integer> best = Fuzzy.best(target, names);
        if (best.isEmpty()) {
            h.say(t(h, R.string.vc_contact_not_found, target));
            return;
        }
        if (best.size() == 1) {
            number(h, names.get(best.get(0)), all.get(names.get(best.get(0))), p);
            return;
        }
        List<String> options = new ArrayList<>();
        for (int i : best) options.add(names.get(i));
        h.choose(t(h, R.string.vc_which_contact), options, v -> {
            String name = options.get(Integer.parseInt(v));
            number(h, name, all.get(name), p);
        });
    }

    private static void number(VoiceCommand.Host h, String name, List<String> numbers, Picked p) {
        if (numbers.size() == 1) {
            p.got(name, numbers.get(0));
            return;
        }
        h.choose(t(h, R.string.vc_which_number, name), numbers, v -> p.got(name, numbers.get(Integer.parseInt(v))));
    }

    private static String who(String name, String number) {
        return name == null ? number : name + " (" + number + ")";
    }

    private static void call(VoiceCommand.Host h, String target) {
        resolve(h, target, (name, number) -> h.confirm(t(h, R.string.vc_call_title), who(name, number), () -> {
            Activity a = h.activity();
            if (a.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                h.say(t(h, R.string.vc_no_permission, a.getString(R.string.access_phone)));
                return;
            }
            try {
                a.startActivity(new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)));
                h.say(t(h, R.string.vc_calling, who(name, number)));
            } catch (Exception e) {
                h.say(t(h, R.string.vc_failed, String.valueOf(e.getMessage())));
            }
        }));
    }

    private static void sms(VoiceCommand.Host h, String target, String text) {
        resolve(h, target, (name, number) -> h.confirm(t(h, R.string.vc_sms_title),
                t(h, R.string.vc_sms_msg, who(name, number), text), () -> {
                    Activity a = h.activity();
                    if (a.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                        h.say(t(h, R.string.vc_no_permission, a.getString(R.string.access_sms)));
                        return;
                    }
                    Context app = a.getApplicationContext();
                    bg(() -> {
                        int r;
                        try {
                            r = DeviceTools.sendSms(app, number, text);
                        } catch (Exception e) {
                            r = DeviceTools.SMS_FAILED;
                        }
                        sayOnMain(h, t(h, r == DeviceTools.SMS_SENT ? R.string.vc_sms_sent : r == DeviceTools.SMS_WAITING
                                ? R.string.vc_sms_waiting : R.string.vc_sms_failed, who(name, number)));
                    });
                }));
    }

    // ------------------------------------------------------- clock things

    private static String hm(VoiceCommand.Host h, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        return DateFormat.getTimeInstance(DateFormat.SHORT, locale(h)).format(c.getTime());
    }

    private static String length(VoiceCommand.Host h, long seconds) {
        long hr = seconds / 3600, min = seconds % 3600 / 60, sec = seconds % 60;
        StringBuilder b = new StringBuilder();
        if (hr > 0) b.append(t(h, R.string.vc_hours, hr)).append(' ');
        if (min > 0) b.append(t(h, R.string.vc_minutes, min)).append(' ');
        if (sec > 0) b.append(t(h, R.string.vc_seconds, sec));
        return b.toString().trim();
    }

    private static void alarm(VoiceCommand.Host h, int hour, int minute) {
        try {
            h.activity().startActivity(new Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, minute).putExtra(AlarmClock.EXTRA_SKIP_UI, true));
            h.say(t(h, R.string.vc_alarm_set, hm(h, hour, minute)));
        } catch (Exception e) {
            h.say(t(h, R.string.vc_failed, t(h, R.string.vc_no_clock_app)));
        }
    }

    private static void timer(VoiceCommand.Host h, long seconds) {
        try {
            h.activity().startActivity(new Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, (int) Math.min(86400, seconds)).putExtra(AlarmClock.EXTRA_SKIP_UI, true));
            h.say(t(h, R.string.vc_timer_set, length(h, seconds)));
        } catch (Exception e) {
            h.say(t(h, R.string.vc_failed, t(h, R.string.vc_no_clock_app)));
        }
    }

    private static void reminder(VoiceCommand.Host h, Command c) {
        long at;
        if (c.seconds > 0) {
            at = System.currentTimeMillis() + c.seconds * 1000;
        } else {
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, c.hour);
            cal.set(Calendar.MINUTE, c.minute);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            if (cal.getTimeInMillis() <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_MONTH, 1);
            at = cal.getTimeInMillis();
        }
        ReminderReceiver.schedule(h.activity(), at, c.text);
        h.say(t(h, R.string.vc_reminder_set, DateFormat.getTimeInstance(DateFormat.SHORT, locale(h)).format(new Date(at)), c.text));
    }

    // --------------------------------------------------- sound and screen

    private static int stream(String s) {
        return "ring".equals(s) ? AudioManager.STREAM_RING : "alarm".equals(s) ? AudioManager.STREAM_ALARM : AudioManager.STREAM_MUSIC;
    }

    private static void volume(VoiceCommand.Host h, Command c) {
        AudioManager am = h.activity().getSystemService(AudioManager.class);
        int st = stream(c.stream);
        try {
            if (c.delta != 0) {
                am.adjustStreamVolume(st, c.delta > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                am.adjustStreamVolume(st, c.delta > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
            } else {
                am.setStreamVolume(st, Math.round(c.level * am.getStreamMaxVolume(st) / 100f), AudioManager.FLAG_SHOW_UI);
            }
            int max = am.getStreamMaxVolume(st);
            h.say(t(h, R.string.vc_volume, max > 0 ? Math.round(am.getStreamVolume(st) * 100f / max) : 0));
        } catch (SecurityException e) { // Do Not Disturb holds the ring volume
            h.say(t(h, R.string.vc_no_permission, h.activity().getString(R.string.access_dnd)));
        }
    }

    private static void ringer(VoiceCommand.Host h, String mode) {
        AudioManager am = h.activity().getSystemService(AudioManager.class);
        int m = "silent".equals(mode) ? AudioManager.RINGER_MODE_SILENT : "vibrate".equals(mode)
                ? AudioManager.RINGER_MODE_VIBRATE : AudioManager.RINGER_MODE_NORMAL;
        if (m == AudioManager.RINGER_MODE_SILENT && !FullAccess.dndGranted(h.activity())) {
            h.say(t(h, R.string.vc_no_permission, h.activity().getString(R.string.access_dnd)));
            return;
        }
        try {
            am.setRingerMode(m);
            h.say(t(h, m == AudioManager.RINGER_MODE_SILENT ? R.string.vc_ringer_silent
                    : m == AudioManager.RINGER_MODE_VIBRATE ? R.string.vc_ringer_vibrate : R.string.vc_ringer_normal));
        } catch (SecurityException e) {
            h.say(t(h, R.string.vc_no_permission, h.activity().getString(R.string.access_dnd)));
        }
    }

    private static void brightness(VoiceCommand.Host h, Command c) {
        Activity a = h.activity();
        if (!Settings.System.canWrite(a)) {
            h.say(t(h, R.string.vc_no_permission, a.getString(R.string.access_write_settings)));
            return;
        }
        android.content.ContentResolver cr = a.getContentResolver();
        if (c.auto) {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
            h.say(t(h, R.string.vc_brightness_auto));
            return;
        }
        int now = Math.round(Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128) * 100f / 255);
        int pct = c.delta != 0 ? Math.max(0, Math.min(100, now + c.delta * 20)) : c.level;
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, Math.max(1, Math.round(pct * 255f / 100)));
        h.say(t(h, R.string.vc_brightness, pct));
    }

    // --------------------------------------------------------------- apps

    private static boolean openApp(VoiceCommand.Host h, String target) {
        Activity a = h.activity();
        PackageManager pm = a.getPackageManager();
        List<ResolveInfo> apps = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        List<String> labels = new ArrayList<>(), pkgs = new ArrayList<>();
        for (ResolveInfo r : apps) {
            String l = String.valueOf(r.loadLabel(pm));
            if (labels.contains(l)) continue;
            labels.add(l);
            pkgs.add(r.activityInfo.packageName);
        }
        List<Integer> best = Fuzzy.best(target, labels);
        if (best.isEmpty()) return false; // not an app here: maybe the agent knows what was meant
        if (best.size() == 1) {
            launch(h, labels.get(best.get(0)), pkgs.get(best.get(0)));
            return true;
        }
        List<String> options = new ArrayList<>();
        for (int i : best) options.add(labels.get(i));
        h.choose(t(h, R.string.vc_which_app), options, v -> {
            int i = best.get(Integer.parseInt(v));
            launch(h, labels.get(i), pkgs.get(i));
        });
        return true;
    }

    private static void launch(VoiceCommand.Host h, String label, String pkg) {
        Intent i = h.activity().getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            h.say(t(h, R.string.vc_failed, label));
            return;
        }
        h.say(t(h, R.string.vc_opening, label));
        h.activity().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    // ------------------------------------------------------------- camera

    private static void photo(VoiceCommand.Host h, boolean front) {
        Activity a = h.activity();
        String which = a.getString(front ? R.string.dev_camera_front : R.string.dev_camera_back);
        h.confirm(t(h, R.string.vc_photo_title), a.getString(R.string.dev_card_snapshot, which), () -> {
            Context app = a.getApplicationContext();
            bg(() -> {
                File shot = FilesProvider.snapshotFile(app);
                shot.delete();
                Object r;
                try {
                    r = PhoneToolActivity.run(app, PhoneToolActivity.SNAPSHOT, front ? "front" : "back", 45);
                } catch (InterruptedException e) {
                    return;
                }
                if (!Boolean.TRUE.equals(r) || shot.length() == 0) {
                    sayOnMain(h, t(h, R.string.vc_photo_failed, r instanceof String ? (String) r : ""));
                    return;
                }
                String folder = "Pictures/" + app.getString(R.string.app_name);
                try {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                            Locale.ROOT).format(new Date()) + ".jpg");
                    v.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, folder);
                    Uri u = app.getContentResolver().insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
                    if (u == null) throw new java.io.IOException("MediaStore");
                    try (InputStream in = new FileInputStream(shot); OutputStream o = app.getContentResolver().openOutputStream(u)) {
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                    }
                    sayOnMain(h, t(h, R.string.vc_photo_saved, folder));
                } catch (Exception e) {
                    sayOnMain(h, t(h, R.string.vc_photo_failed, String.valueOf(e.getMessage())));
                } finally {
                    shot.delete();
                }
            });
        });
    }

    // ------------------------------------------------------------- radios

    /** Wi-Fi, Bluetooth, mobile data: through Shizuku when it's running and allowed, else Android's settings page. */
    private static void radio(VoiceCommand.Host h, Command.Type type, boolean on) {
        Activity a = h.activity();
        String name = t(h, type == Command.Type.WIFI ? R.string.vc_wifi : type == Command.Type.BLUETOOTH
                ? R.string.vc_bluetooth : R.string.vc_data);
        String state = t(h, on ? R.string.vc_on : R.string.vc_off);
        if (ShizukuShell.state(a) != ShizukuShell.State.READY) {
            panel(h, type, name);
            return;
        }
        String cmd = type == Command.Type.WIFI ? "svc wifi " + (on ? "enable" : "disable")
                : type == Command.Type.BLUETOOTH ? "cmd bluetooth_manager " + (on ? "enable" : "disable")
                + " || svc bluetooth " + (on ? "enable" : "disable")
                : "svc data " + (on ? "enable" : "disable");
        Context app = a.getApplicationContext();
        bg(() -> {
            String err = null;
            try {
                Object[] r = ShizukuShell.run(app, cmd, 20);
                if ((Integer) r[0] != 0) err = String.valueOf(r[1]).trim();
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String why = err;
            main.post(() -> {
                if (why == null) {
                    h.say(t(h, R.string.vc_radio_done, name, state));
                } else {
                    h.say(t(h, R.string.vc_radio_shizuku_failed, why.length() > 80 ? why.substring(0, 80) : why));
                    panel(h, type, name);
                }
            });
        });
    }

    private static void panel(VoiceCommand.Host h, Command.Type type, String name) {
        Intent i = new Intent(type == Command.Type.WIFI ? Settings.Panel.ACTION_WIFI : type == Command.Type.BLUETOOTH
                ? Settings.ACTION_BLUETOOTH_SETTINGS : Settings.Panel.ACTION_INTERNET_CONNECTIVITY);
        try {
            h.activity().startActivity(i);
            h.say(t(h, R.string.vc_radio_panel, name));
        } catch (Exception e) {
            h.say(t(h, R.string.vc_failed, name));
        }
    }
}
