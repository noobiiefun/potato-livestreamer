# 🥔 Potato Livestreamer — Mode IRL

Prototipe **mode kedua** untuk keluarga proyek [`potato-livestreamer`](https://github.com/noobiiefun/potato-livestreamer):
kalau `android-app/` (mode utama) menjadikan HP sebagai *encoder* untuk siaran
layar PC lewat USB, modul ini menjadikan HP sebagai **kamera IRL (In Real
Life) mandiri** — cukup HP saja, tanpa PC, cocok untuk livestream jalan-jalan
atau naik kendaraan. Dioptimasi untuk perangkat *Android Go* spesifikasi
rendah seperti **Xiaomi Redmi A3**.

Menggabungkan umpan kamera langsung dengan **HUD (Heads-Up Display) ala game
balap** yang menampilkan kecepatan berkendara secara *real-time* dari sensor
GPS internal HP.

> **Status:** prototipe/eksperimen, modul Gradle berdiri sendiri (belum
> digabung ke `android-app/`). Lihat [`FIXES.md`](./FIXES.md) untuk daftar
> perbaikan yang sudah dilakukan dan apa yang masih perlu diuji langsung di
> Android Studio (kode ini belum pernah di-*build* nyata).

---

## ✨ Fitur Utama
- **Optimasi Ekstrem Android Go:** encoding video 480p @ 30 FPS, bitrate
  1200 Kbps tetap (jangan dinaikkan — lihat `CONTRIBUTING.md`), agar HP tidak
  *overheating* atau kehabisan RAM.
- **HUD Speedometer Real-Time:** kecepatan berkendara dalam **km/h**, dari
  Google Play Services Location (FusedLocationProvider, akurasi tinggi).
- **Transmisi RTMP Ringan:** menggunakan [RootEncoder](https://github.com/pedroSG94/RootEncoder)
  (`com.pedro.rtplibrary`), render lewat `OpenGlView`.
- **Hot-Switch Camera:** balik kamera depan/belakang instan, termasuk saat
  sedang live, tanpa memutus koneksi stream.
- **Live Chat Slot:** placeholder `RecyclerView` transparan di pojok layar,
  siap dikoneksikan ke API chat YouTube/Twitch (belum diimplementasikan).

---

## 🛠️ Persyaratan Minimum Perangkat

| Komponen | Spesifikasi Minimum | Catatan |
| :--- | :--- | :--- |
| **Sistem Operasi** | Android 8.0 (API 26) atau lebih tinggi | Kompatibel dengan Android Go Edition |
| **Prosesor/Chipset**| MediaTek Helio G36 / Snapdragon 450 | Dioptimalkan untuk prosesor hemat daya |
| **Memori RAM** | 2 GB / 3 GB / 4 GB | Pantau penggunaan RAM lewat Android Studio Profiler saat uji coba |
| **Sensor Wajib** | GPS / Hardware Location Sensor | Dibutuhkan untuk fungsionalitas speedometer |

---

## ⚡ Panduan Instalasi & Pengujian

### 1. Kloning Repositori
```bash
git clone https://github.com/noobiiefun/potato-livestreamer.git
cd "potato-livestreamer/IRL livestreamer"
```

### 2. Membuka Proyek
1. Buka **Android Studio** (disarankan versi Koala/2024.1 atau lebih baru).
2. **Open an Existing Project** → pilih folder `IRL livestreamer` (folder ini
   sendiri, BUKAN root repo — modul ini punya `settings.gradle.kts` sendiri
   dan belum jadi submodule dari `android-app/`).
3. Tunggu **Gradle Sync** selesai. Kalau ini pertama kali sync, koneksi
   internet dibutuhkan untuk mengunduh dependency dari `google()`,
   `mavenCentral()`, dan `jitpack.io` (untuk RootEncoder).

### 3. Mengonfigurasi Stream Key
1. Jalankan aplikasi di perangkat fisik (disarankan langsung di HP Android Go
   target, bukan emulator — encoder hardware & GPS emulator tidak
   representatif).
2. Masukkan URL RTMP + Stream Key lengkap di kolom bawah layar.
   - *Contoh format YouTube:* `rtmp://a.rtmp.youtube.com/live2/STREAM-KEY-KAMU`
   - Jangan gunakan stream key asli untuk uji coba pertama — lihat bagian
     **Uji Coba Aman** di README utama repo (`nginx-rtmp-module` lokal).
3. Tekan **MULAI LIVE**.

---

## 🗂️ Struktur Proyek Modul Ini
```text
IRL livestreamer/
├── build.gradle.kts / settings.gradle.kts / gradle.properties  <-- konfigurasi Gradle level modul
├── app/
│   ├── build.gradle.kts                  <-- dependensi + konfigurasi Android (compileSdk, minSdk, dst.)
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── java/com/potato/livestreamer/irl/
│       │   └── MainActivity.kt           <-- logika kamera, GPS, RTMP
│       ├── res/layout/activity_main.xml  <-- UI & HUD ala game
│       ├── res/values/strings.xml
│       ├── res/values/themes.xml
│       └── AndroidManifest.xml
├── CONTRIBUTING.md
├── LICENSE
└── FIXES.md                              <-- catatan perbaikan & progres antar sesi
```

> **Catatan package name:** modul ini pakai `com.potato.livestreamer.irl`
> (bukan `com.potato.livestreamer` seperti `android-app/`) supaya kalau nanti
> digabung jadi satu app dua-mode, tidak ada tabrakan nama class/`applicationId`.

---

## 🤝 Kontribusi & Roadmap Masa Depan
- [ ] Integrasi WebSocket untuk menarik pesan *Live Chat* YouTube/Twitch
      secara real-time ke `RecyclerView` yang sudah disiapkan.
- [ ] `GlWatermarkObject` agar tulisan HUD kecepatan ikut ter-*render* ke
      dalam video streaming (saat ini HUD cuma tampil di layar HP, tidak ikut
      ke penonton).
- [ ] Fitur *Chroma Key* sederhana untuk avatar VTuber 2D statis.
- [ ] Pindahkan proses streaming ke **Foreground Service** supaya siaran
      tidak berhenti saat layar HP dikunci (saat ini semua logic ada di
      `Activity` biasa — lihat catatan di `FIXES.md`).
- [ ] Evaluasi apakah modul ini digabung jadi satu `Activity` tambahan di
      `android-app/` (mode selector: "Relay dari PC" vs "Kamera IRL"), atau
      tetap jadi APK terpisah.

Bug atau ide peningkatan performa? Buka **Issue** atau kirim **Pull Request**.

---
## 📄 Lisensi
MIT License — lihat file `LICENSE`.
