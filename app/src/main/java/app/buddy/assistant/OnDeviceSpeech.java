package app.buddy.assistant;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Android's own on-device speech recognition (Android 12+; which languages, and downloading them, Android 13+): what
 * the phone can recognize offline, and which tag to ask for. Arabic: Egyptian (ar-EG) when the phone has it, else any
 * Arabic it has, and the settings say which one.
 */
final class OnDeviceSpeech {
    /** What the phone knows for on-device recognition. Lists of language tags, lower case. */
    static final class Support {
        final List<String> installed = new ArrayList<>(), pending = new ArrayList<>(), supported = new ArrayList<>();
        /** Not null: couldn't be asked (Android too old, no on-device recognizer, an error). */
        String error;
    }

    interface Callback {
        void done(Support s);
    }

    private OnDeviceSpeech() {
    }

    static boolean available(Context c) {
        return Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(c);
    }

    static Intent intent(String tag) {
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag);
    }

    /** Asks the on-device recognizer what it has for this language; cb on the main thread. */
    static void check(Context c, String tag, Callback cb) {
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            Support s = new Support();
            if (!available(c)) {
                s.error = "none";
                cb.done(s);
                return;
            }
            if (Build.VERSION.SDK_INT < 33) {
                s.error = "old";
                cb.done(s);
                return;
            }
            final SpeechRecognizer r = SpeechRecognizer.createOnDeviceSpeechRecognizer(c.getApplicationContext());
            final boolean[] answered = {false};
            Runnable timeout = () -> {
                if (answered[0]) return;
                answered[0] = true;
                r.destroy();
                s.error = "timeout";
                cb.done(s);
            };
            main.postDelayed(timeout, 5000);
            r.checkRecognitionSupport(intent(tag), c.getMainExecutor(), new android.speech.RecognitionSupportCallback() {
                @Override
                public void onSupportResult(android.speech.RecognitionSupport rs) {
                    if (answered[0]) return;
                    answered[0] = true;
                    main.removeCallbacks(timeout);
                    for (String l : rs.getInstalledOnDeviceLanguages()) s.installed.add(l.toLowerCase(Locale.ROOT));
                    for (String l : rs.getPendingOnDeviceLanguages()) s.pending.add(l.toLowerCase(Locale.ROOT));
                    for (String l : rs.getSupportedOnDeviceLanguages()) s.supported.add(l.toLowerCase(Locale.ROOT));
                    r.destroy();
                    cb.done(s);
                }

                @Override
                public void onError(int error) {
                    if (answered[0]) return;
                    answered[0] = true;
                    main.removeCallbacks(timeout);
                    r.destroy();
                    s.error = "error " + error;
                    cb.done(s);
                }
            });
        });
    }

    /**
     * The tag to recognize this language with offline, from those in the list (installed, or supported for a
     * download): the exact one, else the same language in another country (Arabic: ar-EG first, then any ar-*).
     * null if none.
     */
    static String pick(List<String> tags, String want) {
        String w = want.toLowerCase(Locale.ROOT), lang = w.split("-")[0];
        if (tags.contains(w)) return w;
        if (tags.contains(lang)) return lang;
        String best = null;
        for (String t : tags) if (t.split("-")[0].equals(lang) && (best == null || t.compareTo(best) < 0)) best = t;
        return best;
    }

    /** Asks Android to download this language for on-device recognition (Android 13+); it shows its own progress. */
    static boolean download(Context c, String tag) {
        if (!available(c) || Build.VERSION.SDK_INT < 33) return false;
        new Handler(Looper.getMainLooper()).post(() -> {
            SpeechRecognizer r = SpeechRecognizer.createOnDeviceSpeechRecognizer(c.getApplicationContext());
            try {
                r.triggerModelDownload(intent(tag));
            } catch (Exception ignored) {
            }
            new Handler(Looper.getMainLooper()).postDelayed(r::destroy, 3000);
        });
        return true;
    }
}
