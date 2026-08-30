package com.potato.livestreamer.relay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

interface RelayStatusListener {
    fun onRelayConnected()
    fun onRelayFailed(reason: String)
    fun onRelayDisconnected()
}

/**
 * Foreground service tipe "dataSync" biasa -- BUKAN mediaProjection, karena
 * service ini tidak pernah capture layar. Yang dilakukan cuma menerima data
 * dari socket TCP (feed dari PC) lalu meneruskannya ke RTMP.
 */
class RelayStreamService : Service() {

    companion object {
        private const val CHANNEL_ID = "potato_relay_channel"
        private const val NOTIF_ID = 502
        private const val HOST = "127.0.0.1"
        private const val STREAM_PORT = 9999
        private const val WAKE_LOCK_TAG = "PotatoMonitorDesk:LiveRelayWakeLock"
    }

    private val binder = LocalBinder()
    private var engine: RtmpRelayEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    var listener: RelayStatusListener? = null

    inner class LocalBinder : Binder() {
        fun getService(): RelayStreamService = this@RelayStreamService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Potato Monitor Desk - Live ke YouTube", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Potato Monitor Desk")
            .setContentText("Meneruskan tampilan PC ke YouTube Live...")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)  // batas aman 6 jam
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    fun startRelay(rtmpUrl: String, width: Int, height: Int, bitrateBps: Int, fps: Int) {
        startForegroundNotification()
        acquireWakeLock()
        val eng = RtmpRelayEngine(HOST, STREAM_PORT, object : RtmpRelayEngine.Listener {
            override fun onRelayConnected() = listener?.onRelayConnected() ?: Unit
            override fun onRelayFailed(reason: String) {
                stopForeground(true)
                releaseWakeLock()
                listener?.onRelayFailed(reason)
            }
            override fun onRelayDisconnected() {
                stopForeground(true)
                releaseWakeLock()
                listener?.onRelayDisconnected()
            }
        })
        engine = eng
        eng.start(rtmpUrl, width, height, bitrateBps, fps)
    }

    fun stopRelay() {
        engine?.stop()
        engine = null
        stopForeground(true)
        releaseWakeLock()
        stopSelf()
    }

    override fun onDestroy() {
        engine?.stop()
        releaseWakeLock()
        super.onDestroy()
    }
}
