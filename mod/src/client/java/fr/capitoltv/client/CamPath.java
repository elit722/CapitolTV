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
    /** blocs par seconde */
    public static double speed = 3.0;

    public static boolean playing() { return playing; }
    public static int count() { return pts.size(); }

    public static void add(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player; if (p == null) return;
        if (pts.size() >= 24) { Hud.toast("Travelling", "24 points maximum", Hud.ORANGE, 2); return; }
        pts.add(new Pt(p.getPos(), p.getYaw(), p.getPitch()));
        if (playing) { double f = total > 0 ? s / total : 0; build(); s = f * total; }
        Hud.toast("Travelling", "Point " + pts.size() + " posé" + (pts.size() >= 2 ? " · " + Keys.of(CapitolClient.pathPlayKey) + " pour lancer la boucle" : " · pose-en au moins 2"), Hud.GOLD, 3);
    }
    public static void clear() { stop(null); pts.clear(); Hud.toast("Travelling", "Points effacés", Hud.DIM, 2); }
    public static void stop(String msg) { if (playing && msg != null) Hud.toast("Travelling", msg, Hud.DIM, 2); playing = false; }

    public static void toggle(MinecraftClient mc) {
        if (playing) { stop("Arrêté"); return; }
        if (pts.size() < 2) { Hud.toast("Travelling", "Pose au moins 2 points avec " + Keys.of(CapitolClient.pathAddKey), Hud.ORANGE, 3); return; }
        if (mc.player == null || !mc.player.isSpectator()) { Hud.toast("Travelling", "Réservé aux caméras en spectateur", Hud.ORANGE, 3); return; }
        CamMoves.stop(null); CamDrone.off(mc);
        build(); s = 0; playing = true;
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

    /* ---------- trajectoire échantillonnée à vitesse constante ----------
     * La courbe est découpée en petits morceaux, mesurés en longueur (plus les rotations de regard, pour qu'un
     * panoramique sur place avance aussi). La caméra avance d'une distance fixe par seconde réelle, à chaque
     * image (CameraMixin) : plus d'à-coups aux passages de points ni de saccades liées aux ticks du jeu. */
    private static final int K = 32;
    private static double[] sx, sy, sz, cum; private static float[] syaw, spitch;
    private static double s, total;

    private static void build() {
        int n = pts.size(), m = n * K + 1;
        sx = new double[m]; sy = new double[m]; sz = new double[m]; cum = new double[m]; syaw = new float[m]; spitch = new float[m];
        float prevYaw = pts.get(0).yaw;
        for (int i = 0; i < n; i++) {
            Pt a = pts.get((i - 1 + n) % n), b = pts.get(i), c = pts.get((i + 1) % n), d = pts.get((i + 2) % n);
            float by = b.yaw, ay = by + MathHelper.wrapDegrees(a.yaw - by), cy = by + MathHelper.wrapDegrees(c.yaw - by), dy = cy + MathHelper.wrapDegrees(d.yaw - c.yaw);
            for (int k = 0; k < K; k++) {
                double t = k / (double) K; int j = i * K + k;
                Vec3d q = cr(a.p, b.p, c.p, d.p, t);
                sx[j] = q.x; sy[j] = q.y; sz[j] = q.z;
                float raw = (float) crf(ay, by, cy, dy, t);
                prevYaw = j == 0 ? raw : prevYaw + MathHelper.wrapDegrees(raw - prevYaw);
                syaw[j] = prevYaw;
                spitch[j] = (float) MathHelper.clamp(crf(a.pitch, b.pitch, c.pitch, d.pitch, t), -90, 90);
            }
        }
        int L = m - 1; // dernier échantillon = retour au premier (boucle)
        sx[L] = sx[0]; sy[L] = sy[0]; sz[L] = sz[0]; spitch[L] = spitch[0];
        syaw[L] = syaw[L - 1] + MathHelper.wrapDegrees(syaw[0] - syaw[L - 1]);
        cum[0] = 0;
        for (int j = 1; j < m; j++) {
            double dx = sx[j] - sx[j - 1], dy = sy[j] - sy[j - 1], dz = sz[j] - sz[j - 1];
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double ang = Math.abs(syaw[j] - syaw[j - 1]) + Math.abs(spitch[j] - spitch[j - 1]);
            cum[j] = cum[j - 1] + Math.max(dist, ang / 40.0) + 1e-4;   // 40° de rotation ≈ 1 bloc
        }
        total = cum[m - 1];
    }

    /** position (pieds), yaw, pitch à l'abscisse donnée */
    private static double[] at(double d) {
        if (total <= 0) return null;
        d = d % total; if (d < 0) d += total;
        int lo = 0, hi = cum.length - 1;
        while (hi - lo > 1) { int mid = (lo + hi) >>> 1; if (cum[mid] <= d) lo = mid; else hi = mid; }
        double f = (d - cum[lo]) / Math.max(1e-9, cum[hi] - cum[lo]);
        // lissage léger entre échantillons (les morceaux sont déjà très petits)
        return new double[]{ sx[lo] + (sx[hi] - sx[lo]) * f, sy[lo] + (sy[hi] - sy[lo]) * f, sz[lo] + (sz[hi] - sz[lo]) * f,
                syaw[lo] + (syaw[hi] - syaw[lo]) * f, spitch[lo] + (spitch[hi] - spitch[lo]) * f };
    }

    /** appelé à chaque image par CameraMixin : avance et renvoie la pose de la caméra (œil), ou null */
    public static double[] frame(float dt) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!playing || mc.player == null || cum == null) return null;
        if (!mc.isPaused()) s += speed * dt;
        double[] q = at(s); if (q == null) return null;
        q[1] += mc.player.getStandingEyeHeight();
        return q;
    }

    public static void endTick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (!playing) return;
        if (p == null || !p.isSpectator()) { stop(null); return; }
        double[] q = at(s); if (q == null) return;
        // l'entité suit la caméra (chargement des chunks, position vue par le serveur et les autres)
        p.setVelocity(Vec3d.ZERO);
        p.setPosition(q[0], q[1], q[2]);
        float yaw = (float) q[3], pitch = (float) q[4];
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
