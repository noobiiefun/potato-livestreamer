plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // Disamakan dengan modul android-app (potato-livestreamer utama) supaya
    // gampang digabung sebagai satu app dua "mode" di masa depan.
    namespace = "com.potato.livestreamer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.potato.livestreamer"
        // API 26 (Android 8.0) adalah syarat minimum Android Go Edition,
        // sesuai yang ditulis di readme.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // PENTING untuk HP Android Go (mis. Xiaomi Redmi A3): banyak HP kelas
        // ini menjalankan sistem 32-bit-only walau chipnya 64-bit-capable,
        // demi hemat RAM. Kalau ini tidak diisi, APK release bisa gagal
        // diinstall (INSTALL_FAILED_NO_MATCHING_ABIS) di beberapa unit.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    // UI Android Standar
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Google Play Services untuk Tracking GPS Real-Time
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Media3 ExoPlayer untuk decode + render video/audio MPEG-TS (Mode Relay/PC)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.media3:media3-extractor:1.4.1")

    // RTMP Streaming Engine (RootEncoder / pedroSG94).
    implementation("com.github.pedroSG94.RootEncoder:library:2.5.4")

    // Peta live-tracking — pakai osmdroid (OpenStreetMap)
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
