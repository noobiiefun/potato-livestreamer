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
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

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
    private lateinit var btnToggleMap: Button

    // --- Live-Tracking (peta) ---
    private lateinit var mapView: MapView
    private var routeLine: Polyline? = null
    private var positionMarker: Marker? = null
    private var mapVisible = true
    private var lastMapUpdateMillis = 0L
    private var lastMapPoint: GeoPoint? = null
    // Throttle: peta di-refresh minimal tiap sekian ms & sekian meter,
    // supaya tidak membebani CPU/RAM HP Android Go (beda dengan tvSpeed yang
    // aman di-update tiap lokasi masuk karena cuma ganti teks).
    private val MAP_UPDATE_MIN_INTERVAL_MS = 2000L
    private val MAP_UPDATE_MIN_DISTANCE_M = 8f
    private val MAP_DEFAULT_ZOOM = 17.0

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
        // WAJIB dipanggil SEBELUM inflate layout yang mengandung MapView —
        // ini set User-Agent (syarat tile server OSM, kalau tidak diisi
        // beberapa request bisa ditolak/rate-limit) dan pindahkan folder
        // cache osmdroid ke cache dir milik app sendiri (getCacheDir()),
        // supaya TIDAK perlu izin WRITE_EXTERNAL_STORAGE sama sekali.
        Configuration.getInstance().load(
            applicationContext,
            getSharedPreferences("osmdroid_prefs", MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().osmdroidBasePath = File(cacheDir, "osmdroid")
        Configuration.getInstance().osmdroidTileCache = File(cacheDir, "osmdroid/tiles")

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvSpeed = findViewById(R.id.tvSpeed)
        tvStreamStatus = findViewById(R.id.tvStreamStatus)
        etRtmpUrl = findViewById(R.id.etRtmpUrl)
        btnStartStream = findViewById(R.id.btnStartStream)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
        btnToggleMap = findViewById(R.id.btnToggleMap)
        openGlView = findViewById(R.id.surfaceView)
        mapView = findViewById(R.id.mapView)

        setupMap()

        rtmpCamera = RtmpCamera2(openGlView, this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        btnStartStream.setOnClickListener { onStartStreamClicked() }
        btnSwitchCamera.setOnClickListener {
            // Aman dipanggil kapan pun kamera sudah aktif (preview atau streaming);
            // RootEncoder menangani switch tanpa memutus koneksi RTMP yang berjalan.
            if (::rtmpCamera.isInitialized) rtmpCamera.switchCamera()
        }
        btnToggleMap.setOnClickListener { toggleMapVisibility() }

        if (checkPermissions()) {
            startLocationTracking()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)
        }
    }

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        // Multi-touch tapi tanpa zoom-control bawaan (hemat ruang di mini-map).
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(MAP_DEFAULT_ZOOM)

        routeLine = Polyline().apply {
            outlinePaint.strokeWidth = 6f
            outlinePaint.color = 0xFF00FF00.toInt() // hijau, senada HUD kecepatan
        }
        mapView.overlays.add(routeLine)

        positionMarker = Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = "Posisi sekarang"
        }
        mapView.overlays.add(positionMarker)
    }

    private fun toggleMapVisibility() {
        mapVisible = !mapVisible
        mapView.visibility = if (mapVisible) android.view.View.VISIBLE else android.view.View.GONE
        btnToggleMap.text = if (mapVisible) {
            getString(R.string.btn_toggle_map_hide)
        } else {
            getString(R.string.btn_toggle_map_show)
        }
        // Kalau disembunyikan, hentikan animasi tile osmdroid supaya benar-benar
        // tidak makan CPU/baterai — bukan cuma disembunyikan secara visual.
        if (!mapVisible) mapView.onPause() else mapView.onResume()
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
        // osmdroid WAJIB dapat callback onResume/onPause sendiri (di luar
        // lifecycle Activity) untuk mengelola thread download tile — kalau
        // tidak dipanggil, tile bisa terus diunduh di background walau
        // Activity sudah tidak terlihat (boros kuota & baterai).
        if (::mapView.isInitialized && mapVisible) mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (::rtmpCamera.isInitialized && rtmpCamera.isOnPreview && !rtmpCamera.isStreaming) {
            // Kalau sedang live, biarkan preview jalan supaya stream tidak putus.
            // Kalau cuma preview biasa (belum live), aman untuk dilepas.
            rtmpCamera.stopPreview()
        }
        if (::mapView.isInitialized) mapView.onPause()
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
                    // Kecepatan dari GPS dalam meter/detik, dikonversi ke km/jam.
                    // Ini murah (cuma ganti teks), jadi aman di-update tiap lokasi masuk.
                    val speedKmH = (location.speed * 3.6).toInt()
                    tvSpeed.text = getString(R.string.hud_speed_format, speedKmH)

                    updateMap(location)
                }
            }
        }
        locationCallback = callback
        fusedLocationClient.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())
    }

    /**
     * Gerakkan mini-peta: posisi marker + arah hadap (bearing GPS) + tambah
     * titik ke garis rute. Di-throttle by waktu & jarak supaya render tile
     * osmdroid tidak membebani CPU HP Android Go tiap 500ms seperti update
     * teks kecepatan.
     */
    private fun updateMap(location: android.location.Location) {
        if (!::mapView.isInitialized || !mapVisible) return

        val point = GeoPoint(location.latitude, location.longitude)
        val now = System.currentTimeMillis()
        val movedEnough = lastMapPoint?.distanceToAsDouble(point)?.let { it >= MAP_UPDATE_MIN_DISTANCE_M } ?: true
        val timeElapsed = now - lastMapUpdateMillis >= MAP_UPDATE_MIN_INTERVAL_MS
        if (lastMapPoint != null && !(movedEnough && timeElapsed)) return

        lastMapPoint = point
        lastMapUpdateMillis = now

        positionMarker?.position = point
        // Bearing (arah hadap) cuma valid kalau device sedang benar-benar
        // bergerak — kalau diam, GPS sering kirim bearing 0 yang menyesatkan
        // (seolah selalu "menghadap utara"), jadi hanya dipakai kalau hasBearing().
        if (location.hasBearing()) {
            positionMarker?.rotation = location.bearing
        }

        routeLine?.addPoint(point)
        mapView.controller.animateTo(point)
        mapView.invalidate()
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
        // osmdroid menahan referensi bitmap tile & thread; wajib dilepas
        // eksplisit atau bisa memory-leak Activity ini.
        if (::mapView.isInitialized) mapView.onDetach()
    }
}
