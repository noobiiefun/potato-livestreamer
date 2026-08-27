# 🥔 potato-livestreamer

Aplikasi Android khusus untuk *Live Streaming IRL* (In Real Life) dengan optimasi ekstrem yang dirancang agar berjalan lancar di perangkat berspesifikasi rendah (*potato hardware*) seperti **Xiaomi Redmi A3 (Android Go)**.

Aplikasi ini menggabungkan umpan kamera video nyata dengan fungsionalitas **HUD (Heads-Up Display) ala game balap** yang melacak kecepatan berkendara secara *real-time* menggunakan sensor GPS internal ponsel.

---

## ✨ Fitur Utama
- **Optimasi Ekstrem Android Go:** Menggunakan konfigurasi encoding video rendah (480p @ 30 FPS, Bitrate 1200Kbps) untuk mencegah perangkat dari panas berlebih (*overheating*) dan kehabisan memori RAM.
- **HUD Speedometer Real-Time:** Menampilkan kecepatan berkendara dalam satuan **km/h** secara dinamis menggunakan API akurasi tinggi Google Play Services Location.
- **Transmisi RTMP Ringan:** Mengandalkan *streaming engine* berbasis `RootEncoder` yang efisien dan langsung terintegrasi dengan pemrosesan OpenGlView.
- **Hot-Switch Camera:** Pengguna dapat membalikkan fungsi kamera antara kamera depan dan kamera belakang secara instan bahkan di tengah-tengah siaran langsung tanpa merusak koneksi *stream*.
- **Live Chat Slot:** Menyediakan antarmuka transparan berbasis `RecyclerView` di pojok layar yang siap dikoneksikan dengan API pihak ketiga.

---

## 🛠️ Persyaratan Minimum Perangkat

| Komponen | Spesifikasi Minimum | Catatan |
| :--- | :--- | :--- |
| **Sistem Operasi** | Android 8.0 (API 26) atau lebih tinggi | Kompatibel dengan Android Go Edition |
| **Prosesor/Chipset**| MediaTek Helio G36 / Snapdragon 450 | Dioptimalkan untuk prosesor hemat daya |
| **Memori RAM** | 2 GB / 3 GB / 4 GB | Penggunaan RAM dipantau ketat di bawah 250MB |
| **Sensor Wajib** | GPS / Hardware Location Sensor | Dibutuhkan untuk fungsionalitas speedometer |

---

## ⚡ Panduan Instalasi & Pengujian

### 1. Kloning Repositori
```bash
git clone https://github.com
```

### 2. Membuka Proyek
1. Buka aplikasi **Android Studio** (Disarankan versi Koala atau yang lebih baru).
2. Pilih **Open an Existing Project** dan arahkan ke direktori `potato-livestreamer`.
3. Tunggu hingga proses *Gradle Sync* selesai sepenuhnya.

### 3. Mengonfigurasi Stream Key
1. Jalankan aplikasi di perangkat fisik Anda (Xiaomi Redmi A3).
2. Masukkan alamat URL RTMP server dan *Stream Key* Anda pada kolom teks yang disediakan di bagian bawah layar.
   - *Contoh YouTube:* `rtmp://://youtube.com`
3. Tekan tombol **MULAI LIVE**.

---

## 🗂️ Struktur Proyek Utama
```text
potato-livestreamer/
│
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/potato/livestreamer/
│   │   │   │   └── MainActivity.kt       <-- Logika utama kamera & GPS
│   │   │   │
│   │   │   ├── res/layout/
│   │   │   │   └── activity_main.xml     <-- Tata letak UI & HUD ala Game
│   │   │   │
│   │   │   └── AndroidManifest.xml       <-- Deklarasi izin sistem Android
│   │   │
│   │   └── build.gradle.kts              <-- Dependensi library pihak ketiga
│   │
│   └── build.gradle.kts                  <-- Konfigurasi build tingkat aplikasi
```

---

## 🤝 Kontribusi & Roadmap Masa Depan
Proyek ini bersifat open-source. Beberapa rencana pengembangan fitur ke depan meliputi:
- [ ] Integrasi WebSocket untuk menarik data pesan *Live Chat* YouTube/Twitch secara real-time.
- [ ] Implementasi `GlWatermarkObject` agar tulisan HUD kecepatan ter-render langsung ke dalam video streaming (bukan hanya tampil di layar HP).
- [ ] Fitur *Chroma Key* sederhana untuk menyisipkan avatar VTuber 2D statis.

Apabila Anda menemukan *bug* atau ingin meningkatkan performa pengkodean, silakan buka **Issue** atau kirimkan **Pull Request**.

---
## 📄 Lisensi
Proyek ini didistribusikan di bawah lisensi **MIT License**. Lihat file `LICENSE` untuk informasi lebih lanjut.
