// This file defines build configuration and dependencies for the Whisper Remote Android application module (:app).
// It configures compileSdk, minSdk, signingConfigs, build features (BuildConfig generation), and dependencies.

// Reason for edit: Bumped versionCode = 4, versionName = "1.0.1" for release deployment per contract F-15.
// Reason for edit: Set applicationId = com.cyrus_studio.whisperunchained (reflecting cyrus-studio.com domain) per contract F-14.
// Reason for edit: Set applicationId = com.cyrusstudio.whisperunchained, versionCode = 3, versionName = "1.0.0" per contract F-13.
// Reason for edit: Enabled buildFeatures.buildConfig to expose BuildConfig.DEBUG for privacy log gating per contract F-9.

import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(FileInputStream(localPropertiesFile))
    }
}

val releaseStoreFile = localProperties.getProperty("RELEASE_STORE_FILE") ?: System.getenv("RELEASE_STORE_FILE")
val releaseStorePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD") ?: System.getenv("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS") ?: System.getenv("RELEASE_KEY_ALIAS")
val releaseKeyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD") ?: System.getenv("RELEASE_KEY_PASSWORD")

android {
    namespace = "com.example.whisperremote"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cyrus_studio.whisperunchained"
        minSdk = 34
        targetSdk = 36
        versionCode = 4
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (!releaseStoreFile.isNullOrBlank() && file(releaseStoreFile).exists()) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Reason for edit: Expose BuildConfig.DEBUG for release log gating per contract F-9
    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.okhttp)
    // Updated to match new dependency: Google Play Services Code Scanner for camera QR code pairing
    implementation(libs.play.services.code.scanner)
    // Updated to match new dependency: AndroidX Security Crypto for hardware-backed EncryptedSharedPreferences
    implementation(libs.androidx.security.crypto)
    testImplementation(libs.junit)
    // Updated to match new dependency: org.json for JVM unit testing of JSON parsing
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}