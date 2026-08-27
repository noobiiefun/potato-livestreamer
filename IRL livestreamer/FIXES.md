# FIXES.md — Catatan Perapian "IRL livestreamer"

Sesi ini merapikan folder `IRL livestreamer/` dari repo `potato-livestreamer`.
Kode sebelumnya adalah draf awal yang **belum pernah berhasil di-build**
(banyak error struktural). Dokumen ini adalah tracker progres untuk sesi AI
berikutnya — baca ini dulu sebelum lanjut.

## Status saat ini
🟡 **Belum diuji nyata di Android Studio / device.** Semua perbaikan di bawah
dilakukan lewat pembacaan kode manual (tidak ada Android SDK/emulator di
lingkungan yang mengerjakan ini). Yang WAJIB dilakukan berikutnya:

1. Buka modul ini di Android Studio, jalankan Gradle Sync, lihat apakah
   dependency RootEncoder (`com.github.pedroSG94.RootEncoder:library:2.4.3`)
   benar-benar resolve dari JitPack.
2. Cek versi API `com.pedro.common.ConnectChecker` yang benar-benar
   ter-download — nama method callback (`onConnectionSuccess`,
   `onConnectionFailed`, dst.) bisa beda antar versi minor RootEncoder.
   Kalau Android Studio menandai "does not override anything", cek Wiki:
   https://github.com/pedroSG94/RootEncoder/wiki
3. Build & jalankan langsung di HP Android Go asli (bukan emulator) — GPS dan
   hardware encoder emulator tidak representatif.

## Bug yang diperbaiki

| # | File | Masalah | Perbaikan |
|---|------|---------|-----------|
| 1 | `AndroidManifest.xml`, `activity_main.xml` | Namespace XML salah: `xmlns:android="http://android.com"` (harusnya `http://schemas.android.com/apk/res/android`), begitu juga `xmlns:app`. Ini bikin semua atribut `android:`/`app:` tidak dikenali — **project tidak akan pernah compile**. | Diganti ke namespace resmi yang benar. |
| 2 | `activity_main.xml` | Atribut ConstraintLayout yang dipakai (`app:layout_top_toTopOf`, `app:layout_bottom_toTopOf`, `app:layout_leading_toLeadingOf`, `app:layout_bottom_toBottomOf`) **tidak ada** di ConstraintLayout asli — nama itu tidak pernah jadi bagian dari library manapun. | Diganti ke atribut asli: `app:layout_constraintTop_toTopOf`, `app:layout_constraintBottom_toTopOf`, `app:layout_constraintStart_toStartOf`, `app:layout_constraintBottom_toBottomOf`, dan ditambah constraint yang sebelumnya hilang (`OpenGlView` sebelumnya tanpa constraint sama sekali → akan collapse jadi ukuran 0). |
| 3 | `app/build.gradle.kts` | File cuma berisi blok `dependencies { }` — tidak ada `plugins { }` maupun `android { }` (namespace, compileSdk, minSdk, defaultConfig, dst). Tidak mungkin di-build sebagai modul Android. | Ditulis ulang lengkap dengan `plugins`, `android { }` (disamakan konvensinya dengan `android-app/` di root repo: compileSdk 34, Java/Kotlin target 17). |
| 4 | `app/build.gradle.kts` | Dependency RootEncoder salah artifact: `com.github.pedroSG94.RootEncoder:rtmp:2.4.3` — artifact `rtmp` tidak ada di versi ini. | Diganti ke `com.github.pedroSG94.RootEncoder:library:2.4.3` (dikonfirmasi dari README & Wiki resmi pedroSG94/RootEncoder). |
| 5 | *(project-level)* | Tidak ada `settings.gradle.kts` / repo JitPack terdaftar — dependency RootEncoder tidak akan ketemu saat sync. | Dibuat `settings.gradle.kts` + `build.gradle.kts` root dengan `maven { url = uri("https://jitpack.io") }`. |
| 6 | `app/build.gradle.kts` | `RecyclerView` dipakai di layout (`rvLiveChat`) tapi dependency `androidx.recyclerview:recyclerview` tidak pernah ditambahkan. | Ditambahkan. |
| 7 | `MainActivity.kt` | Class implements `com.pedro.common.ConnectChecker` (interface unifikasi RootEncoder versi baru), tapi override method pakai nama gaya lama `onConnectionSuccessRtmp`, `onConnectionFailedRtmp`, `onDisconnectRtmp`, dst. (gaya `ConnectCheckerRtmp` versi lama). Kombinasi ini **tidak kompatibel** — akan gagal compile ("method does not override anything"). | Method diganti ke nama generik interface `ConnectChecker`: `onConnectionStarted`, `onConnectionSuccess`, `onConnectionFailed`, `onNewBitrate`, `onDisconnect`, `onAuthError`, `onAuthSuccess`. **Perlu diverifikasi lagi terhadap versi RootEncoder yang benar-benar ke-resolve** (lihat poin status di atas). |
| 8 | `MainActivity.kt` | Preview kamera cuma dimulai sekali di `onCreate()`, tidak pernah dilepas/dimulai ulang mengikuti lifecycle Activity → rawan crash saat app dikirim ke background lalu dibuka lagi (Surface jadi tidak valid). | Ditambahkan `onResume()`/`onPause()`: mulai preview di `onResume` kalau belum aktif, lepas di `onPause` **hanya kalau tidak sedang live** (supaya siaran tidak putus cuma karena layar dikunci). |
| 9 | `activity_main.xml` | Field URL RTMP diisi nilai default `rtmp://://youtube.com` — bukan format RTMP yang valid, berisiko user kirim ini apa adanya dan bingung kenapa gagal connect. | Dikosongkan (hint text saja: contoh format YouTube ada di README), + `android:inputType="textUri"`. |
| 10 | `AndroidManifest.xml` | GPS ditulis di `readme.md` sebagai "wajib" tapi tidak ada `<uses-feature android:name="android.hardware.location.gps" required="true">` di manifest. | Ditambahkan, supaya Play Store (kalau nanti dipublish) otomatis memfilter perangkat tanpa GPS. |
| 11 | Struktur folder | Tidak ada `res/values/strings.xml` / `themes.xml` — semua teks hardcode di XML/Kotlin, `android:theme="@style/Theme.AppCompat.NoActionBar"` langsung dipakai tanpa style custom. | Dibuat `strings.xml` (semua teks dipindah ke sana) dan `themes.xml` (`Theme.PotatoLivestreamerIRL`). |
| 12 | Package name | `MainActivity.kt` pakai package `com.potato.livestreamer` — **sama persis** dengan `android-app/` (mode PC-relay) yang sudah ada di root repo. Kalau dua modul ini pernah dibuka bareng/digabung, akan tabrakan. | Dipindah ke package `com.potato.livestreamer.irl` + `applicationId` disesuaikan. |

## Yang SENGAJA belum diubah
- `CONTRIBUTING.md` dan `LICENSE` — sudah masuk akal, tidak ada bug.
- Belum ada Foreground Service (streaming masih jalan sebagai `Activity`
  biasa) — sudah dicatat di roadmap `readme.md`, tapi ini perubahan
  arsitektur besar, sengaja tidak sekaligus dikerjakan supaya scope perapian
  ini tetap fokus ke bug yang bikin project tidak bisa di-build.
- Belum ada implementasi Live Chat WebSocket / watermark HUD / chroma key —
  masih placeholder seperti sebelumnya (memang belum diminta).
