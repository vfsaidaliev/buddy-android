package app.buddy.assistant;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads the offline packages the voice assistant can use (they're never in the APK): one file at a time from its
 * maker's site, with progress, into Buddy's own storage, and unpacks zips. Callbacks come on the main thread.
 */
final class ModelFetch {
    interface Progress {
        void onProgress(long done, long total);

        /** error: null when it worked. */
        void onDone(String error);
    }

    /** A download under way. */
    static final class Job {
        volatile boolean cancelled;
        volatile long done, total = -1;

        void cancel() {
            cancelled = true;
        }
    }

    private ModelFetch() {
    }

    /** Downloads url to dest (through dest.part), then runs after (off the main thread) before reporting done. */
    static Job fetch(String url, File dest, Progress p, IoStep after) {
        Job job = new Job();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String err = null;
            File part = new File(dest.getPath() + ".part");
            try {
                File parent = dest.getParentFile();
                if (parent != null) parent.mkdirs();
                HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
                h.setConnectTimeout(15000);
                h.setReadTimeout(30000);
                h.setInstanceFollowRedirects(true);
                int code = h.getResponseCode();
                if (code / 100 != 2) throw new IOException("HTTP " + code);
                job.total = h.getContentLengthLong();
                long[] last = {0};
                try (InputStream in = new BufferedInputStream(h.getInputStream()); OutputStream o = new FileOutputStream(part)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (job.cancelled) throw new IOException("cancelled");
                        o.write(buf, 0, n);
                        job.done += n;
                        long now = System.currentTimeMillis();
                        if (now - last[0] > 250) {
                            last[0] = now;
                            long d = job.done, t = job.total;
                            main.post(() -> p.onProgress(d, t));
                        }
                    }
                } finally {
                    h.disconnect();
                }
                if (dest.exists()) rm(dest);
                if (!part.renameTo(dest)) throw new IOException("rename");
                if (after != null) after.run(dest);
            } catch (Exception e) {
                part.delete();
                err = job.cancelled ? "cancelled" : String.valueOf(e.getMessage());
            }
            final String error = err;
            main.post(() -> p.onDone(error));
        }, "model-fetch").start();
        return job;
    }

    interface IoStep {
        void run(File downloaded) throws IOException;
    }

    /** Unpacks a zip into dir; stripTop: drop the one folder everything in it sits in. Refuses paths leaving dir. */
    static void unzip(File zip, File dir, boolean stripTop) throws IOException {
        dir.mkdirs();
        String root = dir.getCanonicalPath() + File.separator;
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(new java.io.FileInputStream(zip)))) {
            ZipEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = z.getNextEntry()) != null) {
                String name = e.getName();
                if (stripTop) {
                    int slash = name.indexOf('/');
                    if (slash < 0) continue;
                    name = name.substring(slash + 1);
                    if (name.isEmpty()) continue;
                }
                File out = new File(dir, name);
                if (!out.getCanonicalPath().startsWith(root)) throw new IOException("bad path in zip: " + e.getName());
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (OutputStream o = new FileOutputStream(out)) {
                    int n;
                    while ((n = z.read(buf)) > 0) o.write(buf, 0, n);
                }
            }
        }
    }

    /** Bytes in a file or folder. */
    static long du(File f) {
        if (!f.exists()) return 0;
        if (!f.isDirectory()) return f.length();
        long n = 0;
        File[] in = f.listFiles();
        if (in != null) for (File c : in) n += du(c);
        return n;
    }

    static void rm(File f) {
        File[] in = f.isDirectory() ? f.listFiles() : null;
        if (in != null) for (File c : in) rm(c);
        f.delete();
    }
}
