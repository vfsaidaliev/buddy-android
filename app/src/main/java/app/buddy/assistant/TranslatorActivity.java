package app.buddy.assistant;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import static app.buddy.assistant.Ui.*;

/**
 * The translator: Russian, English and Egyptian Arabic. Text to text; voice to voice (hear, translate, say it); and a
 * conversation of two people, with "I speak" and "They speak" (theirs recognized as Egyptian Arabic) and the
 * translation large on the screen. Arabic is shown right to left.
 */
public class TranslatorActivity extends Screen {
    private static final String M_TEXT = "text", M_VOICE = "voice", M_DIALOG = "dialog";
    private LinearLayout body;
    private String from, to, mode;
    private TextView result, how, status;
    private EditText input;
    private String lastText, lastLang;

    private SharedPreferences sp() {
        return getSharedPreferences("voice", Context.MODE_PRIVATE);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        from = sp().getString("tr_from", Translate.base(VoicePrefs.lang(this)));
        to = sp().getString("tr_to", "ar".equals(from) ? "ru" : "ar");
        mode = sp().getString("tr_mode", M_DIALOG);
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        LinearLayout top = row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPaddingRelative(dp(this, 4), dp(this, 4), dp(this, 16), dp(this, 4));
        ImageButton back = iconButton(this, R.drawable.ms_arrow_back, getString(R.string.perms_back));
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        TextView t = title(this, getString(R.string.tr_title), 18);
        t.setPaddingRelative(dp(this, 6), 0, 0, 0);
        top.addView(t, weight1());
        root.addView(top, full());
        ScrollView sv = new ScrollView(this);
        body = column(this);
        body.setPaddingRelative(dp(this, 20), dp(this, 4), dp(this, 20), dp(this, 24));
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        render();
    }

    @Override
    protected void onDestroy() {
        if (SpeechInput.listening()) SpeechInput.cancel();
        super.onDestroy();
    }

    private static String tag(String lang) {
        return "ar".equals(lang) ? "ar-EG" : "en".equals(lang) ? "en-US" : "ru-RU";
    }

    private static final String[][] LANG_OPTS = {{"ru", "RU"}, {"en", "EN"}, {"ar", "AR"}};

    private void render() {
        body.removeAllViews();
        body.addView(segmented(this, new String[][]{{M_TEXT, getString(R.string.tr_mode_text)}, {M_VOICE, getString(R.string.tr_mode_voice)},
                {M_DIALOG, getString(R.string.tr_mode_dialog)}}, mode, false, v -> {
            mode = v;
            sp().edit().putString("tr_mode", v).apply();
            render();
        }), full());

        LinearLayout langs = row(this);
        langs.setGravity(Gravity.CENTER_VERTICAL);
        langs.addView(segmented(this, LANG_OPTS, from, true, v -> setLangs(v, v.equals(to) ? from : to)), weight1());
        ImageButton swap = iconButton(this, R.drawable.ms_arrow_back, getString(R.string.tr_swap));
        swap.setRotation(180);
        swap.setOnClickListener(v -> setLangs(to, from));
        langs.addView(swap, new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        langs.addView(segmented(this, LANG_OPTS, to, true, v -> setLangs(v.equals(from) ? to : from, v)), weight1());
        body.addView(langs, gap(12));

        if ("ar".equals(from) || "ar".equals(to)) {
            body.addView(segmented(this, new String[][]{{"egyptian", getString(R.string.tr_arabic_egyptian)},
                    {"msa", getString(R.string.tr_arabic_msa)}}, Translate.egyptian(this) ? "egyptian" : "msa", false, v -> {
                Translate.setEgyptian(this, "egyptian".equals(v));
                render();
            }), gap(8));
        }

        status = monoText(this, "", 12, MUTED);
        if (M_TEXT.equals(mode)) {
            input = new EditText(this);
            input.setTextColor(TEXT);
            input.setHintTextColor(MUTED);
            input.setHint(getString(R.string.tr_input_hint));
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            input.setMinLines(3);
            input.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            input.setBackground(rounded(this, CARD, 12));
            input.setPaddingRelative(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
            body.addView(input, gap(12));
            Button go = button(this, getString(R.string.tr_translate), true);
            go.setOnClickListener(v -> {
                String s = input.getText().toString().trim();
                if (!s.isEmpty()) translate(s, from, to, false);
            });
            body.addView(go, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 48)));
        } else if (M_VOICE.equals(mode)) {
            body.addView(bigButton(getString(R.string.tr_speak, SpeechInput.languageName(this, tag(from))), () -> listen(from, to)), gap(16));
        } else {
            body.addView(bigButton(getString(R.string.tr_i_speak, SpeechInput.languageName(this, tag(from))), () -> listen(from, to)), gap(16));
            body.addView(bigButton(getString(R.string.tr_they_speak, SpeechInput.languageName(this, tag(to))), () -> listen(to, from)), gap(10));
        }
        body.addView(status, gap(12));
        result = text(this, "", M_DIALOG.equals(mode) ? 30 : 22, TEXT);
        result.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG); // Arabic right to left
        result.setTextIsSelectable(true);
        body.addView(result, gap(8));
        how = monoText(this, "", 12, MUTED);
        body.addView(how, gap(4));
        Button again = outlinedButton(this, getString(R.string.tr_say_again), ACCENT_TEXT);
        again.setOnClickListener(v -> {
            if (lastText != null) Speaker.say(this, lastText, lastLang);
        });
        body.addView(again, gap(8));
    }

    private Button bigButton(String label, Runnable r) {
        Button b = button(this, label, true);
        b.setMinHeight(dp(this, 72));
        b.setTextSize(18);
        b.setOnClickListener(v -> r.run());
        return b;
    }

    private LinearLayout.LayoutParams gap(int d) {
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, d);
        return p;
    }

    private void setLangs(String f, String t) {
        from = f;
        to = t;
        sp().edit().putString("tr_from", f).putString("tr_to", t).apply();
        render();
    }

    private void listen(String speak, String into) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 52);
            return;
        }
        Speaker.stop();
        status.setText(getString(R.string.voice_starting));
        SpeechInput.start(this, tag(speak), new SpeechInput.Callback() {
            @Override
            public void onReady(String engine) {
                status.setText(getString(R.string.voice_listening, engine));
            }

            @Override
            public void onPartial(String text) {
                status.setText(text);
            }

            @Override
            public void onFinal(String text) {
                status.setText(text);
                translate(text, speak, into, true);
            }

            @Override
            public void onError(String message) {
                status.setText(message);
            }
        });
    }

    /** Translates and shows it (large); speak: and says it in the other language. */
    private void translate(String text, String f, String t, boolean speak) {
        result.setText("…");
        how.setText("");
        Translate.translate(this, text, f, t, new Translate.Done() {
            @Override
            public void ok(String out, String label) {
                if (isFinishing()) return;
                result.setText(out);
                how.setText(label);
                lastText = out;
                lastLang = tag(t);
                if (speak) Speaker.say(TranslatorActivity.this, out, tag(t));
            }

            @Override
            public void fail(String why) {
                if (isFinishing()) return;
                result.setText("");
                how.setText(why);
            }
        });
    }
}
