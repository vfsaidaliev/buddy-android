package app.buddy.assistant;

import android.app.Activity;
import android.os.Bundle;

/** Base for Buddy's screens: applies the theme and rebuilds when the appearance settings change. */
abstract class Screen extends Activity {
    private String themeKey;

    @Override
    protected void onCreate(Bundle state) {
        Theme.apply(this);
        themeKey = Theme.key();
        super.onCreate(state);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Theme.load(this);
        if (!Theme.key().equals(themeKey)) recreate();
        // Buddy Full: the wake phrase can only start from the app on screen (after a restart, the first time it's opened)
        if (WakeWordService.enabled(this) && !WakeWordService.running()) {
            try {
                startForegroundService(new android.content.Intent(this, WakeWordService.class));
            } catch (Exception ignored) {
            }
        }
    }

    /** Re-apply the theme in place (after a change made on this screen). */
    protected void rethemed() {
        Theme.apply(this);
        themeKey = Theme.key();
    }
}
