package fr.capitoltv.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;

/**
 * Radar d'action : boussole en haut de l'écran avec les tributs (or) et les actions
 * récentes signalées par le serveur (rouge : dégâts, morts). G l'affiche ou le masque.
 */
public final class Radar {
    private Radar() { }
    public static boolean on = true;

    public static void render(DrawContext ctx, TextRenderer tr, int sw) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity me = mc.player;
        if (!on || me == null || mc.world == null || !CamMode.active()) return;
        int w = Math.min(320, sw - 40), x0 = (sw - w) / 2, y = 20, h = 14;
        ctx.fill(x0, y, x0 + w, y + h, 0x90101014);
        ctx.fill(x0 + w / 2, y, x0 + w / 2 + 1, y + h, 0x80EDE6D6);
        float yaw = me.getYaw();
        String[] card = {"S", "O", "N", "E"};
        for (int k = 0; k < 4; k++) {
            float d = MathHelper.wrapDegrees(k * 90 - yaw);
            if (Math.abs(d) < 88) { int px = x0 + w / 2 + Math.round(d / 90f * (w / 2f)); ctx.drawText(tr, card[k], px - tr.getWidth(card[k]) / 2, y + 3, 0xFF9A9284, false); }
        }
        long now = Data.now();
        String dim = mc.world.getRegistryKey().getValue().toString();
        // actions récentes (60 s)
        for (Data.Feed f : Data.feed) {
            if (!f.hasPos || now - f.ts > 60_000 || (!f.d.isEmpty() && !f.d.equals(dim))) continue;
            mark(ctx, tr, me, x0, y, w, h, f.x, f.z, now - f.ts < 10_000 ? 0xFFFF5A4E : 0xFFB0403A, "!", f.text);
        }
        // tributs : joueur visible, sinon dernière position connue
        for (Data.Trib t : Data.tribs.values()) {
            if ("dead".equals(t.status)) continue;
            double tx = Double.NaN, tz = 0;
            for (PlayerEntity e : mc.world.getPlayers()) if (!t.mc.isEmpty() && e.getGameProfile().getName().equalsIgnoreCase(t.mc)) { tx = e.getX(); tz = e.getZ(); }
            if (Double.isNaN(tx)) { Data.Pos q = Data.pos.get(t.id); if (q == null || now - q.ts > 120_000 || (!q.d.isEmpty() && !q.d.equals(dim))) continue; tx = q.x; tz = q.z; }
            boolean fol = t.id.equals(CapitolClient.follow);
            mark(ctx, tr, me, x0, y, w, h, tx, tz, fol ? 0xFFF2D27A : 0xFFD4AF37, fol ? "◆" : "•", t.district > 0 ? "D" + t.district : "");
        }
    }

    private static void mark(DrawContext ctx, TextRenderer tr, ClientPlayerEntity me, int x0, int y, int w, int h, double x, double z, int col, String sym, String label) {
        double dx = x - me.getX(), dz = z - me.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 2) return;
        float ang = (float) (MathHelper.atan2(dz, dx) * 180 / Math.PI) - 90f;
        float d = MathHelper.wrapDegrees(ang - me.getYaw());
        boolean out = Math.abs(d) > 88;
        int px = x0 + w / 2 + Math.round(MathHelper.clamp(d, -88, 88) / 90f * (w / 2f));
        ctx.drawText(tr, out ? (d < 0 ? "◀" : "▶") : sym, px - 2, y + 3, col, false);
        if (!out && dist < 400) { String s = Math.round(dist) + "m"; ctx.drawText(tr, s, px - tr.getWidth(s) / 2, y + h + 1, (col & 0x00FFFFFF) | 0xC0000000, false); }
    }
}
