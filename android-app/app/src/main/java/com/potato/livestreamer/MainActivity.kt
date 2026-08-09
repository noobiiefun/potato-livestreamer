package com.potato.livestreamer

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics

/**
 * Potato Livestreamer — Android side.
 *
 * Flow:
 *   1. User taps "Tunggu Koneksi PC" -> opens a small control server (see
 *      ControlServer.kt) that waits for the PC to send Stream URL + Stream Key
 *      over the USB tunnel (adb forward on the control port).
 *   2. Once config arrives, "Go LIVE" becomes enabled.
 *   3. User taps "Go LIVE" -> starts FFmpeg listening on the video port for
 *      the H.264/AAC stream sent by the PC (mpegts container), and remuxes it
 *      (no re-encode, -c copy) straight into an RTMP push to YouTube using
 *      this phone's own network connection.
 *   4. Resilience: if the video session drops unexpectedly (USB hiccup, PC
 *      app restarted, etc.) and the user did NOT press Stop, the app does
 *      NOT go idle — it automatically re-listens and resumes, so a brief
 *      disconnect doesn't require the user to manually restart anything.
 *      Note: this minimizes downtime but can't guarantee a fully seamless
 *      broadcast on YouTube's side if the gap is long — see README.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var videoPortInput: EditText
    private lateinit var controlPortInput: EditText
    private lateinit var connectionStatus: TextView
    private lateinit var startWaitingButton: Button
    private lateinit var goLiveButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusLog: TextView
    private lateinit var badgePositionSpinner: Spinner
    private lateinit var liveBadge: TextView

    private var controlServer: ControlServer? = null
    private var activeSession: FFmpegSession? = null

    private var receivedStreamUrl: String? = null
    private var receivedStreamKey: String? = null

    @Volatile private var userStopped = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        videoPortInput = findViewById(R.id.videoPortInput)
        controlPortInput = findViewById(R.id.controlPortInput)
        connectionStatus = findViewById(R.id.connectionStatus)
        startWaitingButton = findViewById(R.id.startWaitingButton)
        goLiveButton = findViewById(R.id.goLiveButton)
        stopButton = findViewById(R.id.stopButton)
        statusLog = findViewById(R.id.statusLog)
        badgePositionSpinner = findViewById(R.id.badgePositionSpinner)
        liveBadge = findViewById(R.id.liveBadge)

        val positions = arrayOf("Kiri Atas", "Kanan Atas", "Kiri Bawah", "Kanan Bawah")
        badgePositionSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, positions)
        badgePositionSpinner.setSelection(1) // default: Kanan Atas
        badgePositionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyBadgePosition(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        applyBadgePosition(1)

        startWaitingButton.setOnClickListener { startWaitingForPc() }
        goLiveButton.setOnClickListener { goLive() }
        stopButton.setOnClickListener { stopEverything() }
    }

    private fun startWaitingForPc() {
        val controlPort = controlPortInput.text.toString().trim().ifEmpty { "6001" }.toInt()

        controlServer?.stop()
        controlServer = ControlServer(
            port = controlPort,
            onConfigReceived = { streamUrl, streamKey ->
                receivedStreamUrl = streamUrl
                receivedStreamKey = streamKey
                runOnUiThread {
                    connectionStatus.text = "✅ Terhubung ke PC. Siap LIVE."
                    goLiveButton.isEnabled = true
                    log("📥 Konfigurasi (Stream URL + Key) diterima dari PC.")
                }
            },
            onLog = { msg -> log(msg) }
        )
        controlServer?.start()

        connectionStatus.text = "⏳ Menunggu PC menyambung (port $controlPort)..."
        log("🔌 Menunggu koneksi kontrol dari PC di port $controlPort")
        log("   Jalankan pc_client.py di PC sekarang (pastikan 'adb forward' sudah aktif).")
        startWaitingButton.isEnabled = false
    }

    private fun goLive() {
        val streamUrl = receivedStreamUrl
        val streamKey = receivedStreamKey
        if (streamUrl.isNullOrEmpty() || streamKey.isNullOrEmpty()) {
            log("⚠️ Belum ada konfigurasi dari PC. Tunggu PC menyambung dulu.")
            return
        }
        val fullRtmpUrl = streamUrl.trimEnd('/') + "/" + streamKey
        val videoPort = videoPortInput.text.toString().trim().ifEmpty { "6000" }

        userStopped = false
        setLiveUiState(live = true)
        liveBadge.visibility = View.VISIBLE
        startFfmpegListenLoop(videoPort, fullRtmpUrl, isRetry = false)
    }

    /**
     * Starts (or restarts) the FFmpeg "listen for PC, remux to RTMP" session.
     * On unexpected termination (and only if the user hasn't pressed Stop),
     * this re-calls itself after a short delay instead of going idle — this
     * is the resilience behaviour requested: brief USB/network hiccups don't
     * kill the live session, they just cause a short automatic retry.
     */
    private fun startFfmpegListenLoop(videoPort: String, fullRtmpUrl: String, isRetry: Boolean) {
        if (userStopped) return

        // Input is mpegts (not raw h264) because the PC client may include an
        // AAC audio track alongside the video — mpegts supports muxed A/V and
        // is copy-safe end to end. "-c copy" copies whatever streams exist
        // (video-only or video+audio) without re-encoding on the phone.
        val command = "-f mpegts -i tcp://0.0.0.0:$videoPort?listen=1 -c copy -f flv $fullRtmpUrl"

        log(if (isRetry) "🔁 Mencoba menyambung ulang ke PC..." else "▶️ Menunggu koneksi video dari PC di port $videoPort ...")
        if (!isRetry) {
            log("   Di PC, jalankan/lanjutkan pc_client.py sekarang.")
        }

        activeSession = FFmpegKit.executeAsync(
            command,
            { session ->
                runOnUiThread {
                    if (userStopped) {
                        log("⏹️ Live dihentikan.")
                        setLiveUiState(live = false)
                        liveBadge.visibility = View.GONE
                        return@runOnUiThread
                    }

                    val returnCode = session.returnCode
                    if (ReturnCode.isSuccess(returnCode)) {
                        log("ℹ️ Sesi video berakhir, menunggu koneksi baru dari PC...")
                    } else {
                        log("⚠️ Koneksi terputus (kemungkinan USB/PC tidak stabil).")
                        log("   LIVE TIDAK dimatikan — mencoba menyambung ulang otomatis dalam 2 detik...")
                    }

                    connectionStatus.text = "⚠️ Terputus sementara — menyambung ulang..."
                    statusLog.postDelayed({
                        if (!userStopped) {
                            startFfmpegListenLoop(videoPort, fullRtmpUrl, isRetry = true)
                        }
                    }, 2000)
                }
            },
            { /* raw ffmpeg log lines go to Logcat by default */ },
            { statistics: Statistics ->
                runOnUiThread {
                    connectionStatus.text = "🔴 LIVE — bitrate ${statistics.bitrate} kbits/s"
                }
            }
        )
    }

    private fun stopEverything() {
        userStopped = true
        activeSession?.let { FFmpegKit.cancel(it.sessionId) }
        controlServer?.stop()
        receivedStreamUrl = null
        receivedStreamKey = null

        setLiveUiState(live = false)
        liveBadge.visibility = View.GONE
        connectionStatus.text = "⏹️ Berhenti. Tunggu koneksi PC lagi untuk mulai ulang."
        startWaitingButton.isEnabled = true
        goLiveButton.isEnabled = false
        log("⏹️ Semua proses dihentikan oleh pengguna.")
    }

    private fun setLiveUiState(live: Boolean) {
        goLiveButton.isEnabled = !live
        stopButton.isEnabled = live
    }

    private fun applyBadgePosition(position: Int) {
        val params = liveBadge.layoutParams as FrameLayout.LayoutParams
        params.gravity = when (position) {
            0 -> Gravity.TOP or Gravity.START
            1 -> Gravity.TOP or Gravity.END
            2 -> Gravity.BOTTOM or Gravity.START
            else -> Gravity.BOTTOM or Gravity.END
        }
        liveBadge.layoutParams = params
    }

    private fun log(message: String) {
        runOnUiThread { statusLog.append("\n$message") }
    }
}
