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
        Hud.toast("Travelling", "Point " + pts.size() + " posé" + (pts.size() >= 2 ? " · M pour lancer la boucle" : " · pose-en au moins 2"), Hud.GOLD, 3);
    }
    public static void clear() { stop(null); pts.clear(); Hud.toast("Travelling", "Points effacés", Hud.DIM, 2); }
    public static void stop(String msg) { if (playing && msg != null) Hud.toast("Travelling", msg, Hud.DIM, 2); playing = false; }

    public static void toggle(MinecraftClient mc) {
        if (playing) { stop("Arrêté"); return; }
        if (pts.size() < 2) { Hud.toast("Travelling", "Pose au moins 2 points avec N", Hud.ORANGE, 3); return; }
        if (mc.player == null || !mc.player.isSpectator()) { Hud.toast("Travelling", "Réservé aux caméras en spectateur", Hud.ORANGE, 3); return; }
        CamMoves.stop(null); CamDrone.off(mc);
        playing = true; u = 0;
        Hud.toast("Travelling", "Lecture en boucle · M pour arrêter", Hud.GOLD, 3);
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
        int i = (int) Math.floor(u) % n;
        Pt a = pts.get((i - 1 + n) % n), b = pts.get(i), c = pts.get((i + 1) % n), d = pts.get((i + 2) % n);
        double len = Math.max(1.5, b.p.distanceTo(c.p));
        u += speed / 20.0 / len;
        if (u >= n) u -= n;
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

    public static void render(DrawContext ctx, TextRenderer tr, int sw) {
        if (!playing) return;
        String s = "◉ TRAVELLING · " + pts.size() + " points · M pour arrêter";
        int w = tr.getWidth(s) + 10;
        ctx.fill((sw - w) / 2, 4, (sw + w) / 2, 17, 0xC0101014);
        ctx.drawText(tr, s, (sw - w) / 2 + 5, 7, 0xFFF2D27A, false);
    }
}
