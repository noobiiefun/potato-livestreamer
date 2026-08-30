package com.potato.livestreamer.relay

/**
 * Penampung 1 slot untuk frame terbaru.
 */
class LatestFrameSlot {
    private val lock = Object()
    private var pending: ByteArray? = null

    fun put(data: ByteArray) {
        synchronized(lock) {
            pending = data
            lock.notifyAll()
        }
    }

    fun take(): ByteArray? {
        synchronized(lock) {
            try {
                while (pending == null) lock.wait()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            val data = pending!!
            pending = null
            return data
        }
    }

    fun wakeUp() {
        synchronized(lock) { lock.notifyAll() }
    }
}
