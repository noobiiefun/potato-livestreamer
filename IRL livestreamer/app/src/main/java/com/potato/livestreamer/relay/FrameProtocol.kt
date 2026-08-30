package com.potato.livestreamer.relay

import java.io.EOFException
import java.io.InputStream

/**
 * Protokol sederhana: [1 byte type][4 byte length][payload]
 */
object FrameProtocol {
    const val TYPE_VIDEO = 'V'.code.toByte()
    const val TYPE_AUDIO = 'A'.code.toByte()

    interface Listener {
        fun onVideoFrame(jpeg: ByteArray)
        fun onAudioFrame(adts: ByteArray)
    }

    fun readLoop(input: InputStream, listener: Listener, isRunning: () -> Boolean) {
        val header = ByteArray(5)
        while (isRunning()) {
            readFully(input, header, 5)
            val type = header[0]
            val length = ((header[1].toInt() and 0xFF) shl 24) or
                    ((header[2].toInt() and 0xFF) shl 16) or
                    ((header[3].toInt() and 0xFF) shl 8) or
                    (header[4].toInt() and 0xFF)
            if (length < 0 || length > 32 * 1024 * 1024) {
                throw IllegalStateException("Panjang frame tidak masuk akal: $length")
            }
            val payload = ByteArray(length)
            readFully(input, payload, length)
            when (type) {
                TYPE_VIDEO -> listener.onVideoFrame(payload)
                TYPE_AUDIO -> listener.onAudioFrame(payload)
            }
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int) {
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n == -1) throw EOFException("Koneksi ke PC terputus")
            read += n
        }
    }
}
