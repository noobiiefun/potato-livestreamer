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

## Fase 2 — Fitur Live-Tracking (peta + arah + rute)

Konteks: user mau kembangkan **mode IRL livestreaming** ini dulu sebelum
menyelesaikan mode relay-dari-PC (yang jalan terpisah di repo
`potato-monitor-desk`, masih dalam pengembangan). 5 fitur yang diminta untuk
mode IRL:
1. **Live tracking** — kecepatan + peta visual + arah, dan idealnya rute
   perjalanan real-time. **Tanpa Google Maps API** (user mau yang gratis).
2. Kamera depan/belakang — ✅ sudah ada dari Fase 1.
3. Android Go — ✅ sudah jadi basis desain dari awal.
4. Live langsung RTMP — ✅ sudah ada dari Fase 1.
5. Live chat masuk ke dalam video (burned-in, bukan cuma overlay UI lokal) —
   **belum dikerjakan**, lihat roadmap di bawah.

### Yang sudah dikerjakan di fase ini (poin #1)
- Ditambahkan **osmdroid** (`org.osmdroid:osmdroid-android:6.1.20`) sebagai
  library peta — ini pakai tile OpenStreetMap, **gratis, open source, TANPA
  API key/akun/billing**, beda dari Google Maps SDK. Ini pilihan yang
  cocok dengan permintaan "gratis" di atas.
- Mini `MapView` (150x150dp) ditambahkan di pojok kanan atas layout, di
  bawah panel HUD kecepatan. Sengaja dibuat kecil (bukan fullscreen) supaya
  render tile tidak terlalu berat buat chip Android Go.
- `Marker` yang berputar mengikuti `location.bearing` (arah hadap GPS) —
  cuma dipakai kalau `location.hasBearing()` true, supaya tidak "muter-muter"
  menyesatkan saat HP diam.
- `Polyline` yang terus bertambah titik → jadi jejak rute perjalanan
  real-time di peta (bukan cuma titik terakhir).
- **Throttle update peta**: HUD kecepatan tetap update tiap lokasi masuk
  (murah, cuma ganti teks), tapi peta cuma di-refresh kalau sudah geser
  ≥8 meter DAN sudah lewat ≥2 detik sejak update terakhir — supaya
  render tile OSM (yang jauh lebih berat dari ganti teks) tidak jadi beban
  CPU/baterai ekstra di atas beban encoding RTMP yang sudah berat.
- Tombol **"SEMBUNYIKAN PETA" / "TAMPILKAN PETA"** — kalau di HP tertentu
  performanya masih berat, user/penonton bisa nonaktifkan peta on-the-fly
  tanpa berhenti live. Saat disembunyikan, `mapView.onPause()` betulan
  dipanggil (bukan cuma `View.GONE`) supaya thread download tile osmdroid
  juga berhenti, bukan cuma disembunyikan visual.
- Cache tile osmdroid diarahkan ke `cacheDir` milik app sendiri (bukan
  penyimpanan eksternal) — sengaja begini supaya **tidak perlu izin
  `WRITE_EXTERNAL_STORAGE` sama sekali**, konsisten dengan filosofi app ini
  yang serba minim izin.
- `mapView.onResume()` / `onPause()` / `onDetach()` dikaitkan ke lifecycle
  Activity — ini WAJIB menurut dokumentasi osmdroid, kalau tidak, thread
  tile download bisa terus jalan di background & memory leak.

### Yang masih perlu diuji langsung (belum bisa dicek di lingkungan ini)
- Apakah render peta + kamera preview + RTMP encoder jalan bareng tanpa
  drop frame parah di Redmi A3 asli (3 proses berat sekaligus: encode video,
  GPS high-accuracy, render tile map).
- Kebijakan penggunaan tile OSM publik (`tile.openstreetmap.org`) yang
  dipakai osmdroid secara default itu untuk *penggunaan wajar/ringan*. Kalau
  nanti dipakai banyak pengguna sekaligus (bukan cuma testing pribadi), ada
  risiko kena rate-limit dari server OSM. Kalau sampai ke tahap rilis
  publik/banyak user, pertimbangkan ganti tile source ke penyedia yang
  membolehkan pemakaian lebih tinggi meski tetap gratis (mis. akun gratis
  MapTiler/Stadia Maps yang masih ada tier gratisnya, atau self-host tile
  server sendiri) — untuk sekarang (pengembangan/testing pribadi) tile
  publik osmdroid sudah cukup.
- Belum ada penyimpanan/replay rute setelah live selesai (`routeLine` cuma
  hidup selama Activity hidup, hilang begitu app ditutup) — belum diminta,
  tapi dicatat kalau nanti relevan.

### Belum dikerjakan (poin #5 — live chat burned-in ke video)
Ini beda jenis pekerjaan dari live-tracking: butuh compositing OpenGL di atas
frame kamera SEBELUM di-encode, supaya chat ikut kelihatan oleh penonton
(bukan cuma di layar HP). RootEncoder punya mekanisme untuk ini
(`GlObjectStreamingBase` / custom OpenGL filter), tapi ini pekerjaan
terpisah dan cukup besar (butuh: sumber data chat real-time dari YouTube/
Twitch API dulu, baru render teksnya ke texture OpenGL). **Belum digarap di
sesi ini** — kandidat kuat untuk sesi berikutnya setelah live-tracking ini
dites nyata di device.

## Fase 3 — Fitur Atur Posisi Live-Tracking (drag mini-peta)

Konteks: user mau bisa atur posisi mini-peta di layar (mis. pindah dari
pojok kanan-atas ke pojok lain), tapi latar belakang **wajib tetap kamera
full-screen** — sangat relevan buat IRL livestreaming karena setiap orang
punya preferensi tata letak HUD yang beda (tergantung dipasang di mana:
dada, kepala, setang motor, dst).

### Perubahan struktural
- **Root layout diganti dari `ConstraintLayout` ke `FrameLayout`.** Ini
  BUKAN sekadar gaya — `ConstraintLayout` akan selalu menarik ulang child ke
  constraint yang didefinisikan di XML setiap layout pass, jadi kalau tetap
  dipakai, posisi hasil drag lewat kode gampang "ketarik balik" ke posisi
  constraint semula. `FrameLayout` murni mengandalkan `LayoutParams`
  (margin/gravity) yang kita kontrol penuh dari kode.
- Kamera (`OpenGlView`) jadi child PERTAMA & `match_parent` — otomatis jadi
  latar paling belakang untuk semua overlay lain di atasnya (HUD, peta,
  chat, kontrol). Ini yang menjamin "latar belakang wajib kamera full"
  sesuai permintaan, apa pun posisi overlay yang dipilih user.

### Cara kerja drag
- **Tekan-tahan dulu (long press, 350ms), baru bisa digeser** — sengaja
  BUKAN drag-langsung, supaya jari yang cuma numpang lewat/menyenggol peta
  pas lagi live tidak sengaja memindahkan posisinya. ada toleransi gerak
  kecil (`DRAG_TOUCH_SLOP_PX`) sebelum long-press dianggap batal (dianggap
  scroll/tap biasa).
- Gestur bawaan osmdroid (pan/zoom manual peta) DIMATIKAN
  (`setMultiTouchControls(false)`) — masuk akal karena peta ini murni
  tampilan otomatis (auto-center ke lokasi live), bukan peta yang dijelajah
  manual. Jadi semua sentuhan di area peta aman diklaim penuh untuk
  drag-reposisi tanpa konflik gestur.
- **Posisi tersimpan otomatis** (`SharedPreferences`, sebagai PERSENTASE
  layar, bukan pixel mentah) — supaya tetap masuk akal kalau dites di HP
  lain dengan resolusi beda. Dipulihkan otomatis tiap app dibuka lagi.
- **Bug yang sempat saya tangkap sebelum kepakai:** posisi default peta di
  XML pakai `gravity="top|end"` + `marginEnd`, BUKAN `leftMargin` — jadi
  kalau titik awal drag dibaca dari `params.leftMargin` (defaultnya 0),
  geseran pertama bakal "melompat" ke pojok kiri-atas dulu sebelum ngikutin
  jari. Diperbaiki dengan baca posisi awal dari `view.left`/`view.top`
  (posisi piksel aktual hasil layout), bukan dari nilai margin di
  `LayoutParams`.

### Yang masih perlu diuji langsung
- Rasa "enak"-nya durasi long-press 350ms & radius toleransi geser — ini
  angka tebakan awal, mungkin perlu disesuaikan setelah dicoba di device
  fisik (terutama kalau device terpasang di kendaraan yang bergetar, getaran
  bisa ke-anggap gerakan jari kalau radius toleransi kekecilan).
- Interaksi drag ini cuma diterapkan ke **mini-peta**, belum ke HUD
  kecepatan/live-chat (elemen lain masih posisi tetap by design, sesuai
  scope permintaan sejauh ini).

## Fase 4 — Respon 4 Pertanyaan User (preset posisi, one-click live, mode orientasi)

Konteks: user tanya 4 hal setelah lihat Fase 3. Jawaban singkat + status
pengerjaan tiap poin:

**1. Apakah drag berat buat Android Go?**
Drag itu sendiri ringan (cuma hitung angka + geser margin, bukan proses
kamera/GPU). Tapi drag pakai jari kurang presisi & agak riskan dipakai pas
live (apalagi HP dipasang di kendaraan yang bergetar). **Solusi:** ditambah
dialog **"Posisi Peta"** — pilih salah satu dari 4 pojok (Kiri Atas/Kanan
Atas/Kiri Bawah/Kanan Bawah), sekali tap langsung pindah tanpa perlu kontrol
jari halus. Drag manual tetap ada sebagai opsi buat yang mau atur lebih
bebas.

**2. One-click go live (mirip potato-monitor-desk)?**
✅ Sudah bisa. URL RTMP terakhir yang dipakai disimpan otomatis
(`SharedPreferences`) dan diisikan lagi tiap app dibuka — jadi sesi
berikutnya tinggal buka app, tekan **MULAI LIVE**, tanpa ngetik ulang.

**3. Mic wireless untuk IRL?**
⬜ **Belum dikerjakan** — sengaja ditunda karena implementasinya beda
tergantung JENIS mic wireless yang dipakai:
- Kit dengan penerima USB-C/3.5mm (mis. DJI Mic, Rode Wireless GO, Boya,
  Hollyland Lark) → plug-and-play, Android otomatis mendeteksinya sebagai
  mic eksternal biasa, kemungkinan besar TIDAK butuh kode tambahan sama
  sekali (auto-routing OS).
- Mic Bluetooth murni (tanpa kabel/receiver fisik ke HP) → BUTUH kode
  tambahan (`AudioManager.startBluetoothSco()`), dan kualitas suaranya
  biasanya jauh lebih rendah (mode Bluetooth SCO itu narrowband, ~8kHz,
  didesain untuk telepon bukan broadcast).
Perlu tahu dulu jenis mic yang dipakai sebelum implementasi diambil arahnya.

**4. Mode Vertikal & Horizontal?**
✅ Sudah bisa. Tombol **"Mode: Horizontal/Vertikal"** ditambahkan — dipilih
SEBELUM live, dan **DIKUNCI otomatis selama live** (tombol jadi disabled +
transparan, ada toast kalau dipaksa tekan). Ini disengaja: ganti orientasi
di TENGAH siaran bisa bikin video korup/aneh di sisi penonton, karena
resolusi encode (lebar x tinggi) yang dikirim ke RTMP server juga ikut
ditukar (mis. 854x480 landscape jadi 480x854 portrait) — bukan cuma
visual di HP yang diputar.

### Perubahan teknis
- `AndroidManifest.xml`: `android:screenOrientation="portrait"` yang tadinya
  fixed di-lepas — sekarang dikontrol penuh dari kode
  (`requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_...`), supaya
  bisa gonta-ganti mode.
- Layout kontrol bawah dirombak: tombol Start jadi full-width baris sendiri
  (paling gampang dijangkau), tombol sekunder (Balik Kamera/Peta/Posisi
  Peta/Mode) dipindah ke baris `HorizontalScrollView` di bawahnya —
  supaya tidak numpuk/kepotong di layar sempit Android Go sekarang ada 4
  tombol sekunder.
- `onStartStreamClicked()`: dimensi encode (`prepareVideo`) ditukar
  (width↔height) sesuai mode yang dipilih.

### Yang masih perlu diuji langsung
- Belum ada verifikasi nyata apakah RootEncoder/RTMP server tujuan (YouTube/
  Twitch) menerima metadata portrait dengan benar dari kombinasi
  width<height + rotation=0 seperti ini, atau butuh parameter rotasi
  tambahan. Kalau videonya kebalik/miring pas dites, kemungkinan perlu ganti
  parameter rotasi terakhir di `prepareVideo()` (saat ini selalu `0`).

## Yang SENGAJA belum diubah
- `CONTRIBUTING.md` dan `LICENSE` — sudah masuk akal, tidak ada bug.
- Belum ada Foreground Service (streaming masih jalan sebagai `Activity`
  biasa) — sudah dicatat di roadmap `readme.md`, tapi ini perubahan
  arsitektur besar, sengaja tidak sekaligus dikerjakan supaya scope perapian
  ini tetap fokus ke bug yang bikin project tidak bisa di-build.
- Belum ada implementasi Live Chat WebSocket / watermark HUD / chroma key —
  masih placeholder seperti sebelumnya (memang belum diminta).
