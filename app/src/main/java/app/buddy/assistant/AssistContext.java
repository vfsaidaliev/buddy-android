package app.buddy.assistant;

import android.app.assist.AssistStructure;
import android.content.Context;
import android.graphics.Bitmap;

import java.io.File;
import java.io.FileOutputStream;

/**
 * What was on the screen when Buddy was called as the digital assistant: the text of the app in front (AssistStructure)
 * and a screenshot, when "Use text from screen" / "Use screenshot" are on in Android's assistant settings. The next
 * voice command (within two minutes) takes it along to the agent, once.
 */
final class AssistContext {
    static final long FRESH_MS = 120_000;
    private static final int MAX_TEXT = 8000;

    static final class Data {
        String text, app;
        File shot;
        long at;
    }

    private static Data current;

    private AssistContext() {
    }

    /** The assistant was called: what's kept from before goes. */
    static synchronized void start() {
        current = new Data();
        current.at = System.currentTimeMillis();
    }

    static synchronized void setStructure(AssistStructure s) {
        if (current == null || s == null) return;
        StringBuilder b = new StringBuilder();
        for (int w = 0; w < s.getWindowNodeCount() && b.length() < MAX_TEXT; w++) walk(s.getWindowNodeAt(w).getRootViewNode(), b);
        current.text = b.length() > MAX_TEXT ? b.substring(0, MAX_TEXT) + "…" : b.toString().trim();
        if (s.getActivityComponent() != null) current.app = s.getActivityComponent().getPackageName();
    }

    private static void walk(AssistStructure.ViewNode n, StringBuilder b) {
        if (n == null || b.length() >= MAX_TEXT) return;
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        if (t != null && t.toString().trim().length() > 0) b.append(t.toString().trim()).append('\n');
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChildAt(i), b);
    }

    static synchronized void setScreenshot(Context c, Bitmap shot) {
        if (current == null || shot == null) return;
        File f = new File(c.getCacheDir(), "assist-screen.png");
        try (FileOutputStream o = new FileOutputStream(f)) {
            shot.compress(Bitmap.CompressFormat.PNG, 100, o);
            current.shot = f;
        } catch (Exception ignored) {
        }
    }

    /** The screen as it was, once, if the assistant was called just now and saw something; else null. */
    static synchronized Data take() {
        Data d = current;
        current = null;
        if (d == null || System.currentTimeMillis() - d.at > FRESH_MS) return null;
        if ((d.text == null || d.text.isEmpty()) && d.shot == null) return null;
        return d;
    }
}
