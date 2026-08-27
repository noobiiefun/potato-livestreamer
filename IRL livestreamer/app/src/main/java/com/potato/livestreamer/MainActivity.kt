package com.potato.livestreamer

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
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvSpeed = findViewById(R.id.tvSpeed)
        tvStreamStatus = findViewById(R.id.tvStreamStatus)
        etRtmpUrl = findViewById(R.id.etRtmpUrl)
        btnStartStream = findViewById(R.id.btnStartStream)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
        val openGlView = findViewById<OpenGlView>(R.id.surfaceView)

        // Inisialisasi rtmpCamera menggunakan OpenGlView hemat memori
        rtmpCamera = RtmpCamera2(openGlView, this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        if (checkPermissions()) {
            initAppFeatures()
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
            initAppFeatures()
        } else {
            Toast.makeText(this, "Aplikasi membutuhkan seluruh izin untuk berjalan!", Toast.LENGTH_LONG).show()
        }
    }

    private fun initAppFeatures() {
        startLocationTracking()

        // Mulai jalankan pratinjau kamera utama (kamera belakang)
        if (!rtmpCamera.isOnPreview) {
            rtmpCamera.startPreview()
        }

        btnStartStream.setOnClickListener {
            if (!rtmpCamera.isStreaming) {
                val url = etRtmpUrl.text.toString().trim()
                if (url.isEmpty()) {
                    Toast.makeText(this, "URL RTMP wajib diisi", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // KONFIGURASI OPTIMASI ANDROID GO:
                // Set ke 480p (854x480), 30 FPS, Bitrate 1200Kbps. 
                // HP dijamin lancar dan tidak hang akibat kehabisan RAM.
                if (rtmpCamera.prepareVideo(854, 480, 30, 1200 * 1024, 0) && rtmpCamera.prepareAudio()) {
                    rtmpCamera.startStream(url)
                } else {
                    Toast.makeText(this, "Gagal menyetel konfigurasi media encoder", Toast.LENGTH_SHORT).show()
                }
            } else {
                rtmpCamera.stopStream()
                tvStreamStatus.text = "Status: Terputus"
                btnStartStream.text = "MULAI LIVE"
            }
        }

        btnSwitchCamera.setOnClickListener {
            rtmpCamera.switchCamera() // Membalikkan kamera secara realtime saat live
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationTracking() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateIntervalMillis(500)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    // Mengambil nilai kecepatan (meter/detik) dan diubah ke KM/Jam
                    val speedKmH = (location.speed * 3.6).toInt()
                    tvSpeed.text = "$speedKmH km/h"
                }
            }
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    // --- Implementasi Interface Callback dari Library Pedro RTMP ---
    override fun onConnectionSuccessRtmp() {
        runOnUiThread {
            tvStreamStatus.text = "Status: LIVE STREAMING"
            btnStartStream.text = "STOP LIVE"
            Toast.makeText(this, "Koneksi RTMP Berhasil!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onConnectionFailedRtmp(reason: String) {
        runOnUiThread {
            tvStreamStatus.text = "Status: Koneksi Gagal"
            btnStartStream.text = "MULAI LIVE"
            Toast.makeText(this, "Gagal terhubung: $reason", Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewBitrateRtmp(bitrate: Long) {}
    override fun onDisconnectRtmp() {
        runOnUiThread {
            tvStreamStatus.text = "Status: Terputus"
            btnStartStream.text = "MULAI LIVE"
        }
    }
    override fun onAuthErrorRtmp() {}
    override fun onAuthSuccessRtmp() {}

    override fun onDestroy() {
        super.onDestroy()
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        if (rtmpCamera.isStreaming) {
            rtmpCamera.stopStream()
        }
        rtmpCamera.stopPreview()
    }
}
