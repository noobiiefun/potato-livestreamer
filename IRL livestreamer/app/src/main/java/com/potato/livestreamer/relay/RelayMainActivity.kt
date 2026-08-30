package com.potato.livestreamer.relay

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.potato.livestreamer.R
import com.potato.livestreamer.relay.speedtest.SpeedTest

/**
 * Potato Monitor Desk - Client (Ported to IRL Livestreamer)
 *
 * Menerima stream MPEG-TS (video H.264 + audio AAC) dari server PC lewat
 * TCP socket (diteruskan via adb reverse lewat kabel USB), DAN sekaligus bisa
 * live-streaming isi layar HP ini (mirror PC) ke RTMP (YouTube/Facebook/server
 * sendiri) -- jadi tidak perlu app live-streaming terpisah lagi.
 */
class RelayMainActivity : AppCompatActivity() {

    companion object {
        private const val HOST = "127.0.0.1"
        private const val STREAM_PORT = 9999
        private const val RETRY_DELAY_MS = 2000L

        private val QUALITY_PRESETS = linkedMapOf(
            "Rendah (640x360, hemat data)" to Pair("640x360", "1M"),
            "Sedang (960x540)" to Pair("960x540", "2M"),
            "Tinggi (1280x720)" to Pair("1280x720", "3M"),
            "Sangat Tinggi (1920x1080)" to Pair("1920x1080", "6M")
        )
    }

    private lateinit var previewImage: ImageView
    private lateinit var previewEngine: MjpegPreviewEngine
    private lateinit var statusText: TextView
    private lateinit var reconnectButton: Button
    private lateinit var settingsButton: ImageButton
    private lateinit var minimizeButton: ImageButton
    private lateinit var liveSwitch: SwitchCompat
    private lateinit var liveBadge: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private var retryPending = false

    // ---------- live streaming state ----------
    private var relayService: RelayStreamService? = null
    private var serviceBound = false
    private var liveSeconds = 0
    private var suppressSwitchCallback = false

    private val liveTimerRunnable = object : Runnable {
        override fun run() {
            liveSeconds++
            liveBadge.text = "🔴 LIVE ${formatDuration(liveSeconds)}"
            mainHandler.postDelayed(this, 1000)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RelayStreamService.LocalBinder
            relayService = binder.getService()
            relayService?.listener = object : RelayStatusListener {
                override fun onRelayConnected() {
                    mainHandler.post {
                        liveSeconds = 0
                        liveBadge.visibility = View.VISIBLE
                        mainHandler.removeCallbacks(liveTimerRunnable)
                        mainHandler.post(liveTimerRunnable)
                        Toast.makeText(this@RelayMainActivity, "Live streaming dimulai.", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onRelayFailed(reason: String) {
                    mainHandler.post {
                        setSwitchStateSilently(false)
                        liveBadge.visibility = View.GONE
                        mainHandler.removeCallbacks(liveTimerRunnable)
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        Toast.makeText(this@RelayMainActivity, "Live gagal: $reason", Toast.LENGTH_LONG).show()
                    }
                }

                override fun onRelayDisconnected() {
                    mainHandler.post {
                        setSwitchStateSilently(false)
                        liveBadge.visibility = View.GONE
                        mainHandler.removeCallbacks(liveTimerRunnable)
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            relayService = null
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_relay_main)
        applyImmersiveFullscreen()

        val playerView = findViewById<ImageView>(R.id.previewImage)
        previewImage = playerView
        statusText = findViewById(R.id.statusText)
        reconnectButton = findViewById(R.id.reconnectButton)
        settingsButton = findViewById(R.id.settingsButton)
        minimizeButton = findViewById(R.id.minimizeButton)
        liveSwitch = findViewById(R.id.liveSwitch)
        liveBadge = findViewById(R.id.liveBadge)

        previewEngine = MjpegPreviewEngine(HOST, STREAM_PORT, previewImage) { reason ->
            mainHandler.post {
                statusText.text = "Terputus dari PC. Mencoba ulang..."
                scheduleReconnect()
            }
        }

        reconnectButton.setOnClickListener { startStream() }
        settingsButton.setOnClickListener { showSettingsMenu() }
        minimizeButton.setOnClickListener { enterPipMode() }
        liveSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!suppressSwitchCallback) onLiveToggle(isChecked)
        }

        applyBadgePosition()
        requestNotificationPermissionIfNeeded()
        bindService(Intent(this, RelayStreamService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)

        startStream()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun applyImmersiveFullscreen() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    // ---------- preview stream (dari PC) ----------
    private fun startStream() {
        statusText.text = "Menghubungkan ke PC..."
        previewEngine.stop()
        previewEngine = MjpegPreviewEngine(HOST, STREAM_PORT, previewImage) { reason ->
            mainHandler.post {
                statusText.text = "Terputus dari PC. Mencoba ulang..."
                scheduleReconnect()
            }
        }
        previewEngine.start()

        // Ambil RTMP URL+key yang diisi di server -- otomatis tersimpan,
        // supaya kamu cukup toggle switch LIVE tanpa perlu ketik manual.
        ControlClient.fetchRtmpUrl { url ->
            if (!url.isNullOrBlank()) {
                mainHandler.post { LivePrefs.setRtmpUrl(this, url.trim()) }
            }
        }
        statusText.text = ""
    }

    private fun scheduleReconnect() {
        if (retryPending) return
        retryPending = true
        mainHandler.postDelayed({
            retryPending = false
            startStream()
        }, RETRY_DELAY_MS)
    }

    // ---------- live streaming (relay TS dari PC -> RTMP) ----------
    private fun onLiveToggle(isChecked: Boolean) {
        // Cegah layar mati/terkunci sendiri selama live aktif -- encoding
        // H.264 + push RTMP tetap harus jalan mulus walau HP didiamkan.
        // (Wake lock di RelayStreamService jadi jaring pemanen tambahan
        // kalau user tetap menekan tombol power manual.)
        if (isChecked) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        if (isChecked) {
            val rtmpUrlFallback = LivePrefs.getRtmpUrl(this).trim()
            statusText.text = "Mengambil setting live dari PC..."
            // Selalu ambil setting TERBARU dari server (bukan cache lama) --
            // supaya kalau kamu baru ganti resolusi/bitrate di tab
            // Pengaturan PC, HP langsung pakai yang terbaru tanpa perlu
            // restart app HP.
            ControlClient.fetchLiveSettings { settings ->
                mainHandler.post {
                    statusText.text = ""
                    val rtmpUrl = settings?.rtmpUrl?.ifBlank { null } ?: rtmpUrlFallback
                    if (rtmpUrl.isBlank()) {
                        Toast.makeText(this, "Isi dulu alamat RTMP di ⚙ > Pengaturan Live (PC).", Toast.LENGTH_LONG).show()
                        setSwitchStateSilently(false)
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        return@post
                    }
                    if (settings != null) {
                        LivePrefs.setRtmpUrl(this, rtmpUrl)
                        relayService?.startRelay(rtmpUrl, settings.width, settings.height, settings.bitrateBps, settings.fps)
                    } else {
                        // Server tidak terjangkau saat ini -- tetap coba live
                        // pakai RTMP URL yang tersimpan sebelumnya + kualitas
                        // default, daripada gagal total.
                        Toast.makeText(this, "Tidak bisa ambil setting dari PC, pakai default 1280x720.", Toast.LENGTH_SHORT).show()
                        relayService?.startRelay(rtmpUrl, 1280, 720, 3_000_000, 30)
                    }
                }
            }
        } else {
            relayService?.stopRelay()
            liveBadge.visibility = View.GONE
            mainHandler.removeCallbacks(liveTimerRunnable)
        }
    }

    private fun setSwitchStateSilently(checked: Boolean) {
        suppressSwitchCallback = true
        liveSwitch.isChecked = checked
        suppressSwitchCallback = false
    }

    private fun formatDuration(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    private fun applyBadgePosition() {
        val params = liveBadge.layoutParams as android.widget.FrameLayout.LayoutParams
        params.gravity = when (LivePrefs.getBadgePosition(this)) {
            BadgePosition.TOP_LEFT -> Gravity.TOP or Gravity.START
            BadgePosition.TOP_RIGHT -> Gravity.TOP or Gravity.END
            BadgePosition.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
            BadgePosition.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
        }
        liveBadge.layoutParams = params
    }

    // ---------- menu pengaturan ----------
    private fun showSettingsMenu() {
        val options = arrayOf(
            "Kualitas Streaming",
            "Pengaturan Live (RTMP & Posisi Timer)",
            "Tes Kecepatan Internet"
        )
        AlertDialog.Builder(this)
            .setTitle("Pengaturan")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showQualityDialog()
                    1 -> showLiveSettingsDialog()
                    2 -> runSpeedTest()
                }
            }
            .show()
    }

    private fun runSpeedTest() {
        val progress = AlertDialog.Builder(this)
            .setTitle("Tes Kecepatan Internet")
            .setMessage("Mengukur kecepatan download HP...")
            .setCancelable(false)
            .show()

        SpeedTest.run { result ->
            mainHandler.post {
                progress.dismiss()
                if (result.error != null) {
                    Toast.makeText(this, "Tes gagal: ${result.error}", Toast.LENGTH_LONG).show()
                    return@post
                }
                val mbps = result.downloadMbps
                AlertDialog.Builder(this)
                    .setTitle("Hasil Tes Kecepatan")
                    .setMessage(
                        "Download: ${"%.1f".format(mbps)} Mbps\n\n" +
                            "Panduan kasar untuk live 720p30: idealnya kecepatan " +
                            "upload internetmu minimal 4 Mbps (angka di atas ini " +
                            "cuma proxy download, upload biasanya lebih rendah dari " +
                            "download di kebanyakan koneksi rumah/seluler)."
                    )
                    .setPositiveButton("OK", null)
                    .show()
                ControlClient.sendSpeedtestReport(mbps, null)
            }
        }
    }

    private fun showQualityDialog() {
        val labels = QUALITY_PRESETS.keys.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Pilih Kualitas Streaming")
            .setItems(labels) { _, which ->
                val label = labels[which]
                val (resolution, bitrate) = QUALITY_PRESETS.getValue(label)
                Toast.makeText(this, "Menerapkan: $label...", Toast.LENGTH_SHORT).show()
                ControlClient.sendQuality(resolution, bitrate) { success ->
                    mainHandler.post {
                        val msg = if (success) "Kualitas diubah, stream akan reconnect otomatis."
                        else "Gagal mengirim ke server. Pastikan server sedang jalan."
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun showLiveSettingsDialog() {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val urlLabel = TextView(this).apply { text = "Alamat RTMP (URL + stream key digabung):" }
        val urlInput = EditText(this).apply {
            hint = "rtmp://a.rtmp.youtube.com/live2/xxxx-xxxx-xxxx"
            setText(LivePrefs.getRtmpUrl(this@RelayMainActivity))
        }

        val posLabel = TextView(this).apply {
            text = "\nPosisi timer LIVE di layar:"
        }
        val radioGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val positions = listOf(
            "Kiri Atas" to BadgePosition.TOP_LEFT,
            "Kanan Atas" to BadgePosition.TOP_RIGHT,
            "Kiri Bawah" to BadgePosition.BOTTOM_LEFT,
            "Kanan Bawah" to BadgePosition.BOTTOM_RIGHT
        )
        val currentPos = LivePrefs.getBadgePosition(this)
        val radioButtons = positions.map { (label, pos) ->
            RadioButton(this).apply {
                text = label
                id = View.generateViewId()
                isChecked = pos == currentPos
            }
        }
        radioButtons.forEach { radioGroup.addView(it) }

        layout.addView(urlLabel)
        layout.addView(urlInput)
        layout.addView(posLabel)
        layout.addView(radioGroup)

        AlertDialog.Builder(this)
            .setTitle("Pengaturan Live")
            .setView(layout)
            .setPositiveButton("Simpan") { _, _ ->
                LivePrefs.setRtmpUrl(this, urlInput.text.toString().trim())
                val checkedIndex = radioButtons.indexOfFirst { it.id == radioGroup.checkedRadioButtonId }
                if (checkedIndex >= 0) {
                    LivePrefs.setBadgePosition(this, positions[checkedIndex].second)
                    applyBadgePosition()
                }
                Toast.makeText(this, "Pengaturan live disimpan.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ---------- Picture-in-Picture ----------
    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().build())
        } else {
            Toast.makeText(this, "Picture-in-Picture butuh Android 8.0+", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPipMode()
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        previewEngine.stop()
        super.onDestroy()
    }
}
