# 🥔 Potato Livestreamer

Livestreaming layar PC ke YouTube **lewat HP Android sebagai encoder**, tersambung
hanya dengan kabel USB — terinspirasi dari cara sambung `spacedesk`, tapi untuk
kebutuhan livestream (yang tidak bisa dilakukan spacedesk).

Repo: https://github.com/noobiiefun/potato-livestreamer

## Cara Kerja (v3 — PC ringan, HP yang kerja)

```
[PC]                                                          [HP Android]
 - capture layar (murah, MJPEG saja, TIDAK encode H.264)       app Potato Livestreamer
 - tidak mengganggu game yang lagi jalan                        - decode MJPEG dari PC
        |                                                       - ENCODE ke H.264 pakai
        | 1. kirim Stream URL + Stream Key + target bitrate       hardware encoder Android
        |    lewat CONTROL PORT (adb forward) ------------------> (h264_mediacodec)
        |                                                       - push ke RTMP
        |                                                              |
        | 2. user tekan "Go LIVE" di HP                                |
        |                                                              |
        | 3. kirim video MJPEG (gambar mentah terkompresi ringan)      |
        |    lewat VIDEO PORT (adb forward) --------------------------> FFmpeg di HP:
        |                                                                decode + H.264
        |                                                                encode (hardware)
        |                                                                     |
        |                                                                     v
        |                                                      rtmp://a.rtmp.youtube.com/...
        |                                                    (pakai internet HP sendiri)
```

**Kenapa dibagi begini:** PC sering dipakai buat hal lain yang berat (main game),
jadi PC sengaja TIDAK melakukan encode H.264 (yang mahal CPU/GPU) — cuma capture +
compress ringan ke MJPEG. HP-lah yang melakukan pekerjaan "merekam" sebenarnya:
decode gambar itu lalu encode ke H.264 pakai chip hardware encoder-nya sendiri
(persis seperti saat HP merekam layarnya sendiri), lalu langsung dialirkan ke
YouTube alih-alih disimpan ke file.

**Konsekuensi**: MJPEG jauh lebih besar per frame dibanding H.264, jadi versi ini
lebih boros bandwidth USB dibanding pendekatan lama (PC yang encode). Ada dropdown
"Kualitas Capture PC → HP" untuk mengatur trade-off ini — turunkan kalau lag/patah.

- **Stream URL, Stream Key, dan target bitrate YouTube dimasukkan di PC**
  (field terpisah, sama seperti YouTube Studio → Go Live → Stream), lalu otomatis
  dikirim ke HP lewat *control channel*. Di HP tinggal tekan **"Tunggu Koneksi PC"**
  lalu **"🔴 Go LIVE"** — tidak perlu ketik apa-apa.
- **Auto-reconnect di kedua sisi**: USB longgar/putus sesaat tidak langsung
  mematikan LIVE, keduanya otomatis retry.
- **Audio belum didukung** di versi ini (lihat bagian *Batasan*).

## Struktur Proyek

```
potato-livestreamer/
├── android-app/     → Project Android Studio (Kotlin) - app di HP (decode + encode)
│   ├── gradlew, gradlew.bat   → Gradle wrapper (build APK dari CLI)
│   └── app/src/main/java/.../
│       ├── MainActivity.kt     → UI + logic "Go LIVE" + auto-reconnect + hardware encode
│       └── ControlServer.kt    → penerima Stream URL/Key/bitrate dari PC
├── pc-client/
│   ├── pc_client_gui.py        → ⭐ GUI (rekomendasi) — dropdown, log, timer
│   ├── pc_client.py            → versi CLI (terminal)
│   ├── core.py                 → logika inti (capture MJPEG) dipakai bareng GUI & CLI
│   └── requirements.txt
├── .github/workflows/build-apk.yml → auto-build APK tiap push (GitHub Actions)
├── assets/                     → Logo Potato Livestreamer
└── README.md
```

## Prasyarat

- **PC**: Python 3.8+ (tkinter biasanya sudah termasuk; di Ubuntu/Debian kalau
  belum ada: `sudo apt install python3-tk`), FFmpeg di PATH, Android Platform
  Tools (`adb`) — **pastikan extract SEMUA isi folder `platform-tools`, bukan
  cuma `adb.exe`**, karena butuh `AdbWinApi.dll` & `AdbWinUsbApi.dll` satu folder.
- **HP Android**: Developer Options + USB debugging aktif, Android 7.0+ (API 24+),
  chip yang mendukung hardware H.264 encoder (hampir semua HP modern termasuk
  kelas Android Go, karena dipakai juga oleh fitur perekam layar bawaan).
- **Android Studio** (opsional; bisa juga build lewat GitHub Actions)
- Kabel USB data (bukan kabel charging-only)

## Quick Start

### 1. Siapkan app di HP

Build & install (lihat *Build APK*), buka app, tekan **"Tunggu Koneksi PC"**.

### 2. Jalankan GUI di PC

```bash
cd pc-client
python pc_client_gui.py
```

1. Isi **Stream URL** & **Stream Key** dari YouTube Studio.
2. Pilih **Resolusi**, **FPS**.
3. **Kualitas Capture PC → HP**: ini beban ke kabel USB (MJPEG), bukan ke YouTube.
   Turunkan kalau video patah-patah/lag.
4. **Kualitas / Bitrate Video ke YouTube**: ini yang dipakai HP saat encode H.264,
   menentukan kualitas akhir yang dilihat penonton.
5. Pilih **Sumber Capture**: Seluruh Layar / Area Tertentu / Jendela Aplikasi.
6. Klik **"🔴 Mulai Live"**.

Begitu tersambung, status di HP akan berbunyi **"🔴 LIVE — encoding X fps, bitrate
Y kbits/s"** — angka ini datang dari proses encode yang benar-benar berjalan di HP.

## Build APK

### Opsi A — Android Studio
Buka folder `android-app/` di Android Studio → Gradle sync → Run/Build APK.

### Opsi B — CLI
```bash
cd android-app
./gradlew assembleDebug        # Linux/macOS
gradlew.bat assembleDebug      # Windows
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Pakai `adb install -r ...` (bukan drag-drop file APK) supaya kalau instalasi
gagal, kamu lihat kode error persisnya (mis. `INSTALL_FAILED_NO_MATCHING_ABIS`)
alih-alih cuma "gagal" tanpa keterangan.

### Opsi C — GitHub Actions
Push ke folder `android-app/` → cek tab **Actions** → download artifact APK.

## Uji Coba Aman

Sebelum pakai stream key YouTube asli, tes dulu dengan RTMP server lokal
(`nginx-rtmp-module`, Stream URL `rtmp://127.0.0.1/live`, Key `test`).

## Troubleshooting

### `adb.exe - System Error: AdbWinApi.dll was not found`
Kamu cuma copy `adb.exe` sendirian. Download ulang
[platform-tools lengkap](https://developer.android.com/tools/releases/platform-tools),
extract, copy **semua isi foldernya**.

### APK tidak bisa diinstall di HP (terutama Android Go / HP kentang)
Penyebab paling umum: dependency FFmpegKit di `app/build.gradle` cuma menyediakan
native library untuk `arm64-v8a` (64-bit), sementara banyak HP Android Go
menjalankan sistem 32-bit-only demi hemat RAM meski chipnya 64-bit-capable.
**Sudah diperbaiki** di repo ini dengan menambahkan:
```groovy
ndk {
    abiFilters "armeabi-v7a", "arm64-v8a"
}
```
di `android { ... }` pada `app/build.gradle`, ditambah dependency yang memang
menyediakan kedua arsitektur (`io.github.maitrungduc1410:ffmpeg-kit-https`).
Kalau masih gagal, jalankan `adb install -r app-debug.apk` dan lihat kode error
persisnya — kirim ke saya kalau butuh dibantu lagi.

### Video tidak sampai ke YouTube
Cek kotak **Log** di GUI PC (atau output CMD di CLI) — pesan error FFmpeg paling
sering karena parameter capture (offset/resolusi) melebihi ukuran layar asli.
Cek juga log di app HP — kalau muncul error terkait `h264_mediacodec`, kemungkinan
chip HP tidak mendukung encoder itu (jarang terjadi, tapi mungkin di HP sangat tua).

### Capture jendela (Zoom/Meet) muncul layar hitam
Pakai opsi **"Area Tertentu"** dan posisikan window di area itu — beberapa app
GPU-rendered tidak kompatibel dengan `gdigrab` mode jendela.

### Video patah-patah / lag
Turunkan **"Kualitas Capture PC → HP"** di GUI — ini yang paling menentukan beban
USB. Resolusi/FPS lebih rendah juga membantu.

## Badge "LIVE" di App HP

Dropdown "Posisi badge LIVE" mengatur badge status **di dalam UI app HP saja**
(indikator lokal), bukan watermark yang ikut tampil di video yang ditonton
pemirsa YouTube.

## Batasan & Rencana Lanjutan

- **Audio belum didukung.** MJPEG adalah video-only elementary stream. Rencana:
  buka port TCP kedua khusus audio (AAC) dari PC, HP mux dua sumber itu bareng
  saat encode. Kabari kalau ini prioritas berikutnya.
- **`h264_mediacodec`** (hardware encoder Android) hampir selalu tersedia karena
  dipakai juga oleh fitur perekam layar bawaan — tapi kompatibilitas bisa
  bervariasi per chip. Kalau gagal terus, kirim log errornya.
- **FFmpegKit** yang dipakai (`io.github.maitrungduc1410`) adalah rebuild
  komunitas dari project asli yang sudah di-retire pemiliknya tahun 2025 — LGPL
  only (tidak ada libx264 software encoder), makanya HP wajib pakai hardware
  encoder.
- App HP berjalan sebagai `Activity` biasa — untuk produksi (stabil saat HP
  di-*lock*), pindahkan ke **Foreground Service**.
- Pastikan mode USB di HP = **"File Transfer"/"MTP"**, bukan "Charging only".
- `config.json` di `pc-client/` berisi Stream Key kamu — sudah masuk
  `.gitignore`.
- Folder `android-app/.artifacts/` (scratch file dari AI assistant Android
  Studio) dan `android-app/build/` **tidak boleh ikut ke-commit** — sudah
  ditambahkan ke `.gitignore`; kalau repo kamu terlanjur ada foldernya, hapus
  dengan `git rm -r --cached android-app/.artifacts android-app/build`.

## Lisensi Komponen Pihak Ketiga

- FFmpeg: LGPL/GPL tergantung build yang dipakai
- FFmpegKit (io.github.maitrungduc1410 rebuild): LGPL-3.0
- Android Platform Tools: Apache 2.0
- Gradle Wrapper: Apache 2.0
