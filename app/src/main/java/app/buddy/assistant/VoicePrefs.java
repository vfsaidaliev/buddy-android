package app.buddy.assistant;

import android.content.Context;
import android.content.SharedPreferences;

/** Buddy Full's voice assistant settings (Settings → Voice). */
final class VoicePrefs {
    /** The three languages: {tag, short label}. */
    static final String[][] LANGS = {{"ru-RU", "RU"}, {"en-US", "EN"}, {"ar-EG", "AR"}};
    static final String AUTO = "auto", ONLINE = "online", OFFLINE = "offline";

    private VoicePrefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("voice", Context.MODE_PRIVATE);
    }

    /** Speech recognition: auto (by the network), online only or offline only. */
    static String mode(Context c) {
        return sp(c).getString("mode", AUTO);
    }

    static void setMode(Context c, String m) {
        sp(c).edit().putString("mode", m).apply();
    }

    /** The language spoken to Buddy: ru-RU, en-US or ar-EG. */
    static String lang(Context c) {
        return sp(c).getString("lang", "ru-RU");
    }

    static void setLang(Context c, String l) {
        sp(c).edit().putString("lang", l).apply();
    }

    /** The voice chat: the last one a voice command went to, and when (see VoiceCommand). */
    static String chat(Context c) {
        return sp(c).getString("chat", null);
    }

    static long chatAt(Context c) {
        return sp(c).getLong("chat_at", 0);
    }

    static void setChat(Context c, String id, long at) {
        sp(c).edit().putString("chat", id).putLong("chat_at", at).apply();
    }
}
