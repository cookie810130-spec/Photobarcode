plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pharmacy.photobarcodemvp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pharmacy.photobarcodemvp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0-ppocrv5"
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
    implementation(project(":ppocr-sdk"))

    // Direct dependency is required because MainActivity uses OpenCVLoader.
    // QuickBird recommends 4.5.3.0 rather than 4.5.3 for Android runtime reliability.
    implementation("com.quickbirdstudios:opencv:4.5.3.0")

    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
