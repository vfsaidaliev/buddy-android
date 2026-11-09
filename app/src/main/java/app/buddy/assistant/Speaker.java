package app.buddy.assistant;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Speech out for the voice assistant: the phone's text-to-speech in the language of the command (ru, en, ar: Egyptian
 * when there's a voice for it, else another Arabic). Texts said before it's ready wait for it.
 */
final class Speaker {
    private static TextToSpeech tts;
    private static boolean ready;
    private static final List<String[]> waiting = new ArrayList<>();

    private Speaker() {
    }

    /** Says text in this language (ru-RU, en-US, ar-EG). */
    static synchronized void say(Context c, String text, String lang) {
        if (text == null || text.trim().isEmpty()) return;
        if (tts == null) {
            tts = new TextToSpeech(c.getApplicationContext(), status -> {
                synchronized (Speaker.class) {
                    ready = status == TextToSpeech.SUCCESS;
                    if (ready) for (String[] w : waiting) speak(w[0], w[1]);
                    waiting.clear();
                }
            });
        }
        if (!ready) {
            waiting.add(new String[]{text, lang});
            return;
        }
        speak(text, lang);
    }

    static synchronized void stop() {
        waiting.clear();
        if (tts != null && ready) tts.stop();
    }

    static synchronized boolean speaking() {
        return tts != null && ready && tts.isSpeaking();
    }

    private static void speak(String text, String lang) {
        Locale want = Locale.forLanguageTag(lang);
        Voice v = voice(want);
        if (v != null) tts.setVoice(v);
        else if (tts.isLanguageAvailable(want) >= TextToSpeech.LANG_AVAILABLE) tts.setLanguage(want);
        else tts.setLanguage(new Locale(want.getLanguage()));
        tts.speak(clean(text), TextToSpeech.QUEUE_ADD, new Bundle(), "voice-" + System.nanoTime());
    }

    /** The best installed voice for the language, the same country first (ar-EG before other Arabic). */
    static Voice voice(Locale want) {
        java.util.Set<Voice> all;
        try {
            all = tts.getVoices();
        } catch (Exception e) {
            return null;
        }
        if (all == null) return null;
        Voice best = null;
        int bestScore = -1;
        for (Voice v : all) {
            if (!v.getLocale().getLanguage().equals(want.getLanguage())) continue;
            if (v.getFeatures() != null && v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
            int score = v.getQuality() + (v.getLocale().getCountry().equals(want.getCountry()) ? 1000 : 0)
                    + (v.isNetworkConnectionRequired() ? 0 : 100);
            if (score > bestScore) {
                bestScore = score;
                best = v;
            }
        }
        return best;
    }

    /** Without markdown and links, which sound bad read aloud. */
    static String clean(String t) {
        return t.replaceAll("```[\\s\\S]*?```", " ").replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                .replaceAll("https?://\\S+", "").replaceAll("[*_`#>]", "").replaceAll("\\s+", " ").trim();
    }
}
