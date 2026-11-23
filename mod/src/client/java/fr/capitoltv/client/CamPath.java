package fr.capitoltv.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Travelling enregistré : N pose un point (position + regard), Maj+N efface tout,
 * M lance / arrête la boucle. La trajectoire passe en courbe douce par tous les points.
 */
public final class CamPath {
    private CamPath() { }

    private record Pt(Vec3d p, float yaw, float pitch) { }
    private static final List<Pt> pts = new ArrayList<>();
    private static boolean playing;
    private static double u;
    /** blocs par seconde */
    public static double speed = 3.0;

    public static boolean playing() { return playing; }
    public static int count() { return pts.size(); }

    public static void add(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player; if (p == null) return;
        if (pts.size() >= 24) { Hud.toast("Travelling", "24 points maximum", Hud.ORANGE, 2); return; }
        pts.add(new Pt(p.getPos(), p.getYaw(), p.getPitch()));
        Hud.toast("Travelling", "Point " + pts.size() + " posé" + (pts.size() >= 2 ? " · " + Keys.of(CapitolClient.pathPlayKey) + " pour lancer la boucle" : " · pose-en au moins 2"), Hud.GOLD, 3);
    }
    public static void clear() { stop(null); pts.clear(); Hud.toast("Travelling", "Points effacés", Hud.DIM, 2); }
    public static void stop(String msg) { if (playing && msg != null) Hud.toast("Travelling", msg, Hud.DIM, 2); playing = false; }

    public static void toggle(MinecraftClient mc) {
        if (playing) { stop("Arrêté"); return; }
        if (pts.size() < 2) { Hud.toast("Travelling", "Pose au moins 2 points avec " + Keys.of(CapitolClient.pathAddKey), Hud.ORANGE, 3); return; }
        if (mc.player == null || !mc.player.isSpectator()) { Hud.toast("Travelling", "Réservé aux caméras en spectateur", Hud.ORANGE, 3); return; }
        CamMoves.stop(null); CamDrone.off(mc);
        playing = true; u = 0;
        Hud.toast("Travelling", "Lecture en boucle · " + Keys.of(CapitolClient.pathPlayKey) + " pour arrêter", Hud.GOLD, 3);
    }

    private static Vec3d cr(Vec3d a, Vec3d b, Vec3d c, Vec3d d, double t) {
        double t2 = t * t, t3 = t2 * t;
        return a.multiply(-0.5 * t3 + t2 - 0.5 * t).add(b.multiply(1.5 * t3 - 2.5 * t2 + 1)).add(c.multiply(-1.5 * t3 + 2 * t2 + 0.5 * t)).add(d.multiply(0.5 * t3 - 0.5 * t2));
    }
    private static double crf(double a, double b, double c, double d, double t) {
        double t2 = t * t, t3 = t2 * t;
        return 0.5 * ((2 * b) + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3);
    }

    public static void endTick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (!playing) return;
        if (p == null || !p.isSpectator()) { stop(null); return; }
        int n = pts.size();
        // on avance d'abord, PUIS on prend le tronçon où l'on se trouve
        // (avant : au passage d'un point, on revenait une image sur le point précédent → tremblement)
        int i0 = Math.floorMod((int) Math.floor(u), n);
        double len0 = Math.max(1.5, pts.get(i0).p.distanceTo(pts.get((i0 + 1) % n).p));
        u += speed / 20.0 / len0;
        if (u >= n) u -= n;
        int i = Math.floorMod((int) Math.floor(u), n);
        Pt a = pts.get((i - 1 + n) % n), b = pts.get(i), c = pts.get((i + 1) % n), d = pts.get((i + 2) % n);
        double t = u - Math.floor(u);
        Vec3d at = cr(a.p, b.p, c.p, d.p, t);
        // angles déroulés pour ne pas faire un tour complet entre 179° et -179°
        float by = b.yaw, ay = by + MathHelper.wrapDegrees(a.yaw - by), cy = by + MathHelper.wrapDegrees(c.yaw - by), dy = cy + MathHelper.wrapDegrees(d.yaw - c.yaw);
        float yaw = (float) crf(ay, by, cy, dy, t);
        float pitch = (float) MathHelper.clamp(crf(a.pitch, b.pitch, c.pitch, d.pitch, t), -90, 90);
        p.setVelocity(Vec3d.ZERO);
        p.setPosition(at.x, at.y, at.z);
        p.setYaw(yaw); p.setHeadYaw(yaw); p.setPitch(pitch);
    }

    /* ---------- points visibles pour le caméraman (dessinés sur l'interface : jamais dans le flux) ---------- */
    private static float[] proj(MinecraftClient mc, Vec3d w, int sw, int sh) {
        var cam = mc.gameRenderer.getCamera();
        Vec3d d = w.subtract(cam.getPos());
        Vec3d f = Vec3d.fromPolar(cam.getPitch(), cam.getYaw());
        Vec3d r = f.crossProduct(new Vec3d(0, 1, 0));
        if (r.lengthSquared() < 1e-6) r = new Vec3d(1, 0, 0);
        r = r.normalize();
        Vec3d up = r.crossProduct(f);
        double z = d.dotProduct(f);
        if (z < 0.2) return null;
        double vfov = Math.toRadians(mc.options.getFov().getValue() * CamDrone.fov);
        double th = Math.tan(vfov / 2), tw = th * sw / (double) sh;
        return new float[]{(float) (sw / 2.0 + d.dotProduct(r) / z / tw * sw / 2.0), (float) (sh / 2.0 - d.dotProduct(up) / z / th * sh / 2.0), (float) z};
    }
    public static void renderPoints(DrawContext ctx, TextRenderer tr, int sw, int sh) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (pts.isEmpty() || mc.player == null || !CamMode.active()) return;
        int col = playing ? 0x80F2D27A : 0xFFF2D27A;
        float[] prev = null, first = null;
        // trajet : petits points le long de la courbe
        int n = pts.size();
        if (n >= 2) for (int i = 0; i < n; i++) {
            Pt a = pts.get((i - 1 + n) % n), b = pts.get(i), c = pts.get((i + 1) % n), d = pts.get((i + 2) % n);
            for (int k = 1; k < 8; k++) {
                float[] q = proj(mc, cr(a.p, b.p, c.p, d.p, k / 8.0).add(0, mc.player.getStandingEyeHeight(), 0), sw, sh);
                if (q != null && q[2] < 160) ctx.fill((int) q[0], (int) q[1], (int) q[0] + 1, (int) q[1] + 1, playing ? 0x60F2D27A : 0xB0F2D27A);
            }
        }
        for (int i = 0; i < n; i++) {
            Pt pt = pts.get(i);
            float[] q = proj(mc, pt.p.add(0, mc.player.getStandingEyeHeight(), 0), sw, sh);
            if (q == null || q[2] > 200) continue;
            int x = (int) q[0], y = (int) q[1], r = q[2] < 20 ? 5 : 3;
            ctx.fill(x - r, y - r, x + r + 1, y + r + 1, 0xC0101014);
            ctx.fill(x - r + 1, y - r + 1, x + r, y + r, col);
            // direction du regard enregistrée
            float[] q2 = proj(mc, pt.p.add(0, mc.player.getStandingEyeHeight(), 0).add(Vec3d.fromPolar(pt.pitch, pt.yaw).multiply(1.5)), sw, sh);
            if (q2 != null) { int dx = (int) q2[0] - x, dy = (int) q2[1] - y; int st = Math.max(Math.abs(dx), Math.abs(dy)); for (int s2 = 0; s2 <= st; s2 += 2) ctx.fill(x + dx * s2 / Math.max(1, st), y + dy * s2 / Math.max(1, st), x + dx * s2 / Math.max(1, st) + 1, y + dy * s2 / Math.max(1, st) + 1, col); }
            String lb = String.valueOf(i + 1);
            ctx.drawText(tr, lb, x - tr.getWidth(lb) / 2, y - r - 10, col, true);
        }
    }

    public static void render(DrawContext ctx, TextRenderer tr, int sw) {
        if (!playing) return;
        String s = "◉ TRAVELLING · " + pts.size() + " points · " + Keys.of(CapitolClient.pathPlayKey) + " pour arrêter";
        int w = tr.getWidth(s) + 10;
        ctx.fill((sw - w) / 2, 4, (sw + w) / 2, 17, 0xC0101014);
        ctx.drawText(tr, s, (sw - w) / 2 + 5, 7, 0xFFF2D27A, false);
    }
}
