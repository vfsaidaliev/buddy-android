package app.buddy.assistant;

import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

/**
 * The speech recognizer Android needs from a digital assistant (and uses as the phone's voice input while Buddy Full
 * is the assistant): Buddy's own SpeechInput, online, on the phone or with Vosk as Settings → Voice says.
 */
public class AssistantRecognitionService extends RecognitionService {
    @Override
    protected void onStartListening(Intent intent, Callback cb) {
        String lang = intent == null ? null : intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE);
        if (lang == null || lang.isEmpty()) lang = VoicePrefs.lang(this);
        SpeechInput.start(this, lang, new SpeechInput.Callback() {
            @Override
            public void onReady(String engine) {
                try {
                    cb.readyForSpeech(new Bundle());
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onPartial(String text) {
                try {
                    cb.partialResults(results(text));
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onFinal(String text) {
                try {
                    cb.results(results(text));
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onError(String message) {
                try {
                    cb.error(SpeechRecognizer.ERROR_NO_MATCH);
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onLevel(float level) {
                try {
                    cb.rmsChanged(level * 12f - 2f);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private static Bundle results(String text) {
        Bundle b = new Bundle();
        ArrayList<String> l = new ArrayList<>();
        l.add(text);
        b.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, l);
        return b;
    }

    @Override
    protected void onStopListening(Callback cb) {
        SpeechInput.finish();
    }

    @Override
    protected void onCancel(Callback cb) {
        SpeechInput.cancel();
    }
}
