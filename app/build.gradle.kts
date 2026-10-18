import java.io.File
import java.util.Properties
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing is loaded from a gitignored wayfindr-signing.properties at the repo root (see
// that file). It points at the stable release keystore — the SAME key every public APK
// is signed with, so updates install in place. If the file is absent (e.g. a contributor
// just building locally), the release build falls back to the debug keystore.
val signingProps = Properties()
rootProject.file("wayfindr-signing.properties").let { f ->
    if (f.exists()) f.inputStream().use { signingProps.load(it) }
}
val hasReleaseSigning = signingProps.getProperty("storeFile")?.let { File(it).exists() } == true

// Google Maps API key is bring-your-own: put MAPS_API_KEY=... in a gitignored secrets.properties
// at the repo root. Without it the build gets the placeholder below and the app runs on the free
// stack only (OSM / offline maps, OSM / BRouter routing) — Google choices are hidden.
// The same placeholder string is checked in GoogleKey.kt.
val mapsKeyPlaceholder = "add gmaps api key here"
val mapsApiKey: String = Properties().let { p ->
    rootProject.file("secrets.properties").takeIf { it.exists() }?.inputStream()?.use { p.load(it) }
    p.getProperty("MAPS_API_KEY")?.trim()?.takeIf { it.isNotEmpty() } ?: mapsKeyPlaceholder
}

android {
    namespace = "com.example.wayfindr"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.wayfindr.app"
        // API 29 (Android 10) is the real floor: the dash link is built on WifiNetworkSpecifier
        // + ConnectivityManager.requestNetwork(specifier), both added in API 29, and that IS the
        // app. Below 29 the core feature can't run and the unguarded calls would crash, so we
        // don't pretend to support it. Also makes the API-26 notification paths always safe.
        minSdk = 29
        targetSdk = 36
        // 1.3.2 (14): two editions, standard + full (see productFlavors below).
        versionCode = 14
        versionName = "1.3.2"
        // Open-source builds are marked OSS everywhere: version shown in the app ("1.3.2-OSS",
        // "1.3.2-OSS-full") and the APK names. The update check ignores the suffix.
        versionNameSuffix = "-OSS"


        // Maps SDK reads its key from the manifest (${MAPS_API_KEY}); routing reads BuildConfig.
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsApiKey\"")

        // Ship arm64-v8a only: every modern OEM phone (OnePlus/Samsung/Xiaomi/Nothing…) is
        // 64-bit ARM; the other ABIs (armeabi-v7a/x86/x86_64) are old 32-bit or emulators. This
        // cuts the universal APK from ~107 MB to well under GitHub's 100 MB file limit so the
        // test build can live in the repo for direct download.
        ndk { abiFilters += "arm64-v8a" }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Two editions, same app ID and key (switching keeps data):
    //  - standard: no notification listener. Google Play Protect's enhanced fraud protection blocks
    //    sideloaded apps that declare one, so this is the default download.
    //  - full: adds the listener (src/full/AndroidManifest.xml) for now-playing and caller names on
    //    the dash. Play Protect may block installing it from a browser or file manager.
    flavorDimensions += "edition"
    productFlavors {
        create("standard") {
            dimension = "edition"
            isDefault = true
            buildConfigField("boolean", "MEDIA_LISTENER", "false")
        }
        create("full") {
            dimension = "edition"
            versionNameSuffix = "-full"
            buildConfigField("boolean", "MEDIA_LISTENER", "true")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = File(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // Hobby/open-source distribution: signed by the stable release keystore (from
            // signing.properties) so every published APK shares one signature and updates
            // install in place. Falls back to the debug keystore when signing.properties is
            // absent (contributors building locally) — that build just can't update users.
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
            // No git commit info in the APK (AGP otherwise adds META-INF/version-control-info).
            vcsInfo.include = false
        }
    }
    // Leave out AGP's encrypted dependency list from the APK signing block (only Play reads it).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // Generates BuildConfig.DEBUG — used to gate sensitive logging (GPS coordinates,
        // shared-location text/URLs, the signed-in uid, raw dash packets) out of release
        // builds. See util/Dbg.kt.
        buildConfig = true
        // IBRouterService.aidl — the BRouter app's routing service (offline routing).
        aidl = true
    }
}

base {
    archivesName = "Wayfindr-${android.defaultConfig.versionName}-OSS"
}

// One command for a release: `./gradlew releaseOss` (Windows: `gradlew releaseOss`).
// Builds both editions' release APKs, copies them to dist/ as Wayfindr-<version>-OSS.apk and
// Wayfindr-<version>-OSS-full.apk, and prints their checksums for the release notes.
val ossVersion = android.defaultConfig.versionName
val distDir = rootProject.layout.projectDirectory.dir("dist")
tasks.register<Copy>("releaseOss") {
    group = "distribution"
    description = "Builds the standard and full release APKs and copies them to dist/."
    dependsOn("assembleStandardRelease", "assembleFullRelease")
    from(layout.buildDirectory.dir("outputs/apk/standard/release")) {
        include("*.apk"); rename { "Wayfindr-$ossVersion-OSS.apk" }
    }
    from(layout.buildDirectory.dir("outputs/apk/full/release")) {
        include("*.apk"); rename { "Wayfindr-$ossVersion-OSS-full.apk" }
    }
    into(distDir)
    val outputs = listOf("Wayfindr-$ossVersion-OSS.apk", "Wayfindr-$ossVersion-OSS-full.apk")
        .map { distDir.file(it).asFile }
    doLast {
        println()
        println("Release APKs in dist/ (paste the sha256 lines into the release notes):")
        outputs.forEach { f ->
            val sha = MessageDigest.getInstance("SHA-256").digest(f.readBytes())
                .joinToString("") { "%02x".format(it) }
            val key = if (f.name.endsWith("-full.apk")) "sha256-full" else "sha256"
            println("  ${f.name}  (${f.length() / (1024 * 1024)} MB)")
            println("    $key: ${sha.take(12)}")
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.maps.compose)
    implementation(libs.play.services.maps)
    implementation(libs.maplibre)
    implementation(libs.maplibre.annotation)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}