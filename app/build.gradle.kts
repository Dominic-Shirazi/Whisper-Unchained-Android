plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.whisperremote"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.whisperremote"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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