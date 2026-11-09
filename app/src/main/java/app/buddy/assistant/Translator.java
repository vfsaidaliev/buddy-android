package app.buddy.assistant;

import android.content.Intent;

/** The translator's voice commands ("переведи на арабский …", "включи переводчик"): they work offline too. */
final class Translator {
    private Translator() {
    }

    /** TRANSLATE and TRANSLATOR commands; false: not one of them. */
    static boolean run(VoiceCommand.Host h, Command c) {
        if (c.type == Command.Type.TRANSLATOR) {
            h.activity().startActivity(new Intent(h.activity(), TranslatorActivity.class));
            return true;
        }
        if (c.type != Command.Type.TRANSLATE) return false;
        String to = "ar".equals(c.toLang) ? "ar-EG" : "en".equals(c.toLang) ? "en-US" : "ru-RU";
        Translate.translate(h.activity(), c.text, h.lang(), to, new Translate.Done() {
            @Override
            public void ok(String text, String how) {
                h.sayIn(text, to);
                if (!how.isEmpty()) h.note(how);
            }

            @Override
            public void fail(String why) {
                h.say(why);
            }
        });
        return true;
    }
}
