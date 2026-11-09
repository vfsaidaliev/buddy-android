package app.buddy.assistant;

/** A voice command CommandParser understood, ready for LocalCommands. Plain Java, so the parser can be unit-tested. */
final class Command {
    enum Type {
        CALL, SMS, ALARM, TIMER, REMINDER, FLASHLIGHT, VOLUME, RINGER, BRIGHTNESS, OPEN_APP, PHOTO, WIFI, BLUETOOTH,
        MOBILE_DATA, TIME, BATTERY, DATE, TRANSLATE, TRANSLATOR
    }

    final Type type;
    /** CALL, SMS: a contact's name or a number; OPEN_APP: the app's name. */
    String target;
    /** SMS: the message; REMINDER: what to remind of; TRANSLATE: what to translate. */
    String text;
    /** ALARM, REMINDER at a time: the hour (0-23) and minute. */
    int hour = -1, minute;
    /** TIMER: the length; REMINDER in a while: from now. */
    long seconds;
    /** FLASHLIGHT, WIFI, BLUETOOTH, MOBILE_DATA: on or off. */
    boolean on;
    /** VOLUME: media, ring or alarm. */
    String stream = "media";
    /** VOLUME, BRIGHTNESS: 0-100, or -1 with delta. */
    int level = -1;
    /** VOLUME, BRIGHTNESS: +1 up, -1 down, 0 none. */
    int delta;
    /** BRIGHTNESS: automatic. */
    boolean auto;
    /** RINGER: normal, vibrate or silent. */
    String ringer;
    /** PHOTO: the front camera. */
    boolean front;
    /** TRANSLATE: ru, en or ar. */
    String toLang;

    Command(Type type) {
        this.type = type;
    }

    @Override
    public String toString() {
        return type + "{target=" + target + ", text=" + text + ", hour=" + hour + ", minute=" + minute + ", seconds="
                + seconds + ", on=" + on + ", stream=" + stream + ", level=" + level + ", delta=" + delta + ", auto=" + auto
                + ", ringer=" + ringer + ", front=" + front + ", toLang=" + toLang + "}";
    }
}
