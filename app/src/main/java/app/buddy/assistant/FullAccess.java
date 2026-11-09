package app.buddy.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * Buddy Full's extra access to the phone, in one list: the setup guide's "Files and phone" step and Check Buddy both
 * show it, so a permission is asked for and checked in the same way everywhere.
 */
final class FullAccess {
    /** One permission (or a group Android asks for together). */
    abstract static class Item {
        final int title, why;

        Item(int title, int why) {
            this.title = title;
            this.why = why;
        }

        abstract boolean granted(Context c);

        /** Opens Android's page or dialog for it; the activity's onResume / onRequestPermissionsResult re-checks. */
        abstract void request(Activity a);

        /** Optional: the setup step is done without it. */
        boolean optional() {
            return false;
        }

        /** Fine as far as Check Buddy goes: granted, or an optional one the user doesn't use. */
        boolean ok(Context c) {
            return granted(c);
        }

        /** Where it stands, when that's more than allowed / not yet (null: the "why" line says it). */
        String status(Context c) {
            return null;
        }
    }

    /** "All files access": every file in the phone's shared storage (/sdcard in Buddy's Linux). */
    static final Item FILES = new Item(R.string.access_files, R.string.access_files_why) {
        @Override
        boolean granted(Context c) {
            return filesGranted();
        }

        @Override
        void request(Activity a) {
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + a.getPackageName())));
            } catch (Exception e) { // some phones only have the list of apps
                a.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        }
    };

    /**
     * Permissions Android asks for in its own dialog. Asked once and refused for good (or greyed out as a "restricted
     * setting" for an app installed from a file), the dialog no longer shows: then Buddy's App info opens instead.
     */
    static final class RuntimePerm extends Item {
        final String key;
        final String[] perms;
        /** SMS and the call log: Android 13+ keeps them from apps installed from a file until the user allows it. */
        final boolean restricted;

        RuntimePerm(String key, int title, int why, boolean restricted, String... perms) {
            super(title, why);
            this.key = key;
            this.perms = perms;
            this.restricted = restricted;
        }

        @Override
        boolean granted(Context c) {
            for (String p : perms) if (c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
            return true;
        }

        @Override
        void request(Activity a) {
            android.content.SharedPreferences sp = a.getSharedPreferences("full_access", Context.MODE_PRIVATE);
            boolean asked = sp.getBoolean(key, false), dialog = !asked;
            for (String p : perms) dialog |= a.shouldShowRequestPermissionRationale(p);
            if (dialog) {
                sp.edit().putBoolean(key, true).apply();
                a.requestPermissions(perms, 41);
            } else {
                a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName())));
            }
        }
    }

    static final Item CONTACTS = new RuntimePerm("contacts", R.string.access_contacts, R.string.access_contacts_why, false,
            Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS);
    static final Item SMS = new RuntimePerm("sms", R.string.access_sms, R.string.access_sms_why, true,
            Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS);
    static final Item CALL_LOG = new RuntimePerm("call_log", R.string.access_call_log, R.string.access_call_log_why, true,
            Manifest.permission.READ_CALL_LOG);
    static final Item PHONE = new RuntimePerm("phone", R.string.access_phone, R.string.access_phone_why, false,
            Manifest.permission.CALL_PHONE);
    static final Item CALENDAR = new RuntimePerm("calendar", R.string.access_calendar, R.string.access_calendar_why, false,
            Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR);
    static final Item CAMERA = new RuntimePerm("camera", R.string.access_camera, R.string.access_camera_why, false,
            Manifest.permission.CAMERA);

    /** "Modify system settings": brightness and screen timeout. */
    static final Item WRITE_SETTINGS = new Item(R.string.access_write_settings, R.string.access_write_settings_why) {
        @Override
        boolean granted(Context c) {
            return Settings.System.canWrite(c);
        }

        @Override
        void request(Activity a) {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + a.getPackageName())));
        }
    };

    /** "Do Not Disturb access": needed to put the phone on silent. */
    static final Item DND = new Item(R.string.access_dnd, R.string.access_dnd_why) {
        @Override
        boolean granted(Context c) {
            return dndGranted(c);
        }

        @Override
        void request(Activity a) {
            a.startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
        }
    };

    /** Shizuku (optional): commands with ADB's rights for shell_privileged. */
    static final Item SHIZUKU = new Item(R.string.access_shizuku, R.string.access_shizuku_why) {
        @Override
        boolean granted(Context c) {
            return ShizukuShell.state(c) == ShizukuShell.State.READY;
        }

        @Override
        boolean optional() {
            return true;
        }

        @Override
        boolean ok(Context c) { // not installed: not used, so not a problem; installed but not ready: Fix
            ShizukuShell.State st = ShizukuShell.state(c);
            return st == ShizukuShell.State.READY || st == ShizukuShell.State.MISSING;
        }

        @Override
        String status(Context c) {
            switch (ShizukuShell.state(c)) {
                case MISSING: return c.getString(R.string.access_shizuku_missing);
                case STOPPED: return c.getString(R.string.access_shizuku_stopped);
                case OLD: return c.getString(R.string.access_shizuku_old);
                case DENIED: return c.getString(R.string.access_shizuku_denied);
                default: return null;
            }
        }

        @Override
        void request(Activity a) {
            ShizukuShell.State st = ShizukuShell.state(a);
            if (st == ShizukuShell.State.DENIED) {
                ShizukuShell.requestPermission();
                return;
            }
            Intent open = st == ShizukuShell.State.MISSING || st == ShizukuShell.State.OLD ? null
                    : a.getPackageManager().getLaunchIntentForPackage(ShizukuShell.PACKAGE);
            if (open == null) open = new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"));
            try {
                a.startActivity(open);
            } catch (Exception ignored) {
            }
        }
    };

    private FullAccess() {
    }

    static boolean filesGranted() {
        return Environment.isExternalStorageManager();
    }

    static boolean dndGranted(Context c) {
        return c.getSystemService(NotificationManager.class).isNotificationPolicyAccessGranted();
    }

    /** Everything, in the order shown. */
    static List<Item> all() {
        List<Item> l = new ArrayList<>();
        l.add(FILES);
        l.add(CONTACTS);
        l.add(SMS);
        l.add(CALL_LOG);
        l.add(PHONE);
        l.add(CALENDAR);
        l.add(CAMERA);
        l.add(WRITE_SETTINGS);
        l.add(DND);
        l.add(SHIZUKU);
        return l;
    }

    /** Whether any of the ones Android may grey out ("restricted settings") is still missing. */
    static boolean restrictedMissing(Context c) {
        for (Item i : all()) if (i instanceof RuntimePerm && ((RuntimePerm) i).restricted && !i.granted(c)) return true;
        return false;
    }

    static boolean allGranted(Context c) {
        for (Item i : all()) if (!i.optional() && !i.granted(c)) return false;
        return true;
    }
}
