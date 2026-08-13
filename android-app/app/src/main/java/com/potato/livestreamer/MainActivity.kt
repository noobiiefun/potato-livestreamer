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
 * ARSITEKTUR (v4): PC yang encode H.264 (hardware), HP cuma remux.
 *   - PC capture layar dan langsung ENCODE ke H.264 (NVENC/QuickSync/AMF,
 *     fallback libx264), dibungkus MPEG-TS, dikirim lewat video port.
 *   - HP (di sinilah kita) TIDAK decode/encode apa-apa lagi — cuma REMUX
 *     (`-c copy`) stream MPEG-TS itu langsung ke RTMP/YouTube. Karena tidak
 *     ada decode+encode ulang, HP jauh lebih dingin/hemat baterai, DAN tidak
 *     ada kompresi lossy dobel yang bikin gambar "flicker" seperti versi
 *     MJPEG sebelumnya — video yang sampai ke YouTube persis sama kualitas
 *     H.264 yang di-encode PC.
 *
 * Flow:
 *   1. User tekan "Tunggu Koneksi PC" -> buka ControlServer, menunggu PC
 *      mengirim Stream URL + Stream Key.
 *   2. Begitu config diterima, "Go LIVE" aktif.
 *   3. User tekan "Go LIVE" -> FFmpeg mulai listen di video port, menunggu
 *      stream MPEG-TS/H.264 dari PC, lalu REMUX (copy, tanpa re-encode) ke RTMP.
 *   4. Resilience: kalau sesi terputus tak terduga (USB goyang, dsb) dan
 *      user belum menekan Stop, sesi otomatis di-restart, bukan langsung
 *      idle — supaya gangguan sesaat tidak memutus LIVE sepenuhnya.
 *
 * CATATAN: audio belum didukung di versi ini — PC mengirim video-only H.264.
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
    private var receivedBitrateKbps: Int = 2500

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
            onConfigReceived = { streamUrl, streamKey, videoBitrateKbps ->
                receivedStreamUrl = streamUrl
                receivedStreamKey = streamKey
                receivedBitrateKbps = videoBitrateKbps
                runOnUiThread {
                    connectionStatus.text = "✅ Terhubung ke PC. Siap LIVE."
                    goLiveButton.isEnabled = true
                    log("📥 Konfigurasi diterima dari PC (Stream URL + Key, target bitrate ${videoBitrateKbps}kbps).")
                }
            },
            onLog = { msg -> log(msg) }
        )
        controlServer?.start()

        connectionStatus.text = "⏳ Menunggu PC menyambung (port $controlPort)..."
        log("🔌 Menunggu koneksi kontrol dari PC di port $controlPort")
        log("   Jalankan pc_client.py / pc_client_gui.py di PC sekarang.")
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
     * Starts (or restarts) the FFmpeg session: listen for the MPEG-TS/H.264
     * stream PC already encoded, and just REMUX it (no decode, no re-encode)
     * straight into the RTMP push.
     *
     * On unexpected termination (and only if the user hasn't pressed Stop),
     * this re-calls itself after a short delay instead of going idle — brief
     * USB/network hiccups cause a short automatic retry, not a full stop.
     */
    private fun startFfmpegListenLoop(videoPort: String, fullRtmpUrl: String, isRetry: Boolean) {
        if (userStopped) return

        // Input: MPEG-TS containing H.264 already encoded by the PC.
        // -c copy: REMUX only — just repackages the existing H.264 bitstream
        //   into FLV/RTMP without touching the pixels. No decode, no
        //   re-encode, so the phone barely works (no heat, no battery hit),
        //   and there's no second lossy compression pass (no flicker).
        val command = "-fflags +genpts -i tcp://0.0.0.0:$videoPort?listen=1 " +
            "-c copy -f flv $fullRtmpUrl"

        log(if (isRetry) "🔁 Mencoba menyambung ulang ke PC..." else "▶️ Menunggu stream H.264 dari PC di port $videoPort ...")
        if (!isRetry) {
            log("   Di PC, jalankan/lanjutkan pc_client_gui.py sekarang.")
            log("   HP cuma remux (copy) stream ini ke RTMP — ringan, tidak decode/encode apapun.")
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
                        log("⚠️ Koneksi terputus (kemungkinan USB tidak stabil atau PC berhenti mengirim).")
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
                    connectionStatus.text = "🔴 LIVE — encoding ${statistics.videoFps} fps, bitrate ${statistics.bitrate} kbits/s"
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
