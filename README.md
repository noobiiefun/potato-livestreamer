# 🥔 Potato Livestreamer

Livestreaming layar PC ke YouTube **lewat HP Android sebagai relay**, tersambung hanya
dengan kabel USB — terinspirasi dari cara sambung `spacedesk`, tapi untuk kebutuhan
livestream (yang tidak bisa dilakukan spacedesk).

Repo: https://github.com/noobiiefun/potato-livestreamer

## Cara Kerja

```
[PC]                                                          [HP Android]
 capture layar/jendela + audio (FFmpeg)                        app Potato Livestreamer
        |                                                              |
        | 1. kirim Stream URL + Stream Key                             |
        |    lewat CONTROL PORT (adb forward) ------------------------>|
        |                                                     (HP simpan config,
        |                                                      tombol "Go LIVE" aktif)
        |                                                              |
        | 2. user tekan "Go LIVE" di HP                                |
        |                                                              |
        | 3. kirim video(+audio) mpegts                                |
        |    lewat VIDEO PORT (adb forward) --------------------------> FFmpeg (remux,
        |                                                                -c copy, TANPA
        |                                                                re-encode)
        |                                                                     |
        |                                                                     v
        |                                                      rtmp://a.rtmp.youtube.com/...
        |                                                    (pakai internet HP sendiri)
```

## Struktur Proyek

```
potato-livestreamer/
├── android-app/               → Project Android Studio (Kotlin) - app di HP
│   ├── gradlew, gradlew.bat   → Gradle wrapper (build APK dari CLI)
│   └── app/src/main/java/.../
│       ├── MainActivity.kt     → UI + logic "Go LIVE" + auto-reconnect
│       └── ControlServer.kt    → penerima Stream URL/Key dari PC
├── pc-client/
│   ├── pc_client_gui.py        → ⭐ GUI (rekomendasi) — dropdown, log, timer
│   ├── pc_client.py            → versi CLI (terminal)
│   ├── core.py                 → logika inti dipakai bareng GUI & CLI
│   └── requirements.txt
├── .github/workflows/build-apk.yml → auto-build APK tiap push (GitHub Actions)
├── assets/                     → Logo Potato Livestreamer
└── README.md
```

## Prasyarat

- **PC**: Python 3.8+ (tkinter biasanya sudah termasuk; di Ubuntu/Debian kalau
  belum ada: `sudo apt install python3-tk`), FFmpeg di PATH, Android Platform
  Tools (`adb`) — **pastikan extract SEMUA isi folder `platform-tools`, bukan
  cuma `adb.exe`**, karena butuh `AdbWinApi.dll` & `AdbWinUsbApi.dll` satu folder
  (lihat bagian *Troubleshooting*).
- **HP Android**: Developer Options + USB debugging aktif, Android 7.0+ (API 24+)
- **Android Studio** (opsional, kalau mau build APK sendiri; bisa juga lewat
  GitHub Actions — lihat bagian *Build APK*)
- Kabel USB data (bukan kabel charging-only)

## Quick Start

### 1. Siapkan app di HP

Build & install (lihat bagian *Build APK* di bawah), lalu buka app di HP dan
tekan **"Tunggu Koneksi PC"**.

### 2. Jalankan GUI di PC

```bash
cd pc-client
python pc_client_gui.py
```

Di jendela yang terbuka:

1. Isi **Stream URL** & **Stream Key** dari YouTube Studio → Go Live → Stream
   (field terpisah, sama seperti tampilan YouTube Studio).
2. Pilih **Resolusi** (480p/720p/1080p), **FPS**, dan **Kualitas/Bitrate**.
3. Pilih **Sumber Capture**: Seluruh Layar / Area Tertentu / Jendela Aplikasi
   (klik "🔄 Refresh" untuk daftar jendela yang sedang terbuka, Windows only).
4. Pilih **Perangkat Audio** (klik "🔄 Refresh" dulu untuk memunculkan daftar),
   atau biarkan "Tanpa Audio".
5. Klik **"🔴 Mulai Live"**.

Semua log (termasuk error FFmpeg kalau ada) muncul di kotak log paling bawah —
kalau video tidak sampai ke YouTube, cek dulu di situ untuk tahu di langkah
mana macetnya.

Begitu tersambung, status akan berubah jadi **"🔴 LIVE — hh:mm:ss"** dengan
hitungan durasi berjalan.

### (Alternatif) CLI

```bash
cd pc-client
python pc_client.py
```

Wizard tanya-jawab di terminal, fungsinya sama seperti GUI tapi berbasis teks.

## Build APK

### Opsi A — Android Studio (paling mudah)
Buka folder `android-app/` di Android Studio → biarkan Gradle sync → Run ke
HP yang tersambung, atau **Build → Build Bundle(s) / APK(s) → Build APK(s)**.

### Opsi B — CLI (gradlew)
```bash
cd android-app
./gradlew assembleDebug        # Linux/macOS
gradlew.bat assembleDebug      # Windows
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Opsi C — GitHub Actions (otomatis, tanpa setup lokal)
Repo ini sudah punya `.github/workflows/build-apk.yml`. Tiap kali kamu push
perubahan ke folder `android-app/`, GitHub otomatis build APK dan menaruhnya
di tab **Actions → (run terakhir) → Artifacts → potato-livestreamer-debug-apk**
— tinggal download, tidak perlu Android Studio di komputer kamu sama sekali.

## Uji Coba Aman (sebelum live beneran)

Sebelum pakai stream key YouTube asli, disarankan tes dulu dengan RTMP server
lokal (misalnya `nginx-rtmp-module` di PC, isi Stream URL dengan
`rtmp://127.0.0.1/live` dan Stream Key `test`) supaya kamu bisa validasi
seluruh pipeline tanpa risiko tayang ke publik.

## Ketahanan Koneksi (Auto-Reconnect)

- **HP**: kalau sesi video terputus tanpa kamu tekan Stop, app otomatis mulai
  "listen" lagi 2 detik kemudian.
- **PC**: kalau capture/koneksi terputus, GUI/CLI otomatis mengulang dengan
  jeda membesar bertahap (2s → 3s → ... maks 15s).
- Kamu **tidak perlu lagi menekan Enter secara manual** setelah menekan Go
  LIVE di HP — versi sebelumnya butuh ini dan sering kelewat, itu penyebab
  paling umum "video tidak sampai ke YouTube". Sekarang PC otomatis retry
  connect sampai HP siap.

**Batasan jujur**: ini meminimalkan gangguan, tapi kalau putusnya lebih dari
beberapa puluh detik, YouTube kemungkinan tetap menampilkan status "stream
terputus" ke penonton sebelum otomatis melanjutkan.

## Troubleshooting

### `adb.exe - System Error: AdbWinApi.dll was not found`
Kamu cuma copy `adb.exe` sendirian. File ini butuh `AdbWinApi.dll` dan
`AdbWinUsbApi.dll` di folder yang sama. **Solusi**: download ulang
[platform-tools lengkap](https://developer.android.com/tools/releases/platform-tools),
extract, dan copy **semua isi foldernya** (bukan cuma `adb.exe`).

### Video tidak sampai ke YouTube / HP nyangkut di "Menunggu koneksi video..."
- Pastikan kamu memakai `pc_client_gui.py` atau `pc_client.py` versi terbaru
  (yang ini) — versi sebelumnya butuh tekan Enter manual di CMD yang sering
  kelewat.
- Cek kotak **Log** di GUI (atau output CMD di versi CLI) untuk pesan error
  FFmpeg — paling sering karena parameter capture (offset/resolusi) melebihi
  ukuran layar asli, atau nama device audio salah ketik.
- Pastikan HP benar-benar sudah menekan **"🔴 Go LIVE"** (bukan cuma "Tunggu
  Koneksi PC") — status HP harus berbunyi "Menunggu koneksi video dari PC..."
  baru PC bisa konek.

### Capture jendela (Zoom/Meet) muncul layar hitam
Beberapa aplikasi yang pakai GPU rendering tidak kompatibel dengan `gdigrab`
mode jendela. Solusinya pakai opsi **"Area Tertentu"** dan posisikan window
aplikasinya di area itu.

## Audio

Audio bersifat opsional, diatur lewat dropdown "Perangkat Audio" di GUI
(klik Refresh dulu). Capture audio per-aplikasi sangat tergantung OS:

- **Windows**: butuh perangkat loopback (mis. "Stereo Mix" kalau tersedia,
  atau virtual audio cable seperti VB-CABLE).
- **macOS**: butuh virtual audio device (mis. BlackHole).
- **Linux**: paling fleksibel — PulseAudio/PipeWire punya "monitor" per sink.

## Badge "LIVE" di App HP

Dropdown "Posisi badge LIVE" di app HP mengatur badge status **di dalam UI
app itu sendiri** — indikator lokal untukmu, bukan watermark yang ikut
tampil di video yang ditonton pemirsa YouTube (karena video sudah
"jadi"/di-composite di PC sebelum sampai ke HP). Kalau mau badge itu
benar-benar terlihat di siaran, itu perlu ditambahkan sebagai filter overlay
FFmpeg di sisi PC (`-vf overlay=...`) — kabari kalau mau saya tambahkan.

## Batasan & Catatan Lain

- **FFmpegKit** (library FFmpeg untuk Android) sudah di-*archive* oleh
  pembuat aslinya sejak 2024. Karena APK ini di-*sideload* (bukan lewat Play
  Store), library tetap bisa dipakai — kalau butuh maintenance jangka
  panjang, pertimbangkan fork komunitas.
- App HP berjalan sebagai `Activity` biasa. Untuk pemakaian produksi (stabil
  saat HP di-*lock*), pindahkan proses FFmpeg ke **Foreground Service**.
- Pastikan mode USB di HP di-set ke **"File Transfer"/"MTP"**, bukan
  "Charging only".
- `config.json` di `pc-client/` berisi Stream Key kamu — sudah masuk
  `.gitignore`, jangan sampai ter-commit ke GitHub.

## Lisensi Komponen Pihak Ketiga

- FFmpeg: LGPL/GPL tergantung build yang dipakai
- FFmpegKit: LGPL-3.0 (untuk paket non-GPL)
- Android Platform Tools: Apache 2.0
- Gradle Wrapper: Apache 2.0
