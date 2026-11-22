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
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "CapitolTV-video"); t.setDaemon(true); t.setPriority(Thread.NORM_PRIORITY - 2); return t; });

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
    private static volatile int maxH = 720, maxFps = 30;

    public static boolean live() { return live; }
    public static String status() { return status; }
    public static int sent() { return frames; }

    /** clé à utiliser : celle collée dans les réglages, sinon celle reçue du site (équipe) */
    public static String key() {
        String k = ClientConfig.get().streamKey;
        if (k != null && !k.isBlank()) return k.trim();
        if (!Data.serverKey.isEmpty()) return Data.serverKey;
        return Data.streamKey;
    }
    /** adresse du site : celle donnée par le serveur Minecraft si la clé vient de lui */
    static String base() {
        String k = ClientConfig.get().streamKey;
        if ((k == null || k.isBlank()) && !Data.serverKey.isEmpty() && !Data.serverUrl.isBlank()) return Data.serverUrl;
        return ClientConfig.get().url;
    }

    /** chemin d'une capture de contrôle demandée par le test automatique */
    public static volatile java.nio.file.Path testShot, testFast;
    public static volatile boolean hooked;

    /** Appelé par le mixin juste après le rendu du monde (avant HUD et menus). */
    public static void afterWorld() {
        hooked = true;
        java.nio.file.Path t = testShot;
        if (t != null) {
            testShot = null;
            capture(img -> { try (img) { img.writeTo(t); } catch (Exception e) { CapitolTV.LOG.warn("Capitol TV : capture de test ratée ({})", e.toString()); } }, null);
        }
        java.nio.file.Path tf = testFast;
        if (tf != null) {
            testFast = null;
            Capture.grab(MinecraftClient.getInstance().getFramebuffer(), 720, (buf, w, h) -> {
                try (NativeImage img = new NativeImage(w, h, false)) {
                    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) { int i = (y * w + x) * 4; img.setColor(x, y, 0xFF000000 | (buf.get(i + 2) & 255) << 16 | (buf.get(i + 1) & 255) << 8 | (buf.get(i) & 255)); }
                    img.writeTo(tf);
                } catch (Exception e) { CapitolTV.LOG.warn("Capitol TV : test capture rapide raté ({})", e.toString()); }
                Capture.recycle(buf);
            });
        }
        try { onWorldRendered(); } catch (Throwable e) { status = "erreur : " + e; }
    }

    public static void onWorldRendered() {
        ClientConfig c = ClientConfig.get();
        if (!c.streamOn || "off".equals(c.streamLevel)) { if (live || starting) stop("coupée"); return; }
        if (!live) baseline();
        if (!live && !starting) { if (System.currentTimeMillis() >= retryAt) start(); return; }
        if (!live || videoSource == null) return;
        adapt();
        long now = System.nanoTime(), every = 1_000_000_000L / Math.max(10, curFps);
        if (now - lastFrame < every || !busy.compareAndSet(false, true)) return;
        lastFrame = now;
        if (!Capture.broken()) {
            // capture rapide : réduction et copie par la carte graphique, conversion hors du fil du jeu
            boolean ok = Capture.grab(MinecraftClient.getInstance().getFramebuffer(), curH, (buf, w, h) -> WORK.execute(() -> { try { pushRgba(buf, w, h); } finally { Capture.recycle(buf); busy.set(false); } }));
            if (!ok) busy.set(false);
            return;
        }
        capture(img -> WORK.execute(() -> { try { push(img); } finally { img.close(); busy.set(false); } }), () -> busy.set(false));
    }

    /* ---------- anti-lag : si le jeu ralentit, on envoie moins (puis on remonte) ---------- */
    private static volatile int curH = 720, curFps = 30;
    private static long adaptAt;
    private static int lowSec, highSec;
    private static final int[][] STEPS = {{1080, 60}, {1080, 30}, {720, 30}, {720, 24}, {540, 24}, {480, 20}, {360, 20}};
    private static int step;

    /** niveaux possibles sous le maximum choisi par le joueur (et autorisé par la régie) */
    private static int firstStep() {
        for (int i = 0; i < STEPS.length; i++) if (STEPS[i][0] <= maxH && STEPS[i][1] <= maxFps) return i;
        return STEPS.length - 1;
    }

    private static void applyStep() {
        int i = Math.max(firstStep(), Math.min(STEPS.length - 1, step));
        step = i;
        curH = Math.min(maxH, STEPS[i][0]); curFps = Math.min(maxFps, STEPS[i][1]);
    }

    private static void adapt() {
        long now = System.currentTimeMillis();
        if (now - adaptAt < 1000) return;
        adaptAt = now;
        // jeu en arrière-plan (autre appli, menu Échap) : Minecraft se bride lui-même à quelques images/s,
        // ce n'est pas du lag → on ne baisse rien et on ne prévient personne
        if (inactive()) { lowSec = 0; highSec = 0; badSec = 0; return; }
        // anti-lag toujours actif : la qualité suit les performances du joueur
        // comparé aux images/s du joueur sans diffusion (un petit PC n'est pas « en retard » à 35 i/s)
        int fps = MinecraftClient.getInstance().getCurrentFps();
        watchImpact(fps);
        double low = Math.max(20, baseFps * 0.8), high = baseFps * 0.93;
        if (fps < low) { lowSec++; highSec = 0; } else if (fps >= high) { highSec++; lowSec = 0; } else { lowSec = 0; highSec = 0; }
        if (lowSec >= 4 && step < STEPS.length - 1) { step++; lowSec = 0; applyStep(); status = "anti-lag : " + curH + "p " + curFps + " i/s"; }
        else if (highSec >= 25 && step > firstStep()) { step--; highSec = 0; applyStep(); status = "en direct · " + curH + "p " + curFps + " i/s"; }
    }

    private static long activeSince;
    /** fenêtre pas au premier plan, réduite ou jeu en pause : les images/s ne veulent rien dire (+ 5 s pour repartir) */
    static boolean inactive() {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean bg = !mc.isWindowFocused() || mc.isPaused() || mc.currentScreen instanceof net.minecraft.client.gui.screen.GameMenuScreen
                || org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(mc.getWindow().getHandle(), org.lwjgl.glfw.GLFW.GLFW_ICONIFIED) != 0;
        long now = System.currentTimeMillis();
        if (bg) { activeSince = now; return true; }
        return now - activeSince < 5000;
    }

    private static double baseFps = 60;
    private static long baseAt;
    private static void baseline() {
        long now = System.currentTimeMillis();
        if (now - baseAt < 1000) return;
        baseAt = now;
        if (inactive()) return;
        int f = MinecraftClient.getInstance().getCurrentFps();
        if (f > 0) baseFps = baseFps * 0.75 + f * 0.25;
    }

    /** qualité réellement envoyée en ce moment (après anti-lag) */
    public static String sending() { return curH + "p · " + curFps + " i/s"; }

    private static void pushRgba(java.nio.ByteBuffer rgba, int w, int h) {
        CustomVideoSource src = videoSource;
        if (src == null) return;
        try {
            NativeI420Buffer buf = NativeI420Buffer.allocate(w, h);
            VideoBufferConverter.convertToI420(rgba, buf, FourCC.ABGR);
            VideoFrame f = new VideoFrame(buf, System.nanoTime());
            src.pushFrame(f);
            f.release();
            frames++;
        } catch (Throwable t) {
            lastErr = t.toString();
            CapitolTV.LOG.debug("Capitol TV : image non envoyée ({})", lastErr);
        }
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
            int max = curH;
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
        String key = key(), base = base();
        if (key == null || key.isBlank()) { status = "en attente de la clé (serveur de l'event)"; retryAt = System.currentTimeMillis() + 5000; return; }
        starting = true; status = "connexion…";
        WORK.execute(() -> {
            try {
                factory();
                // qualité : toujours le maximum autorisé par la régie (l'anti-lag baisse ensuite si besoin)
                int vh = 720, vk = 4500, vf = 30;
                try {
                    HttpResponse<String> q = HTTP.send(HttpRequest.newBuilder(URI.create(qualityUrl(base, key))).timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
                    if (q.statusCode() == 200) {
                        com.google.gson.JsonObject j = com.google.gson.JsonParser.parseString(q.body()).getAsJsonObject();
                        if (j.has("moved")) { moveTo(j.get("moved").getAsString()); throw new IllegalStateException("le site a déménagé, reconnexion…"); }
                        readQuality(j);
                        if (Quality.capOff || Quality.blocked) throw new Paused(Quality.capOff ? "capture coupée par la régie" : "POV coupée par la régie");
                        if (j.has("h") && j.get("h").getAsInt() > 0) { vh = j.get("h").getAsInt(); vk = j.get("kbps").getAsInt(); vf = j.get("fps").getAsInt(); }
                    } else if (q.statusCode() == 404) throw new IllegalStateException("clé de diffusion inconnue");
                } catch (IllegalStateException | Paused e) { throw e; } catch (Exception ignored) { }
                maxH = vh; maxFps = vf; step = firstStep(); applyStep();
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
                vi.sendEncodings = encodings(vh, vk, vf);
                pc.addTransceiver(videoTrack, vi);
                if (GameAudio.active()) {
                    // son du jeu (capturé par OpenAL) envoyé avec l'image
                    gameSource = new dev.onvoid.webrtc.media.audio.CustomAudioSource();
                    audioTrack = factory.createAudioTrack("audio", gameSource);
                    RTCRtpTransceiverInit ai = new RTCRtpTransceiverInit();
                    ai.direction = RTCRtpTransceiverDirection.SEND_ONLY;
                    ai.streamIds = List.of("ctv");
                    pc.addTransceiver(audioTrack, ai);
                    final dev.onvoid.webrtc.media.audio.CustomAudioSource gs = gameSource;
                    GameAudio.sink = (pcm, frames) -> { if (live) try { gs.pushAudio(pcm, 16, GameAudio.RATE, 2, frames); } catch (Throwable ignored) { } };
                } else if (ClientConfig.get().streamMic && !noAudio) {
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
            } catch (Paused p) {
                status = p.getMessage();
                stop(null);
                retryAt = System.currentTimeMillis() + 30000;
                return;
            } catch (Throwable t) {
                String m = t.getMessage() == null ? t.toString() : t.getMessage();
                status = "erreur : " + m;
                CapitolTV.LOG.warn("Capitol TV : diffusion impossible ({})", m);
                stop(null);
                retryAt = System.currentTimeMillis() + 10000;
            }
        });
    }

    /** couches envoyées : pleine qualité (f) + version légère 360p (q) pour les vignettes de la régie */
    static List<RTCRtpEncodingParameters> encodings(int h, int kbps, int fps) {
        RTCRtpEncodingParameters f = new RTCRtpEncodingParameters();
        f.maxBitrate = kbps * 1000;
        f.maxFramerate = (double) fps;
        if (h < 720) return List.of(f);
        f.rid = "f"; f.scaleResolutionDownBy = 1.0;
        RTCRtpEncodingParameters q = new RTCRtpEncodingParameters();
        q.rid = "q"; q.maxBitrate = 600_000; q.maxFramerate = (double) Math.min(fps, 30); q.scaleResolutionDownBy = h / 360.0;
        return List.of(f, q);
    }

    private static String qualityUrl(String base, String key) {
        String lv = Quality.chosen();
        return base.replaceAll("/+$", "") + "/whip/" + key + (lv.isEmpty() ? "" : "?lvl=" + lv);
    }

    private static void readQuality(com.google.gson.JsonObject j) {
        if (j.has("level")) Quality.applied = j.get("level").getAsString();
        if (j.has("cap")) Quality.cap = j.get("cap").getAsString();
        Quality.blocked = j.has("blocked") && j.get("blocked").getAsBoolean();
        Quality.capOff = j.has("capOff") && j.get("capOff").getAsBoolean();
    }

    /** pause demandée par la régie (capture coupée) : on réessaie plus tard sans erreur */
    private static final class Paused extends RuntimeException { Paused(String m) { super(m); } }
    private static dev.onvoid.webrtc.media.audio.CustomAudioSource gameSource;

    /** le site a basculé vers le site de secours : on retient la nouvelle adresse pour toujours */
    public static void moveTo(String url) {
        if (url == null || !url.startsWith("https://")) return;
        String u = url.replaceAll("/+$", "");
        ClientConfig c = ClientConfig.get();
        if (!Data.serverKey.isEmpty() && (c.streamKey == null || c.streamKey.isBlank())) Data.serverUrl = u;
        if (!u.equals(c.url)) { c.url = u; ClientConfig.save(); CapitolTV.LOG.info("Capitol TV : nouvelle adresse du site {}", u); }
        retryAt = System.currentTimeMillis() + 1000;
    }

    /* ---------- impact de la capture sur le jeu : signalé à la régie ---------- */
    private static int badSec;
    private static long reportedAt;
    private static void watchImpact(int fps) {
        boolean atFloor = step >= STEPS.length - 1;
        if (baseFps > 25 && fps < baseFps * (atFloor ? 0.75 : 0.5)) badSec++; else badSec = Math.max(0, badSec - 2);
        if (badSec < 30 || System.currentTimeMillis() - reportedAt < 5 * 60000) return;
        badSec = 0; reportedAt = System.currentTimeMillis();
        String key = key(); if (key == null || key.isBlank()) return;
        String body = String.format(java.util.Locale.ROOT, "{\"base\":%d,\"fps\":%d,\"h\":%d,\"step\":%d}", Math.round(baseFps), fps, curH, step);
        HTTP.sendAsync(HttpRequest.newBuilder(URI.create(base().replaceAll("/+$", "") + "/whip/" + key.trim() + "/perf")).timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding());
    }

    /** prévient le serveur du niveau choisi (même « sans POV ») et récupère le plafond de la régie */
    public static void announce() {
        String key = key();
        if (key == null || key.isBlank()) return;
        String url = qualityUrl(base(), key.trim());
        HTTP.sendAsync(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString())
                .thenAccept(r -> { if (r.statusCode() == 200) try { com.google.gson.JsonObject j = com.google.gson.JsonParser.parseString(r.body()).getAsJsonObject(); if (j.has("moved")) moveTo(j.get("moved").getAsString()); else readQuality(j); } catch (Exception ignored) { } });
    }

    private static boolean noAudio;
    /** usine WebRTC partagée (diffusion + interphone) */
    public static synchronized PeerConnectionFactory factory() { if (factory == null) factory = newFactory(); return factory; }
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
            String url = base().replaceAll("/+$", "") + loc;
            HTTP.sendAsync(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).DELETE().build(), HttpResponse.BodyHandlers.discarding());
        }
        try { if (pc != null) pc.close(); } catch (Throwable ignored) { }
        try { if (videoTrack != null) videoTrack.dispose(); } catch (Throwable ignored) { }
        try { if (videoSource != null) videoSource.dispose(); } catch (Throwable ignored) { }
        try { if (audioSource != null) audioSource.dispose(); } catch (Throwable ignored) { }
        GameAudio.sink = null;
        try { if (gameSource != null) gameSource.dispose(); } catch (Throwable ignored) { }
        gameSource = null;
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
            vi.sendEncodings = encodings(1080, 7000, 30);
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
            String res = "OK (offre " + sdp.length() + " octets, vidéo " + (sdp.contains("m=video") ? "oui" : "non") + ", couches " + (sdp.contains("a=simulcast:send f;q") ? "f+q" : "1") + ", image envoyée)";
            try { p.close(); } catch (Throwable ignored) { }
            return res;
        } catch (Throwable e) {
            return "ÉCHEC " + e;
        }
    }
}
