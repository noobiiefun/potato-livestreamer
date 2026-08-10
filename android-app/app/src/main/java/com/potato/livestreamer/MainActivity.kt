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
 * ARSITEKTUR (v3): PC ringan, HP yang kerja berat.
 *   - PC hanya capture layar dan compress ke MJPEG (murah CPU, tidak
 *     mengganggu game yang sedang berjalan di PC). PC TIDAK melakukan
 *     H.264 encode sama sekali.
 *   - HP (di sinilah kita) menerima MJPEG itu lewat video port, lalu
 *     mendecode setiap frame-nya dan meng-ENCODE ke H.264 memakai hardware
 *     encoder Android (`h264_mediacodec`) — mirip proses "merekam layar",
 *     tapi hasilnya langsung dialirkan ke RTMP/YouTube, bukan disimpan ke
 *     file. Ini pekerjaan yang nyata — makanya HP jadi panas/baterai
 *     terpakai, beda dari versi lama yang cuma remux (lewatin) doang.
 *
 * Flow:
 *   1. User tekan "Tunggu Koneksi PC" -> buka ControlServer, menunggu PC
 *      mengirim Stream URL + Stream Key + target bitrate H.264.
 *   2. Begitu config diterima, "Go LIVE" aktif.
 *   3. User tekan "Go LIVE" -> FFmpeg mulai listen di video port, menunggu
 *      MJPEG stream dari PC, decode + encode H.264 (hardware) + push RTMP.
 *   4. Resilience: kalau sesi terputus tak terduga (USB goyang, dsb) dan
 *      user belum menekan Stop, sesi otomatis di-restart, bukan langsung
 *      idle — supaya gangguan sesaat tidak memutus LIVE sepenuhnya.
 *
 * CATATAN: audio belum didukung di versi ini — PC mengirim video-only MJPEG.
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
     * Starts (or restarts) the FFmpeg session: listen for MJPEG from PC,
     * decode it, encode to H.264 with the phone's hardware encoder
     * (h264_mediacodec), and push to RTMP.
     *
     * On unexpected termination (and only if the user hasn't pressed Stop),
     * this re-calls itself after a short delay instead of going idle — brief
     * USB/network hiccups cause a short automatic retry, not a full stop.
     */
    private fun startFfmpegListenLoop(videoPort: String, fullRtmpUrl: String, isRetry: Boolean) {
        if (userStopped) return

        // Input: MJPEG elementary stream (sequence of JPEG frames) from PC.
        // -c:v h264_mediacodec: HARDWARE H.264 encoder built into the phone's
        //   chipset — this is the actual "recording" work this phone does.
        //   (Software x264 isn't available in the LGPL-only ffmpeg-kit build
        //   this app uses, and would be far too slow on a budget phone anyway.)
        // -b:v: target bitrate the PC told us to aim for (YouTube quality).
        val command = "-f mjpeg -i tcp://0.0.0.0:$videoPort?listen=1 " +
            "-c:v h264_mediacodec -b:v ${receivedBitrateKbps}k -f flv $fullRtmpUrl"

        log(if (isRetry) "🔁 Mencoba menyambung ulang ke PC..." else "▶️ Menunggu koneksi MJPEG dari PC di port $videoPort ...")
        if (!isRetry) {
            log("   Di PC, jalankan/lanjutkan pc_client_gui.py sekarang.")
            log("   HP akan decode + encode H.264 (hardware) sendiri — ini yang bikin HP kerja/panas.")
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
                        log("⚠️ Koneksi terputus atau encoder gagal (kemungkinan USB tidak stabil,")
                        log("   atau chip HP tidak mendukung h264_mediacodec — cek log lengkap kalau berulang).")
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
