package com.potato.livestreamer

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics

/**
 * Potato Livestreamer — Android side.
 *
 * This app does NOT capture this phone's own screen. It receives an already-encoded
 * H.264 elementary stream from a PC (sent over USB via `adb forward`), and remuxes
 * it (no re-encode, -c:v copy) straight into an RTMP push to YouTube — using this
 * phone's own network connection as the uplink.
 *
 * Flow reminder:
 *   PC (captures + encodes its own screen)
 *     -> adb forward tcp:PORT tcp:PORT (USB cable)
 *       -> this app, FFmpeg in "listen" mode on 0.0.0.0:PORT
 *         -> remux -> rtmp://.../live2/<key>
 */
class MainActivity : AppCompatActivity() {

    private lateinit var rtmpUrlInput: EditText
    private lateinit var portInput: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusLog: TextView

    private var activeSession: FFmpegSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rtmpUrlInput = findViewById(R.id.rtmpUrlInput)
        portInput = findViewById(R.id.portInput)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        statusLog = findViewById(R.id.statusLog)

        startButton.setOnClickListener { startListening() }
        stopButton.setOnClickListener { stopListening() }
    }

    private fun startListening() {
        val rtmpUrl = rtmpUrlInput.text.toString().trim()
        if (rtmpUrl.isEmpty() || !rtmpUrl.startsWith("rtmp")) {
            log("⚠️ Masukkan RTMP URL yang valid dulu (harus diawali rtmp:// atau rtmps://).")
            return
        }

        val port = portInput.text.toString().trim().ifEmpty { "6000" }

        // -f h264            : input adalah raw H.264 elementary stream (bukan file container)
        // -i tcp://0.0.0.0:PORT?listen=1 : FFmpeg bertindak sebagai TCP SERVER, menunggu
        //                       koneksi masuk dari PC lewat tunnel adb forward
        // -c:v copy          : remux saja, TANPA re-encode -> ringan di HP
        // -f flv <rtmpUrl>   : mux ke FLV dan push ke endpoint RTMP (YouTube)
        val command = "-f h264 -i tcp://0.0.0.0:$port?listen=1 -c:v copy -f flv $rtmpUrl"

        log("▶️ Memulai FFmpeg, menunggu koneksi dari PC di port $port ...")
        log("   Pastikan di PC sudah dijalankan:")
        log("   adb forward tcp:$port tcp:$port")
        log("   lalu jalankan pc_client.py")

        setRunningState(true)

        activeSession = FFmpegKit.executeAsync(
            command,
            { session ->
                // Called when the session completes (success, error, or cancelled)
                runOnUiThread {
                    val returnCode = session.returnCode
                    if (ReturnCode.isSuccess(returnCode)) {
                        log("✅ Stream selesai dengan normal.")
                    } else if (ReturnCode.isCancel(returnCode)) {
                        log("⏹️ Dihentikan oleh pengguna.")
                    } else {
                        log("❌ FFmpeg berhenti dengan error. Lihat log lengkap di Logcat.")
                        log("   Return code: $returnCode")
                    }
                    setRunningState(false)
                }
            },
            { log ->
                // Raw FFmpeg log lines — useful for debugging, keep this light in the UI
                if (log.message.contains("Non-monotonous", ignoreCase = true).not()) {
                    // no-op: verbose logs go to Logcat via FFmpegKitConfig by default
                }
            },
            { statistics: Statistics ->
                runOnUiThread {
                    log("📡 LIVE — bitrate: ${statistics.bitrate} kbits/s, waktu: ${statistics.time}ms")
                }
            }
        )
    }

    private fun stopListening() {
        activeSession?.let { session ->
            FFmpegKit.cancel(session.sessionId)
            log("⏹️ Menghentikan sesi...")
        }
        setRunningState(false)
    }

    private fun setRunningState(running: Boolean) {
        startButton.isEnabled = !running
        stopButton.isEnabled = running
        rtmpUrlInput.isEnabled = !running
        portInput.isEnabled = !running
    }

    private fun log(message: String) {
        runOnUiThread {
            statusLog.append("\n$message")
        }
    }
}
