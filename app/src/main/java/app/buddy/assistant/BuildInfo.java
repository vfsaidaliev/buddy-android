package app.buddy.assistant;

/** Single source of the app version and package name; read by build.sh and app/build.gradle.kts. */
final class BuildInfo {
    // "-full": Valkyrie's releases (tags v…-full); Updates.compare reads only the numbers
    static final String VERSION = "2.2.28-full";
    static final int VERSION_CODE = 197;
    /** This build's package name: "Buddy Full", installed next to the original app.buddy.assistant. */
    static final String APPLICATION_ID = "app.buddy.assistant.full";

    private BuildInfo() {
    }
}
