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
        Speaker.prepare(this, () -> {
            if (!isFinishing()) render(); // the phone's own voices, once its TTS is up
        });
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

        // Valkyrie's voice: per language, the voice, its pitch and speed; Piper voices to download
        body.addView(sectionLabel(this, getString(R.string.speak_title)));
        body.addView(switchRow(this, getString(R.string.speak_replies), getString(R.string.speak_replies_info),
                VoicePrefs.speakReplies(this), on -> VoicePrefs.setSpeakReplies(this, on)), full());
        for (String l : new String[]{"ru", "en", "ar"}) body.addView(voiceBlock(l), topGap(14));
        body.addView(note(getString(R.string.speak_piper_info)));
        for (String[] v : PiperVoices.ALL) body.addView(piperRow(v), topGap(10));

        // Valkyrie's character
        body.addView(sectionLabel(this, getString(R.string.persona_title)));
        body.addView(switchRow(this, getString(R.string.persona_harsh), getString(R.string.persona_harsh_info),
                VoicePrefs.harsh(this), on -> VoicePrefs.setHarsh(this, on)), full());
        body.addView(switchRow(this, getString(R.string.persona_mat), getString(R.string.persona_mat_info),
                VoicePrefs.mat(this), on -> VoicePrefs.setMat(this, on)), topGap(4));

        // the translator: Arabic variant online, and ML Kit's offline languages
        body.addView(sectionLabel(this, getString(R.string.tr_title)));
        Button tr = outlinedButton(this, getString(R.string.tr_open), ACCENT_TEXT);
        tr.setOnClickListener(v -> startActivity(new Intent(this, TranslatorActivity.class)));
        body.addView(tr, wrap());
        body.addView(note(getString(R.string.tr_arabic_info)));
        body.addView(segmented(this, new String[][]{{"egyptian", getString(R.string.tr_arabic_egyptian)},
                {"msa", getString(R.string.tr_arabic_msa)}}, Translate.egyptian(this) ? "egyptian" : "msa", false, v -> {
            Translate.setEgyptian(this, "egyptian".equals(v));
            render();
        }), topGap(8));
        body.addView(note(getString(R.string.tr_mlkit_info)));
        LinearLayout ml = column(this);
        body.addView(ml, full());
        Translate.downloaded(have -> {
            if (isFinishing()) return;
            for (String l : new String[]{"ru", "ar"}) ml.addView(mlkitRow(l, have.contains(l)), topGap(10));
        });

        // the wake phrase (off by default)
        body.addView(sectionLabel(this, getString(R.string.wake_title)));
        body.addView(warning(this, getString(R.string.wake_warning)), full());
        boolean wake = WakeWordService.enabled(this);
        body.addView(switchRow(this, getString(R.string.wake_switch), getString(R.string.wake_switch_info,
                WakeWordService.phrase(this)), wake, on -> {
            String ph = WakeWordService.phrase(this);
            if (on && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 53);
            } else if (on && !VoskModels.installed(this, WakeWordService.lang(ph))) {
                android.widget.Toast.makeText(this, getString(R.string.wake_need_model,
                        SpeechInput.languageName(this, WakeWordService.lang(ph))), android.widget.Toast.LENGTH_LONG).show();
            } else {
                WakeWordService.setEnabled(this, on);
            }
            body.postDelayed(this::render, 300);
        }), topGap(8));
        if (wake) {
            String why = WakeWordService.pausedWhy();
            body.addView(note(!WakeWordService.running() ? getString(R.string.wake_not_running)
                    : why != null ? getString(R.string.wake_paused, why) : getString(R.string.wake_listening, WakeWordService.phrase(this))));
        }
        LinearLayout phraseRow = row(this);
        phraseRow.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.EditText phrase = new android.widget.EditText(this);
        phrase.setText(WakeWordService.phrase(this));
        phrase.setTextColor(TEXT);
        phrase.setSingleLine(true);
        phraseRow.addView(phrase, weight1());
        Button save = outlinedButton(this, getString(R.string.wake_save), ACCENT_TEXT);
        save.setOnClickListener(v -> {
            String p = phrase.getText().toString().trim();
            if (p.isEmpty()) return;
            WakeWordService.setPhrase(this, p);
            if (WakeWordService.enabled(this)) { // listen for the new phrase
                WakeWordService.setEnabled(this, false);
                WakeWordService.setEnabled(this, true);
            }
            render();
        });
        phraseRow.addView(save);
        body.addView(phraseRow, topGap(8));
        java.util.List<String> unknown = WakeWordService.unknownWords(this, WakeWordService.phrase(this));
        if (!unknown.isEmpty()) {
            TextView u = note(getString(R.string.wake_unknown_words, android.text.TextUtils.join(", ", unknown)));
            u.setTextColor(ERR);
            body.addView(u);
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

    private static String tagOf(String l) {
        return "ar".equals(l) ? "ar-EG" : "en".equals(l) ? "en-US" : "ru-RU";
    }

    /** What a language speaks with now, for the screen. */
    private String voiceLabel(String l) {
        String c = VoicePrefs.voice(this, l), piper = Speaker.piperFor(this, l);
        if (piper != null) return "Piper · " + PiperVoices.of(piper)[2] + ("kareem".equals(piper) ? " · " + getString(R.string.speak_not_egyptian) : "");
        android.speech.tts.Voice v = Speaker.systemVoice(this, l, tagOf(l));
        String sys = v == null ? getString(R.string.speak_system_default) : getString(R.string.speak_system, v.getName(), v.getLocale().toLanguageTag());
        if ("ar".equals(l) && v != null && !"EG".equals(v.getLocale().getCountry())) sys += " · " + getString(R.string.speak_no_eg);
        return ("auto".equals(c) ? getString(R.string.speak_auto) + " · " : "") + sys;
    }

    private LinearLayout voiceBlock(String l) {
        LinearLayout b = column(this);
        b.setPaddingRelative(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
        b.setBackground(rounded(this, CARD, 14));
        b.addView(title(this, SpeechInput.languageName(this, tagOf(l)), 15));
        b.addView(text(this, voiceLabel(l), 13, MUTED));
        LinearLayout btns = row(this);
        Button pick = outlinedButton(this, getString(R.string.speak_pick), ACCENT_TEXT);
        pick.setOnClickListener(v -> pickVoice(l));
        btns.addView(pick);
        Button hear = outlinedButton(this, getString(R.string.speak_listen), ACCENT_TEXT);
        hear.setOnClickListener(v -> {
            Speaker.stop();
            Speaker.say(this, getString("ar".equals(l) ? R.string.speak_sample_ar : "en".equals(l) ? R.string.speak_sample_en
                    : R.string.speak_sample_ru), tagOf(l));
        });
        LinearLayout.LayoutParams hp = wrap();
        hp.setMarginStart(dp(this, 8));
        btns.addView(hear, hp);
        b.addView(btns, topGap(8));
        b.addView(slider(getString(R.string.speak_pitch), VoicePrefs.pitch(this, l), x -> VoicePrefs.setPitch(this, l, x)), topGap(6));
        b.addView(slider(getString(R.string.speak_rate), VoicePrefs.rate(this, l), x -> VoicePrefs.setRate(this, l, x)), topGap(2));
        return b;
    }

    private interface FloatSet {
        void set(float v);
    }

    /** A 0.50 to 1.50 slider with its value. */
    private LinearLayout slider(String label, float value, FloatSet set) {
        LinearLayout r = row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = monoText(this, String.format(java.util.Locale.ROOT, "%s %.2f", label, value), 12, MUTED);
        r.addView(t, new LinearLayout.LayoutParams(dp(this, 110), LinearLayout.LayoutParams.WRAP_CONTENT));
        android.widget.SeekBar s = new android.widget.SeekBar(this);
        s.setMax(100);
        s.setProgress(Math.round((value - 0.5f) * 100));
        s.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar sb, int p, boolean user) {
                float v = 0.5f + p / 100f;
                t.setText(String.format(java.util.Locale.ROOT, "%s %.2f", label, v));
                if (user) set.set(v);
            }

            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {}
        });
        r.addView(s, weight1());
        return r;
    }

    /** Auto, a Piper voice, or one of the phone's own (a male-sounding name is marked; Android doesn't say). */
    private void pickVoice(String l) {
        LinearLayout c = column(this);
        c.addView(title(this, getString(R.string.speak_pick_title, SpeechInput.languageName(this, tagOf(l))), 18));
        java.util.List<String[]> opts = new java.util.ArrayList<>();
        opts.add(new String[]{"auto", getString(R.string.speak_auto)});
        for (String[] v : PiperVoices.ALL) {
            if (!v[1].equals(l)) continue;
            boolean have = PiperVoices.installed(this, v[0]);
            opts.add(new String[]{have ? "piper:" + v[0] : null, "Piper · " + v[2] + ("kareem".equals(v[0])
                    ? " · " + getString(R.string.speak_not_egyptian) : "") + (have ? "" : " · " + getString(R.string.speak_download_first))});
        }
        for (android.speech.tts.Voice v : Speaker.systemVoices(l)) {
            opts.add(new String[]{"system:" + v.getName(), v.getName() + " (" + v.getLocale().toLanguageTag() + ")"
                    + (Speaker.soundsMale(v) ? " ♂" : "") + (v.isNetworkConnectionRequired() ? " · online" : "")});
        }
        final android.app.Dialog[] d = {null};
        String cur = VoicePrefs.voice(this, l);
        for (String[] o : opts) {
            TextView t = text(this, (o[0] != null && o[0].equals(cur) ? "● " : "○ ") + o[1], 15, o[0] == null ? MUTED : TEXT);
            t.setPaddingRelative(0, dp(this, 10), 0, dp(this, 10));
            if (o[0] != null) {
                t.setBackgroundResource(ripple(this, false));
                t.setOnClickListener(v -> {
                    VoicePrefs.setVoice(this, l, o[0]);
                    if (d[0] != null) d[0].dismiss();
                    render();
                });
            }
            c.addView(t, full());
        }
        d[0] = bottomSheet(this, c);
        d[0].show();
    }

    private LinearLayout piperRow(String[] v) {
        LinearLayout r = row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout t = column(this);
        t.addView(title(this, "Piper · " + v[2] + " · " + SpeechInput.languageName(this, tagOf(v[1])), 15));
        TextView st = text(this, "", 13, MUTED);
        t.addView(st);
        r.addView(t, weight1());
        ModelFetch.Job running = PiperVoices.job(v[0]);
        if (running != null) {
            st.setText(getString(R.string.voice_downloading, percent(running.done, running.total)));
            Button cancel = outlinedButton(this, getString(R.string.voice_cancel), MUTED);
            cancel.setOnClickListener(x -> running.cancel());
            r.addView(cancel);
            body.removeCallbacks(tick);
            body.postDelayed(tick, 1000);
        } else if (PiperVoices.installed(this, v[0])) {
            st.setText(getString(R.string.voice_installed, Formatter.formatShortFileSize(this, PiperVoices.size(this, v[0]))));
            st.setTextColor(OK);
            Button del = outlinedButton(this, getString(R.string.voice_delete), ERR);
            del.setOnClickListener(x -> {
                PiperVoices.delete(this, v[0]);
                render();
            });
            r.addView(del);
        } else {
            st.setText(getString(R.string.voice_size_about, v[4]));
            Button dl = outlinedButton(this, getString(R.string.voice_download), ACCENT_TEXT);
            dl.setOnClickListener(x -> {
                PiperVoices.download(this, v[0], new ModelFetch.Progress() {
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

    private static final java.util.Set<String> mlkitBusy = new java.util.HashSet<>();

    private LinearLayout mlkitRow(String lang, boolean have) {
        LinearLayout r = row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout t = column(this);
        String tag = "ar".equals(lang) ? "ar" : "ru-RU";
        t.addView(title(this, "ML Kit · " + SpeechInput.languageName(this, tag), 15));
        TextView st = text(this, "", 13, MUTED);
        t.addView(st);
        r.addView(t, weight1());
        if (mlkitBusy.contains(lang)) {
            st.setText(getString(R.string.voice_downloading, ""));
        } else if (have) {
            st.setText(getString("ar".equals(lang) ? R.string.tr_mlkit_have_ar : R.string.tr_mlkit_have));
            st.setTextColor(OK);
            Button del = outlinedButton(this, getString(R.string.voice_delete), ERR);
            del.setOnClickListener(v -> Translate.delete(lang, e -> {
                if (!isFinishing()) render();
            }));
            r.addView(del);
        } else {
            st.setText(getString(R.string.voice_size_about, "30"));
            Button dl = outlinedButton(this, getString(R.string.voice_download), ACCENT_TEXT);
            dl.setOnClickListener(v -> {
                mlkitBusy.add(lang);
                Translate.download(lang, e -> {
                    mlkitBusy.remove(lang);
                    if (e != null && !isFinishing()) {
                        android.widget.Toast.makeText(this, getString(R.string.voice_download_failed, e),
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                    if (!isFinishing()) render();
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
