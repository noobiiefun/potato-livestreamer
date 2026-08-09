# 🥔 Potato Livestreamer

Livestreaming layar PC ke YouTube **lewat HP Android sebagai relay**, tersambung hanya
dengan kabel USB — terinspirasi dari cara sambung `spacedesk`, tapi untuk kebutuhan
livestream (yang tidak bisa dilakukan spacedesk).

## Cara Kerja

```
[PC]  --tangkap layar sendiri (FFmpeg)-->  [USB / adb forward]  -->  [HP Android]
                                                                       |
                                                                       | FFmpeg (remux only,
                                                                       | -c:v copy, tanpa
                                                                       | re-encode)
                                                                       v
                                                              rtmp://a.rtmp.youtube.com/...
                                                          (pakai internet HP sendiri)
```

- **PC** meng-capture layarnya sendiri, encode ke H.264, lalu mengirim stream itu
  sebagai TCP client ke `127.0.0.1:6000`.
- **`adb forward tcp:6000 tcp:6000`** menunnel port itu dari PC ke HP lewat kabel USB.
- **HP** (app Potato Livestreamer) menjalankan FFmpeg dalam mode *listen*, menerima
  stream itu, lalu langsung remux (tanpa re-encode ulang, jadi ringan) dan push ke
  RTMP YouTube — memakai kuota/WiFi HP itu sendiri sebagai jalur upload, bukan
  internet PC.

## Struktur Proyek

```
potato-livestreamer/
├── android-app/     → Project Android Studio (Kotlin) - app yang jalan di HP
├── pc-client/        → Script Python yang jalan di PC
├── assets/           → Logo Potato Livestreamer
└── README.md
```

## Prasyarat

- **PC**: Python 3.8+, FFmpeg (harus ada di PATH), Android Platform Tools (`adb`)
- **HP Android**: Developer Options + USB debugging aktif, Android 7.0+ (API 24+)
- **Android Studio** untuk build APK dari `android-app/`
- Kabel USB data (bukan kabel charging-only)

## Quick Start

### 1. Build & install app Android

```bash
cd android-app
# buka folder ini di Android Studio, lalu Run ke HP yang tersambung via USB
# atau build manual:
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 2. Jalankan app di HP

1. Buka app **Potato Livestreamer** di HP.
2. Masukkan RTMP URL lengkap dari YouTube Studio (Go Live → Stream), contoh:
   `rtmp://a.rtmp.youtube.com/live2/xxxx-xxxx-xxxx-xxxx-xxxx`
3. Tekan **Start Listening**. Status akan menunjukkan "Menunggu koneksi dari PC...".

### 3. Jalankan client di PC

```bash
cd pc-client
pip install -r requirements.txt   # cuma butuh Python bawaan, ini opsional
python pc_client.py
```

Script ini otomatis:
- Cek `adb devices` (HP harus kedetect)
- Jalankan `adb forward tcp:6000 tcp:6000`
- Jalankan FFmpeg capture layar PC sesuai OS (Windows/macOS/Linux) dan kirim ke HP

**Urutan penting**: HP harus sudah "Listening" dulu (langkah 2) sebelum menjalankan
`pc_client.py`, karena FFmpeg di HP menunggu koneksi masuk (mode listen).

## Uji Coba Aman (sebelum live beneran)

Sebelum pakai stream key YouTube asli, disarankan tes dulu dengan RTMP server lokal
(misalnya `nginx-rtmp-module` di PC, arahkan ke `rtmp://127.0.0.1/live/test`) supaya
kamu bisa validasi seluruh pipeline tanpa risiko tayang ke publik.

## Konfigurasi Kualitas

Karena HP cuma me-remux (`-c:v copy`), semua pengaturan kualitas video (resolusi,
bitrate, FPS) diatur dari **sisi PC** di `pc_client.py` — lihat konstanta di bagian
atas file tersebut.

## Batasan & Catatan

- **FFmpegKit** (library FFmpeg untuk Android yang dipakai app ini) sudah di-*archive*
  oleh pembuat aslinya sejak 2024. Karena APK ini di-*sideload* (bukan lewat Play
  Store), library tetap bisa dipakai — tapi kalau butuh maintenance jangka panjang,
  pertimbangkan fork komunitas atau ganti ke library RTMP client Android lain.
- Versi awal ini berjalan sebagai `Activity` biasa. Untuk pemakaian produksi
  (stabil saat HP di-*lock*/berpindah app), pindahkan proses FFmpeg ke
  **Foreground Service** dengan notifikasi persisten, supaya tidak dimatikan sistem.
- Audio dari PC belum ditangani di starter ini — lihat komentar `TODO` di
  `pc_client.py` untuk menambahkan audio capture (contoh: `-f dshow` di Windows,
  `-f pulse` di Linux) dan mux bareng video.
- Pastikan mode USB di HP di-set ke **"File Transfer" atau "MTP"**, bukan
  "Charging only", agar `adb` bisa mendeteksi device.

## Lisensi Komponen Pihak Ketiga

- FFmpeg: LGPL/GPL tergantung build yang dipakai
- FFmpegKit: LGPL-3.0 (untuk paket non-GPL)
- Android Platform Tools: Apache 2.0
