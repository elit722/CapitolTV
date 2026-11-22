package fr.capitoltv.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.capitoltv.CapitolTV;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Écrans envoyés par le serveur de l'event : récap de mort du tribut, hommage projeté dans le ciel. */
public final class Overlays {
    private Overlays() { }

    /* ---------- réception ---------- */
    public static void onServerMsg(String json) {
        try {
            JsonObject m = JsonParser.parseString(json).getAsJsonObject();
            String t = Data.s(m, "t");
            if (t.equals("recap")) { recap = m; recapAt = System.currentTimeMillis(); }
            if (t.equals("sky")) startSky(m);
        } catch (Throwable e) { CapitolTV.LOG.warn("Capitol TV : message serveur illisible ({})", e.toString()); }
    }

    /* ---------- récap de mort ---------- */
    private static JsonObject recap;
    private static long recapAt;

    public static void renderRecap(DrawContext ctx, int sw, int sh) {
        if (recap == null) return;
        long el = System.currentTimeMillis() - recapAt;
        if (el > 130_000) { recap = null; return; }
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        List<String> lines = new ArrayList<>();
        for (JsonElement e : Data.arr(recap, "lines")) lines.add(e.getAsString());
        int w = Math.min(sw - 20, 280), h = 58 + lines.size() * 12 + 18, x = (sw - w) / 2, y = Math.max(8, sh / 2 - h - 30);
        float a = MathHelper.clamp(el / 600f, 0, 1);
        int al = (int) (a * 0xE0) << 24;
        ctx.fill(x, y, x + w, y + h, al | 0x0B0B0F);
        ctx.fill(x, y, x + w, y + 2, ((int) (a * 255) << 24) | 0xD4AF37);
        String d = Data.l(recap, "district") > 0 ? "DISTRICT " + Data.l(recap, "district") : "";
        ctx.drawText(tr, "CAPITOL TV · " + d, x + (w - tr.getWidth("CAPITOL TV · " + d)) / 2, y + 8, 0xFFD4AF37, false);
        String n = Data.s(recap, "name");
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(x + w / 2f, y + 22);
        ctx.getMatrices().scale(1.6f, 1.6f);
        ctx.drawText(tr, n, -tr.getWidth(n) / 2, 0, 0xFFEDE6D6, true);
        ctx.getMatrices().popMatrix();
        for (int i = 0; i < lines.size(); i++) ctx.drawText(tr, lines.get(i), x + (w - tr.getWidth(lines.get(i))) / 2, y + 44 + i * 12, 0xFFB8B0A0, false);
        if (Data.b(recap, "ban")) {
            long left = Math.max(0, Data.l(recap, "delay") * 1000 - el) / 1000;
            String s = left > 0 ? "Tu quittes l'arène dans " + left + " s · merci d'avoir joué !" : "Merci d'avoir joué !";
            ctx.drawText(tr, s, x + (w - tr.getWidth(s)) / 2, y + h - 14, 0xFFE0514A, false);
        }
    }

    /* ---------- hommage dans le ciel ---------- */
    private record Face(String name, int d, int num, String mc) { }
    private static final List<Face> sky = new ArrayList<>();
    private static long skyAt;
    private static int per = 7;
    private static String skyTitle = "";
    /** direction de projection (fixée au début : face au nord, haut dans le ciel) */
    private static final float SKY_YAW = 180f, SKY_PITCH = -32f;
    private static final Map<String, Identifier> faces = new ConcurrentHashMap<>();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).followRedirects(HttpClient.Redirect.NORMAL).build();

    private static void startSky(JsonObject m) {
        sky.clear();
        JsonArray l = Data.arr(m, "list");
        for (JsonElement e : l) { JsonObject o = e.getAsJsonObject(); sky.add(new Face(Data.s(o, "name"), (int) Data.l(o, "d"), (int) Data.l(o, "num"), Data.s(o, "mc"))); }
        per = (int) Math.max(4, Math.min(15, Data.l(m, "per") == 0 ? 7 : Data.l(m, "per")));
        skyTitle = Data.s(m, "title");
        skyAt = System.currentTimeMillis() + 2500; // le temps de charger les visages
        for (Face f : sky) if (!f.mc.isEmpty()) loadFace(f.mc);
        Hud.toast("Hommage", "Lève les yeux vers le ciel, au nord.", Hud.BLUE, 6);
    }

    private static void loadFace(String mc) {
        String k = mc.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        if (k.isEmpty() || faces.containsKey(k)) return;
        HttpRequest rq = HttpRequest.newBuilder(URI.create("https://mc-heads.net/avatar/" + k + "/128")).timeout(Duration.ofSeconds(10)).header("User-Agent", "CapitolTV-Cam/1.0").build();
        HTTP.sendAsync(rq, HttpResponse.BodyHandlers.ofInputStream()).thenAccept(r -> {
            if (r.statusCode() != 200) return;
            try (InputStream in = r.body()) {
                NativeImage img = NativeImage.read(in);
                MinecraftClient mcl = MinecraftClient.getInstance();
                mcl.execute(() -> {
                    Identifier id = Identifier.of("capitoltv", "face/" + k);
                    mcl.getTextureManager().registerTexture(id, new NativeImageBackedTexture(() -> "capitoltv face " + k, img));
                    faces.put(k, id);
                });
            } catch (Throwable e) { CapitolTV.LOG.warn("Capitol TV : visage de {} indisponible ({})", k, e.toString()); }
        });
    }

    private static double hash(long n) { n = (n ^ (n >>> 13)) * 0x5bd1e995L; n ^= n >>> 15; return (n & 0xFFFF) / 65535.0; }

    public static void renderSky(DrawContext ctx, int sw, int sh) {
        if (sky.isEmpty()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;
        long now = System.currentTimeMillis();
        double el = (now - skyAt) / 1000.0;
        int intro = 4;
        double total = intro + sky.size() * per + 1;
        if (el > total) { sky.clear(); return; }
        if (el < 0) return;
        Camera cam = mc.gameRenderer.getCamera();
        float dYaw = MathHelper.wrapDegrees(SKY_YAW - cam.getYaw()), dPitch = SKY_PITCH - cam.getPitch();
        if (Math.abs(dYaw) > 75 || Math.abs(dPitch) > 75) return;
        double vfov = Math.toRadians(mc.options.getFov().getValue());
        double hfov = 2 * Math.atan(Math.tan(vfov / 2) * sw / (double) sh);
        float cx = (float) (sw / 2.0 + Math.tan(Math.toRadians(dYaw)) / Math.tan(hfov / 2) * sw / 2.0);
        float cy = (float) (sh / 2.0 + Math.tan(Math.toRadians(dPitch)) / Math.tan(vfov / 2) * sh / 2.0);
        float scale = (float) (sh / 240.0 * (Math.toRadians(70) / vfov));
        TextRenderer tr = mc.textRenderer;
        // scintillement du projecteur
        double fl = 0.82 + 0.18 * hash(now / 70) - (hash(now / 400) > 0.93 ? 0.35 : 0);
        String big, sub = "", name = "";
        Identifier img = null;
        double a;
        if (el < intro) { big = "PANEM"; sub = skyTitle.isEmpty() ? "LE CAPITOLE REND HOMMAGE AUX TRIBUTS TOMBÉS" : skyTitle.toUpperCase(Locale.ROOT); a = Math.min(1, el / 1.2) * Math.min(1, (intro - el) / 0.8); }
        else {
            int i = Math.min(sky.size() - 1, (int) ((el - intro) / per));
            double le = el - intro - i * per;
            a = Math.min(1, le / 1.0) * Math.min(1, (per - le) / 1.0);
            Face f = sky.get(i);
            big = f.d > 0 ? "DISTRICT " + f.d : "TRIBUT";
            sub = f.num > 0 ? String.valueOf(f.num) : "";
            name = f.name.toUpperCase(Locale.ROOT);
            img = faces.get(f.mc.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", ""));
        }
        a = MathHelper.clamp(a * fl, 0, 1);
        int A = (int) (a * 255);
        var mx = ctx.getMatrices();
        mx.pushMatrix();
        mx.translate(cx, cy);
        mx.scale(scale, scale);
        // halo du faisceau
        for (int r = 6; r >= 1; r--) { int al = (int) (A * 0.05); ctx.fill(-70 - r * 10, -60 - r * 8, 70 + r * 10, 60 + r * 8, (al << 24) | 0x2A7FD0); }
        // « DISTRICT N » géant derrière le visage
        mx.pushMatrix(); mx.scale(3.4f, 3.4f);
        int bw = tr.getWidth(big);
        ctx.drawText(tr, big, -bw / 2, -14, ((int) (A * 0.55) << 24) | 0x6FC8FF, false);
        mx.popMatrix();
        if (!sub.isEmpty() && el >= intro) { mx.pushMatrix(); mx.scale(2.2f, 2.2f); ctx.drawText(tr, sub, -tr.getWidth(sub) / 2, 14, ((int) (A * 0.7) << 24) | 0x8AD8FF, false); mx.popMatrix(); }
        else if (!sub.isEmpty()) { mx.pushMatrix(); mx.scale(0.9f, 0.9f); ctx.drawText(tr, sub, -tr.getWidth(sub) / 2, 34, (A << 24) | 0x8AD8FF, false); mx.popMatrix(); }
        // visage projeté, teinté bleu
        if (img != null) {
            int s = 64, x = -s / 2, y = -s / 2 - 6;
            int col = ((int) (A * 0.85) << 24) | 0x9FE0FF;
            int jit = hash(now / 90) > 0.9 ? 1 : 0;
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, img, x + jit, y, 0f, 0f, s, s, s, s, col);
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, img, x - 1, y, 0f, 0f, s, s, s, s, ((int) (A * 0.25) << 24) | 0x40A0FF);
            // lignes de balayage
            int off = (int) ((now / 60) % 3);
            for (int yy = y + off; yy < y + s; yy += 3) ctx.fill(x, yy, x + s, yy + 1, ((int) (A * 0.28) << 24) | 0x02121E);
        } else if (el >= intro) {
            ctx.fill(-24, -36, 24, 12, ((int) (A * 0.35) << 24) | 0x6FC8FF);
        }
        if (!name.isEmpty()) ctx.drawText(tr, name, -tr.getWidth(name) / 2, 34, (A << 24) | 0xCFF0FF, false);
        mx.popMatrix();
    }
}
