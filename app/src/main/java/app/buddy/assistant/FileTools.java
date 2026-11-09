package app.buddy.assistant;

import android.media.MediaScannerConnection;
import android.text.format.Formatter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Buddy Full's tools for the phone's shared storage (/sdcard in Buddy's Linux): moving and deleting files. They need
 * "All files access". Deleting always asks the user on a card, and so does moving more than one file; neither ever
 * overwrites a file. Not in a notification rule's run (that works for someone else's message).
 */
final class FileTools {
    static final java.util.Set<String> NAMES = new java.util.HashSet<>(java.util.Arrays.asList(
            "file_move", "file_delete", "media_scan", "media_list", "trash_move", "trash_restore", "trash_empty"));
    /** The phone's shared storage, as Android names it and as Buddy's Linux does. */
    static final String STORAGE = "/storage/emulated/0", SDCARD = "/sdcard";
    /** Buddy Full's trash: files keep their place under it (…/_Корзина/DCIM/Camera/a.jpg), so they can go back. */
    static final String TRASH = STORAGE + "/_Корзина";
    /** In the trash: when each thing was put there and where from ({trash path: {orig, at}}). */
    private static final String INDEX = ".trash-index.json";
    /** How many names a card lists before "and N more". */
    private static final int CARD_NAMES = 12;

    private final BuddyService svc;

    FileTools(BuddyService svc) {
        this.svc = svc;
    }

    static void list(JSONArray t) throws JSONException {
        t.put(Tools.tool("file_move", "Move or rename files and folders in the phone's shared storage (/sdcard). With "
                        + "\"to\" an existing folder (or ending in /), everything goes into it under the same names; with one "
                        + "path, \"to\" may also be its new full path (a rename). Never overwrites: a name that's already "
                        + "taken is skipped and reported. Moving more than one file shows the user a card with the summary "
                        + "first. The Gallery is updated afterwards.",
                Tools.props().put("paths", pathsProp("Full paths of the files or folders, e.g. /sdcard/DCIM/Camera/IMG_1.jpg"))
                        .put("to", Tools.strProp("The folder to move them into, or (one path) the new full path")),
                "paths", "to"));
        t.put(Tools.tool("file_delete", "Delete files or folders in the phone's shared storage (/sdcard) for good: they "
                        + "can't be got back. Prefer trash_move unless the user asked to delete permanently. Always shows "
                        + "the user a card with what will be deleted (how many files, how much space) first.",
                Tools.props().put("paths", pathsProp("Full paths of the files or folders")), "paths"));
        t.put(Tools.tool("trash_move", "Remove files or folders from where they are by moving them to the trash, "
                        + "/sdcard/_Корзина, keeping their place under it (…/_Корзина/DCIM/Camera/a.jpg), so trash_restore can "
                        + "put them back. The Gallery stops showing them. More than one file: the user confirms a card with "
                        + "the summary first. Use this instead of deleting.",
                Tools.props().put("paths", pathsProp("Full paths of the files or folders")), "paths"));
        t.put(Tools.tool("trash_restore", "Put files from the trash (/sdcard/_Корзина) back where they were. Give their "
                        + "paths in the trash or where they used to be, or all=true. A name that's taken there gets \" (2)\". "
                        + "More than one file: the user confirms a card first.",
                Tools.props().put("paths", pathsProp("Paths in the trash, or the original paths"))
                        .put("all", Tools.boolProp("true: everything in the trash"))));
        t.put(Tools.tool("trash_empty", "Delete files in the trash (/sdcard/_Корзина) for good: all of them, those put "
                        + "there more than older_than_days ago, or the given paths. Always shows the user a card with what "
                        + "goes (how many files, how much space) first.",
                Tools.props().put("older_than_days", Tools.intProp("Only what has been in the trash longer than this, e.g. 30"))
                        .put("paths", pathsProp("Optional: only these, paths in the trash"))));
        t.put(Tools.tool("media_scan", "Have Android's media index (MediaStore) look at files or folders again after "
                        + "they changed outside the file tools (e.g. made or moved with the shell), so the Gallery shows the "
                        + "new state: new files appear, gone ones disappear. Folders: everything in them.",
                Tools.props().put("paths", pathsProp("Files or folders, e.g. /sdcard/Pictures/Edited")), "paths"));
        t.put(Tools.tool("media_list", "List the photos (and with video=true the videos) on the phone from Android's "
                        + "media index: path, date taken, size, folder, pixels and whether it's a screenshot; with the "
                        + "number and total size of all that match first. Page with offset.",
                Tools.props().put("folder", Tools.strProp("Optional: only in this folder and below, e.g. /sdcard/DCIM/Camera"))
                        .put("from", Tools.strProp("Optional: taken on or after, \"2026-01-31\""))
                        .put("to", Tools.strProp("Optional: taken on or before, \"2026-02-28\""))
                        .put("screenshots", Tools.enumProp("include", "only", "exclude"))
                        .put("min_size_kb", Tools.intProp("Optional: only files at least this big"))
                        .put("video", Tools.boolProp("true: videos too"))
                        .put("sort", Tools.enumProp("newest", "oldest", "largest"))
                        .put("limit", Tools.intProp("How many to list (default 100, max 500)"))
                        .put("offset", Tools.intProp("Skip this many first (for the next page)"))));
    }

    static JSONObject pathsProp(String d) throws JSONException {
        return new JSONObject().put("type", "array").put("items", new JSONObject().put("type", "string")).put("description", d);
    }

    JSONObject call(String name, JSONObject a, boolean ruleRun) throws Exception {
        if (ruleRun) return text("Not available in a notification rule's run. Say in your reply what you wanted to do.");
        if (!FullAccess.filesGranted()) {
            return text("Buddy doesn't have \"All files access\" yet. Ask the user to allow it: Buddy, Settings, Check "
                    + "Buddy, All files access, Fix.");
        }
        switch (name) {
            case "file_move": return text(move(paths(a), a.getString("to")));
            case "file_delete": return text(delete(paths(a)));
            case "trash_move": return text(trashMove(paths(a)));
            case "trash_restore": return text(trashRestore(a.optBoolean("all") ? null : paths(a)));
            case "trash_empty": return text(trashEmpty(a.has("older_than_days") ? a.getInt("older_than_days") : -1,
                    a.has("paths") ? paths(a) : null));
            case "media_scan": return text(mediaScan(paths(a)));
            case "media_list": return text(mediaList(a));
            default: throw new IllegalArgumentException("Unknown tool " + name);
        }
    }

    static JSONObject text(String s) throws JSONException {
        return new JSONObject().put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", s)));
    }

    // ------------------------------------------------------------- paths

    /** The "paths" argument (a list, or one path as a plain string). */
    static List<String> paths(JSONObject a) throws JSONException {
        List<String> l = new ArrayList<>();
        JSONArray arr = a.optJSONArray("paths");
        if (arr != null) for (int i = 0; i < arr.length(); i++) l.add(arr.getString(i));
        else if (a.has("paths")) l.add(a.getString("paths"));
        if (l.isEmpty()) throw new IllegalArgumentException("Give at least one path.");
        return l;
    }

    /**
     * A path in the shared storage, from /sdcard/… or /storage/emulated/0/… (relative: under /sdcard). Throws for
     * anything outside it, the storage's own top folder included.
     */
    static File resolve(String p) throws IOException {
        p = p.trim();
        if (p.startsWith("~")) throw new IllegalArgumentException(p + ": use a path under /sdcard.");
        if (p.equals(SDCARD) || p.startsWith(SDCARD + "/")) p = STORAGE + p.substring(SDCARD.length());
        else if (!p.startsWith("/")) p = STORAGE + "/" + p;
        File f = new File(p).getCanonicalFile();
        String s = f.getPath();
        if (!s.startsWith(STORAGE + "/")) throw new IllegalArgumentException(p + " is outside the phone's storage (/sdcard).");
        return f;
    }

    /** As the agent and the user see it: /sdcard/… */
    static String shown(File f) {
        String s = f.getPath();
        return s.startsWith(STORAGE) ? SDCARD + s.substring(STORAGE.length()) : s;
    }

    /** {files, bytes} in a file or folder, all the way down. */
    static long[] count(File f) {
        if (!f.isDirectory()) return new long[]{1, f.length()};
        long[] n = {0, 0};
        File[] in = f.listFiles();
        if (in != null) for (File c : in) {
            long[] m = count(c);
            n[0] += m[0];
            n[1] += m[1];
        }
        return n;
    }

    /** The card's list of names: the first few, then "and N more". */
    String names(List<File> fs) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < fs.size() && i < CARD_NAMES; i++) {
            File f = fs.get(i);
            b.append("\n• ").append(shown(f)).append(f.isDirectory() ? "/" : "");
        }
        if (fs.size() > CARD_NAMES) b.append("\n").append(svc.getString(R.string.files_card_more, fs.size() - CARD_NAMES));
        return b.toString();
    }

    String size(long bytes) {
        return Formatter.formatShortFileSize(svc, bytes);
    }

    /** Asks the Gallery and other apps to see these files' new state (moved, deleted or new); doesn't wait. */
    void scan(List<String> paths) {
        if (paths.isEmpty()) return;
        MediaScannerConnection.scanFile(svc, paths.toArray(new String[0]), null, null);
    }

    /** Every file in a file or folder, for scanning. */
    static void allFiles(File f, List<String> out) {
        if (!f.isDirectory()) {
            out.add(f.getPath());
            return;
        }
        File[] in = f.listFiles();
        if (in != null) for (File c : in) allFiles(c, out);
    }

    // -------------------------------------------------------------- move

    private String move(List<String> paths, String to) throws Exception {
        List<File> src = new ArrayList<>();
        StringBuilder problems = new StringBuilder();
        for (String p : paths) {
            File f = resolve(p);
            if (!f.exists()) problems.append("\n- ").append(p).append(": not found");
            else src.add(f);
        }
        if (src.isEmpty()) return "Nothing moved." + problems;
        boolean into = to.trim().endsWith("/") || resolve(to).isDirectory();
        File dest = resolve(to);
        if (!into && src.size() > 1) return "\"to\" must be a folder when moving more than one path.";
        long files = 0, bytes = 0;
        for (File f : src) {
            long[] n = count(f);
            files += n[0];
            bytes += n[1];
        }
        if (files > 1 || src.size() > 1) {
            String card = svc.getString(R.string.files_card_move, files, size(bytes), shown(dest)) + names(src);
            String answer = svc.askConfirm(card, false);
            if (!answer.startsWith("APPROVED")) return "Not moved. " + answer;
        }
        if (into && !dest.isDirectory() && !dest.mkdirs()) return "Couldn't create the folder " + shown(dest) + ".";
        int moved = 0;
        StringBuilder done = new StringBuilder();
        List<String> changed = new ArrayList<>();
        for (File f : src) {
            File target = into ? new File(dest, f.getName()) : dest;
            if (target.exists()) {
                problems.append("\n- ").append(shown(f)).append(": skipped, ").append(shown(target)).append(" already exists");
                continue;
            }
            if (target.getPath().startsWith(f.getPath() + "/")) {
                problems.append("\n- ").append(shown(f)).append(": skipped, can't move a folder into itself");
                continue;
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            List<String> before = new ArrayList<>();
            allFiles(f, before);
            if (!moveOne(f, target)) {
                problems.append("\n- ").append(shown(f)).append(": couldn't be moved");
                continue;
            }
            moved++;
            done.append("\n- ").append(shown(f)).append(" -> ").append(shown(target));
            changed.addAll(before);
            allFiles(target, changed);
        }
        scan(changed);
        return "Moved " + moved + " of " + src.size() + "." + done + (problems.length() > 0 ? "\nNot moved:" + problems : "");
    }

    /** Renames (same storage, instant); if Android refuses, copies and then deletes the original. */
    static boolean moveOne(File from, File to) {
        if (from.renameTo(to)) return true;
        try {
            copyTree(from, to);
        } catch (IOException e) {
            deleteTree(to);
            return false;
        }
        return deleteTree(from);
    }

    private static void copyTree(File from, File to) throws IOException {
        if (from.isDirectory()) {
            if (!to.mkdirs() && !to.isDirectory()) throw new IOException("mkdir " + to);
            File[] in = from.listFiles();
            if (in != null) for (File c : in) copyTree(c, new File(to, c.getName()));
            return;
        }
        if (to.exists()) throw new IOException(to + " exists");
        try (InputStream i = new FileInputStream(from); OutputStream o = new FileOutputStream(to)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
        }
        to.setLastModified(from.lastModified());
    }

    static boolean deleteTree(File f) {
        File[] in = f.isDirectory() ? f.listFiles() : null;
        if (in != null) for (File c : in) deleteTree(c);
        return f.delete() || !f.exists();
    }

    // ------------------------------------------------------------ delete

    private String delete(List<String> paths) throws Exception {
        List<File> fs = new ArrayList<>();
        StringBuilder problems = new StringBuilder();
        for (String p : paths) {
            File f = resolve(p);
            if (!f.exists()) problems.append("\n- ").append(p).append(": not found");
            else fs.add(f);
        }
        if (fs.isEmpty()) return "Nothing deleted." + problems;
        long files = 0, bytes = 0;
        for (File f : fs) {
            long[] n = count(f);
            files += n[0];
            bytes += n[1];
        }
        String answer = svc.askConfirm(svc.getString(R.string.files_card_delete, files, size(bytes)) + names(fs), false);
        if (!answer.startsWith("APPROVED")) return "Not deleted. " + answer;
        int deleted = 0;
        List<String> changed = new ArrayList<>();
        for (File f : fs) {
            List<String> before = new ArrayList<>();
            allFiles(f, before);
            if (deleteTree(f)) {
                deleted++;
                changed.addAll(before);
            } else {
                problems.append("\n- ").append(shown(f)).append(": couldn't be deleted (or only partly)");
            }
        }
        scan(changed);
        return "Deleted " + deleted + " of " + fs.size() + " (" + files + " files, " + size(bytes) + ")."
                + (problems.length() > 0 ? "\nNot deleted:" + problems : "");
    }

    // ------------------------------------------------------------- trash

    private static final Object TRASH_LOCK = new Object();

    private static File trashDir() {
        File t = new File(TRASH);
        if (!t.isDirectory()) t.mkdirs();
        File nomedia = new File(t, ".nomedia"); // the Gallery doesn't show what's in the trash
        try {
            if (!nomedia.exists()) nomedia.createNewFile();
        } catch (IOException ignored) {
        }
        return t;
    }

    private static boolean inTrash(File f) {
        return f.getPath().equals(TRASH) || f.getPath().startsWith(TRASH + "/");
    }

    private static JSONObject readIndex() {
        File f = new File(TRASH, INDEX);
        if (!f.exists()) return new JSONObject();
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return new JSONObject(b.toString("UTF-8"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void writeIndex(JSONObject idx) {
        try (OutputStream o = new FileOutputStream(new File(trashDir(), INDEX))) {
            o.write(idx.toString().getBytes("UTF-8"));
        } catch (IOException ignored) {
        }
    }

    /** f, or "name (2).ext", "name (3).ext"… if that's taken. */
    static File unique(File f) {
        if (!f.exists()) return f;
        String name = f.getName();
        int dot = f.isDirectory() ? -1 : name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            File c = new File(f.getParentFile(), base + " (" + i + ")" + ext);
            if (!c.exists()) return c;
        }
    }

    private String trashMove(List<String> paths) throws Exception {
        List<File> src = new ArrayList<>();
        StringBuilder problems = new StringBuilder();
        for (String p : paths) {
            File f = resolve(p);
            if (!f.exists()) problems.append("\n- ").append(p).append(": not found");
            else if (inTrash(f)) problems.append("\n- ").append(p).append(": already in the trash (trash_empty deletes it)");
            else src.add(f);
        }
        if (src.isEmpty()) return "Nothing moved to the trash." + problems;
        long files = 0, bytes = 0;
        for (File f : src) {
            long[] n = count(f);
            files += n[0];
            bytes += n[1];
        }
        if (files > 1 || src.size() > 1) {
            String answer = svc.askConfirm(svc.getString(R.string.files_card_trash, files, size(bytes)) + names(src), false);
            if (!answer.startsWith("APPROVED")) return "Not moved to the trash. " + answer;
        }
        int moved = 0;
        StringBuilder done = new StringBuilder();
        List<String> changed = new ArrayList<>();
        synchronized (TRASH_LOCK) {
            File trash = trashDir();
            JSONObject idx = readIndex();
            for (File f : src) {
                File target = unique(new File(trash, f.getPath().substring(STORAGE.length() + 1)));
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();
                List<String> before = new ArrayList<>();
                allFiles(f, before);
                if (!moveOne(f, target)) {
                    problems.append("\n- ").append(shown(f)).append(": couldn't be moved");
                    continue;
                }
                moved++;
                idx.put(target.getPath(), new JSONObject().put("orig", f.getPath()).put("at", System.currentTimeMillis()));
                done.append("\n- ").append(shown(f)).append(" -> ").append(shown(target));
                changed.addAll(before); // gone from where they were (the trash itself isn't indexed: .nomedia)
            }
            writeIndex(idx);
        }
        scan(changed);
        return "Moved " + moved + " of " + src.size() + " to the trash (" + files + " files, " + size(bytes) + "). "
                + "trash_restore puts them back." + done + (problems.length() > 0 ? "\nNot moved:" + problems : "");
    }

    /** Where a thing in the trash came from: the index's note, else its place under the trash. */
    private static File origin(JSONObject idx, File inTrash) {
        JSONObject e = idx.optJSONObject(inTrash.getPath());
        if (e != null && !e.optString("orig").isEmpty()) return new File(e.optString("orig"));
        return new File(STORAGE + inTrash.getPath().substring(TRASH.length()));
    }

    private String trashRestore(List<String> paths) throws Exception {
        List<File> items = new ArrayList<>();
        StringBuilder problems = new StringBuilder();
        synchronized (TRASH_LOCK) {
            JSONObject idx = readIndex();
            if (paths == null) { // everything: what the index knows, then anything else at the trash's top
                java.util.Iterator<String> k = idx.keys();
                while (k.hasNext()) {
                    File f = new File(k.next());
                    if (f.exists()) items.add(f);
                }
                File[] top = new File(TRASH).listFiles();
                if (top != null) for (File f : top) {
                    if (f.getName().equals(".nomedia") || f.getName().equals(INDEX)) continue;
                    boolean covered = false;
                    for (File i : items) covered |= i.equals(f) || i.getPath().startsWith(f.getPath() + "/");
                    if (!covered) items.add(f);
                }
            } else {
                for (String p : paths) {
                    File f = resolve(p);
                    if (!inTrash(f)) { // an original path: find what came from there
                        File found = null;
                        java.util.Iterator<String> k = idx.keys();
                        while (k.hasNext()) {
                            String t = k.next();
                            if (idx.optJSONObject(t) != null && f.getPath().equals(idx.optJSONObject(t).optString("orig"))) found = new File(t);
                        }
                        if (found == null) found = new File(TRASH + f.getPath().substring(STORAGE.length()));
                        f = found;
                    }
                    if (!f.exists() || f.getPath().equals(TRASH)) problems.append("\n- ").append(p).append(": not in the trash");
                    else items.add(f);
                }
            }
        }
        if (items.isEmpty()) return "Nothing to restore." + problems;
        long files = 0, bytes = 0;
        for (File f : items) {
            long[] n = count(f);
            files += n[0];
            bytes += n[1];
        }
        if (files > 1 || items.size() > 1) {
            String answer = svc.askConfirm(svc.getString(R.string.files_card_restore, files, size(bytes)) + names(items), false);
            if (!answer.startsWith("APPROVED")) return "Not restored. " + answer;
        }
        int restored = 0;
        StringBuilder done = new StringBuilder();
        List<String> changed = new ArrayList<>();
        synchronized (TRASH_LOCK) {
            JSONObject idx = readIndex();
            for (File f : items) {
                File target = unique(origin(idx, f));
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();
                if (!moveOne(f, target)) {
                    problems.append("\n- ").append(shown(f)).append(": couldn't be moved back");
                    continue;
                }
                restored++;
                idx.remove(f.getPath());
                done.append("\n- ").append(shown(target));
                allFiles(target, changed);
            }
            writeIndex(idx);
            pruneEmpty(new File(TRASH));
        }
        scan(changed);
        return "Restored " + restored + " of " + items.size() + "." + done + (problems.length() > 0 ? "\nNot restored:" + problems : "");
    }

    private String trashEmpty(int olderThanDays, List<String> paths) throws Exception {
        long cutoff = olderThanDays >= 0 ? System.currentTimeMillis() - olderThanDays * 86_400_000L : Long.MAX_VALUE;
        List<File> chosen = new ArrayList<>();
        long bytes = 0;
        synchronized (TRASH_LOCK) {
            JSONObject idx = readIndex();
            List<String> all = new ArrayList<>();
            if (paths == null) {
                if (!new File(TRASH).isDirectory()) return "The trash is empty.";
                allFiles(new File(TRASH), all);
            } else {
                for (String p : paths) {
                    File f = resolve(p);
                    if (!inTrash(f)) return p + " isn't in the trash (/sdcard/_Корзина). Nothing deleted.";
                    if (f.exists()) allFiles(f, all);
                }
            }
            for (String p : all) {
                File f = new File(p);
                if (f.getParent() != null && f.getParent().equals(TRASH)
                        && (f.getName().equals(".nomedia") || f.getName().equals(INDEX))) continue;
                if (trashedAt(idx, f) <= cutoff) {
                    chosen.add(f);
                    bytes += f.length();
                }
            }
        }
        if (chosen.isEmpty()) {
            return olderThanDays >= 0 ? "Nothing has been in the trash longer than " + olderThanDays + " days." : "The trash is empty.";
        }
        String card = (olderThanDays >= 0 ? svc.getString(R.string.files_card_empty_older, chosen.size(), size(bytes), olderThanDays)
                : svc.getString(R.string.files_card_empty, chosen.size(), size(bytes))) + names(chosen);
        String answer = svc.askConfirm(card, false);
        if (!answer.startsWith("APPROVED")) return "Not deleted. " + answer;
        int deleted = 0;
        synchronized (TRASH_LOCK) {
            for (File f : chosen) if (f.delete() || !f.exists()) deleted++;
            pruneEmpty(new File(TRASH));
            JSONObject idx = readIndex(); // forget what's no longer there
            List<String> gone = new ArrayList<>();
            java.util.Iterator<String> k = idx.keys();
            while (k.hasNext()) {
                String t = k.next();
                if (!new File(t).exists()) gone.add(t);
            }
            for (String t : gone) idx.remove(t);
            writeIndex(idx);
        }
        return "Deleted " + deleted + " of " + chosen.size() + " files from the trash for good (" + size(bytes) + ").";
    }

    /** When a file went into the trash: its own or a folder above it in the index, else its last change. */
    private static long trashedAt(JSONObject idx, File f) {
        for (File p = f; p != null && inTrash(p) && !p.getPath().equals(TRASH); p = p.getParentFile()) {
            JSONObject e = idx.optJSONObject(p.getPath());
            if (e != null && e.has("at")) return e.optLong("at");
        }
        return f.lastModified();
    }

    /** Removes the empty folders under dir (dir itself stays). */
    private static void pruneEmpty(File dir) {
        File[] in = dir.listFiles();
        if (in == null) return;
        for (File c : in) {
            if (!c.isDirectory()) continue;
            pruneEmpty(c);
            String[] left = c.list();
            if (left != null && left.length == 0) c.delete();
        }
    }

    // ------------------------------------------------------------- media

    private String mediaScan(List<String> paths) throws Exception {
        List<String> files = new ArrayList<>();
        StringBuilder problems = new StringBuilder();
        for (String p : paths) {
            File f = resolve(p);
            if (f.isDirectory()) {
                allFiles(f, files);
                // what the index still lists there but is gone: scanning a missing file takes it out
                try (android.database.Cursor c = svc.getContentResolver().query(
                        android.provider.MediaStore.Files.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL),
                        new String[]{android.provider.MediaStore.MediaColumns.DATA},
                        android.provider.MediaStore.MediaColumns.DATA + " LIKE ?", new String[]{f.getPath() + "/%"}, null)) {
                    while (c != null && c.moveToNext()) {
                        String d = c.getString(0);
                        if (d != null && !new File(d).exists()) files.add(d);
                    }
                }
            } else {
                files.add(f.getPath()); // a missing file too: then it leaves the index
            }
        }
        if (files.isEmpty()) return "Nothing to scan." + problems;
        CountDownLatch done = new CountDownLatch(files.size());
        MediaScannerConnection.scanFile(svc, files.toArray(new String[0]), null, (path, uri) -> done.countDown());
        long waitMs = Math.min(180_000L, 20_000L + files.size() * 100L);
        boolean all = done.await(waitMs, TimeUnit.MILLISECONDS);
        long left = done.getCount();
        return "Scanned " + (files.size() - left) + " of " + files.size() + " files"
                + (all ? "; the Gallery shows the change now." : "; the rest is still being scanned in the background.");
    }

    private String mediaList(JSONObject a) throws Exception {
        String folder = a.optString("folder").trim();
        String folderPath = folder.isEmpty() ? null : resolve(folder).getPath();
        long from = Long.MIN_VALUE, to = Long.MAX_VALUE;
        if (!a.optString("from").isEmpty()) {
            java.util.Date d = PhoneTools.parseTime(a.getString("from"));
            if (d == null) return "Give from as \"2026-01-31\".";
            from = d.getTime();
        }
        if (!a.optString("to").isEmpty()) {
            java.util.Date d = PhoneTools.parseTime(a.getString("to"));
            if (d == null) return "Give to as \"2026-02-28\".";
            to = d.getTime() + (a.getString("to").contains(":") ? 59_999L : 86_400_000L - 1); // the whole day
        }
        String shots = a.optString("screenshots", "include");
        long minBytes = a.optLong("min_size_kb", 0) * 1024;
        int limit = Math.max(1, Math.min(500, a.optInt("limit", 100))), offset = Math.max(0, a.optInt("offset", 0));
        String sort = a.optString("sort", "newest");

        List<Object[]> rows = new ArrayList<>(); // {path, date, size, folder, w, h, screenshot}
        List<android.net.Uri> uris = new ArrayList<>();
        uris.add(android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL));
        if (a.optBoolean("video")) uris.add(android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL));
        String[] cols = {android.provider.MediaStore.MediaColumns.DATA, android.provider.MediaStore.MediaColumns.SIZE,
                android.provider.MediaStore.MediaColumns.DATE_TAKEN, android.provider.MediaStore.MediaColumns.DATE_ADDED,
                android.provider.MediaStore.MediaColumns.WIDTH, android.provider.MediaStore.MediaColumns.HEIGHT,
                android.provider.MediaStore.MediaColumns.BUCKET_DISPLAY_NAME};
        for (android.net.Uri uri : uris) {
            try (android.database.Cursor c = svc.getContentResolver().query(uri, cols,
                    folderPath == null ? null : android.provider.MediaStore.MediaColumns.DATA + " LIKE ?",
                    folderPath == null ? null : new String[]{folderPath + "/%"}, null)) {
                while (c != null && c.moveToNext()) {
                    String path = c.getString(0);
                    if (path == null) continue;
                    long size = c.getLong(1);
                    long date = c.getLong(2) > 0 ? c.getLong(2) : c.getLong(3) * 1000;
                    if (size < minBytes || date < from || date > to) continue;
                    String lower = path.toLowerCase(Locale.ROOT), name = new File(path).getName().toLowerCase(Locale.ROOT);
                    boolean shot = lower.contains("/screenshots/") || name.startsWith("screenshot") || name.startsWith("скриншот");
                    if ("only".equals(shots) && !shot || "exclude".equals(shots) && shot) continue;
                    String bucket = c.getString(6);
                    if (bucket == null) bucket = new File(path).getParentFile() == null ? "" : new File(path).getParentFile().getName();
                    rows.add(new Object[]{path, date, size, bucket, c.getInt(4), c.getInt(5), shot});
                }
            }
        }
        if (rows.isEmpty()) return "No photos" + (a.optBoolean("video") ? " or videos" : "") + " match.";
        java.util.Comparator<Object[]> byDate = (x, y) -> Long.compare((Long) x[1], (Long) y[1]);
        if ("oldest".equals(sort)) java.util.Collections.sort(rows, byDate);
        else if ("largest".equals(sort)) java.util.Collections.sort(rows, (x, y) -> Long.compare((Long) y[2], (Long) x[2]));
        else java.util.Collections.sort(rows, java.util.Collections.reverseOrder(byDate));
        long total = 0;
        int shotsCount = 0;
        for (Object[] r : rows) {
            total += (Long) r[2];
            if ((Boolean) r[6]) shotsCount++;
        }
        java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT);
        StringBuilder b = new StringBuilder();
        b.append(rows.size()).append(" match (").append(size(total)).append(", screenshots: ").append(shotsCount).append(").");
        int end = Math.min(rows.size(), offset + limit);
        if (offset >= rows.size()) return b.append(" No more after offset ").append(offset).append(".").toString();
        b.append(" Showing ").append(offset + 1).append("-").append(end).append(", ").append(sort).append(" first")
                .append(end < rows.size() ? " (next page: offset " + end + ")" : "").append(":\npath | date | size | folder | pixels | screenshot");
        for (int i = offset; i < end; i++) {
            Object[] r = rows.get(i);
            b.append("\n").append(shown(new File((String) r[0]))).append(" | ").append(df.format(new java.util.Date((Long) r[1])))
                    .append(" | ").append(size((Long) r[2])).append(" | ").append(r[3]).append(" | ").append(r[4]).append("x")
                    .append(r[5]).append(" | ").append((Boolean) r[6] ? "yes" : "no");
        }
        return b.toString();
    }
}
