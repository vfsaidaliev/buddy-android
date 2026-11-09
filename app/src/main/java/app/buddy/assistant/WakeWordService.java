package app.buddy.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.File;
import java.util.Locale;

/**
 * The wake phrase (off by default): listens on the phone, with Vosk and a grammar of only that phrase, for "Валькирия"
 * (Settings → Voice), and opens voice input when it's said. A foreground service of the microphone type with a
 * notification the whole time. Pauses itself below 15 % battery, in battery saver, during calls, and while voice input
 * has the microphone. Android lets it start only from the app on screen, so after a restart the app has to be opened.
 */
public class WakeWordService extends Service {
    static final String CHANNEL = "wake", ACTION_STOP = "app.buddy.assistant.WAKE_STOP";
    private static final int ID = 7302, LOW_BATTERY = 15;
    private static WakeWordService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private org.vosk.android.SpeechService listening;
    private boolean loading;
    private String paused; // why it's paused, or null
    private boolean voiceOpen;
    private BroadcastReceiver power;
    private AudioManager.OnModeChangedListener modes;

    // ------------------------------------------------------------ settings

    static boolean enabled(Context c) {
        return c.getSharedPreferences("voice", MODE_PRIVATE).getBoolean("wake", false);
    }

    static String phrase(Context c) {
        return c.getSharedPreferences("voice", MODE_PRIVATE).getString("wake_phrase", "валькирия");
    }

    static void setPhrase(Context c, String p) {
        c.getSharedPreferences("voice", MODE_PRIVATE).edit().putString("wake_phrase",
                p.trim().toLowerCase(Locale.ROOT).replace('ё', 'е')).apply();
    }

    /** The Vosk model the phrase needs: Russian for Cyrillic, English for Latin. */
    static String lang(String phrase) {
        return phrase.matches(".*[а-яё].*") ? "ru" : "en";
    }

    /**
     * Words of the phrase this Vosk model doesn't know (it would never hear them); empty if it knows them all or the
     * model doesn't list its words.
     */
    static java.util.List<String> unknownWords(Context c, String phrase) {
        java.util.List<String> out = new java.util.ArrayList<>();
        File words = new File(VoskModels.dir(c, lang(phrase)), "graph/words.txt");
        if (!words.exists()) return out;
        java.util.Set<String> want = new java.util.HashSet<>(java.util.Arrays.asList(phrase.split("\\s+")));
        java.util.Set<String> found = new java.util.HashSet<>();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(words))) {
            String line;
            while ((line = r.readLine()) != null) {
                String w = line.split(" ")[0];
                if (want.contains(w)) found.add(w);
            }
        } catch (Exception e) {
            return out;
        }
        for (String w : want) if (!found.contains(w)) out.add(w);
        return out;
    }

    /** On or off; on starts it now (from the app on screen, as Android requires for the microphone). */
    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences("voice", MODE_PRIVATE).edit().putBoolean("wake", on).apply();
        Intent i = new Intent(c, WakeWordService.class);
        if (on) c.startForegroundService(i);
        else c.stopService(i);
    }

    static boolean running() {
        return instance != null;
    }

    /** Voice input takes the microphone: stop listening until it's done. */
    static void voiceOpened() {
        WakeWordService s = instance;
        if (s != null) s.main.post(() -> {
            s.voiceOpen = true;
            s.update();
        });
    }

    static void voiceClosed() {
        WakeWordService s = instance;
        if (s != null) s.main.post(() -> {
            s.voiceOpen = false;
            s.update();
        });
    }

    /** Why it's paused right now (shown in Settings → Voice), or null. */
    static String pausedWhy() {
        WakeWordService s = instance;
        return s == null ? null : s.paused;
    }

    // ------------------------------------------------------------- service

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.wake_channel), NotificationManager.IMPORTANCE_LOW));
        try {
            startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } catch (Exception e) { // not allowed from the background (after a restart): the app has to be opened
            stopSelf();
            return;
        }
        power = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                update();
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        f.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        registerReceiver(power, f);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            modes = mode -> update();
            getSystemService(AudioManager.class).addOnModeChangedListener(getMainExecutor(), modes);
        }
        update();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            setEnabled(this, false);
            return START_NOT_STICKY;
        }
        update();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopListening();
        if (power != null) unregisterReceiver(power);
        if (modes != null && android.os.Build.VERSION.SDK_INT >= 31) {
            getSystemService(AudioManager.class).removeOnModeChangedListener(modes);
        }
        instance = null;
        super.onDestroy();
    }

    /** Listens, or pauses, as the battery, battery saver, calls and voice input allow. */
    private void update() {
        String why = null;
        Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = bat == null ? 100 : bat.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) * 100
                / Math.max(1, bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
        int mode = getSystemService(AudioManager.class).getMode();
        if (level < LOW_BATTERY) why = getString(R.string.wake_paused_battery, LOW_BATTERY);
        else if (getSystemService(PowerManager.class).isPowerSaveMode()) why = getString(R.string.wake_paused_saver);
        else if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) why = getString(R.string.wake_paused_call);
        else if (voiceOpen) why = getString(R.string.wake_paused_voice);
        else if (!VoskModels.installed(this, lang(phrase(this)))) why = getString(R.string.wake_paused_model);
        boolean changed = why == null ? paused != null : !why.equals(paused);
        paused = why;
        if (why != null) stopListening();
        else if (listening == null) startListening();
        if (changed) getSystemService(NotificationManager.class).notify(ID, notification());
    }

    private void startListening() {
        if (loading) return;
        loading = true;
        String phrase = phrase(this);
        String lang = lang(phrase);
        new Thread(() -> {
            try {
                org.vosk.Model model = VoskModels.model(this, lang);
                String grammar = new org.json.JSONArray().put(phrase).put("[unk]").toString();
                org.vosk.Recognizer rec = new org.vosk.Recognizer(model, 16000f, grammar);
                main.post(() -> {
                    loading = false;
                    if (paused != null || listening != null || instance != this) {
                        rec.close();
                        return;
                    }
                    try {
                        listening = new org.vosk.android.SpeechService(rec, 16000f);
                    } catch (Exception e) {
                        rec.close();
                        return;
                    }
                    listening.startListening(new org.vosk.android.RecognitionListener() {
                        @Override
                        public void onPartialResult(String json) {
                            if (SpeechInput.field(json, "partial").contains(phrase)) heard();
                        }

                        @Override
                        public void onResult(String json) {
                            if (SpeechInput.field(json, "text").contains(phrase)) heard();
                        }

                        @Override
                        public void onFinalResult(String json) {
                        }

                        @Override
                        public void onError(Exception e) {
                            main.postDelayed(WakeWordService.this::restart, 2000);
                        }

                        @Override
                        public void onTimeout() {
                        }
                    });
                });
            } catch (Exception e) {
                main.post(() -> {
                    loading = false;
                    paused = String.valueOf(e.getMessage());
                    getSystemService(NotificationManager.class).notify(ID, notification());
                });
            }
        }, "wake-load").start();
    }

    private void restart() {
        stopListening();
        update();
    }

    private void stopListening() {
        if (listening != null) {
            try {
                listening.cancel();
                listening.shutdown();
            } catch (Exception ignored) {
            }
            listening = null;
        }
    }

    /** The phrase was said: voice input opens and takes the microphone. */
    private void heard() {
        main.post(() -> {
            if (listening == null) return;
            voiceOpen = true;
            stopListening();
            Intent i = new Intent(this, VoiceActivity.class).putExtra("listen", true).putExtra("wake", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
            } catch (Exception e) {
                voiceOpen = false;
            }
            update();
            // voice input closes (or never opened): listening again after a while at the latest
            main.postDelayed(() -> {
                if (!VoiceActivityOpen.open) voiceClosed();
            }, 8000);
        });
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, VoiceSettingsActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, WakeWordService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        String text = paused != null ? getString(R.string.wake_paused, paused) : getString(R.string.wake_listening, phrase(this));
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(getString(R.string.wake_title)).setContentText(text).setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, getString(R.string.wake_turn_off), stop).build())
                .build();
    }

    /** Whether voice input is on screen (VoiceActivity sets it). */
    static final class VoiceActivityOpen {
        static volatile boolean open;

        private VoiceActivityOpen() {
        }
    }
}
