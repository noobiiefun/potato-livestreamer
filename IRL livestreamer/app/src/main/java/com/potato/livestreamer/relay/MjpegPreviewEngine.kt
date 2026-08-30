package com.potato.livestreamer.relay

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Preview ringan: connect ke server PC, decode JPEG, tampilkan di ImageView.
 */
class MjpegPreviewEngine(
    private val host: String,
    private val port: Int,
    private val imageView: ImageView,
    private val onError: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var decodeThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var socket: Socket? = null
    private val videoSlot = LatestFrameSlot()

    fun start() {
        if (running.getAndSet(true)) return

        decodeThread = Thread {
            while (running.get()) {
                val jpeg = videoSlot.take() ?: break
                val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: continue
                mainHandler.post { imageView.setImageBitmap(bmp) }
            }
        }.apply { isDaemon = true; start() }

        thread = Thread {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 5000)
                s.tcpNoDelay = true
                socket = s
                FrameProtocol.readLoop(s.getInputStream(), object : FrameProtocol.Listener {
                    override fun onVideoFrame(jpeg: ByteArray) {
                        videoSlot.put(jpeg)
                    }
                    override fun onAudioFrame(adts: ByteArray) {}
                }, isRunning = { running.get() })
            } catch (e: Exception) {
                if (running.get()) onError(e.message ?: "Terputus dari PC")
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        decodeThread?.interrupt()
        videoSlot.wakeUp()
        try { socket?.close() } catch (_: Exception) {}
    }
}
