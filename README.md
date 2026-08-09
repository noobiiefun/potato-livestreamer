# 🥔 Potato Livestreamer

Livestreaming layar PC ke YouTube **lewat HP Android sebagai relay**, tersambung hanya
dengan kabel USB — terinspirasi dari cara sambung `spacedesk`, tapi untuk kebutuhan
livestream (yang tidak bisa dilakukan spacedesk).

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

Poin penting:
- **Stream URL & Stream Key dimasukkan di PC** (persis seperti field terpisah di
  YouTube Studio → Go Live → Stream), lalu otomatis dikirim ke HP lewat *control
  channel* terpisah dari jalur video. Di HP kamu **tidak perlu ketik apa-apa**,
  tinggal tekan **"Tunggu Koneksi PC"** lalu **"🔴 Go LIVE"**.
- **HP hanya me-remux** (`-c copy`), tidak re-encode ulang — jadi ringan di HP.
  Semua pengaturan kualitas (resolusi/bitrate/FPS) diatur di PC.
- **Auto-reconnect di kedua sisi**: kalau kabel USB longgar / koneksi sempat putus,
  HP tidak langsung mematikan LIVE — otomatis mencoba menyambung ulang. PC juga
  otomatis mencoba capture+kirim ulang. Lihat bagian *Batasan* untuk detail
  realistiknya.
- **PC bisa pilih sumber capture**: seluruh layar, area/monitor tertentu, atau
  jendela aplikasi tertentu (mis. Zoom/Google Meet), plus perangkat audio.

## Struktur Proyek

```
potato-livestreamer/
├── android-app/     → Project Android Studio (Kotlin) - app yang jalan di HP
│   └── .../MainActivity.kt   → UI + logic "Go LIVE" + auto-reconnect
│   └── .../ControlServer.kt  → penerima Stream URL/Key dari PC
├── pc-client/
│   └── pc_client.py  → wizard konfigurasi + capture + kirim ke HP
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

### 2. Di HP: buka app, tekan "Tunggu Koneksi PC"

App akan menampilkan "⏳ Menunggu PC menyambung..." — biarkan dulu, lanjut ke PC.

### 3. Di PC: jalankan client

```bash
cd pc-client
python pc_client.py
```

Saat pertama kali jalan, kamu akan diminta mengisi (tersimpan otomatis ke
`config.json` untuk pemakaian berikutnya):

1. **Stream URL** — dari YouTube Studio → Go Live → Stream → "Stream URL"
   (contoh: `rtmp://a.rtmp.youtube.com/live2`)
2. **Stream key** — dari field terpisah "Stream key" di halaman yang sama
3. **Sumber capture**: seluruh layar / area tertentu / jendela aplikasi (Windows)
4. **Perangkat audio** (opsional): kosongkan kalau tidak perlu suara

Setelah itu script akan:
- Membuka `adb forward` untuk video port & control port
- Mengirim Stream URL + Key ke HP otomatis
- Menunggu kamu menekan **"🔴 Go LIVE"** di HP, lalu mulai capture & kirim

Untuk mengubah konfigurasi kapan saja: `python pc_client.py --reconfigure`

## Uji Coba Aman (sebelum live beneran)

Sebelum pakai stream key YouTube asli, disarankan tes dulu dengan RTMP server lokal
(misalnya `nginx-rtmp-module` di PC, isi Stream URL dengan `rtmp://127.0.0.1/live`
dan Stream Key `test`) supaya kamu bisa validasi seluruh pipeline tanpa risiko
tayang ke publik.

## Memilih Sumber Capture (Zoom / Google Meet, dll.)

Ada dua mode yang ditawarkan wizard di `pc_client.py`:

- **Area/monitor tertentu** — paling stabil di semua OS. Kalau Zoom/Meet kamu
  buka di monitor kedua, atau kamu posisikan window-nya di area tertentu, cukup
  isi offset X/Y + lebar/tinggi area itu.
- **Jendela aplikasi tertentu (Windows only)** — pakai `gdigrab title=...` untuk
  capture window spesifik by judul. **Catatan penting**: capture per-jendela
  seperti ini kadang menghasilkan layar hitam untuk aplikasi yang pakai GPU
  rendering (beberapa versi Zoom/Chrome/Meet mengalami ini). Kalau kamu
  mengalami ini, pakai opsi "area tertentu" sebagai alternatif yang lebih
  reliable — cukup posisikan window aplikasinya di area yang di-capture.

## Audio

Audio bersifat opsional dan sepenuhnya diatur di PC (`pc_client.py` akan tanya
nama/index perangkat audio). Karena capture audio per-aplikasi (misal "cuma suara
tab Chrome yang lagi Meet") sangat tergantung OS:

- **Windows**: butuh perangkat loopback (mis. "Stereo Mix" jika tersedia, atau
  virtual audio cable seperti VB-CABLE) untuk menangkap audio sistem/aplikasi.
- **macOS**: butuh virtual audio device (mis. BlackHole) karena `avfoundation`
  tidak punya loopback bawaan.
- **Linux**: paling fleksibel — PulseAudio/PipeWire punya "monitor" per sink,
  bisa diarahkan supaya cuma audio dari aplikasi tertentu yang ditangkap.

Jalankan wizard dan pilih "Tampilkan daftar perangkat audio" untuk melihat opsi
yang tersedia di sistemmu.

## Badge "LIVE" yang bisa diatur posisinya

App HP punya dropdown "Posisi badge LIVE" (Kiri Atas/Kanan Atas/Kiri Bawah/Kanan
Bawah) yang mengontrol badge merah "● LIVE" **di dalam UI app itu sendiri** —
ini indikator status lokal, bukan watermark yang ikut tampil di video yang
ditonton pemirsa YouTube (karena video sudah di-composite duluan di PC, sebelum
sampai ke HP). Kalau kamu mau badge/watermark itu benar-benar muncul di video
siaran, itu perlu ditambahkan sebagai filter overlay di FFmpeg sisi PC (`-vf
overlay=...`) — bilang saja kalau mau saya tambahkan fitur ini.

## Ketahanan Koneksi (Auto-Reconnect)

Kalau USB longgar / adb tunnel putus sesaat:

- **HP** tidak langsung mematikan sesi LIVE — begitu koneksi FFmpeg putus tanpa
  kamu menekan Stop, app otomatis mulai "listen" lagi 2 detik kemudian, berulang
  sampai berhasil tersambung lagi.
- **PC** juga otomatis mengulang proses capture+kirim dengan jeda yang membesar
  bertahap (2s → 3s → ... maks 15s) sampai kamu menekan Ctrl+C.

**Batasan jujur**: ini meminimalkan gangguan (kamu tidak perlu klik ulang manual),
tapi bukan jaminan siaran 100% tanpa jeda di sisi penonton YouTube — kalau
putusnya lebih dari beberapa puluh detik, YouTube kemungkinan tetap menampilkan
status "stream terputus" ke penonton sebelum otomatis melanjutkan begitu koneksi
pulih. Untuk ketahanan maksimal, pastikan kabel USB terpasang kencang dan HP
tidak masuk mode hemat baterai yang mematikan koneksi USB.

## Batasan & Catatan Lain

- **FFmpegKit** (library FFmpeg untuk Android yang dipakai app ini) sudah di-*archive*
  oleh pembuat aslinya sejak 2024. Karena APK ini di-*sideload* (bukan lewat Play
  Store), library tetap bisa dipakai — tapi kalau butuh maintenance jangka panjang,
  pertimbangkan fork komunitas atau ganti ke library RTMP client Android lain.
- Versi awal ini berjalan sebagai `Activity` biasa. Untuk pemakaian produksi
  (stabil saat HP di-*lock*/berpindah app), pindahkan proses FFmpeg ke
  **Foreground Service** dengan notifikasi persisten, supaya tidak dimatikan sistem.
- Pastikan mode USB di HP di-set ke **"File Transfer" atau "MTP"**, bukan
  "Charging only", agar `adb` bisa mendeteksi device.

## Lisensi Komponen Pihak Ketiga

- FFmpeg: LGPL/GPL tergantung build yang dipakai
- FFmpegKit: LGPL-3.0 (untuk paket non-GPL)
- Android Platform Tools: Apache 2.0
