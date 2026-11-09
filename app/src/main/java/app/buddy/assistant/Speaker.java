package app.buddy.assistant;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.PlaybackParams;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Valkyrie's voice. Russian and English: a Piper voice on the phone (sherpa-onnx) when it's downloaded, else the
 * phone's own text-to-speech. Arabic: the phone's Egyptian voice, else another Arabic one, else Piper's Kareem. Low
 * and hard by default (pitch 0.8, speed 0.95), set per language in Settings → Voice. One text after another.
 */
final class Speaker {
    private static TextToSpeech tts;
    private static boolean ready;
    private static final List<Object[]> waiting = new ArrayList<>();
    private static final ExecutorService piper = Executors.newSingleThreadExecutor();
    private static volatile AudioTrack playing;
    private static volatile int generation;

    private Speaker() {
    }

    /** Says text in this language (ru-RU, en-US, ar-EG). */
    static void say(Context c, String text, String lang) {
        if (text == null || text.trim().isEmpty()) return;
        Context app = c.getApplicationContext();
        String l = Translate.base(lang);
        String piperId = piperFor(app, l);
        if (piperId != null) {
            sayPiper(app, clean(text), piperId, l);
            return;
        }
        saySystem(app, clean(text), lang, l);
    }

    /** The Piper voice this language uses now, or null for the phone's own. */
    static String piperFor(Context c, String l) {
        String choice = VoicePrefs.voice(c, l);
        if (choice.startsWith("piper:")) {
            String id = choice.substring(6);
            return PiperVoices.installed(c, id) ? id : null;
        }
        if (choice.startsWith("system:")) return null;
        // auto: Russian and English prefer Piper; Arabic prefers the phone's own (Egyptian if it has one)
        if (!"ar".equals(l)) {
            String id = PiperVoices.defaultFor(l);
            return PiperVoices.installed(c, id) ? id : null;
        }
        if (hasSystemArabic(c)) return null;
        return PiperVoices.installed(c, "kareem") ? "kareem" : null;
    }

    static synchronized void stop() {
        waiting.clear();
        generation++;
        AudioTrack t = playing;
        if (t != null) {
            try {
                t.pause();
                t.flush();
            } catch (Exception ignored) {
            }
        }
        if (tts != null && ready) tts.stop();
    }

    static boolean speaking() {
        return playing != null || (tts != null && ready && tts.isSpeaking());
    }

    // ---------------------------------------------------------------- Piper

    private static void sayPiper(Context app, String text, String id, String l) {
        final int gen = generation;
        float pitch = VoicePrefs.pitch(app, l), rate = VoicePrefs.rate(app, l);
        piper.execute(() -> {
            if (gen != generation) return;
            try {
                com.k2fsa.sherpa.onnx.OfflineTts t = PiperVoices.tts(app, id);
                com.k2fsa.sherpa.onnx.GeneratedAudio a = t.generate(text, 0, rate);
                if (gen != generation) return;
                play(a.getSamples(), a.getSampleRate(), pitch, gen);
            } catch (Throwable e) { // the model didn't load or run: the phone's own voice says it
                saySystem(app, text, l, l);
            }
        });
    }

    /** Plays float samples at this pitch (the speed stays: the voice already spoke at its rate). */
    private static void play(float[] samples, int rate, float pitch, int gen) throws InterruptedException {
        int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(Math.max(min, rate * 4 / 2)).setTransferMode(AudioTrack.MODE_STREAM).build();
        try {
            try {
                track.setPlaybackParams(new PlaybackParams().setPitch(pitch).setSpeed(1f));
            } catch (Exception ignored) { // this phone can't change the pitch: as it is
            }
            playing = track;
            track.play();
            int off = 0;
            while (off < samples.length && gen == generation) {
                int n = track.write(samples, off, Math.min(4096, samples.length - off), AudioTrack.WRITE_BLOCKING);
                if (n <= 0) break;
                off += n;
            }
            // until it has all been heard
            long end = System.currentTimeMillis() + (long) (samples.length * 1000L / rate) + 3000;
            while (gen == generation && track.getPlaybackHeadPosition() < off && System.currentTimeMillis() < end) {
                Thread.sleep(40);
            }
        } finally {
            playing = null;
            try {
                track.stop();
            } catch (Exception ignored) {
            }
            track.release();
        }
    }

    // ------------------------------------------------- the phone's own TTS

    private interface Ready {
        void go();
    }

    private static synchronized void withTts(Context app, Ready r) {
        if (tts == null) {
            tts = new TextToSpeech(app, status -> {
                synchronized (Speaker.class) {
                    ready = status == TextToSpeech.SUCCESS;
                    List<Object[]> w = new ArrayList<>(waiting);
                    waiting.clear();
                    if (ready) for (Object[] x : w) ((Ready) x[0]).go();
                }
            });
        }
        if (!ready) {
            waiting.add(new Object[]{r});
            return;
        }
        r.go();
    }

    private static void saySystem(Context app, String text, String lang, String l) {
        withTts(app, () -> {
            // no Arabic voice on the phone after all: Piper's Kareem, when it's there
            if ("ar".equals(l) && systemVoices("ar").isEmpty() && !VoicePrefs.voice(app, l).startsWith("system:")
                    && PiperVoices.installed(app, "kareem")) {
                sayPiper(app, text, "kareem", l);
                return;
            }
            Voice v = systemVoice(app, l, lang);
            if (v != null) tts.setVoice(v);
            else tts.setLanguage(Locale.forLanguageTag(lang));
            tts.setPitch(VoicePrefs.pitch(app, l));
            tts.setSpeechRate(VoicePrefs.rate(app, l));
            tts.speak(text, TextToSpeech.QUEUE_ADD, new Bundle(), "valkyrie-" + System.nanoTime());
        });
    }

    /** The phone's voices for a language (ru, en, ar), installed ones only; empty until the TTS is ready. */
    static synchronized List<Voice> systemVoices(String l) {
        List<Voice> out = new ArrayList<>();
        if (tts == null || !ready) return out;
        java.util.Set<Voice> all;
        try {
            all = tts.getVoices();
        } catch (Exception e) {
            return out;
        }
        if (all == null) return out;
        for (Voice v : all) {
            if (!v.getLocale().getLanguage().equals(new Locale(l).getLanguage())) continue;
            if (v.getFeatures() != null && v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
            out.add(v);
        }
        out.sort((a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    /** Starts the phone's TTS (for Settings to list its voices); done on the main thread once it's ready. */
    static void prepare(Context c, Runnable done) {
        withTts(c.getApplicationContext(), () -> new android.os.Handler(android.os.Looper.getMainLooper()).post(done));
    }

    private static boolean hasSystemArabic(Context c) {
        return !systemVoices("ar").isEmpty() || (tts == null); // not started yet: assume the phone has one
    }

    /** True if the voice's name says it's male (Android doesn't tell a voice's gender otherwise). */
    static boolean soundsMale(Voice v) {
        String n = v.getName().toLowerCase(Locale.ROOT);
        return n.contains("male") && !n.contains("female");
    }

    /**
     * The voice the settings picked, else the best guess: the same country (ar-EG first for Arabic), a voice whose
     * name says male, on the phone rather than online, then quality.
     */
    static Voice systemVoice(Context c, String l, String lang) {
        String choice = VoicePrefs.voice(c, l);
        List<Voice> vs = systemVoices(l);
        if (choice.startsWith("system:")) {
            for (Voice v : vs) if (v.getName().equals(choice.substring(7))) return v;
        }
        Locale want = Locale.forLanguageTag("ar".equals(l) ? "ar-EG" : lang);
        Voice best = null;
        int bestScore = -1;
        for (Voice v : vs) {
            int score = v.getQuality() + (v.getLocale().getCountry().equals(want.getCountry()) ? 4000 : 0)
                    + (soundsMale(v) ? 2000 : 0) + (v.isNetworkConnectionRequired() ? 0 : 1000);
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
