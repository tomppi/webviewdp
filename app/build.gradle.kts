plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.webviewdp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.webviewdp"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.4"
    }

    // Committed on purpose, and used by both build types: a sideload build has
    // to keep one identity, or Android refuses to install the next one over it
    // (INSTALL_FAILED_UPDATE_INCOMPATIBLE) and getting past that uninstalls -
    // taking the app's data. The certificate is public, so anyone with this
    // repository can sign a build the device accepts as an update over the
    // published one; see signing/README.md.
    signingConfigs {
        create("stable") {
            storeFile = file("../signing/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            // The published artifact is this variant: `android:debuggable` off,
            // so no adb run-as, no heap dumps and no debug-only certificate
            // trust. Signed with the same key as the debug builds already on the
            // phone, so it installs over one and keeps its data.
            signingConfig = signingConfigs.getByName("stable")
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
}