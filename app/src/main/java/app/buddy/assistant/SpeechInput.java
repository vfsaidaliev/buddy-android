package app.buddy.assistant;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Speech in for Buddy Full's voice assistant. Picks the recognizer by Settings → Voice: online (Android's recognizer,
 * usually Google's), Android's on-device recognizer, or Vosk (Russian, English) when neither works offline. Auto mode
 * goes online when there's a network and offline when not. One listening at a time; callbacks on the main thread.
 */
final class SpeechInput {
    interface Callback {
        /** Listening; engine: which recognizer, for the screen. */
        void onReady(String engine);

        void onPartial(String text);

        void onFinal(String text);

        /** Stopped without words: why (shown and said). */
        void onError(String message);

        default void onLevel(float level) {
        }
    }

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static SpeechRecognizer recognizer;
    private static org.vosk.android.SpeechService vosk;
    private static Callback current;
    private static int session;

    private SpeechInput() {
    }

    static boolean listening() {
        return current != null;
    }

    /** Starts listening in this language (ru-RU, en-US, ar-EG), on the engine the settings and the network allow. */
    static void start(Context c, String lang, Callback cb) {
        cancel();
        current = cb;
        final int id = ++session;
        Context app = c.getApplicationContext();
        String mode = VoicePrefs.mode(app);
        boolean online = Net.online(app);
        if (VoicePrefs.ONLINE.equals(mode) || (VoicePrefs.AUTO.equals(mode) && online)) {
            if (!online) {
                fail(id, app.getString(R.string.voice_err_no_network_online));
                return;
            }
            android(app, id, lang, false);
            return;
        }
        // offline: Android's on-device recognizer if it has the language, else Vosk
        OnDeviceSpeech.check(app, lang, s -> {
            if (id != session) return;
            String tag = s.error == null ? OnDeviceSpeech.pick(s.installed, lang) : null;
            if (tag != null) {
                android(app, id, tag, true);
            } else if (VoskModels.installed(app, lang)) {
                vosk(app, id, lang);
            } else {
                fail(id, app.getString(R.string.voice_err_no_offline, languageName(app, lang)));
            }
        });
    }

    static String languageName(Context c, String tag) {
        return c.getString(tag.startsWith("ru") ? R.string.voice_lang_ru : tag.startsWith("en") ? R.string.voice_lang_en
                : R.string.voice_lang_ar);
    }

    /** Ends listening and gives what was heard (the recognizer decides when it's said). */
    static void finish() {
        if (recognizer != null) recognizer.stopListening();
        if (vosk != null) vosk.stop(); // gives its final result
    }

    /** Stops without a result. */
    static void cancel() {
        session++;
        current = null;
        if (recognizer != null) {
            try {
                recognizer.cancel();
                recognizer.destroy();
            } catch (Exception ignored) {
            }
            recognizer = null;
        }
        if (vosk != null) {
            try {
                vosk.cancel();
                vosk.shutdown();
            } catch (Exception ignored) {
            }
            vosk = null;
        }
    }

    private static void fail(int id, String msg) {
        if (id != session) return;
        Callback cb = current;
        cancel();
        if (cb != null) cb.onError(msg);
    }

    private static void done(int id, String text) {
        if (id != session) return;
        Callback cb = current;
        cancel();
        if (cb == null) return;
        if (text == null || text.trim().isEmpty()) cb.onError(BuddyApp.str(R.string.voice_didnt_catch));
        else cb.onFinal(text.trim());
    }

    // ------------------------------------------------------------ Android

    private static void android(Context app, int id, String tag, boolean onDevice) {
        main.post(() -> {
            if (id != session) return;
            try {
                android.content.ComponentName other = onDevice ? null : otherRecognizer(app);
                recognizer = onDevice && Build.VERSION.SDK_INT >= 31 ? SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
                        : other != null ? SpeechRecognizer.createSpeechRecognizer(app, other)
                        : SpeechRecognizer.createSpeechRecognizer(app);
            } catch (Exception e) {
                fail(id, app.getString(R.string.voice_no_recognizer));
                return;
            }
            final String[] partial = {""};
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle b) {
                    if (id == session && current != null) {
                        current.onReady(app.getString(onDevice ? R.string.voice_engine_device : R.string.voice_engine_online, tag));
                    }
                }

                @Override
                public void onPartialResults(Bundle b) {
                    String t = best(b);
                    if (t != null && !t.isEmpty() && id == session && current != null) {
                        partial[0] = t;
                        current.onPartial(t);
                    }
                }

                @Override
                public void onResults(Bundle b) {
                    String t = best(b);
                    done(id, t == null || t.isEmpty() ? partial[0] : t);
                }

                @Override
                public void onError(int error) {
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        done(id, partial[0]);
                    } else if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                            || error == SpeechRecognizer.ERROR_SERVER) {
                        fail(id, app.getString(R.string.voice_err_network));
                    } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        fail(id, app.getString(R.string.voice_mic_needed));
                    } else if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                            || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                        fail(id, app.getString(R.string.voice_err_language, tag));
                    } else {
                        fail(id, app.getString(R.string.voice_err_other, error));
                    }
                }

                @Override
                public void onRmsChanged(float rms) {
                    if (id == session && current != null) current.onLevel(Math.max(0f, Math.min(1f, (rms + 2f) / 12f)));
                }

                @Override public void onBeginningOfSpeech() {}
                @Override public void onBufferReceived(byte[] buf) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onEvent(int type, Bundle b) {}
            });
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDevice);
            recognizer.startListening(i);
        });
    }

    /**
     * The phone's online recognizer that isn't Buddy's own: as the digital assistant, Buddy's recognizer becomes the
     * default one, and asking "the default" would land back here. Google's first.
     */
    static android.content.ComponentName otherRecognizer(Context c) {
        java.util.List<android.content.pm.ResolveInfo> l = c.getPackageManager().queryIntentServices(
                new Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0);
        android.content.ComponentName any = null;
        for (android.content.pm.ResolveInfo r : l) {
            if (r.serviceInfo == null || r.serviceInfo.packageName.equals(c.getPackageName())) continue;
            android.content.ComponentName cn = new android.content.ComponentName(r.serviceInfo.packageName, r.serviceInfo.name);
            if (r.serviceInfo.packageName.equals("com.google.android.googlequicksearchbox")) return cn;
            if (any == null || r.serviceInfo.packageName.startsWith("com.google")) any = cn;
        }
        return any;
    }

    private static String best(Bundle b) {
        ArrayList<String> r = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return r == null || r.isEmpty() ? null : r.get(0);
    }

    // --------------------------------------------------------------- Vosk

    private static void vosk(Context app, int id, String lang) {
        new Thread(() -> {
            try {
                org.vosk.Model model = VoskModels.model(app, lang);
                org.vosk.Recognizer rec = new org.vosk.Recognizer(model, 16000f);
                main.post(() -> {
                    if (id != session) {
                        rec.close();
                        return;
                    }
                    try {
                        vosk = new org.vosk.android.SpeechService(rec, 16000f);
                    } catch (Exception e) {
                        rec.close();
                        fail(id, app.getString(R.string.voice_mic_needed));
                        return;
                    }
                    final String[] partial = {""};
                    vosk.startListening(new org.vosk.android.RecognitionListener() {
                        @Override
                        public void onPartialResult(String json) {
                            String t = field(json, "partial");
                            if (!t.isEmpty() && id == session && current != null) {
                                partial[0] = t;
                                current.onPartial(t);
                            }
                        }

                        @Override
                        public void onResult(String json) { // the end of an utterance: that's the command
                            String t = field(json, "text");
                            if (!t.isEmpty()) done(id, t);
                        }

                        @Override
                        public void onFinalResult(String json) {
                            String t = field(json, "text");
                            done(id, t.isEmpty() ? partial[0] : t);
                        }

                        @Override
                        public void onError(Exception e) {
                            fail(id, app.getString(R.string.voice_err_other_text, String.valueOf(e.getMessage())));
                        }

                        @Override
                        public void onTimeout() {
                            done(id, partial[0]);
                        }
                    }, 12000);
                    if (current != null) current.onReady(app.getString(R.string.voice_engine_vosk, lang));
                });
            } catch (Exception e) {
                main.post(() -> fail(id, app.getString(R.string.voice_err_other_text, String.valueOf(e.getMessage()))));
            }
        }, "vosk-load").start();
    }

    static String field(String json, String key) {
        try {
            return new JSONObject(json).optString(key).trim();
        } catch (Exception e) {
            return "";
        }
    }
}
