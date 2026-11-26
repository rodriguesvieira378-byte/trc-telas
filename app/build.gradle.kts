plugins {
    id("com.android.application")
}

android {
    namespace = "com.cassio.telashare"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cassio.trctelas"
        minSdk = 24
        targetSdk = 34
        versionCode = 8
        versionName = "2.6"
        // só processadores de celular (ARM): deixa o APK bem menor
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("io.getstream:stream-webrtc-android:1.3.10") // WebRTC nativo (codificação por hardware)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")        // WebSocket da sinalização
}
