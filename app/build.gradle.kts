// This file defines build configuration and dependencies for the Whisper Remote Android application module (:app).
// It configures compileSdk, minSdk, signingConfigs, build features (BuildConfig generation), and dependencies.

// Reason for edit: Bumped versionCode = 8, versionName = "1.2.0" and configured Jetpack Compose for v1.2 makeover per F-20, F-21.
// Reason for edit: Added dist flavor dimension (play and full flavors) with versionCode = 7, versionName = "1.1.0" per contract F-18.
// Reason for edit: Bumped versionCode = 6, versionName = "1.0.3" for Done button and outside tap consumption (F-17).
// Reason for edit: Bumped versionCode = 5, versionName = "1.0.2" for first-run checklist (F-16) and smart cleanup default (K-10).
// Reason for edit: Bumped versionCode = 4, versionName = "1.0.1" for release deployment per contract F-15.
// Reason for edit: Set applicationId = com.cyrus_studio.whisperunchained (reflecting cyrus-studio.com domain) per contract F-14.
// Reason for edit: Set applicationId = com.cyrusstudio.whisperunchained, versionCode = 3, versionName = "1.0.0" per contract F-13.
// Reason for edit: Enabled buildFeatures.buildConfig to expose BuildConfig.DEBUG for privacy log gating per contract F-9.

import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
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
        // Reason for edit: Bump versionCode to 9 for v1.2 makeover update per F-24
        versionCode = 9
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "dist"
    productFlavors {
        create("play") {
            dimension = "dist"
            buildConfigField("boolean", "FEATURE_INTERNAL_AUDIO", "false")
        }
        create("full") {
            dimension = "dist"
            applicationIdSuffix = ".full"
            buildConfigField("boolean", "FEATURE_INTERNAL_AUDIO", "true")
        }
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
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Reason for edit: Enable android resources for Robolectric screenshot tests
        unitTests.isIncludeAndroidResources = true
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

    // Jetpack Compose & Material 3 (v1.2 UI Makeover per F-20, F-21)
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    testImplementation(libs.junit)
    // Updated to match new dependency: org.json for JVM unit testing of JSON parsing
    testImplementation("org.json:json:20240303")
    testImplementation(libs.roborazzi.core)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Reason for edit: Upgrade ASM to 9.9 for Java 25 compatibility in Robolectric tests
    testImplementation("org.ow2.asm:asm:9.9")
    testImplementation("org.ow2.asm:asm-commons:9.9")
    testImplementation("org.ow2.asm:asm-util:9.9")
    testImplementation("org.ow2.asm:asm-analysis:9.9")
    testImplementation("org.ow2.asm:asm-tree:9.9")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}