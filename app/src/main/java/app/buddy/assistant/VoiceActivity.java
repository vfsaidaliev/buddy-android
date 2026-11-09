package app.buddy.assistant;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import static app.buddy.assistant.Ui.*;

/**
 * Buddy Full's voice input: listens at once (the assistant gesture, the wake phrase and Settings → Voice open it),
 * shows the words as they come, and hands the command to VoiceCommand. The language switches on this screen.
 */
public class VoiceActivity extends Screen {
    private TextView status, heard;
    private LinearLayout log, langBox;
    private ScrollView logScroll;
    private FrameLayout mic;
    private String lang;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        lang = VoicePrefs.lang(this);
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        LinearLayout top = row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPaddingRelative(dp(this, 4), dp(this, 4), dp(this, 8), dp(this, 4));
        ImageButton back = iconButton(this, R.drawable.ms_arrow_back, getString(R.string.perms_back));
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        TextView t = title(this, getString(R.string.voice_title), 18);
        t.setPaddingRelative(dp(this, 6), 0, 0, 0);
        top.addView(t, weight1());
        ImageButton settings = iconButton(this, R.drawable.ms_tune, getString(R.string.voice_settings_title));
        settings.setOnClickListener(v -> startActivity(new Intent(this, VoiceSettingsActivity.class)));
        top.addView(settings, new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        root.addView(top, full());

        LinearLayout body = column(this);
        body.setPaddingRelative(dp(this, 20), dp(this, 4), dp(this, 20), dp(this, 16));
        langBox = column(this);
        body.addView(langBox, full());
        renderLang();
        status = monoText(this, "", 12, MUTED);
        LinearLayout.LayoutParams sp = full();
        sp.topMargin = dp(this, 16);
        body.addView(status, sp);
        heard = text(this, "", 24, TEXT);
        LinearLayout.LayoutParams hp = full();
        hp.topMargin = dp(this, 8);
        body.addView(heard, hp);
        root.addView(body, full());

        logScroll = new ScrollView(this);
        log = column(this);
        log.setPaddingRelative(dp(this, 20), 0, dp(this, 20), dp(this, 8));
        logScroll.addView(log);
        root.addView(logScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        mic = new FrameLayout(this);
        mic.setBackground(oval(ACCENT));
        mic.setContentDescription(getString(R.string.voice_mic));
        mic.addView(icon(this, R.drawable.ms_mic, BG, 36), new FrameLayout.LayoutParams(dp(this, 36), dp(this, 36), Gravity.CENTER));
        mic.setOnClickListener(v -> {
            if (SpeechInput.listening()) SpeechInput.finish();
            else listen();
        });
        LinearLayout micRow = row(this);
        micRow.setGravity(Gravity.CENTER);
        micRow.setPaddingRelative(0, dp(this, 8), 0, dp(this, 24));
        micRow.addView(mic, new LinearLayout.LayoutParams(dp(this, 84), dp(this, 84)));
        root.addView(micRow, full());
        setContentView(root);

        if (state == null && getIntent().getBooleanExtra("listen", true)) listen();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra("listen", true)) listen();
    }

    @Override
    protected void onDestroy() {
        if (SpeechInput.listening()) SpeechInput.cancel();
        super.onDestroy();
    }

    private void renderLang() {
        langBox.removeAllViews();
        langBox.addView(segmented(this, VoicePrefs.LANGS, lang, true, v -> {
            lang = v;
            VoicePrefs.setLang(this, v);
            renderLang();
            if (SpeechInput.listening()) listen(); // again, in the new language
        }), full());
    }

    void listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 51);
            return;
        }
        heard.setText("");
        status.setText(getString(R.string.voice_starting));
        mic.setAlpha(1f);
        SpeechInput.start(this, lang, new SpeechInput.Callback() {
            @Override
            public void onReady(String engine) {
                status.setText(getString(R.string.voice_listening, engine));
            }

            @Override
            public void onPartial(String text) {
                heard.setText(text);
            }

            @Override
            public void onFinal(String text) {
                heard.setText(text);
                status.setText("");
                mic.setScaleX(1f);
                mic.setScaleY(1f);
                onHeard(text);
            }

            @Override
            public void onError(String message) {
                status.setText(message);
                mic.setScaleX(1f);
                mic.setScaleY(1f);
            }

            @Override
            public void onLevel(float level) {
                float s = 1f + level * 0.18f;
                mic.setScaleX(s);
                mic.setScaleY(s);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 51 && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) listen();
        else if (code == 51) status.setText(getString(R.string.voice_mic_needed));
    }

    /** A command was heard. */
    void onHeard(String text) {
        addLine(text, true);
    }

    /** A line in the log: what the user said, or what came of it. */
    void addLine(String s, boolean mine) {
        TextView l = text(this, s, 15, mine ? TEXT : MUTED);
        l.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG); // Arabic right to left, the rest left to right
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, 8);
        log.addView(l, p);
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }
}
