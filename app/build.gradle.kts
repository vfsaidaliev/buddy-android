import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
}

// Version lives in BuildInfo.java so the Gradle and on-device (build.sh) builds always agree.
val buildInfo = file("src/main/java/app/buddy/assistant/BuildInfo.java").readText()
val appVersionName = Regex("""VERSION = "([^"]+)"""").find(buildInfo)!!.groupValues[1]
val appVersionCode = Regex("""VERSION_CODE = (\d+)""").find(buildInfo)!!.groupValues[1].toInt()
val appApplicationId = Regex("""APPLICATION_ID = "([^"]+)"""").find(buildInfo)!!.groupValues[1]

android {
    // proot for the built-in Linux ships as native libraries and must be extracted to disk to run
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Vosk, sherpa-onnx and ML Kit may each bring the C++ runtime
            pickFirsts += "**/libc++_shared.so"
        }
    }
    namespace = "app.buddy.assistant"
    compileSdk = 36

    defaultConfig {
        applicationId = appApplicationId
        minSdk = 30
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        // Valkyrie: arm64 phones only (Buddy's own proot is arm64 only too), for a smaller APK
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // Release signing comes from environment variables (set as GitHub Actions secrets).
    // Without them, assembleRelease produces an unsigned APK.
    val keystorePath = System.getenv("BUDDY_KEYSTORE")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("BUDDY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BUDDY_KEY_ALIAS") ?: "buddy"
                keyPassword = System.getenv("BUDDY_KEY_PASSWORD") ?: System.getenv("BUDDY_KEYSTORE_PASSWORD")
            }
        }
    }

    // github: the release on GitHub, which updates itself from GitHub releases.
    // fdroid: built and signed by F-Droid from this source; the same app without the self-updater (F-Droid updates it).
    flavorDimensions += "store"
    productFlavors {
        create("github") { dimension = "store" }
        create("fdroid") { dimension = "store" }
    }

    // No Google-only dependency blob and no git details in the APK: F-Droid requires the first, and both keep the
    // fdroid APK the same whoever builds it (F-Droid's reproducible build compares its APK with the one on GitHub).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            vcsInfo.include = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        buildConfig = false
    }
}

// Valkyrie's offline voices: sherpa-onnx (Piper), whose AAR is only on its GitHub releases, not on Maven. It's fetched
// here once, before anything builds, and must match this SHA-256 (from GitHub's release); it's never committed.
val sherpaVersion = "1.13.8"
val sherpaSha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
val sherpaAar = file("libs/sherpa-onnx-$sherpaVersion.aar")
fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").digest(f.readBytes())
    .joinToString("") { "%02x".format(it) }
if (!sherpaAar.exists() || sha256(sherpaAar) != sherpaSha256) {
    sherpaAar.parentFile.mkdirs()
    val part = File(sherpaAar.path + ".part")
    URI("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar")
        .toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
    val got = sha256(part)
    if (got != sherpaSha256) {
        part.delete()
        throw GradleException("sherpa-onnx-$sherpaVersion.aar: SHA-256 $got, expected $sherpaSha256")
    }
    part.renameTo(sherpaAar)
}

// Buddy Full: Shizuku's official API (optional at run time: shell_privileged works only with Shizuku running and allowed)
dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // the voice assistant's spare offline recognizer and wake phrase (models are downloaded, not in the APK)
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    // the translator offline: Google's ML Kit on the phone (its language packs are downloaded in Settings → Voice)
    implementation("com.google.mlkit:translate:17.0.3")
    // Piper voices on the phone (sherpa-onnx, fetched above), its Kotlin API's runtime, and .tar.bz2 for the voices
    implementation(files("libs/sherpa-onnx-$sherpaVersion.aar"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    implementation("org.apache.commons:commons-compress:1.27.1")
    // CommandParserTest, FuzzyTest: the voice assistant's offline commands
    testImplementation("junit:junit:4.13.2")
}
