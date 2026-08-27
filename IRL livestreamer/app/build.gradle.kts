dependencies {
    // UI Android Standar
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Google Play Services untuk Tracking GPS Real-Time
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // RTMP Streaming Engine (Sangat ringan, stabil, & tidak membebani prosesor Helio G36)
    implementation("com.github.pedroSG94.RootEncoder:rtmp:2.4.3")
}
