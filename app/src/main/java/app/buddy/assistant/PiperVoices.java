package app.buddy.assistant;

import android.content.Context;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Valkyrie's offline voices: Piper models run by sherpa-onnx, downloaded in Settings → Voice (never in the APK) from
 * sherpa-onnx's releases, and deleted there too.
 */
final class PiperVoices {
    /** {id, language, name, archive, approximate MB}. The first of a language is its default. */
    static final String[][] ALL = {
            {"denis", "ru", "Denis", "vits-piper-ru_RU-denis-medium", "64"},
            {"ruslan", "ru", "Ruslan", "vits-piper-ru_RU-ruslan-medium", "64"},
            {"ryan", "en", "Ryan", "vits-piper-en_US-ryan-medium", "64"},
            {"kareem", "ar", "Kareem", "vits-piper-ar_JO-kareem-medium-int8", "19"},
    };
    private static final String BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/";
    private static final Map<String, ModelFetch.Job> jobs = new HashMap<>();
    private static String loadedId;
    private static com.k2fsa.sherpa.onnx.OfflineTts loaded;

    private PiperVoices() {
    }

    static String[] of(String id) {
        for (String[] v : ALL) if (v[0].equals(id)) return v;
        return null;
    }

    /** The default Piper voice for a language (ru, en, ar). */
    static String defaultFor(String lang) {
        for (String[] v : ALL) if (v[1].equals(lang)) return v[0];
        return null;
    }

    static File dir(Context c, String id) {
        return new File(c.getFilesDir(), "piper/" + id);
    }

    static boolean installed(Context c, String id) {
        return id != null && of(id) != null && new File(dir(c, id), ".ok").exists();
    }

    static long size(Context c, String id) {
        return ModelFetch.du(dir(c, id));
    }

    static ModelFetch.Job job(String id) {
        synchronized (jobs) {
            return jobs.get(id);
        }
    }

    static ModelFetch.Job download(Context c, String id, ModelFetch.Progress p) {
        String[] v = of(id);
        if (v == null) return null;
        File dir = dir(c, id), archive = new File(c.getCacheDir(), v[3] + ".tar.bz2");
        ModelFetch.Job j = ModelFetch.fetch(BASE + v[3] + ".tar.bz2", archive, new ModelFetch.Progress() {
            @Override
            public void onProgress(long done, long total) {
                p.onProgress(done, total);
            }

            @Override
            public void onDone(String error) {
                synchronized (jobs) {
                    jobs.remove(id);
                }
                p.onDone(error);
            }
        }, f -> {
            try {
                ModelFetch.rm(dir);
                untar(f, dir);
                if (onnx(dir) == null) throw new IOException("no model in the archive");
                if (!new File(dir, ".ok").createNewFile() && !new File(dir, ".ok").exists()) throw new IOException("marker");
            } finally {
                f.delete();
            }
        });
        synchronized (jobs) {
            jobs.put(id, j);
        }
        return j;
    }

    static synchronized void delete(Context c, String id) {
        if (id.equals(loadedId) && loaded != null) {
            loaded.release();
            loaded = null;
            loadedId = null;
        }
        ModelFetch.rm(dir(c, id));
    }

    /** Unpacks a .tar.bz2 into dir, without the one folder everything sits in; refuses paths leaving dir. */
    static void untar(File archive, File dir) throws IOException {
        dir.mkdirs();
        String root = dir.getCanonicalPath() + File.separator;
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new BZip2CompressorInputStream(
                new BufferedInputStream(new FileInputStream(archive))))) {
            TarArchiveEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = tar.getNextEntry()) != null) {
                String name = e.getName();
                int slash = name.indexOf('/');
                if (slash < 0) continue;
                name = name.substring(slash + 1);
                if (name.isEmpty()) continue;
                File out = new File(dir, name);
                if (!out.getCanonicalPath().startsWith(root)) throw new IOException("bad path in archive: " + e.getName());
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                if (!e.isFile()) continue; // links and the like
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (OutputStream o = new FileOutputStream(out)) {
                    int n;
                    while ((n = tar.read(buf)) > 0) o.write(buf, 0, n);
                }
            }
        }
    }

    private static File onnx(File dir) {
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) if (f.getName().endsWith(".onnx")) return f;
        return null;
    }

    /** The voice, loaded (a second or two the first time; one at a time; not on the main thread). */
    static synchronized com.k2fsa.sherpa.onnx.OfflineTts tts(Context c, String id) throws IOException {
        if (id.equals(loadedId) && loaded != null) return loaded;
        if (!installed(c, id)) throw new IOException("not downloaded");
        if (loaded != null) {
            loaded.release();
            loaded = null;
        }
        File dir = dir(c, id), model = onnx(dir);
        if (model == null) throw new IOException("no model");
        com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig vits = new com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig();
        vits.setModel(model.getPath());
        vits.setTokens(new File(dir, "tokens.txt").getPath());
        File espeak = new File(dir, "espeak-ng-data");
        if (espeak.isDirectory()) vits.setDataDir(espeak.getPath());
        com.k2fsa.sherpa.onnx.OfflineTtsModelConfig m = new com.k2fsa.sherpa.onnx.OfflineTtsModelConfig();
        m.setVits(vits);
        m.setNumThreads(2);
        m.setProvider("cpu");
        com.k2fsa.sherpa.onnx.OfflineTtsConfig cfg = new com.k2fsa.sherpa.onnx.OfflineTtsConfig();
        cfg.setModel(m);
        loaded = new com.k2fsa.sherpa.onnx.OfflineTts(null, cfg);
        loadedId = id;
        return loaded;
    }
}
