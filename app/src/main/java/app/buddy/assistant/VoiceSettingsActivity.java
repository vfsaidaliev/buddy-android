package app.buddy.assistant;

import android.content.Intent;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import static app.buddy.assistant.Ui.*;

/**
 * Settings → Voice: how Buddy Full listens (auto / online / offline), its language, and the offline packages:
 * Android's on-device recognition per language (saying honestly which Arabic it has) and the Vosk models.
 */
public class VoiceSettingsActivity extends Screen {
    private LinearLayout body;
    /** While a download runs, the screen shows its progress again every second. */
    private final Runnable tick = this::render;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        LinearLayout top = row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPaddingRelative(dp(this, 4), dp(this, 4), dp(this, 16), dp(this, 4));
        ImageButton back = iconButton(this, R.drawable.ms_arrow_back, getString(R.string.perms_back));
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        TextView t = title(this, getString(R.string.voice_settings_title), 18);
        t.setPaddingRelative(dp(this, 6), 0, 0, 0);
        top.addView(t, weight1());
        root.addView(top, full());
        ScrollView sv = new ScrollView(this);
        body = column(this);
        body.setPaddingRelative(dp(this, 20), dp(this, 4), dp(this, 20), dp(this, 32));
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    protected void onPause() {
        body.removeCallbacks(tick);
        super.onPause();
    }

    void render() {
        body.removeCallbacks(tick);
        body.removeAllViews();
        Button open = button(this, getString(R.string.voice_open), true);
        open.setOnClickListener(v -> startActivity(new Intent(this, VoiceActivity.class)));
        body.addView(open, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 48)));

        body.addView(sectionLabel(this, getString(R.string.voice_mode)));
        body.addView(segmented(this, new String[][]{{VoicePrefs.AUTO, getString(R.string.voice_mode_auto)},
                {VoicePrefs.ONLINE, getString(R.string.voice_mode_online)},
                {VoicePrefs.OFFLINE, getString(R.string.voice_mode_offline)}}, VoicePrefs.mode(this), false, v -> {
            VoicePrefs.setMode(this, v);
            render();
        }), full());
        body.addView(note(getString(R.string.voice_mode_info)));

        body.addView(sectionLabel(this, getString(R.string.voice_language)));
        body.addView(segmented(this, VoicePrefs.LANGS, VoicePrefs.lang(this), true, v -> {
            VoicePrefs.setLang(this, v);
            render();
        }), full());

        // Android's on-device recognition, per language
        body.addView(sectionLabel(this, getString(R.string.voice_ondevice)));
        if (!OnDeviceSpeech.available(this)) {
            body.addView(note(getString(R.string.voice_ondevice_none)));
        } else {
            for (String[] l : VoicePrefs.LANGS) {
                LinearLayout r = column(this);
                TextView name = title(this, SpeechInput.languageName(this, l[0]) + " (" + l[0] + ")", 15);
                r.addView(name);
                TextView st = text(this, getString(R.string.voice_checking), 13, MUTED);
                r.addView(st);
                LinearLayout.LayoutParams p = full();
                p.topMargin = dp(this, 10);
                body.addView(r, p);
                OnDeviceSpeech.check(this, l[0], s -> {
                    if (isFinishing()) return;
                    if (s.error != null) {
                        st.setText(getString(R.string.voice_ondevice_unknown));
                        return;
                    }
                    String inst = OnDeviceSpeech.pick(s.installed, l[0]);
                    String sup = OnDeviceSpeech.pick(s.supported, l[0]);
                    String pend = OnDeviceSpeech.pick(s.pending, l[0]);
                    String want = l[0].toLowerCase(java.util.Locale.ROOT);
                    if (inst != null) {
                        st.setText(inst.equals(want) ? getString(R.string.voice_ondevice_ready, inst)
                                : getString(R.string.voice_ondevice_ready_other, inst, l[0]));
                        st.setTextColor(OK);
                    } else if (pend != null) {
                        st.setText(getString(R.string.voice_ondevice_pending, pend));
                    } else if (sup != null) {
                        st.setText(sup.equals(want) ? getString(R.string.voice_ondevice_can, sup)
                                : getString(R.string.voice_ondevice_can_other, sup, l[0]));
                        Button dl = outlinedButton(this, getString(R.string.voice_download), ACCENT_TEXT);
                        dl.setOnClickListener(v -> {
                            OnDeviceSpeech.download(this, sup);
                            st.setText(getString(R.string.voice_ondevice_pending, sup));
                            dl.setEnabled(false);
                        });
                        r.addView(dl, wrap());
                    } else {
                        st.setText(getString(R.string.voice_ondevice_no, l[0]));
                    }
                });
            }
        }

        // Vosk, the spare offline recognizer for Russian and English
        body.addView(sectionLabel(this, getString(R.string.voice_vosk)));
        body.addView(note(getString(R.string.voice_vosk_info)));
        for (String[] m : VoskModels.ALL) body.addView(voskRow(m), topGap(10));
    }

    private LinearLayout voskRow(String[] m) {
        LinearLayout r = row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout t = column(this);
        t.addView(title(this, SpeechInput.languageName(this, m[0]) + " · " + m[1], 15));
        TextView st = text(this, "", 13, MUTED);
        t.addView(st);
        r.addView(t, weight1());
        boolean have = VoskModels.installed(this, m[0]);
        ModelFetch.Job running = VoskModels.job(m[0]);
        if (running != null) {
            st.setText(getString(R.string.voice_downloading, percent(running.done, running.total)));
            Button cancel = outlinedButton(this, getString(R.string.voice_cancel), MUTED);
            cancel.setOnClickListener(v -> running.cancel());
            r.addView(cancel);
            body.removeCallbacks(tick);
            body.postDelayed(tick, 1000);
        } else if (have) {
            st.setText(getString(R.string.voice_installed, Formatter.formatShortFileSize(this, VoskModels.size(this, m[0]))));
            st.setTextColor(OK);
            Button del = outlinedButton(this, getString(R.string.voice_delete), ERR);
            del.setOnClickListener(v -> {
                VoskModels.delete(this, m[0]);
                render();
            });
            r.addView(del);
        } else {
            st.setText(getString(R.string.voice_size_about, m[3]));
            Button dl = outlinedButton(this, getString(R.string.voice_download), ACCENT_TEXT);
            dl.setOnClickListener(v -> {
                VoskModels.download(this, m[0], new ModelFetch.Progress() {
                    @Override
                    public void onProgress(long done, long total) {
                    }

                    @Override
                    public void onDone(String error) {
                        if (error != null && !"cancelled".equals(error) && !isFinishing()) {
                            android.widget.Toast.makeText(VoiceSettingsActivity.this,
                                    getString(R.string.voice_download_failed, error), android.widget.Toast.LENGTH_LONG).show();
                        }
                        if (!isFinishing()) render();
                    }
                });
                render();
            });
            r.addView(dl);
        }
        return r;
    }

    static String percent(long done, long total) {
        return total > 0 ? (done * 100 / total) + "%" : (done / (1024 * 1024)) + " MB";
    }

    private TextView note(String s) {
        TextView t = text(this, s, 13, MUTED);
        t.setPaddingRelative(0, dp(this, 6), 0, 0);
        return t;
    }

    private LinearLayout.LayoutParams topGap(int d) {
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, d);
        return p;
    }
}
