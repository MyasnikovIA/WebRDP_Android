package ru.miacomsoft.webrdp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class ScreenStreamService extends Service {

    private static final String TAG = "ScreenStreamService";
    public static final String ACTION_START       = "ru.miacomsoft.webrdp.START";
    public static final String ACTION_STOP        = "ru.miacomsoft.webrdp.STOP";
    public static final String ACTION_RESTART     = "ru.miacomsoft.webrdp.RESTART";
    public static final String ACTION_SHARE_FILE  = "ru.miacomsoft.webrdp.SHARE_FILE";
    public static final String EXTRA_RESULT_CODE  = "rc";
    public static final String EXTRA_RESULT_DATA  = "rd";
    private static final String CHANNEL_ID = "webrdp_svc";
    private static final int NOTIF_ID = 0x5B;

    private SettingsManager settings;
    private SignalingServer httpServer;
    private StunServer stunServer;
    private MediaProjection mediaProjection;

    private PeerConnectionFactory pcFactory;
    private EglBase eglBase;
    private VideoCapturer capturer;
    private SurfaceTextureHelper surfaceHelper;
    private VideoSource videoSource;
    private VideoTrack videoTrack;

    public class ClientSession {
        public String id;
        public PeerConnection pc;
        public DataChannel dataChannel;
        public List<IceCandidate> serverIce = new CopyOnWriteArrayList<>();
        public ClientSession(String id) { this.id = id; }
    }

    private final Map<String, ClientSession> clients = new ConcurrentHashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new SettingsManager(this);
        createChannel();
        initWebRtcFactory();
        ScreenStreamServiceHolder.set(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopAll();
            stopSelf();
            return START_NOT_STICKY;
        }

        boolean hasProjection = intent != null && intent.hasExtra(EXTRA_RESULT_DATA);

        // ВАЖНО: Если проекция уже запущена (videoTrack != null), мы обязаны
        // передавать тип MEDIA_PROJECTION в startForeground, иначе Android
        // остановит трансляцию экрана!
        int fgType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        if ((hasProjection || videoTrack != null) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            fgType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        }

        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, fgType);
        } else {
            startForeground(NOTIF_ID, n);
        }

        if (ACTION_RESTART.equals(action)) {
            stopHttpServer();
            startHttpServer();
            return START_STICKY;
        }

        if (ACTION_START.equals(action) && hasProjection) {
            int rc = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent rd = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            startProjection(rc, rd);
        }

        if (ACTION_SHARE_FILE.equals(action)) {
            String filename = intent.getStringExtra("filename");
            if (filename != null) broadcastDownload(filename);
            return START_STICKY;
        }

        if (httpServer == null) startHttpServer();
        return START_STICKY;
    }

    public void broadcastDownload(String filename) {
        try {
            String encodedName = java.net.URLEncoder.encode(filename, "UTF-8");
            String json = "{\"t\":\"download\",\"filename\":\"" + filename + "\",\"url\":\"/api/download?name=" + encodedName + "\"}";
            org.webrtc.DataChannel.Buffer buffer = new org.webrtc.DataChannel.Buffer(
                    java.nio.ByteBuffer.wrap(json.getBytes("UTF-8")), false
            );
            for (ClientSession session : clients.values()) {
                if (session.dataChannel != null && session.dataChannel.state() == DataChannel.State.OPEN) {
                    session.dataChannel.send(buffer);
                }
            }
            Log.i(TAG, "Broadcasted download for: " + filename);
        } catch (Exception e) { Log.e(TAG, "broadcast error", e); }
    }

    private void startProjection(int resultCode, Intent data) {
        if (videoTrack != null) {
            Log.w(TAG, "Projection already running, ignoring start request.");
            return;
        }

        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mediaProjection = mpm.getMediaProjection(resultCode, data);
            if (mediaProjection == null) { Log.e(TAG, "mediaProjection == null"); return; }
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { Log.w(TAG, "MediaProjection stopped"); }
            }, null);

            capturer = new ScreenCapturerAndroid(data, new MediaProjection.Callback() {
                @Override public void onStop() { Log.w(TAG, "ScreenCapturer stopped"); }
            });
            surfaceHelper = SurfaceTextureHelper.create("ScreenCapture", eglBase.getEglBaseContext());
            videoSource = pcFactory.createVideoSource(capturer.isScreencast());
            capturer.initialize(surfaceHelper, this, videoSource.getCapturerObserver());
            capturer.startCapture(1280, 720, 30);

            videoTrack = pcFactory.createVideoTrack("screen0", videoSource);
            Log.i(TAG, "Projection started");
        } catch (Exception e) {
            Log.e(TAG, "startProjection failed", e);
        }
    }

    private void initWebRtcFactory() {
        PeerConnectionFactory.InitializationOptions init = PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(false).createInitializationOptions();
        PeerConnectionFactory.initialize(init);
        eglBase = EglBase.create();
        pcFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();
    }

    private PeerConnection createPeerConnectionForClient(final String clientId) {
        List<PeerConnection.IceServer> ice = new ArrayList<>();
        PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(ice);
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        cfg.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;

        PeerConnection pc = pcFactory.createPeerConnection(cfg, new PeerConnection.Observer() {
            @Override public void onSignalingChange(PeerConnection.SignalingState s) {}
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                Log.d(TAG, "Client " + clientId + " ICE state: " + s);
                if (s == PeerConnection.IceConnectionState.FAILED || s == PeerConnection.IceConnectionState.CLOSED) {
                    removeClient(clientId);
                }
            }
            @Override public void onIceConnectionReceivingChange(boolean b) {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}
            @Override public void onIceCandidate(IceCandidate c) {
                ClientSession session = clients.get(clientId);
                if (c != null && session != null) session.serverIce.add(c);
            }
            @Override public void onIceCandidatesRemoved(IceCandidate[] c) {}
            @Override public void onAddStream(MediaStream s) {}
            @Override public void onRemoveStream(MediaStream s) {}
            @Override public void onDataChannel(DataChannel dc) {
                ClientSession session = clients.get(clientId);
                if (session != null) session.dataChannel = dc;

                dc.registerObserver(new DataChannel.Observer() {
                    @Override public void onBufferedAmountChange(long l) {}
                    @Override public void onStateChange() {}
                    @Override public void onMessage(DataChannel.Buffer buffer) {
                        try {
                            ByteBuffer data = buffer.data;
                            byte[] bytes = new byte[data.remaining()];
                            data.get(bytes);
                            RemoteControlAccessibilityService.handleMessage(new String(bytes, "UTF-8"));
                        } catch (Exception e) {
                            Log.e(TAG, "DataChannel msg error", e);
                        }
                    }
                });
            }
            @Override public void onRenegotiationNeeded() {}
            @Override public void onAddTrack(RtpReceiver r, MediaStream[] streams) {}
        });

        if (pc != null && videoTrack != null) {
            pc.addTrack(videoTrack, Collections.singletonList("screen"));
        }
        return pc;
    }

    public String handleOffer(String offerSdp, String clientId) throws Exception {
        final long TIMEOUT_MS = 5000;

        if (videoTrack == null) {
            throw new IllegalStateException("Projection not started. Press 'Start' on the device.");
        }

        removeClient(clientId);

        final PeerConnection pc = createPeerConnectionForClient(clientId);
        if (pc == null) throw new IllegalStateException("Failed to create PeerConnection");

        ClientSession session = new ClientSession(clientId);
        session.pc = pc;
        clients.put(clientId, session);

        final java.util.concurrent.CountDownLatch remoteLatch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<String> remoteErr =
                new java.util.concurrent.atomic.AtomicReference<>();

        pc.setRemoteDescription(new org.webrtc.SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription s) {}
            @Override public void onSetSuccess() { remoteLatch.countDown(); }
            @Override public void onCreateFailure(String s) { remoteErr.set(s); remoteLatch.countDown(); }
            @Override public void onSetFailure(String s)     { remoteErr.set(s); remoteLatch.countDown(); }
        }, new SessionDescription(SessionDescription.Type.OFFER, offerSdp));

        if (!remoteLatch.await(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            removeClient(clientId);
            throw new IllegalStateException("setRemoteDescription timeout");
        }
        if (remoteErr.get() != null) {
            removeClient(clientId);
            throw new IllegalStateException("setRemoteDescription failed: " + remoteErr.get());
        }

        final java.util.concurrent.CountDownLatch answerLatch = new java.util.concurrent.CountDownLatch(1);
        final SessionDescription[] answerHolder = new SessionDescription[1];
        final java.util.concurrent.atomic.AtomicReference<String> answerErr =
                new java.util.concurrent.atomic.AtomicReference<>();

        pc.createAnswer(new org.webrtc.SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription sdp) {
                answerHolder[0] = sdp;
                pc.setLocalDescription(new org.webrtc.SdpObserver() {
                    @Override public void onCreateSuccess(SessionDescription s) {}
                    @Override public void onSetSuccess() { answerLatch.countDown(); }
                    @Override public void onCreateFailure(String s) { answerErr.set(s); answerLatch.countDown(); }
                    @Override public void onSetFailure(String s)     { answerErr.set(s); answerLatch.countDown(); }
                }, sdp);
            }
            @Override public void onSetSuccess() {}
            @Override public void onCreateFailure(String s) { answerErr.set(s); answerLatch.countDown(); }
            @Override public void onSetFailure(String s)     { answerErr.set(s); answerLatch.countDown(); }
        }, new org.webrtc.MediaConstraints());

        if (!answerLatch.await(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            removeClient(clientId);
            throw new IllegalStateException("createAnswer timeout");
        }
        if (answerErr.get() != null) {
            removeClient(clientId);
            throw new IllegalStateException("createAnswer failed: " + answerErr.get());
        }
        if (answerHolder[0] == null) {
            removeClient(clientId);
            throw new IllegalStateException("answer SDP is null");
        }

        try { Thread.sleep(500); } catch (InterruptedException ignored) {}

        Log.i(TAG, "Client " + clientId + " connected. Total clients: " + clients.size());
        return answerHolder[0].description;
    }

    public void addClientIce(String clientId, String sdpMid, int sdpMLineIndex, String candidate) {
        ClientSession session = clients.get(clientId);
        if (session != null && session.pc != null) {
            session.pc.addIceCandidate(new IceCandidate(sdpMid, sdpMLineIndex, candidate));
        }
    }

    public List<IceCandidate> drainServerIce(String clientId) {
        ClientSession session = clients.get(clientId);
        if (session != null) {
            List<IceCandidate> copy = new ArrayList<>(session.serverIce);
            session.serverIce.clear();
            return copy;
        }
        return new ArrayList<>();
    }

    public void removeClient(String clientId) {
        ClientSession session = clients.remove(clientId);
        if (session != null && session.pc != null) {
            try { session.pc.close(); } catch (Exception ignored) {}
            Log.i(TAG, "Client " + clientId + " disconnected. Total clients: " + clients.size());
        }
    }

    public void resetAllConnections() {
        for (String id : new ArrayList<>(clients.keySet())) {
            removeClient(id);
        }
    }

    private void startHttpServer() {
        startStunServer();
        try {
            httpServer = new SignalingServer(this, settings);
            httpServer.start();
            Log.i(TAG, "HTTP server started on " + settings.getHttpPort());
        } catch (Exception e) {
            Log.e(TAG, "HTTP start failed", e);
        }
    }

    private void stopHttpServer() {
        stopStunServer();
        if (httpServer != null) { httpServer.stop(); httpServer = null; }
    }

    private void startStunServer() {
        try {
            stunServer = new StunServer(settings.getStunPort());
            stunServer.startServer();
        } catch (Exception e) {
            Log.e(TAG, "STUN start failed", e);
        }
    }

    private void stopStunServer() {
        if (stunServer != null) {
            stunServer.stopServer();
            stunServer = null;
        }
    }

    private void stopAll() {
        stopHttpServer();
        resetAllConnections();
        try { if (capturer != null) capturer.stopCapture(); } catch (Exception ignored) {}
        try { if (capturer != null) capturer.dispose(); } catch (Exception ignored) {}
        if (videoTrack != null) videoTrack.dispose();
        if (videoSource != null) videoSource.dispose();
        if (mediaProjection != null) mediaProjection.stop();
        if (surfaceHelper != null) surfaceHelper.dispose();
        try { stopForeground(true); } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        stopAll();
        if (pcFactory != null) { pcFactory.dispose(); pcFactory = null; }
        if (eglBase != null) { eglBase.release(); eglBase = null; }
        ScreenStreamServiceHolder.set(null);
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.svc_channel_name), NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(getString(R.string.svc_channel_desc));
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.svc_notification_title))
                .setContentText(getString(R.string.svc_notification_text))
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true)
                .build();
    }
}