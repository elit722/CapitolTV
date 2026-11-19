package fr.capitoltv.client;

import com.google.gson.JsonObject;
import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceGatheringState;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCRtpTransceiverDirection;
import dev.onvoid.webrtc.RTCRtpTransceiverInit;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;
import fr.capitoltv.CapitolTV;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Interphone avec la régie : la régie parle depuis le site, la caméra répond en maintenant B.
 * Canal fermé : B envoie une demande à la régie.
 */
public final class Talk {
    private Talk() { }

    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "CapitolTV-interphone"); t.setDaemon(true); return t; });
    private static final Map<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private static final AtomicInteger seq = new AtomicInteger();

    public static volatile boolean open, regieLive, regieSpeaks, speaking;
    public static volatile String regieName = "", status = "";
    private static RTCPeerConnection pubPc, pullPc;
    private static AudioTrackSource micSrc;
    private static AudioTrack mic;
    private static long lastReq;

    /** réponse du site à une requête (champ rid) */
    public static boolean onReply(JsonObject m) {
        if (!m.has("rid")) return false;
        CompletableFuture<JsonObject> f = pending.remove(m.get("rid").getAsString());
        if (f == null) return false;
        if ("ok".equals(Data.s(m, "t"))) f.complete(m); else f.completeExceptionally(new IllegalStateException(Data.s(m, "msg")));
        return true;
    }

    private static JsonObject ask(JsonObject o) throws Exception {
        String rid = "tk" + seq.incrementAndGet();
        o.addProperty("rid", rid);
        CompletableFuture<JsonObject> f = new CompletableFuture<>();
        pending.put(rid, f);
        CapitolClient.send(o);
        try { return f.get(12, TimeUnit.SECONDS); } finally { pending.remove(rid); }
    }

    /** message « talkCh » : canal ouvert ou fermé par la régie */
    public static void onChannel(boolean isOpen, boolean regie, String by) {
        boolean was = open;
        open = isOpen; regieLive = regie;
        if (isOpen && !was) Hud.toast("Interphone", "Canal régie ouvert" + (by == null || by.isEmpty() ? "" : " par " + by) + " · maintiens " + CapitolClient.talkKey.getBoundKeyLocalizedText().getString() + " pour parler", Hud.GOLD, 6);
        if (!isOpen && was) Hud.toast("Interphone", "Canal régie fermé", Hud.DIM, 3);
        WORK.execute(() -> {
            if (!isOpen) { close(); return; }
            try {
                if (pubPc == null) publish();
                if (regie) pull();
                status = "ouvert";
            } catch (Throwable e) {
                status = "erreur : " + e.getMessage();
                CapitolTV.LOG.warn("Capitol TV : interphone ({})", e.toString());
                Hud.toast("Interphone", "Connexion impossible : " + e.getMessage(), Hud.RED, 5);
            }
        });
    }

    public static void onSpeak(String who, boolean on, String name) {
        if ("regie".equals(who)) { regieSpeaks = on; regieName = name == null ? "" : name; }
    }

    private static RTCConfiguration cfg() {
        RTCConfiguration c = new RTCConfiguration();
        RTCIceServer ice = new RTCIceServer();
        ice.urls = List.of("stun:stun.cloudflare.com:3478");
        c.iceServers = List.of(ice);
        return c;
    }

    private static RTCPeerConnection newPc(CompletableFuture<Void> gathered) {
        return Streamer.factory().createPeerConnection(cfg(), new PeerConnectionObserver() {
            @Override public void onIceCandidate(RTCIceCandidate candidate) { }
            @Override public void onIceGatheringChange(RTCIceGatheringState state) { if (state == RTCIceGatheringState.COMPLETE) gathered.complete(null); }
        });
    }

    private static void publish() throws Exception {
        CompletableFuture<Void> g = new CompletableFuture<>();
        RTCPeerConnection pc = newPc(g);
        AudioOptions ao = new AudioOptions();
        ao.echoCancellation = true; ao.noiseSuppression = true; ao.autoGainControl = true;
        micSrc = Streamer.factory().createAudioSource(ao);
        mic = Streamer.factory().createAudioTrack("talk", micSrc);
        mic.setEnabled(false);
        RTCRtpTransceiverInit ti = new RTCRtpTransceiverInit();
        ti.direction = RTCRtpTransceiverDirection.SEND_ONLY;
        pc.addTransceiver(mic, ti);
        CompletableFuture<RTCSessionDescription> offer = new CompletableFuture<>();
        pc.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
            @Override public void onSuccess(RTCSessionDescription d) { offer.complete(d); }
            @Override public void onFailure(String e) { offer.completeExceptionally(new IllegalStateException(e)); }
        });
        RTCSessionDescription o = offer.get(10, TimeUnit.SECONDS);
        set(cb -> pc.setLocalDescription(o, cb));
        try { g.get(3, TimeUnit.SECONDS); } catch (Exception ignored) { }
        JsonObject q = new JsonObject();
        q.addProperty("t", "talkPub");
        q.addProperty("sdp", pc.getLocalDescription().sdp);
        JsonObject r = ask(q);
        set(cb -> pc.setRemoteDescription(new RTCSessionDescription(RTCSdpType.ANSWER, Data.s(r, "sdp")), cb));
        pubPc = pc;
    }

    private static void pull() throws Exception {
        if (pullPc != null) { try { pullPc.close(); } catch (Throwable ignored) { } pullPc = null; }
        JsonObject q = new JsonObject();
        q.addProperty("t", "talkPull");
        JsonObject r = ask(q);
        CompletableFuture<Void> g = new CompletableFuture<>();
        RTCPeerConnection pc = newPc(g);
        set(cb -> pc.setRemoteDescription(new RTCSessionDescription(RTCSdpType.OFFER, Data.s(r, "sdp")), cb));
        CompletableFuture<RTCSessionDescription> ans = new CompletableFuture<>();
        pc.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
            @Override public void onSuccess(RTCSessionDescription d) { ans.complete(d); }
            @Override public void onFailure(String e) { ans.completeExceptionally(new IllegalStateException(e)); }
        });
        RTCSessionDescription a = ans.get(10, TimeUnit.SECONDS);
        set(cb -> pc.setLocalDescription(a, cb));
        try { g.get(3, TimeUnit.SECONDS); } catch (Exception ignored) { }
        JsonObject q2 = new JsonObject();
        q2.addProperty("t", "talkAns");
        q2.addProperty("sid", Data.s(r, "sid"));
        q2.addProperty("sdp", pc.getLocalDescription().sdp);
        ask(q2);
        pullPc = pc;
    }

    private interface SetCall { void run(SetSessionDescriptionObserver cb); }
    private static void set(SetCall call) throws Exception {
        CompletableFuture<Void> f = new CompletableFuture<>();
        call.run(new SetSessionDescriptionObserver() {
            @Override public void onSuccess() { f.complete(null); }
            @Override public void onFailure(String e) { f.completeExceptionally(new IllegalStateException(e)); }
        });
        f.get(10, TimeUnit.SECONDS);
    }

    public static void close() {
        speaking = false; regieSpeaks = false;
        try { if (pubPc != null) pubPc.close(); } catch (Throwable ignored) { }
        try { if (pullPc != null) pullPc.close(); } catch (Throwable ignored) { }
        pubPc = null; pullPc = null; mic = null; micSrc = null;
    }

    /** chaque tick : touche de l'interphone maintenue ? */
    public static void tick(boolean held) {
        if (!CapitolClient.online()) return;
        if (!open) {
            if (held && System.currentTimeMillis() - lastReq > 4000) {
                lastReq = System.currentTimeMillis();
                JsonObject o = new JsonObject(); o.addProperty("t", "talkReq"); CapitolClient.send(o);
            }
            return;
        }
        if (held != speaking) {
            speaking = held;
            AudioTrack m = mic;
            if (m != null) try { m.setEnabled(held); } catch (Throwable ignored) { }
            JsonObject o = new JsonObject(); o.addProperty("t", "talkSpeak"); o.addProperty("on", held); CapitolClient.send(o);
        }
    }
}
