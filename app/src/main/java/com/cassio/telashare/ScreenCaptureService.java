package com.cassio.telashare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import org.json.JSONObject;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpParameters;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Captura a tela com WebRTC nativo (ScreenCapturerAndroid + codificador de vídeo por hardware)
 * e envia direto para cada pessoa que entrar com o código.
 */
public class ScreenCaptureService extends Service {

    public static final String ACTION_STOP = "com.cassio.telashare.STOP";

    /** A tela (MainActivity) recebe o status em JSON: {"state":"live","viewers":2} etc. */
    public interface StatusListener { void onStatus(String json); }
    public static volatile StatusListener statusListener;

    private static final String CHANNEL = "captura";

    /** Predefinições de qualidade: lado menor da imagem, fps e taxa de bits. */
    private static final class Quality {
        final int shortSide, fps, kbps;
        Quality(int s, int f, int k) { shortSide = s; fps = f; kbps = k; }
        static Quality of(String name) {
            if ("eco".equals(name)) return new Quality(540, 15, 1200);
            if ("fluido".equals(name)) return new Quality(720, 60, 4500);
            return new Quality(720, 30, 2500); // normal
        }
    }

    private final ExecutorService engine = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private EglBase egl;
    private PeerConnectionFactory factory;
    private ScreenCapturerAndroid capturer;
    private FrameRepeater repeater;
    private SurfaceTextureHelper textureHelper;
    private VideoSource source;
    private VideoTrack track;
    private SignalClient signal;
    private Quality quality;

    private final Map<String, PeerConnection> pcs = new HashMap<>();
    private final Map<String, List<IceCandidate>> pending = new HashMap<>();
    private final Set<String> remoteReady = new HashSet<>();
    private final Set<String> connected = new HashSet<>();
    private boolean stopping = false;

    /** STUN grátis; o TURN da Cloudflare entra em loadTurn() com credenciais temporárias. */
    private final List<PeerConnection.IceServer> ICE = new ArrayList<>(Arrays.asList(
            PeerConnection.IceServer.builder(Arrays.asList("stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302")).createIceServer()
    ));

    /** Busca as credenciais do TURN em https://trc-telas.vercel.app/api/turn (a chave secreta fica no servidor). */
    private void loadTurn() {
        try {
            okhttp3.OkHttpClient http = new okhttp3.OkHttpClient.Builder()
                    .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build();
            okhttp3.Response r = http.newCall(new okhttp3.Request.Builder()
                    .url("https://trc-telas.vercel.app/api/turn").build()).execute();
            if (!r.isSuccessful() || r.body() == null) return;
            org.json.JSONArray servers = new JSONObject(r.body().string()).optJSONArray("iceServers");
            if (servers == null) return;
            for (int i = 0; i < servers.length(); i++) {
                JSONObject sv = servers.getJSONObject(i);
                List<String> urls = new ArrayList<>();
                org.json.JSONArray u = sv.optJSONArray("urls");
                if (u != null) for (int k = 0; k < u.length(); k++) urls.add(u.getString(k));
                else if (sv.has("urls")) urls.add(sv.getString("urls"));
                if (urls.isEmpty()) continue;
                PeerConnection.IceServer.Builder b = PeerConnection.IceServer.builder(urls);
                if (sv.has("username")) b.setUsername(sv.getString("username")).setPassword(sv.optString("credential"));
                ICE.add(b.createIceServer());
            }
        } catch (Exception ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            emit("stopped", 0, null);
            stopSelf();
            return START_NOT_STICKY;
        }
        startAsForeground(); // Android 14+: precisa estar em primeiro plano antes de usar a MediaProjection
        if (intent == null || factory != null) return START_NOT_STICKY;

        final Intent data = intent.getParcelableExtra("data");
        final String hostId = intent.getStringExtra("hostId");
        quality = Quality.of(intent.getStringExtra("quality"));
        engine.execute(() -> setup(data, hostId));
        return START_NOT_STICKY;
    }

    /* ---------------- captura ---------------- */

    private void setup(Intent data, String hostId) {
        try {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                    .builder(getApplicationContext()).createInitializationOptions());
            egl = EglBase.create();
            factory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(), true, true))
                    .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext()))
                    .createPeerConnectionFactory();

            capturer = new ScreenCapturerAndroid(data, new MediaProjection.Callback() {
                @Override public void onStop() {
                    // o usuário parou pelo sistema (barra de notificações, ícone de transmissão etc.)
                    main.post(() -> { emit("stopped", 0, null); stopSelf(); });
                }
            });
            textureHelper = SurfaceTextureHelper.create("captura", egl.getEglBaseContext());
            source = factory.createVideoSource(true);
            repeater = new FrameRepeater(source.getCapturerObserver());
            capturer.initialize(textureHelper, getApplicationContext(), repeater);

            int[] size = captureSize();
            capturer.startCapture(size[0], size[1], quality.fps);
            track = factory.createVideoTrack("tela", source);
        } catch (Exception e) {
            emit("error", 0, "Não foi possível capturar a tela neste aparelho.");
            main.post(this::stopSelf);
            return;
        }

        loadTurn();

        signal = new SignalClient(new SignalClient.Listener() {
            @Override public void onOpen() { engine.execute(() -> emitLive()); }
            @Override public void onIdTaken() { emit("id-taken", 0, null); main.post(ScreenCaptureService.this::stopSelf); }
            @Override public void onFailure(String reason) { emit("error", 0, reason); }
            @Override public void onMessage(String type, String src, JSONObject p) {
                engine.execute(() -> handleMessage(type, src, p));
            }
        });
        signal.connect(hostId);
    }

    /** Mantém a proporção da tela do aparelho, com o lado menor limitado pela qualidade escolhida. */
    private int[] captureSize() {
        DisplayMetrics m = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(m);
        int w = m.widthPixels, h = m.heightPixels;
        int small = Math.min(w, h);
        if (small > quality.shortSide) {
            double k = quality.shortSide / (double) small;
            w = (int) Math.round(w * k);
            h = (int) Math.round(h * k);
        }
        return new int[]{w - (w % 16), h - (h % 16)}; // múltiplos de 16: alguns decodificadores (iPhone) exigem
    }

    /* ---------------- sinalização ---------------- */

    private void handleMessage(String type, String src, JSONObject p) {
        if (stopping) return;
        String t = p.optString("t");
        switch (type) {
            case "OFFER":
                if ("join".equals(t)) addViewer(src);
                break;
            case "ANSWER":
                if ("sdp".equals(t)) setAnswer(src, p.optString("sdp"));
                break;
            case "CANDIDATE":
                JSONObject c = p.optJSONObject("c");
                if (c != null) addCandidate(src, new IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.optString("candidate")));
                break;
            case "LEAVE":
            case "EXPIRE":
                removeViewer(src);
                break;
        }
    }

    private void addViewer(final String vid) {
        if (pcs.containsKey(vid) || track == null) return;
        PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(ICE);
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        cfg.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;

        PeerConnection pc = factory.createPeerConnection(cfg, new PeerConnection.Observer() {
            @Override public void onIceCandidate(IceCandidate c) {
                try {
                    JSONObject j = new JSONObject();
                    j.put("candidate", c.sdp);
                    j.put("sdpMid", c.sdpMid);
                    j.put("sdpMLineIndex", c.sdpMLineIndex);
                    signal.send("CANDIDATE", vid, new JSONObject().put("t", "ice").put("c", j));
                } catch (Exception ignored) { }
            }
            @Override public void onConnectionChange(PeerConnection.PeerConnectionState s) {
                engine.execute(() -> {
                    if (s == PeerConnection.PeerConnectionState.CONNECTED) { connected.add(vid); emitLive(); }
                    else if (s == PeerConnection.PeerConnectionState.FAILED || s == PeerConnection.PeerConnectionState.CLOSED) removeViewer(vid);
                });
            }
            @Override public void onSignalingChange(PeerConnection.SignalingState s) { }
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) { }
            @Override public void onIceConnectionReceivingChange(boolean b) { }
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) { }
            @Override public void onIceCandidatesRemoved(IceCandidate[] c) { }
            @Override public void onAddStream(MediaStream s) { }
            @Override public void onRemoveStream(MediaStream s) { }
            @Override public void onDataChannel(org.webrtc.DataChannel d) { }
            @Override public void onRenegotiationNeeded() { }
            @Override public void onAddTrack(RtpReceiver r, MediaStream[] s) { }
        });
        if (pc == null) return;
        pcs.put(vid, pc);
        pending.put(vid, new ArrayList<>());

        RtpSender sender = pc.addTrack(track, Collections.singletonList("tela"));
        tune(sender);

        pc.createOffer(new SdpAdapter() {
            @Override public void onCreateSuccess(final SessionDescription offer) {
                pc.setLocalDescription(new SdpAdapter() {
                    @Override public void onSetSuccess() {
                        try {
                            signal.send("OFFER", vid, new JSONObject().put("t", "sdp").put("sdp", offer.description));
                        } catch (Exception ignored) { }
                    }
                }, offer);
            }
        }, new MediaConstraints());
    }

    /** Limita fps e taxa de bits conforme a qualidade; no modo Fluido, prioriza fps. */
    private void tune(RtpSender sender) {
        if (sender == null) return;
        try {
            RtpParameters params = sender.getParameters();
            for (RtpParameters.Encoding e : params.encodings) {
                e.maxFramerate = quality.fps;
                e.maxBitrateBps = quality.kbps * 1000;
            }
            params.degradationPreference = quality.fps >= 60
                    ? RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
                    : RtpParameters.DegradationPreference.BALANCED;
            sender.setParameters(params);
        } catch (Exception ignored) { }
    }

    private void setAnswer(final String vid, String sdp) {
        final PeerConnection pc = pcs.get(vid);
        if (pc == null) return;
        pc.setRemoteDescription(new SdpAdapter() {
            @Override public void onSetSuccess() {
                engine.execute(() -> {
                    remoteReady.add(vid);
                    List<IceCandidate> list = pending.remove(vid);
                    if (list != null) for (IceCandidate c : list) pc.addIceCandidate(c);
                });
            }
        }, new SessionDescription(SessionDescription.Type.ANSWER, sdp));
    }

    private void addCandidate(String vid, IceCandidate c) {
        PeerConnection pc = pcs.get(vid);
        if (pc == null) return;
        if (remoteReady.contains(vid)) pc.addIceCandidate(c);
        else { List<IceCandidate> l = pending.get(vid); if (l != null) l.add(c); }
    }

    private void removeViewer(String vid) {
        PeerConnection pc = pcs.remove(vid);
        pending.remove(vid);
        remoteReady.remove(vid);
        connected.remove(vid);
        if (pc != null) pc.dispose();
        emitLive();
    }

    /* ---------------- status para a tela ---------------- */

    private void emitLive() { emit("live", connected.size(), null); }

    private static void emit(String state, int viewers, String msg) {
        StatusListener l = statusListener;
        if (l == null) return;
        try {
            JSONObject j = new JSONObject().put("state", state).put("viewers", viewers);
            if (msg != null) j.put("msg", msg);
            l.onStatus(j.toString());
        } catch (Exception ignored) { }
    }

    /* ---------------- notificação ---------------- */

    private void startAsForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Compartilhamento de tela",
                    NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ScreenCaptureService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification n = b.setContentTitle("TRC TELAS")
                .setContentText("Sua tela está sendo compartilhada")
                .setSmallIcon(R.drawable.ic_cast)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Parar", stop).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }
    }

    /* ---------------- encerramento ---------------- */

    @Override
    public void onDestroy() {
        stopping = true;
        engine.execute(() -> {
            try {
                for (String vid : new ArrayList<>(pcs.keySet())) {
                    if (signal != null) signal.send("LEAVE", vid, new JSONObject().put("t", "bye"));
                    PeerConnection pc = pcs.remove(vid);
                    if (pc != null) pc.dispose();
                }
                if (signal != null) signal.close();
                if (capturer != null) { try { capturer.stopCapture(); } catch (Exception ignored) { } capturer.dispose(); }
                if (repeater != null) repeater.dispose();
                if (track != null) track.dispose();
                if (source != null) source.dispose();
                if (textureHelper != null) textureHelper.dispose();
                if (factory != null) factory.dispose();
                if (egl != null) egl.release();
            } catch (Exception ignored) { }
        });
        engine.shutdown();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    /** SdpObserver com métodos vazios, para sobrescrever só o necessário. */
    private static class SdpAdapter implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription s) { }
        @Override public void onSetSuccess() { }
        @Override public void onCreateFailure(String e) { }
        @Override public void onSetFailure(String e) { }
    }

    /**
     * A captura de tela do Android só gera quadros quando algo muda na tela. Com a tela parada,
     * quem entra depois não recebe nenhum quadro e vê tela preta. Aqui guardamos uma cópia do
     * último quadro e reenviamos a cada 250 ms enquanto a tela estiver parada.
     */
    private static final class FrameRepeater implements org.webrtc.CapturerObserver {
        private final org.webrtc.CapturerObserver out;
        private final java.util.concurrent.ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        private org.webrtc.VideoFrame.I420Buffer copy;
        private int rotation;
        private volatile long lastFrameNs;

        FrameRepeater(org.webrtc.CapturerObserver out) {
            this.out = out;
            timer.scheduleAtFixedRate(this::tick, 500, 250, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        @Override public void onCapturerStarted(boolean ok) { out.onCapturerStarted(ok); }
        @Override public void onCapturerStopped() { out.onCapturerStopped(); }

        @Override public void onFrameCaptured(org.webrtc.VideoFrame frame) {
            lastFrameNs = System.nanoTime();
            try {
                org.webrtc.VideoFrame.I420Buffer c = frame.getBuffer().toI420();
                synchronized (this) {
                    if (copy != null) copy.release();
                    copy = c;
                    rotation = frame.getRotation();
                }
            } catch (Exception ignored) { }
            out.onFrameCaptured(frame);
        }

        private void tick() {
            if (System.nanoTime() - lastFrameNs < 300_000_000L) return;
            org.webrtc.VideoFrame.I420Buffer c;
            int r;
            synchronized (this) {
                if (copy == null) return;
                copy.retain();
                c = copy;
                r = rotation;
            }
            org.webrtc.VideoFrame vf = new org.webrtc.VideoFrame(c, r, System.nanoTime());
            try { out.onFrameCaptured(vf); } catch (Exception ignored) { }
            vf.release();
        }

        void dispose() {
            timer.shutdownNow();
            synchronized (this) { if (copy != null) { copy.release(); copy = null; } }
        }
    }
}
