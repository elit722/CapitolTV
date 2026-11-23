package fr.capitoltv.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.capitoltv.CapitolTV;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Projection dans le ciel, comme dans les films : hommage aux tombés (visage bleuté, rangées de
 * « DISTRICT N ») ou scènes préparées par la régie (texte de fond, image, texte devant).
 * Toujours au nord, courbée sur la voûte et cachée par les arbres et le relief (dessinée dans le
 * monde). Écran de départ « PANEM » avec l'emblème. Musique en fond : les autres musiques du jeu
 * baissent d'abord en douceur, puis tout remonte à la fin.
 */
public final class SkyShow {
    private SkyShow() { }

    /** une scène : texte de fond (répété), image ou visage, texte devant */
    private static final class Scene {
        String bg = "", fg = "", mc = "", img = ""; double dur = 7; boolean face;
    }
    private static final List<Scene> list = new ArrayList<>();
    private static long at;
    private static final double INTRO = 5, PRE = 2.5;
    private static String title = "", sub = "";
    private static HymnSound hymn, pendingHymn;
    private static final Identifier FONT = Identifier.of("capitoltv", "textures/sky/font.png");
    private static final Identifier GLOW = Identifier.of("capitoltv", "textures/sky/glow.png");
    private static final Identifier EMBLEM = Identifier.of("capitoltv", "textures/gui/emblem.png");
    private static final Map<String, Identifier> heads = new ConcurrentHashMap<>();
    private static final Map<String, Identifier> imgs = new ConcurrentHashMap<>();
    private static final Map<String, Float> aspect = new ConcurrentHashMap<>();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).followRedirects(HttpClient.Redirect.NORMAL).build();

    public static boolean active() { return !list.isEmpty(); }

    static void start(JsonObject m) {
        MinecraftClient mc = MinecraftClient.getInstance();
        list.clear();
        boolean show = "show".equals(Data.s(m, "kind"));
        if (show) {
            for (JsonElement e : Data.arr(m, "scenes")) {
                JsonObject o = e.getAsJsonObject(); Scene sc = new Scene();
                sc.bg = clean(Data.s(o, "bg")); sc.fg = clean(Data.s(o, "fg")); sc.img = Data.s(o, "img");
                sc.dur = Math.max(3, Math.min(30, Data.l(o, "dur") == 0 ? 7 : Data.l(o, "dur")));
                list.add(sc);
                if (!sc.img.isEmpty()) loadImg(sc.img);
            }
        } else {
            double per = Math.max(4, Math.min(15, Data.l(m, "per") == 0 ? 7 : Data.l(m, "per")));
            for (JsonElement e : Data.arr(m, "list")) {
                JsonObject o = e.getAsJsonObject(); Scene sc = new Scene();
                int d = (int) Data.l(o, "d"), num = (int) Data.l(o, "num");
                sc.bg = d > 0 ? "DISTRICT " + d : "TRIBUT " + num; sc.fg = clean(Data.s(o, "name")); sc.mc = Data.s(o, "mc"); sc.face = true; sc.dur = per;
                list.add(sc);
                if (!sc.mc.isEmpty() && skin(sc.mc) == null) loadHead(sc.mc);
            }
        }
        if (list.isEmpty()) return;
        title = clean(Data.s(m, "title"));
        sub = show ? "" : "LE CAPITOLE REND HOMMAGE";
        at = System.currentTimeMillis() + (long) ((PRE + 1) * 1000); // musiques du jeu baissées, images chargées
        stopHymn(); pendingHymn = null;
        JsonObject h = Data.obj(m, "hymn");
        if (h != null && !Data.s(h, "url").isEmpty()) HymnSound.load(Data.s(h, "url"), (float) Math.max(0, Math.min(1, Data.l(h, "vol") / 100.0)), s -> { if (active()) pendingHymn = s; });
        MusicDuck.down(PRE);
        Hud.toast(show ? "Projection" : "Hommage", "Lève les yeux vers le ciel, au nord.", Hud.BLUE, 6);
    }

    private static double shown() { double t = INTRO; for (Scene sc : list) t += sc.dur; return t; }
    /** la musique s'éteint en 4 s après la dernière scène, puis tout s'arrête */
    private static double total() { return shown() + 4.5; }
    private static void stopHymn() { if (hymn != null) { hymn.fadeOut(); hymn = null; } }

    public static void stop() { if (!list.isEmpty() || pendingHymn != null) MusicDuck.up(2.5); list.clear(); stopHymn(); pendingHymn = null; }

    /** chaque tick : lance la musique pile au début, la coupe en douceur à la fin */
    public static void tick(MinecraftClient mc) {
        MusicDuck.tick(mc);
        if (list.isEmpty()) return;
        double el = (System.currentTimeMillis() - at) / 1000.0;
        if (pendingHymn != null && el >= 0 && el < shown() - 2) { hymn = pendingHymn; pendingHymn = null; mc.getSoundManager().play(hymn); }
        if (hymn != null && el >= shown() - 0.3) stopHymn();
        if (el > total() || mc.world == null) stop();
    }

    /* ---------- visages : peau du joueur s'il est dans la liste, sinon tête téléchargée ---------- */
    private static Identifier skin(String name) {
        var nh = MinecraftClient.getInstance().getNetworkHandler();
        if (nh == null) return null;
        PlayerListEntry e = nh.getPlayerListEntry(name);
        return e == null ? null : e.getSkinTextures().texture();
    }
    private static void loadHead(String mc) {
        String k = mc.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        if (k.isEmpty() || heads.containsKey(k)) return;
        HttpRequest rq = HttpRequest.newBuilder(URI.create("https://mc-heads.net/avatar/" + k + "/64")).timeout(Duration.ofSeconds(10)).header("User-Agent", "CapitolTV-Cam/1.0").build();
        HTTP.sendAsync(rq, HttpResponse.BodyHandlers.ofInputStream()).thenAccept(r -> {
            if (r.statusCode() != 200) return;
            try (InputStream in = r.body()) {
                NativeImage img = NativeImage.read(in);
                MinecraftClient c = MinecraftClient.getInstance();
                c.execute(() -> { Identifier id = Identifier.of("capitoltv", "head/" + k); c.getTextureManager().registerTexture(id, new NativeImageBackedTexture(() -> "capitoltv head " + k, img)); heads.put(k, id); });
            } catch (Throwable e) { CapitolTV.LOG.warn("Capitol TV : visage de {} indisponible ({})", k, e.toString()); }
        });
    }

    private static void loadImg(String url) {
        if (imgs.containsKey(url)) return;
        HttpRequest rq = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("User-Agent", "CapitolTV-Cam/1.0").build();
        HTTP.sendAsync(rq, HttpResponse.BodyHandlers.ofInputStream()).thenAccept(r -> {
            if (r.statusCode() != 200) return;
            try (InputStream in = r.body()) {
                NativeImage img = NativeImage.read(in);
                float asp = img.getWidth() / (float) Math.max(1, img.getHeight());
                String k = Integer.toHexString(url.hashCode());
                MinecraftClient c = MinecraftClient.getInstance();
                c.execute(() -> { Identifier id = Identifier.of("capitoltv", "sky/" + k); c.getTextureManager().registerTexture(id, new NativeImageBackedTexture(() -> "capitoltv sky " + k, img)); aspect.put(url, asp); imgs.put(url, id); });
            } catch (Throwable e) { CapitolTV.LOG.warn("Capitol TV : image de projection illisible ({})", e.toString()); }
        });
    }

    /* ---------- tracé sur la voûte : (u, v) en degrés autour du point visé ---------- */
    private static float R, ELEV = 34f;
    /** taille globale : tout tient dans l'écran et reste au-dessus du sol, même sur un relief accidenté */
    private static final float S = 0.68f;
    private static Vec3d pt(float u, float v) {
        float elev = ELEV + S * (v + u * 0.03f - 0.0034f * u * u);   // légère inclinaison + courbure de la voûte
        return Vec3d.fromPolar(-elev, 180f + S * u).multiply(R);
    }
    private interface Shade { float at(float u, float v); }

    private static void quad(VertexConsumer vc, Matrix4f m, float u0, float v0, float u1, float v1, float tu0, float tv0, float tu1, float tv1, float r, float g, float b, Shade sh) {
        int n = Math.max(1, (int) Math.ceil((u1 - u0) / 2.5f));
        for (int i = 0; i < n; i++) {
            float a0 = u0 + (u1 - u0) * i / n, a1 = u0 + (u1 - u0) * (i + 1) / n;
            float t0 = tu0 + (tu1 - tu0) * i / n, t1 = tu0 + (tu1 - tu0) * (i + 1) / n;
            Vec3d p00 = pt(a0, v0), p10 = pt(a1, v0), p11 = pt(a1, v1), p01 = pt(a0, v1);
            float s00 = sh.at(a0, v0), s10 = sh.at(a1, v0), s11 = sh.at(a1, v1), s01 = sh.at(a0, v1);
            // les deux faces : visible quel que soit le sens de tracé
            v(vc, m, p00, t0, tv1, r, g, b, s00); v(vc, m, p10, t1, tv1, r, g, b, s10); v(vc, m, p11, t1, tv0, r, g, b, s11); v(vc, m, p01, t0, tv0, r, g, b, s01);
            v(vc, m, p01, t0, tv0, r, g, b, s01); v(vc, m, p11, t1, tv0, r, g, b, s11); v(vc, m, p10, t1, tv1, r, g, b, s10); v(vc, m, p00, t0, tv1, r, g, b, s00);
        }
    }
    private static void v(VertexConsumer vc, Matrix4f m, Vec3d p, float tu, float tv, float r, float g, float b, float k) {
        k = MathHelper.clamp(k, 0, 1);
        vc.vertex(m, (float) p.x, (float) p.y, (float) p.z).color((int) (r * k * 255), (int) (g * k * 255), (int) (b * k * 255), 255)
          .texture(tu, tv).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0f, 1f, 0f);
    }

    private static String clean(String s) {
        s = Normalizer.normalize(s.toUpperCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) b.append(SkyFont.CHARS.indexOf(c) >= 0 ? c : c == '_' ? '-' : ' ');
        return b.toString().trim();
    }
    private static float width(String s, float h) { float w = 0; for (char c : s.toCharArray()) { int i = SkyFont.CHARS.indexOf(c); if (i >= 0) w += SkyFont.ADV[i] * 1.06f / SkyFont.H * h; } return w; }
    /** écrit un texte (police de l'hommage) ; u = bord gauche, v = bas */
    private static void text(VertexConsumer vc, Matrix4f m, String s, float u, float v, float h, float r, float g, float b, Shade sh, float uMin, float uMax) {
        float x = u;
        for (char c : s.toCharArray()) {
            int i = SkyFont.CHARS.indexOf(c); if (i < 0) continue;
            float adv = SkyFont.ADV[i] * 1.06f / SkyFont.H * h, pad = SkyFont.PAD / (float) SkyFont.H * h;
            if (c != ' ' && x >= uMin && x + adv <= uMax) {
                float tu0 = SkyFont.X[i] / (float) SkyFont.W, tu1 = (SkyFont.X[i] + SkyFont.ADV[i] + 2 * SkyFont.PAD) / (float) SkyFont.W;
                quad(vc, m, x - pad, v, x + adv - pad * 0.1f + pad, v + h, tu0, 0f, tu1, 1f, r, g, b, sh);
            }
            x += adv;
        }
    }

    private static double hash(long n) { n = (n ^ (n >>> 13)) * 0x5bd1e995L; n ^= n >>> 15; return (n & 0xFFFF) / 65535.0; }

    public static void render(WorldRenderContext ctx) {
        if (list.isEmpty()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;
        long now = System.currentTimeMillis();
        double el = (now - at) / 1000.0;
        if (el < 0 || el >= shown()) return;
        R = MathHelper.clamp(mc.options.getClampedViewDistance() * 16 * 0.5f, 40f, 100f);
        // scintillement du projecteur (et quelques sauts d'image)
        float fl = (float) (0.84 + 0.16 * hash(now / 70) - (hash(now / 450) > 0.94 ? 0.4 : 0));
        float glitch = hash(now / 120) > 0.93 ? (float) (hash(now / 33) - 0.5) * 3f : 0f;
        Scene sc = null; float a;
        if (el < INTRO) a = (float) (Math.min(1, el / 1.5) * Math.min(1, (INTRO - el) / 1.0));
        else {
            double t = el - INTRO; int i = 0;
            while (i < list.size() - 1 && t >= list.get(i).dur) { t -= list.get(i).dur; i++; }
            sc = list.get(i);
            a = (float) (Math.min(1, t / 1.2) * Math.min(1, (sc.dur - t) / 1.0));
        }
        final float A = MathHelper.clamp(a * fl, 0, 1);
        VertexConsumerProvider.Immediate imm = mc.getBufferBuilders().getEntityVertexConsumers();
        Matrix4f m = new MatrixStack().peek().getPositionMatrix();
        // image (ou visage) au centre
        Identifier tex = null; boolean skinTex = false; float asp = 1;
        if (sc != null && sc.face) { Identifier sk = sc.mc.isEmpty() ? null : skin(sc.mc); if (sk != null) { tex = sk; skinTex = true; } else tex = heads.get(sc.mc.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "")); }
        else if (sc != null && !sc.img.isEmpty()) { tex = imgs.get(sc.img); asp = aspect.getOrDefault(sc.img, 1f); }
        final boolean hasPic = sc != null && (sc.face || !sc.img.isEmpty());
        final float FH = 30f, FW = MathHelper.clamp(FH * asp, 12f, 56f), FV = 3f;
        final boolean fgOnly = sc != null && !hasPic && !sc.fg.isEmpty();
        // le texte de fond s'efface derrière l'image et le texte devant (il reste derrière, jamais devant)
        Shade back = (u, v) -> {
            float k = A * 0.55f;
            if (!hasPic && !fgOnly) return k;
            float hw = hasPic ? FW * 0.55f : 40f, top = hasPic ? FV + FH * 0.55f : 8f, bot = hasPic ? FV - FH * 0.55f - 10f : -10f;
            float du = Math.max(0, Math.abs(u) - hw), dv = Math.max(0, Math.max(v - top, bot - v));
            return k * MathHelper.clamp(0.12f + (float) Math.sqrt(du * du + dv * dv) / 7f, 0.12f, 1f);
        };
        VertexConsumer g = imm.getBuffer(RenderLayer.getEyes(GLOW));
        quad(g, m, -80, -38, 80, 44, 0, 0, 1, 1, 0.10f, 0.32f, 0.55f, (u, v) -> A * 0.55f);
        imm.draw(RenderLayer.getEyes(GLOW));
        if (sc == null) {
            // écran de départ : emblème du Capitole, PANEM, titre
            VertexConsumer e = imm.getBuffer(RenderLayer.getEyes(EMBLEM));
            quad(e, m, -11, 8, 11, 30, 0, 0, 1, 1, 0.55f, 0.85f, 1f, (u, v) -> A);
            imm.draw(RenderLayer.getEyes(EMBLEM));
        }
        VertexConsumer t = imm.getBuffer(RenderLayer.getEyes(FONT));
        if (sc == null) {
            float h = 14f, w = width("PANEM", h);
            text(t, m, "PANEM", -w / 2, -9, h, 0.45f, 0.82f, 1f, (u, v) -> A, -90, 90);
            String st = title.isEmpty() ? sub : title;
            if (!st.isEmpty()) { float h2 = 3.2f, w2 = width(st, h2); text(t, m, st, -w2 / 2, -15, h2, 0.6f, 0.9f, 1f, (u, v) -> A, -90, 90); }
        } else {
            if (!sc.bg.isEmpty()) {
                // rangées du texte de fond en quinconce
                String row = sc.bg + "   ";
                float h = 7.2f, rw = Math.max(10f, width(row, h));
                for (int r = 0; r < 6; r++) {
                    float v0 = 20f - r * 8.6f, off = -74f + (r % 2) * rw * 0.42f - r * 3f + (r == 2 ? glitch : 0);
                    for (float u = off; u < 74; u += rw) text(t, m, row, u, v0, h, 0.30f, 0.66f, 1f, back, -74, 74);
                }
            }
            if (!sc.fg.isEmpty()) {
                // sous l'image, ou bien grand au centre s'il n'y a pas d'image
                float h2 = hasPic ? 5.2f : 9f, w2 = width(sc.fg, h2);
                if (!hasPic && w2 > 120) { h2 *= 120 / w2; w2 = 120; }
                text(t, m, sc.fg, -w2 / 2, hasPic ? FV - FH / 2 - 7.5f : -h2 / 2 + 2, h2, 0.75f, 0.95f, 1f, (u, v) -> Math.min(1f, A * 1.3f), -90, 90);
            }
        }
        imm.draw(RenderLayer.getEyes(FONT));
        if (tex != null) {
            // image projetée, en bandes horizontales (lignes de balayage), teintée bleu
            VertexConsumer fv = imm.getBuffer(RenderLayer.getEyes(tex));
            int N = 32;
            for (int layer = 0; layer < (skinTex ? 2 : 1); layer++) {
                float tu0 = skinTex ? (layer == 0 ? 8 : 40) / 64f : 0, tu1 = skinTex ? (layer == 0 ? 16 : 48) / 64f : 1, tv0 = skinTex ? 8 / 64f : 0, tv1 = skinTex ? 16 / 64f : 1;
                for (int k = 0; k < N; k++) {
                    float vTop = FV + FH / 2 - FH * k / N, vBot = vTop - FH / N * 0.74f;
                    float shf = (hash(now / 90 + k) > 0.97 ? (float) (hash(k * 7L + now / 90) - 0.5) * 1.5f : 0) + glitch * 0.3f;
                    float ta = tv0 + (tv1 - tv0) * k / N, tb = tv0 + (tv1 - tv0) * (k + 0.74f) / N;
                    quad(fv, m, -FW / 2 + shf, vBot, FW / 2 + shf, vTop, tu0, ta, tu1, tb, 0.50f, 0.84f, 1f, (u, v) -> A * 0.95f);
                }
            }
            imm.draw(RenderLayer.getEyes(tex));
        }
    }
}
