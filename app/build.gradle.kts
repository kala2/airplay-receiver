import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing lives OUTSIDE the project so no secret can end up in backups or git:
// ~/airplayrx-keys/signing.properties (override with env AIRPLAYRX_SIGNING) containing
// storeFile, storePassword, keyAlias, keyPassword. Without it, release builds are unsigned.
val signingProps: Properties? = (System.getenv("AIRPLAYRX_SIGNING")
    ?: "${System.getProperty("user.home")}/airplayrx-keys/signing.properties")
    .let { file(it) }
    .takeIf { it.isFile }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

// Version: semantic-release passes -PversionName/-PversionCode for release builds (computed from
// Conventional Commits; nothing is written to the repo). Local builds use the latest vX.Y.Z tag
// with a "-dev" suffix, or 0.0.0-dev before the first release / in shallow clones.
val releaseTag: String? = runCatching {
    providers.exec {
        commandLine("git", "describe", "--tags", "--match", "v[0-9]*.[0-9]*.[0-9]*", "--abbrev=0")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().removePrefix("v").ifEmpty { null }
}.getOrNull()

fun versionCodeOf(version: String): Int {
    val (major, minor, patch) = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(version)?.destructured
        ?: error("version must be MAJOR.MINOR.PATCH, was '$version'")
    require(minor.toInt() < 100 && patch.toInt() < 100) { "minor/patch must be < 100" }
    return maxOf(1, major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt())
}

android {
    namespace = "dev.personal.airplayrx"
    // "Latest installed" platform; bump together with sdkmanager "platforms;android-XX".
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.personal.airplayrx"
        minSdk = 31
        // Stay on 34 so Android 15 boot-time foreground-service restrictions never apply.
        targetSdk = 34
        // versionCode = MAJOR·10000 + MINOR·100 + PATCH: Android only upgrades over a lower code.
        versionName = (project.findProperty("versionName") as String?) ?: "${releaseTag ?: "0.0.0"}-dev"
        versionCode = (project.findProperty("versionCode") as String?)?.toInt()
            ?: versionCodeOf(releaseTag ?: "0.0.0")

        ndk {
            // From the airplayrx.abis property (gradle.properties, overridable per developer in
            // ~/.gradle/gradle.properties); scripts/build-openssl.sh builds libcrypto for the same.
            abiFilters += (System.getenv("AIRPLAYRX_ABIS") ?: project.property("airplayrx.abis") as String)
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
        externalNativeBuild {
            cmake {
                // Static C++ runtime: we ship a single .so, so there is nothing to share it with.
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    signingConfigs {
        if (signingProps != null) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8: shrink + optimise; JNI entry points are kept by proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    ndkVersion = "30.0.16248370"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Coroutines for control flow (start/stop sequencing in ReceiverService).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
