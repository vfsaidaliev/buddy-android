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

    /** Valkyrie's voice for a language (ru, en, ar): "auto", "piper:<id>" or "system:<name>". */
    static String voice(Context c, String l) {
        return sp(c).getString("voice_" + l, "auto");
    }

    static void setVoice(Context c, String l, String v) {
        sp(c).edit().putString("voice_" + l, v).apply();
    }

    /** Low and hard by default. */
    static float pitch(Context c, String l) {
        return sp(c).getFloat("pitch_" + l, 0.8f);
    }

    static float rate(Context c, String l) {
        return sp(c).getFloat("rate_" + l, 0.95f);
    }

    static void setPitch(Context c, String l, float v) {
        sp(c).edit().putFloat("pitch_" + l, v).apply();
    }

    static void setRate(Context c, String l, float v) {
        sp(c).edit().putFloat("rate_" + l, v).apply();
    }

    /** Valkyrie's stern character (on by default). */
    static boolean harsh(Context c) {
        return sp(c).getBoolean("harsh", true);
    }

    static void setHarsh(Context c, boolean on) {
        sp(c).edit().putBoolean("harsh", on).apply();
    }

    /** Russian swearing, in moderation (on by default). */
    static boolean mat(Context c) {
        return sp(c).getBoolean("mat", true);
    }

    static void setMat(Context c, boolean on) {
        sp(c).edit().putBoolean("mat", on).apply();
    }

    /** Say the agent's replies to voice commands out loud. */
    static boolean speakReplies(Context c) {
        return sp(c).getBoolean("speak_replies", true);
    }

    static void setSpeakReplies(Context c, boolean on) {
        sp(c).edit().putBoolean("speak_replies", on).apply();
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
