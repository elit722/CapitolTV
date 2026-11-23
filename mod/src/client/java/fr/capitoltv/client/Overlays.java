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
            if (t.equals("sky")) SkyShow.start(m);
            if (t.equals("voiceRoster")) VoiceMix.roster(m);
            if (t.equals("scanProg")) { scanPct = (int) Data.l(m, "pct"); scanDone = (int) Data.l(m, "done"); scanTotal = (int) Data.l(m, "total"); scanEnd = Data.b(m, "end"); scanAt = System.currentTimeMillis(); }
            if (t.equals("voice")) { CapitolClient.voiceLoud = Data.b(m, "on"); Hud.toast(CapitolClient.voiceLoud ? "Audible par les joueurs" : "Chat vocal", Data.s(m, "msg"), CapitolClient.voiceLoud ? Hud.RED : Hud.BLUE, 4); }
        } catch (Throwable e) { CapitolTV.LOG.warn("Capitol TV : message serveur illisible ({})", e.toString()); }
    }

    /* ---------- relevé de la carte : barre d'avancement (staff, jamais dans le flux) ---------- */
    private static int scanPct, scanDone, scanTotal; private static boolean scanEnd; private static long scanAt;

    public static void renderScan(DrawContext ctx, net.minecraft.client.font.TextRenderer tr, int sw) {
        long age = System.currentTimeMillis() - scanAt;
        if (scanAt == 0 || age > (scanEnd ? 6000 : 20000)) return;
        int w = 180, x = sw - w - 6, y = 34;
        String s = scanEnd ? "CARTE RELEVÉE ✔" : "RELEVÉ DE LA CARTE · " + scanPct + " %";
        String d = scanTotal > 0 ? scanDone + " / " + scanTotal + " chunks" : "";
        ctx.fill(x - 4, y - 4, x + w + 4, y + 24, 0xC0101014);
        ctx.drawText(tr, s, x, y, scanEnd ? 0xFF7FD46A : 0xFFF2D27A, false);
        ctx.drawText(tr, d, x + w - tr.getWidth(d), y, 0xFF8A8478, false);
        ctx.fill(x, y + 12, x + w, y + 18, 0xFF2A2620);
        int f = scanEnd ? w : w * Math.max(0, Math.min(100, scanPct)) / 100;
        ctx.fill(x, y + 12, x + f, y + 18, scanEnd ? 0xFF7FD46A : 0xFFD4AF37);
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
    }
}
