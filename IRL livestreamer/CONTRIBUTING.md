# Panduan Berkontribusi di potato-livestreamer

Terima kasih telah tertarik untuk membantu mengembangkan proyek **potato-livestreamer**! Karena proyek ini ditargetkan untuk perangkat dengan spesifikasi rendah (Android Go), kami memiliki standar ketat mengenai manajemen memori dan performa.

## ⚠️ Aturan Pengodean yang Wajib Dipatuhi

1. **Jangan Menaikkan Resolusi Video Default**
   Resolusi *default* video encoder harus tetap berada di angka **854x480 (480p)** dengan bitrate maksimal **1500 Kbps**. Jangan mencoba mengubahnya ke 1080p karena akan memicu *Force Close* akibat *Overheating* pada chipset MediaTek Helio G36.

2. **Hindari Kebocoran Memori (Memory Leaks) pada GPS**
   Pastikan Anda selalu memanggil fungsi `fusedLocationClient.removeLocationUpdates(locationCallback)` di dalam siklus hidup `onDestroy()` untuk mencegah pemborosan daya baterai dan RAM saat aplikasi ditutup.

3. **Gunakan Library Ringan**
   Jika ingin menambahkan fitur baru, diskusikan terlebih dahulu melalui tab *Issues*. Hindari menggunakan library besar yang memakan banyak ruang penyimpanan atau runtime RAM berlebih.

## 🚀 Alur Mengirimkan Kontribusi
1. Lakukan *Fork* pada repositori ini.
2. Buat *branch* baru untuk fitur Anda (`git checkout -b fitur/fitur-baru-anda`).
3. Lakukan *Commit* perubahan Anda dengan pesan yang jelas (`git commit -m 'Menambahkan fitur X'`).
4. *Push* ke branch tersebut (`git push origin fitur/fitur-baru-anda`).
5. Buat sebuah *Pull Request* baru di repositori utama.
