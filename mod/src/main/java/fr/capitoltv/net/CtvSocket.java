package fr.capitoltv.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Liaison WebSocket avec le Worker Capitol TV (/mod/ws).
 * Reconnexion automatique, envoi sérialisé, ping toutes les 25 s.
 * Les callbacks sont appelés sur le thread réseau : à ré-aiguiller vers le thread du jeu.
 */
public final class CtvSocket implements WebSocket.Listener {
    public enum State { OFF, CONNECTING, ONLINE, RETRY, REFUSED }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "CapitolTV-WS");
        t.setDaemon(true);
        return t;
    });
    private final Consumer<JsonObject> onMessage;
    private final Consumer<State> onState;
    private final StringBuilder buf = new StringBuilder();

    private volatile WebSocket ws;
    private volatile boolean wanted;
    private volatile State state = State.OFF;
    private volatile String detail = "";
    private CompletableFuture<?> chain = CompletableFuture.completedFuture(null);
    private String url;
    private Map<String, String> headers;
    private int tries;
    private int gen;

    public CtvSocket(Consumer<JsonObject> onMessage, Consumer<State> onState) {
        this.onMessage = onMessage;
        this.onState = onState;
        ex.scheduleAtFixedRate(() -> {
            WebSocket w = ws;
            if (w != null) sendRaw("ping");
        }, 25, 25, TimeUnit.SECONDS);
    }

    public State state() { return state; }
    public String detail() { return detail; }
    public boolean online() { return state == State.ONLINE && ws != null; }

    public synchronized void connect(String baseUrl, Map<String, String> hdrs) {
        disconnect();
        this.url = baseUrl;
        this.headers = hdrs;
        this.wanted = true;
        this.tries = 0;
        final int g = ++gen;
        ex.execute(() -> open(g));
    }

    public synchronized void disconnect() {
        wanted = false;
        gen++;
        WebSocket w = ws;
        ws = null;
        if (w != null) {
            try { w.sendClose(1000, "bye"); } catch (Exception ignored) { }
            try { w.abort(); } catch (Exception ignored) { }
        }
        setState(State.OFF, "");
    }

    private void open(int g) {
        if (!wanted || g != gen) return;
        String base = url == null ? "" : url.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.isEmpty()) { setState(State.REFUSED, "Adresse du site manquante"); return; }
        String u = base.replaceFirst("^http", "ws") + "/mod/ws";
        setState(State.CONNECTING, "");
        try {
            WebSocket.Builder b = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10));
            if (headers != null) headers.forEach(b::header);
            b.buildAsync(URI.create(u), this).whenComplete((w, err) -> {
                if (g != gen) { if (w != null) w.abort(); return; }
                if (err != null) {
                    Throwable c = err;
                    while (c.getCause() != null) c = c.getCause();
                    String m = String.valueOf(c.getMessage());
                    if (c instanceof java.net.http.WebSocketHandshakeException he) m = "HTTP " + he.getResponse().statusCode();
                    if (m.contains("401") || m.contains("403")) { setState(State.REFUSED, "Jeton refusé : génère un nouveau jeton sur le site"); wanted = false; return; }
                    retry(g, m);
                } else {
                    ws = w;
                    tries = 0;
                    synchronized (this) { chain = CompletableFuture.completedFuture(null); }
                    setState(State.ONLINE, "");
                }
            });
        } catch (Exception e) {
            retry(g, e.getMessage());
        }
    }

    private void retry(int g, String why) {
        ws = null;
        if (!wanted || g != gen) return;
        tries++;
        long s = Math.min(30, 2L * tries);
        setState(State.RETRY, why == null ? "" : why);
        ex.schedule(() -> open(g), s, TimeUnit.SECONDS);
    }

    private void setState(State s, String d) {
        state = s;
        detail = d == null ? "" : d;
        try { onState.accept(s); } catch (Exception ignored) { }
    }

    public void send(JsonObject o) { sendRaw(o.toString()); }

    private synchronized void sendRaw(String s) {
        final WebSocket w = ws;
        if (w == null) return;
        chain = chain.handle((a, b) -> null).thenCompose(x -> w.sendText(s, true));
    }

    /* ---------- WebSocket.Listener ---------- */
    @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        buf.append(data);
        if (last) {
            String s = buf.toString();
            buf.setLength(0);
            if (!s.equals("pong")) {
                try {
                    JsonObject o = JsonParser.parseString(s).getAsJsonObject();
                    onMessage.accept(o);
                } catch (Exception ignored) { }
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        if (webSocket != ws) return null;
        ws = null;
        if (statusCode == 4003 || statusCode == 4001) {
            wanted = false;
            setState(State.REFUSED, reason == null || reason.isEmpty() ? "Accès refusé" : reason);
        } else {
            retry(gen, "Connexion fermée (" + statusCode + ")");
        }
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        if (webSocket != ws) return;
        retry(gen, String.valueOf(error.getMessage()));
    }
}
