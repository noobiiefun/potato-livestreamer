package com.potato.livestreamer

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

/**
 * Tiny local control channel, separate from the video port.
 *
 * The PC sends a single JSON line once it has connected:
 *   {"streamUrl":"rtmp://a.rtmp.youtube.com/live2","streamKey":"xxxx-xxxx-xxxx-xxxx","videoBitrateKbps":2500}
 *
 * This lets the Stream URL / Stream Key / target bitrate be entered once on
 * the PC side (same split fields YouTube Studio itself uses) instead of
 * being typed into the phone. The phone just waits, receives it, and shows
 * the "Go LIVE" button. videoBitrateKbps matters here because THIS PHONE is
 * the one doing the H.264 encode (see MainActivity) — the PC only sends
 * cheap MJPEG frames, so it must tell the phone what output bitrate to use.
 *
 * The server keeps accepting new connections after each one, so the PC can
 * safely resend the config on every reconnect attempt without breaking anything.
 */
class ControlServer(
    private val port: Int,
    private val onConfigReceived: (streamUrl: String, streamKey: String, videoBitrateKbps: Int) -> Unit,
    private val onLog: (String) -> Unit
) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            try {
                serverSocket = ServerSocket(port)
                onLog("🔌 Control server menunggu di port $port ...")
                while (running) {
                    val client: Socket = serverSocket!!.accept()
                    handleClient(client)
                }
            } catch (e: Exception) {
                if (running) onLog("⚠️ Control server error: ${e.message}")
            }
        }
        thread?.isDaemon = true
        thread?.start()
    }

    private fun handleClient(client: Socket) {
        try {
            client.use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return
                val json = JSONObject(line)
                val streamUrl = json.optString("streamUrl", "")
                val streamKey = json.optString("streamKey", "")
                val videoBitrateKbps = json.optInt("videoBitrateKbps", 2500)
                if (streamUrl.isNotEmpty() && streamKey.isNotEmpty()) {
                    onConfigReceived(streamUrl, streamKey, videoBitrateKbps)
                    socket.getOutputStream().write("OK\n".toByteArray())
                    socket.getOutputStream().flush()
                } else {
                    onLog("⚠️ Konfigurasi dari PC tidak lengkap (streamUrl/streamKey kosong).")
                }
            }
        } catch (e: Exception) {
            onLog("⚠️ Gagal membaca konfigurasi dari PC: ${e.message}")
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        thread?.interrupt()
    }
}
