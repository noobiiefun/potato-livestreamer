package com.potato.livestreamer.relay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpDisplay

/**
 * Callback status live streaming ke MainActivity.
 */
interface LiveStatusListener {
    fun onLiveConnected()
    fun onLiveFailed(reason: String)
    fun onLiveDisconnected()
}

/**
 * Foreground service (wajib bertipe mediaProjection sesuai kebijakan Android
 * 10+/14+) yang memegang instance RtmpDisplay dari library RootEncoder.
 */
class LiveStreamService : Service(), ConnectChecker {

    companion object {
        private const val CHANNEL_ID = "potato_live_channel"
        private const val NOTIF_ID = 501
    }

    private val binder = LocalBinder()
    private var rtmpDisplay: RtmpDisplay? = null
    var listener: LiveStatusListener? = null

    inner class LocalBinder : Binder() {
        fun getService(): LiveStreamService = this@LiveStreamService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        rtmpDisplay = RtmpDisplay(applicationContext, true, this)
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Potato Monitor Desk - Live", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Potato Monitor Desk")
            .setContentText("Sedang live streaming...")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    fun startLive(resultCode: Int, data: Intent, rtmpUrl: String, useInternalAudio: Boolean): Boolean {
        startForegroundNotification()
        val display = rtmpDisplay ?: return false
        display.setIntentResult(resultCode, data)

        val videoOk = display.prepareVideo()
        val audioOk = if (useInternalAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            display.prepareInternalAudio()
        } else {
            display.prepareAudio()
        }

        return if (videoOk && audioOk) {
            display.startStream(rtmpUrl)
            true
        } else {
            stopForeground(true)
            false
        }
    }

    fun stopLive() {
        rtmpDisplay?.let { if (it.isStreaming) it.stopStream() }
        stopForeground(true)
        stopSelf()
    }

    fun isLive(): Boolean = rtmpDisplay?.isStreaming == true

    override fun onDestroy() {
        rtmpDisplay?.let { if (it.isStreaming) it.stopStream() }
        super.onDestroy()
    }

    // ---------- ConnectChecker ----------
    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() {
        listener?.onLiveConnected()
    }
    override fun onConnectionFailed(reason: String) {
        listener?.onLiveFailed(reason)
    }
    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() {
        listener?.onLiveDisconnected()
    }
    override fun onAuthError() {
        listener?.onLiveFailed("Autentikasi RTMP gagal (cek stream key)")
    }
    override fun onAuthSuccess() {}
}
