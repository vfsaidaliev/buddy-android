package app.buddy.assistant;

/** The voice assistant's translator (ru, en, Egyptian Arabic). Filled in with step 9.3. */
final class Translator {
    private Translator() {
    }

    /** TRANSLATE and TRANSLATOR commands; false: not done here (the agent gets it). */
    static boolean run(VoiceCommand.Host h, Command c) {
        return false;
    }
}
