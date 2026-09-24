import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// --- release signing ---------------------------------------------------------
// signing/release.jks is private: it never enters this repository, and losing it
// means no future build can install over the ones it signed. A public key here
// would mean anyone holding the repository could sign an APK that Android
// installs as an update over the published one - and those installs keep their
// data. Locally the key and its passwords come from keystore.properties
// (gitignored); in CI from the KEYSTORE_* secrets, decoded to that path first.
val signingProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun signingValue(key: String, env: String): String? =
    signingProperties.getProperty(key)?.takeIf(String::isNotBlank)
        ?: System.getenv(env)?.takeIf(String::isNotBlank)

val releaseStore = rootProject.file(signingValue("storeFile", "KEYSTORE_FILE") ?: "signing/release.jks")
val releaseStorePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "KEY_PASSWORD")
val hasReleaseSigning = releaseStore.isFile &&
    releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null

// An unsigned release APK is the failure this catches: nothing installs without
// a signature, and an APK signed with a stray key cannot update an existing
// install. Stop before the build rather than publish either one.
tasks.matching {
    it.name == "preReleaseBuild" || it.name == "packageRelease" || it.name == "assembleRelease"
}.configureEach {
    doFirst {
        check(hasReleaseSigning) {
            "Release signing is not configured. Expected " + releaseStore.path +
                " plus keystore.properties (storeFile/storePassword/keyAlias/keyPassword), " +
                "or KEYSTORE_FILE/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD in the environment."
        }
    }
}

android {
    namespace = "com.webviewdp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.webviewdp"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.5"
    }

    // The published artifact is the release variant - `android:debuggable` off, so
    // no adb run-as, no heap dumps and no debug-only certificate trust - signed
    // with the private key above. The debug build type is left to the SDK's own
    // throwaway debug key (~/.android/debug.keystore): a debug APK is a local
    // artifact, published nowhere, so its identity does not have to outlive the
    // machine that built it.
    signingConfigs {
        // Created only when the key and its passwords are present. A release
        // build without them is stopped by the guard above rather than shipping
        // an APK signed with something else - or with nothing at all.
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
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
    implementation("androidx.core:core:1.13.1")
    // The proxy needs connection pooling, a cookie jar and streaming bodies.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}