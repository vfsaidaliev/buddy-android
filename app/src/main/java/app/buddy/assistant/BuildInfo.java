package app.buddy.assistant;

/** Single source of the app version and package name; read by build.sh and app/build.gradle.kts. */
final class BuildInfo {
    static final String VERSION = "2.2.27";
    static final int VERSION_CODE = 196;
    /** This build's package name: "Buddy Full", installed next to the original app.buddy.assistant. */
    static final String APPLICATION_ID = "app.buddy.assistant.full";

    private BuildInfo() {
    }
}
