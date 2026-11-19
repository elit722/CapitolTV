package fr.capitoltv.client;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceGatheringState;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCRtpEncodingParameters;
import dev.onvoid.webrtc.RTCRtpTransceiverDirection;
import dev.onvoid.webrtc.RTCRtpTransceiverInit;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;
import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoFrameBuffer;
import dev.onvoid.webrtc.media.video.VideoTrack;
import fr.capitoltv.CapitolTV;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Diffusion de l'image du jeu vers Capitol TV (WebRTC, protocole WHIP).
 * L'image est prise juste après le rendu du monde, avant le HUD et les menus :
 * la caméra peut utiliser ses interfaces sans qu'elles apparaissent dans le flux.
 */
public final class Streamer {
    private Streamer() { }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "CapitolTV-video"); t.setDaemon(true); return t; });

    private static PeerConnectionFactory factory;
    private static RTCPeerConnection pc;
    private static CustomVideoSource videoSource;
    private static VideoTrack videoTrack;
    private static AudioTrackSource audioSource;
    private static AudioTrack audioTrack;
    private static String location = "";
    private static volatile String status = "arrêtée";
    private static volatile boolean live, starting;
    private static long lastFrame, retryAt;
    private static final AtomicBoolean busy = new AtomicBoolean();
    private static int frames;
    private static String lastErr = "";

    public static boolean live() { return live; }
    public static String status() { return status; }
    public static int sent() { return frames; }

    /** clé à utiliser : celle collée dans les réglages, sinon celle reçue du site (équipe) */
    public static String key() {
        String k = ClientConfig.get().streamKey;
        return k != null && !k.isBlank() ? k.trim() : Data.streamKey;
    }

    /** chemin d'une capture de contrôle demandée par le test automatique */
    public static volatile java.nio.file.Path testShot;
    public static volatile boolean hooked;

    /** Appelé par le mixin juste après le rendu du monde (avant HUD et menus). */
    public static void afterWorld() {
        hooked = true;
        java.nio.file.Path t = testShot;
        if (t != null) {
            testShot = null;
            capture(img -> { try (img) { img.writeTo(t); } catch (Exception e) { CapitolTV.LOG.warn("Capitol TV : capture de test ratée ({})", e.toString()); } }, null);
        }
        try { onWorldRendered(); } catch (Throwable e) { status = "erreur : " + e; }
    }

    public static void onWorldRendered() {
        ClientConfig c = ClientConfig.get();
        if (!c.streamOn) { if (live || starting) stop("coupée"); return; }
        if (!live && !starting) { if (System.currentTimeMillis() >= retryAt) start(); return; }
        if (!live || videoSource == null) return;
        long now = System.nanoTime(), every = 1_000_000_000L / Math.max(10, Math.min(30, c.streamFps));
        if (now - lastFrame < every || !busy.compareAndSet(false, true)) return;
        lastFrame = now;
        capture(img -> WORK.execute(() -> { try { push(img); } finally { img.close(); busy.set(false); } }), () -> busy.set(false));
    }

    /** copie asynchrone de l'image affichée (le GPU la renvoie quelques images plus tard) */
    public static void capture(Consumer<NativeImage> then, Runnable fail) {
        try {
            ScreenshotRecorder.takeScreenshot(MinecraftClient.getInstance().getFramebuffer(), then);
        } catch (Throwable t) {
            if (fail != null) fail.run();
            CapitolTV.LOG.warn("Capitol TV : capture impossible ({})", t.toString());
        }
    }

    private static void push(NativeImage img) {
        CustomVideoSource src = videoSource;
        if (src == null) return;
        int w = img.getWidth() & ~1, h = img.getHeight() & ~1;
        if (w < 16 || h < 16) return;
        try {
            int[] argb = img.copyPixelsArgb();
            ByteBuffer bb = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.LITTLE_ENDIAN);
            int iw = img.getWidth();
            for (int y = 0; y < h; y++) bb.asIntBuffer().position(y * w).put(argb, y * iw, w);
            NativeI420Buffer buf = NativeI420Buffer.allocate(w, h);
            VideoBufferConverter.convertToI420(bb, buf, FourCC.ARGB);
            int max = ClientConfig.get().streamHeight;
            VideoFrameBuffer out = buf;
            if (max > 0 && h > max) {
                int sh = max & ~1, sw = (int) Math.round(w * (double) sh / h) & ~1;
                out = buf.cropAndScale(0, 0, w, h, sw, sh);
                buf.release();
            }
            VideoFrame f = new VideoFrame(out, System.nanoTime());
            src.pushFrame(f);
            f.release();
            frames++;
        } catch (Throwable t) {
            lastErr = t.toString();
            CapitolTV.LOG.debug("Capitol TV : image non envoyée ({})", lastErr);
        }
    }

    public static synchronized void start() {
        String key = key(), base = ClientConfig.get().url;
        if (key == null || key.isBlank()) { status = "pas de clé de diffusion (Réglages › Diffusion)"; retryAt = System.currentTimeMillis() + 5000; return; }
        starting = true; status = "connexion…";
        WORK.execute(() -> {
            try {
                if (factory == null) factory = newFactory();
                RTCConfiguration cfg = new RTCConfiguration();
                RTCIceServer ice = new RTCIceServer();
                ice.urls = List.of("stun:stun.cloudflare.com:3478");
                cfg.iceServers = List.of(ice);
                CompletableFuture<Void> gathered = new CompletableFuture<>();
                pc = factory.createPeerConnection(cfg, new PeerConnectionObserver() {
                    @Override public void onIceCandidate(RTCIceCandidate candidate) { }
                    @Override public void onIceGatheringChange(RTCIceGatheringState state) { if (state == RTCIceGatheringState.COMPLETE) gathered.complete(null); }
                    @Override public void onConnectionChange(RTCPeerConnectionState state) {
                        if (state == RTCPeerConnectionState.CONNECTED) { live = true; starting = false; status = "en direct vers Capitol TV"; Hud.toast("Diffusion", "Ton image part vers Capitol TV", Hud.GREEN, 4); }
                        else if (state == RTCPeerConnectionState.FAILED || state == RTCPeerConnectionState.DISCONNECTED) { status = "connexion perdue, nouvel essai…"; WORK.execute(() -> stop(null)); retryAt = System.currentTimeMillis() + 4000; }
                    }
                });
                videoSource = new CustomVideoSource();
                videoTrack = factory.createVideoTrack("video", videoSource);
                RTCRtpTransceiverInit vi = new RTCRtpTransceiverInit();
                vi.direction = RTCRtpTransceiverDirection.SEND_ONLY;
                vi.streamIds = List.of("ctv");
                RTCRtpEncodingParameters enc = new RTCRtpEncodingParameters();
                enc.maxBitrate = ClientConfig.get().streamKbps * 1000;
                enc.maxFramerate = (double) ClientConfig.get().streamFps;
                vi.sendEncodings = List.of(enc);
                pc.addTransceiver(videoTrack, vi);
                if (ClientConfig.get().streamMic && !noAudio) {
                    AudioOptions ao = new AudioOptions();
                    ao.echoCancellation = true; ao.noiseSuppression = true; ao.autoGainControl = true; ao.highpassFilter = true;
                    audioSource = factory.createAudioSource(ao);
                    audioTrack = factory.createAudioTrack("audio", audioSource);
                    RTCRtpTransceiverInit ai = new RTCRtpTransceiverInit();
                    ai.direction = RTCRtpTransceiverDirection.SEND_ONLY;
                    ai.streamIds = List.of("ctv");
                    pc.addTransceiver(audioTrack, ai);
                }
                CompletableFuture<RTCSessionDescription> offer = new CompletableFuture<>();
                pc.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
                    @Override public void onSuccess(RTCSessionDescription d) { offer.complete(d); }
                    @Override public void onFailure(String e) { offer.completeExceptionally(new IllegalStateException(e)); }
                });
                RTCSessionDescription o = offer.get(10, TimeUnit.SECONDS);
                waitSet(cb -> pc.setLocalDescription(o, cb));
                try { gathered.get(4, TimeUnit.SECONDS); } catch (Exception ignored) { }
                String sdp = pc.getLocalDescription().sdp;
                HttpRequest req = HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + "/whip/" + key))
                        .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/sdp").header("User-Agent", "CapitolTV-Mod/1.0")
                        .POST(HttpRequest.BodyPublishers.ofString(sdp)).build();
                HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 201) throw new IllegalStateException(r.statusCode() == 404 ? "clé de diffusion inconnue" : r.body());
                location = r.headers().firstValue("Location").orElse("");
                waitSet(cb -> pc.setRemoteDescription(new RTCSessionDescription(RTCSdpType.ANSWER, r.body()), cb));
                status = "connexion au serveur vidéo…";
            } catch (Throwable t) {
                String m = t.getMessage() == null ? t.toString() : t.getMessage();
                status = "erreur : " + m;
                CapitolTV.LOG.warn("Capitol TV : diffusion impossible ({})", m);
                stop(null);
                retryAt = System.currentTimeMillis() + 10000;
            }
        });
    }

    private static boolean noAudio;
    /** usine WebRTC ; sans carte son (ou sans micro), on passe en audio factice */
    private static PeerConnectionFactory newFactory() {
        try { return new PeerConnectionFactory(); }
        catch (Throwable e) {
            noAudio = true;
            CapitolTV.LOG.info("Capitol TV : pas de périphérique audio, diffusion sans micro");
            return new PeerConnectionFactory(new dev.onvoid.webrtc.media.audio.AudioDeviceModule(dev.onvoid.webrtc.media.audio.AudioLayer.kDummyAudio));
        }
    }

    private interface SetCall { void run(SetSessionDescriptionObserver cb); }
    private static void waitSet(SetCall call) throws Exception {
        CompletableFuture<Void> f = new CompletableFuture<>();
        call.run(new SetSessionDescriptionObserver() {
            @Override public void onSuccess() { f.complete(null); }
            @Override public void onFailure(String e) { f.completeExceptionally(new IllegalStateException(e)); }
        });
        f.get(10, TimeUnit.SECONDS);
    }

    public static synchronized void stop(String why) {
        boolean was = live;
        live = false; starting = false;
        String loc = location; location = "";
        if (!loc.isEmpty()) {
            String url = ClientConfig.get().url.replaceAll("/+$", "") + loc;
            HTTP.sendAsync(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).DELETE().build(), HttpResponse.BodyHandlers.discarding());
        }
        try { if (pc != null) pc.close(); } catch (Throwable ignored) { }
        try { if (videoTrack != null) videoTrack.dispose(); } catch (Throwable ignored) { }
        try { if (videoSource != null) videoSource.dispose(); } catch (Throwable ignored) { }
        try { if (audioSource != null) audioSource.dispose(); } catch (Throwable ignored) { }
        pc = null; videoTrack = null; videoSource = null; audioSource = null; audioTrack = null;
        if (why != null) { status = why; if (was) Hud.toast("Diffusion", "Arrêtée", Hud.DIM, 3); }
    }

    /** Vérification au démarrage du test : bibliothèque native et offre WebRTC. */
    public static String selfTest() {
        try {
            PeerConnectionFactory f = newFactory();
            RTCConfiguration cfg = new RTCConfiguration();
            RTCPeerConnection p = f.createPeerConnection(cfg, c -> { });
            CustomVideoSource s = new CustomVideoSource();
            VideoTrack t = f.createVideoTrack("video", s);
            RTCRtpTransceiverInit vi = new RTCRtpTransceiverInit();
            vi.direction = RTCRtpTransceiverDirection.SEND_ONLY;
            p.addTransceiver(t, vi);
            CompletableFuture<RTCSessionDescription> offer = new CompletableFuture<>();
            p.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
                @Override public void onSuccess(RTCSessionDescription d) { offer.complete(d); }
                @Override public void onFailure(String e) { offer.completeExceptionally(new IllegalStateException(e)); }
            });
            String sdp = offer.get(10, TimeUnit.SECONDS).sdp;
            NativeI420Buffer b = NativeI420Buffer.allocate(64, 36);
            ByteBuffer bb = ByteBuffer.allocateDirect(64 * 36 * 4);
            VideoBufferConverter.convertToI420(bb, b, FourCC.ARGB);
            VideoFrame fr = new VideoFrame(b, System.nanoTime());
            s.pushFrame(fr); fr.release();
            String res = "OK (offre " + sdp.length() + " octets, vidéo " + (sdp.contains("m=video") ? "oui" : "non") + ", image envoyée)";
            try { p.close(); } catch (Throwable ignored) { }
            return res;
        } catch (Throwable e) {
            return "ÉCHEC " + e;
        }
    }
}
