package com.potato.livestreamer.relay.speedtest

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Tes kecepatan internet super sederhana.
 */
object SpeedTest {

    private const val TEST_URL_10MB = "https://speed.cloudflare.com/__down?bytes=10000000"
    private const val TEST_URL_2MB = "https://speed.cloudflare.com/__down?bytes=2000000"

    data class Result(val downloadMbps: Double, val error: String? = null)

    private val executor = Executors.newSingleThreadExecutor()

    fun run(onResult: (Result) -> Unit) {
        executor.execute {
            onResult(measureDownload())
        }
    }

    private fun measureDownload(): Result {
        return try {
            downloadAndTime(TEST_URL_2MB)
            val (bytes, elapsedMs) = downloadAndTime(TEST_URL_10MB)
            if (elapsedMs <= 0) return Result(0.0, "Pengukuran tidak valid")
            val mbps = (bytes * 8.0 / 1_000_000.0) / (elapsedMs / 1000.0)
            Result(mbps)
        } catch (e: Exception) {
            Result(0.0, e.message ?: "Gagal tes kecepatan")
        }
    }

    private fun downloadAndTime(urlStr: String): Pair<Long, Long> {
        val start = System.currentTimeMillis()
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 15000
        conn.requestMethod = "GET"
        var totalBytes = 0L
        try {
            conn.inputStream.use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = stream.read(buffer)
                    if (n == -1) break
                    totalBytes += n
                }
            }
        } finally {
            conn.disconnect()
        }
        val elapsed = System.currentTimeMillis() - start
        return Pair(totalBytes, elapsed)
    }
}
