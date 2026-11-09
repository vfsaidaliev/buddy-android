package app.buddy.assistant;

import android.content.Context;

import java.io.File;
import java.io.IOException;

/**
 * Vosk speech models, Buddy Full's spare offline recognizer for Russian and English (and the wake phrase): downloaded
 * from alphacephei.com in Settings → Voice, never part of the APK, and deleted there too.
 */
final class VoskModels {
    /** {language, model, url, approximate download size in MB}. */
    static final String[][] ALL = {
            {"ru", "vosk-model-small-ru-0.22", "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip", "45"},
            {"en", "vosk-model-small-en-us-0.15", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", "40"},
    };

    private static final java.util.Map<String, org.vosk.Model> loaded = new java.util.HashMap<>();
    private static final java.util.Map<String, ModelFetch.Job> jobs = new java.util.HashMap<>();

    private VoskModels() {
    }

    static String[] of(String lang) {
        String l = lang.split("-")[0];
        for (String[] m : ALL) if (m[0].equals(l)) return m;
        return null;
    }

    static File dir(Context c, String lang) {
        return new File(c.getFilesDir(), "vosk/" + lang.split("-")[0]);
    }

    /** Downloaded and unpacked (the marker is written last). */
    static boolean installed(Context c, String lang) {
        return of(lang) != null && new File(dir(c, lang), ".ok").exists();
    }

    static long size(Context c, String lang) {
        return ModelFetch.du(dir(c, lang));
    }

    static ModelFetch.Job job(String lang) {
        synchronized (jobs) {
            return jobs.get(lang.split("-")[0]);
        }
    }

    static ModelFetch.Job download(Context c, String lang, ModelFetch.Progress p) {
        String[] m = of(lang);
        if (m == null) return null;
        String l = m[0];
        File dir = dir(c, l), zip = new File(c.getCacheDir(), m[1] + ".zip");
        ModelFetch.Job j = ModelFetch.fetch(m[2], zip, new ModelFetch.Progress() {
            @Override
            public void onProgress(long done, long total) {
                p.onProgress(done, total);
            }

            @Override
            public void onDone(String error) {
                synchronized (jobs) {
                    jobs.remove(l);
                }
                p.onDone(error);
            }
        }, f -> {
            try {
                ModelFetch.rm(dir);
                ModelFetch.unzip(f, dir, true);
                if (!new File(dir, ".ok").createNewFile() && !new File(dir, ".ok").exists()) throw new IOException("marker");
            } finally {
                f.delete();
            }
        });
        synchronized (jobs) {
            jobs.put(l, j);
        }
        return j;
    }

    static void delete(Context c, String lang) {
        String l = lang.split("-")[0];
        synchronized (loaded) {
            org.vosk.Model m = loaded.remove(l);
            if (m != null) m.close();
        }
        ModelFetch.rm(dir(c, l));
    }

    /** The loaded model (a second or two the first time; not on the main thread). */
    static org.vosk.Model model(Context c, String lang) throws IOException {
        String l = lang.split("-")[0];
        synchronized (loaded) {
            org.vosk.Model m = loaded.get(l);
            if (m == null) {
                if (!installed(c, l)) throw new IOException("not downloaded");
                m = new org.vosk.Model(dir(c, l).getPath());
                loaded.put(l, m);
            }
            return m;
        }
    }
}
