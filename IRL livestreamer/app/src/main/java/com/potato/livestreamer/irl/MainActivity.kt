package com.potato.livestreamer.irl

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.pedro.common.ConnectChecker
import com.pedro.rtplibrary.view.OpenGlView
import com.pedro.rtplibrary.rtmp.RtmpCamera2

class MainActivity : AppCompatActivity(), ConnectChecker {

    private lateinit var rtmpCamera: RtmpCamera2
    private lateinit var openGlView: OpenGlView
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    private lateinit var tvSpeed: TextView
    private lateinit var tvStreamStatus: TextView
    private lateinit var etRtmpUrl: EditText
    private lateinit var btnStartStream: Button
    private lateinit var btnSwitchCamera: Button

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
    private val PERMISSION_REQUEST_CODE = 101

    // Konfigurasi encoding tetap (Android Go): 480p @ 30 FPS, 1200 Kbps.
    // Jangan naikkan — lihat CONTRIBUTING.md.
    private val VIDEO_WIDTH = 854
    private val VIDEO_HEIGHT = 480
    private val VIDEO_FPS = 30
    private val VIDEO_BITRATE = 1200 * 1024

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvSpeed = findViewById(R.id.tvSpeed)
        tvStreamStatus = findViewById(R.id.tvStreamStatus)
        etRtmpUrl = findViewById(R.id.etRtmpUrl)
        btnStartStream = findViewById(R.id.btnStartStream)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
        openGlView = findViewById(R.id.surfaceView)

        rtmpCamera = RtmpCamera2(openGlView, this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        btnStartStream.setOnClickListener { onStartStreamClicked() }
        btnSwitchCamera.setOnClickListener {
            // Aman dipanggil kapan pun kamera sudah aktif (preview atau streaming);
            // RootEncoder menangani switch tanpa memutus koneksi RTMP yang berjalan.
            if (::rtmpCamera.isInitialized) rtmpCamera.switchCamera()
        }

        if (checkPermissions()) {
            startLocationTracking()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)
        }
    }

    private fun checkPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startLocationTracking()
        } else {
            Toast.makeText(this, getString(R.string.error_permissions_required), Toast.LENGTH_LONG).show()
        }
    }

    // --- Lifecycle kamera ---
    // Sebelumnya preview dimulai sekali di onCreate() dan tidak pernah dilepas
    // sampai onDestroy(). Ini rawan crash: begitu Activity dikirim ke
    // background (mis. user buka app lain / kunci layar) lalu kembali,
    // Surface milik OpenGlView bisa sudah tidak valid lagi sementara kamera
    // masih mengira sedang preview. Solusinya: mulai preview di onResume,
    // stop di onPause (tapi JANGAN stop stream RTMP-nya, supaya siaran tidak
    // terputus hanya karena layar HP mati sebentar).
    override fun onResume() {
        super.onResume()
        if (checkPermissions() && ::rtmpCamera.isInitialized && !rtmpCamera.isOnPreview) {
            rtmpCamera.startPreview()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::rtmpCamera.isInitialized && rtmpCamera.isOnPreview && !rtmpCamera.isStreaming) {
            // Kalau sedang live, biarkan preview jalan supaya stream tidak putus.
            // Kalau cuma preview biasa (belum live), aman untuk dilepas.
            rtmpCamera.stopPreview()
        }
    }

    private fun onStartStreamClicked() {
        if (!rtmpCamera.isStreaming) {
            val url = etRtmpUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, getString(R.string.error_rtmp_url_empty), Toast.LENGTH_SHORT).show()
                return
            }

            tvStreamStatus.text = getString(R.string.status_connecting)

            if (rtmpCamera.prepareVideo(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS, VIDEO_BITRATE, 0) &&
                rtmpCamera.prepareAudio()
            ) {
                rtmpCamera.startStream(url)
            } else {
                tvStreamStatus.text = getString(R.string.status_disconnected)
                Toast.makeText(this, getString(R.string.error_encoder_prepare_failed), Toast.LENGTH_SHORT).show()
            }
        } else {
            rtmpCamera.stopStream()
            tvStreamStatus.text = getString(R.string.status_disconnected)
            btnStartStream.text = getString(R.string.btn_start_live)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationTracking() {
        if (!::rtmpCamera.isInitialized) return
        if (!rtmpCamera.isOnPreview) rtmpCamera.startPreview()

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateIntervalMillis(500)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    // Kecepatan dari GPS dalam meter/detik, dikonversi ke km/jam
                    val speedKmH = (location.speed * 3.6).toInt()
                    tvSpeed.text = getString(R.string.hud_speed_format, speedKmH)
                }
            }
        }
        locationCallback = callback
        fusedLocationClient.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())
    }

    // --- Implementasi com.pedro.common.ConnectChecker ---
    // CATATAN PENTING: versi kode sebelumnya menimpa method dengan nama
    // "ala-Rtmp" (onConnectionSuccessRtmp, onConnectionFailedRtmp, dst),
    // padahal import-nya sudah pakai interface unifikasi baru
    // `com.pedro.common.ConnectChecker` (RootEncoder 2.x) yang nama method-nya
    // generik tanpa akhiran protokol. Kombinasi ini TIDAK akan bisa dikompilasi
    // ("method does not override anything"). Nama method di bawah sudah
    // disesuaikan dengan interface tersebut — cek versi RootEncoder yang
    // benar-benar terpasang (lihat Wiki resminya) kalau Android Studio masih
    // menandai error, karena signature bisa berubah antar versi minor.
    override fun onConnectionStarted(url: String) {}

    override fun onConnectionSuccess() {
        runOnUiThread {
            tvStreamStatus.text = getString(R.string.status_live)
            btnStartStream.text = getString(R.string.btn_stop_live)
            Toast.makeText(this, getString(R.string.toast_rtmp_connected), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onConnectionFailed(reason: String) {
        runOnUiThread {
            tvStreamStatus.text = getString(R.string.status_connect_failed)
            btnStartStream.text = getString(R.string.btn_start_live)
            Toast.makeText(this, getString(R.string.toast_rtmp_failed, reason), Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewBitrate(bitrate: Long) {}

    override fun onDisconnect() {
        runOnUiThread {
            tvStreamStatus.text = getString(R.string.status_disconnected)
            btnStartStream.text = getString(R.string.btn_start_live)
        }
    }

    override fun onAuthError() {}
    override fun onAuthSuccess() {}

    override fun onDestroy() {
        super.onDestroy()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        if (::rtmpCamera.isInitialized) {
            if (rtmpCamera.isStreaming) rtmpCamera.stopStream()
            if (rtmpCamera.isOnPreview) rtmpCamera.stopPreview()
        }
    }
}
