package com.potato.livestreamer.relay

import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/** Setting live-encode yang dikirim server (diisi lewat tab Pengaturan di PC). */
data class ServerLiveSettings(
    val rtmpUrl: String,
    val width: Int,
    val height: Int,
    val bitrateBps: Int,
    val fps: Int
)

/**
 * Mengirim perintah ganti resolusi/bitrate ke server PC lewat TCP.
 */
object ControlClient {
    private const val HOST = "127.0.0.1"
    private const val PORT = 9998
    private val executor = Executors.newSingleThreadExecutor()

    fun fetchRtmpUrl(onResult: (String?) -> Unit) {
        executor.execute {
            val url = try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(HOST, PORT), 3000)
                    val line = socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
                    val json = JSONObject(line ?: "{}")
                    json.optString("rtmp_url", "").ifBlank { null }
                }
            } catch (_: Exception) {
                null
            }
            onResult(url)
        }
    }

    fun fetchLiveSettings(onResult: (ServerLiveSettings?) -> Unit) {
        executor.execute {
            val settings = try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(HOST, PORT), 3000)
                    val line = socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
                    val json = JSONObject(line ?: "{}")
                    val res = json.optString("live_resolution", "1280x720")
                    val (w, h) = res.split("x").let {
                        Pair(it.getOrNull(0)?.toIntOrNull() ?: 1280, it.getOrNull(1)?.toIntOrNull() ?: 720)
                    }
                    ServerLiveSettings(
                        rtmpUrl = json.optString("rtmp_url", ""),
                        width = w,
                        height = h,
                        bitrateBps = json.optInt("live_bitrate", 3_000_000),
                        fps = json.optInt("live_fps", 30)
                    )
                }
            } catch (_: Exception) {
                null
            }
            onResult(settings)
        }
    }

    fun sendSpeedtestReport(downloadMbps: Double, uploadMbps: Double?, onResult: ((Boolean) -> Unit)? = null) {
        executor.execute {
            val success = try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(HOST, PORT), 3000)
                    socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
                    val json = JSONObject().apply {
                        put("type", "speedtest_report")
                        put("download_mbps", downloadMbps)
                        if (uploadMbps != null) put("upload_mbps", uploadMbps)
                    }
                    socket.getOutputStream().apply {
                        write(json.toString().toByteArray(Charsets.UTF_8))
                        flush()
                    }
                }
                true
            } catch (_: Exception) {
                false
            }
            onResult?.invoke(success)
        }
    }

    fun sendQuality(resolution: String, videoBitrate: String, onResult: ((Boolean) -> Unit)? = null) {
        executor.execute {
            val success = try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(HOST, PORT), 3000)
                    val json = JSONObject()
                        .put("resolution", resolution)
                        .put("video_bitrate", videoBitrate)
                    socket.getOutputStream().apply {
                        write(json.toString().toByteArray(Charsets.UTF_8))
                        flush()
                    }
                }
                true
            } catch (_: Exception) {
                false
            }
            onResult?.invoke(success)
        }
    }
}
