package fr.capitoltv.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Mouvements de caméra préprogrammés et fluides (orbite, travelling, grue…),
 * lancés en une touche : V ouvre la liste, puis 1 à 8. V arrête le mouvement en cours.
 */
public final class CamMoves {
    private CamMoves() { }

    public static final String[] NAMES = {"Orbite", "Orbite large", "Travelling avant", "Grue", "Suivi auto", "Vue du ciel", "Panoramique", "Regard verrouillé"};
    public static final String[] HINTS = {"tourne autour du tribut suivi", "grand cercle en hauteur", "s'approche lentement du tribut", "s'élève en le regardant", "change d'angle tout seul, évite les murs", "plongée au-dessus, en rotation", "tour complet sur place", "regarde le tribut, tu bouges librement"};

    public static boolean picker;
    private static int move = -1, t, wait;
    private static Vec3d start, fixedTarget, cur;
    private static float startYaw;
    private static double angle0;
    public static double speed = 1.0;

    public static boolean running() { return move >= 0; }
    public static String current() { return move >= 0 ? NAMES[move] : ""; }

    public static void togglePicker() {
        if (move >= 0) { stop("Mouvement arrêté"); return; }
        picker = !picker;
    }

    public static void stop(String msg) {
        if (move >= 0 && msg != null) Hud.toast("Caméra", msg, Hud.DIM, 2);
        move = -1; picker = false;
    }

    /** Touche 1 à 8 pendant que la liste est ouverte. Renvoie true si la touche a servi. */
    public static boolean startTick(MinecraftClient mc) {
        if (!picker || mc.player == null || mc.currentScreen != null) return false;
        KeyBinding[] keys = mc.options.hotbarKeys;
        for (int i = 0; i < 8 && i < keys.length; i++) {
            if (keys[i].wasPressed()) { while (keys[i].wasPressed()) { } begin(mc, i); return true; }
        }
        if (keys.length > 8) while (keys[8].wasPressed()) picker = false;
        return true;
    }

    private static void begin(MinecraftClient mc, int i) {
        picker = false;
        ClientPlayerEntity p = mc.player;
        if (p == null) return;
        CamDrone.off(mc); CamPath.stop(null);
        Vec3d tg = target(mc);
        if (tg == null && i != 6) {
            // pas de tribut suivi : on vise le point regardé à 10 blocs
            fixedTarget = p.getEyePos().add(p.getRotationVec(1f).multiply(10));
        } else fixedTarget = null;
        if (tg != null && tg.distanceTo(p.getPos()) > 48 && i != 7) {
            Data.Trib tr = Data.tribById(CapitolClient.follow);
            if (tr != null) { Teleport.toTribute(tr); wait = 12; }
        } else wait = 0;
        move = i; t = 0;
        start = p.getPos(); cur = p.getPos(); startYaw = p.getYaw();
        Vec3d c = tg != null ? tg : fixedTarget;
        angle0 = c == null ? 0 : Math.atan2(p.getZ() - c.z, p.getX() - c.x);
        Hud.toast("Caméra : " + NAMES[i], HINTS[i] + " · " + Keys.of(CapitolClient.movesKey) + " pour arrêter", Hud.GOLD, 3);
    }

    /** position (yeux) du tribut suivi : joueur visible dans le monde, sinon dernière position connue */
    private static Vec3d target(MinecraftClient mc) {
        Data.Trib tr = Data.tribById(CapitolClient.follow);
        if (tr == null || mc.world == null) return null;
        for (PlayerEntity e : mc.world.getPlayers())
            if (!tr.mc.isEmpty() && e.getGameProfile().getName().equalsIgnoreCase(tr.mc)) return e.getLerpedPos(1f).add(0, e.getStandingEyeHeight(), 0);
        Data.Pos q = Data.pos.get(tr.id);
        return q == null ? null : new Vec3d(q.x, q.y + 1.6, q.z);
    }

    private static boolean moveKeys(GameOptions o) {
        return o.forwardKey.isPressed() || o.backKey.isPressed() || o.leftKey.isPressed() || o.rightKey.isPressed() || o.jumpKey.isPressed() || o.sneakKey.isPressed();
    }

    private static double ease(double x) { x = MathHelper.clamp(x, 0, 1); return x * x * (3 - 2 * x); }

    /** Fin de tick : place la caméra. */
    public static void endTick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (move < 0 || p == null) return;
        if (wait > 0) { wait--; start = p.getPos(); cur = p.getPos(); return; }
        if (move != 7 && mc.currentScreen == null && moveKeys(mc.options) && t > 5) { stop("Mouvement arrêté (tu as bougé)"); return; }
        t++;
        double s = t / 20.0 * speed;
        Vec3d tg = target(mc);
        if (tg == null) tg = fixedTarget;
        Vec3d want = cur;
        Vec3d look = tg;
        switch (move) {
            case 0, 1 -> {
                if (tg == null) { stop("Aucun tribut suivi"); return; }
                double r = move == 0 ? 7 : 16, h = move == 0 ? 2.2 : 7, period = move == 0 ? 24 : 40;
                double a = angle0 + s / period * Math.PI * 2;
                want = new Vec3d(tg.x + Math.cos(a) * r, tg.y + h - 1.6, tg.z + Math.sin(a) * r);
            }
            case 2 -> {
                if (tg == null) { stop("Aucun tribut suivi"); return; }
                double k = ease(s / 7.0);
                Vec3d dir = start.subtract(tg).normalize();
                Vec3d end = tg.add(dir.multiply(3.5)).add(0, -1.2, 0);
                want = start.lerp(end, k);
                if (s > 9) { stop(null); return; }
            }
            case 3 -> {
                double k = ease(s / 9.0);
                want = start.add(0, 14 * k, 0);
                if (s > 11) { stop(null); return; }
            }
            case 4 -> {
                if (tg == null) { stop("Aucun tribut suivi"); return; }
                PlayerEntity te = null;
                Data.Trib tr = Data.tribById(CapitolClient.follow);
                for (PlayerEntity e : mc.world.getPlayers()) if (tr != null && e.getGameProfile().getName().equalsIgnoreCase(tr.mc)) te = e;
                float yaw = te != null ? te.getBodyYaw() : p.getYaw();
                // angle qui change toutes les 8 s : de dos, de côté, en plongée
                int ph = (int) (s / 8) % 3;
                boolean cave = !mc.world.isSkyVisible(net.minecraft.util.math.BlockPos.ofFloored(tg));
                double k0 = cave ? 0.55 : 1.0;
                Vec3d off = switch (ph) {
                    case 0 -> Vec3d.fromPolar(0, yaw).multiply(-6 * k0).add(0, 1.2 * k0, 0);
                    case 1 -> Vec3d.fromPolar(0, yaw + 90).multiply(5 * k0).add(0, 0.6, 0);
                    default -> Vec3d.fromPolar(0, yaw).multiply(-5 * k0).add(0, (cave ? 2.5 : 7), 0);
                };
                Vec3d w0 = tg.add(off);
                // pas de caméra dans un mur : on s'arrête juste avant l'obstacle
                var hit = mc.world.raycast(new net.minecraft.world.RaycastContext(tg, w0, net.minecraft.world.RaycastContext.ShapeType.COLLIDER, net.minecraft.world.RaycastContext.FluidHandling.NONE, p));
                if (hit.getType() != net.minecraft.util.hit.HitResult.Type.MISS) w0 = hit.getPos().add(tg.subtract(hit.getPos()).normalize().multiply(0.6));
                want = w0.add(0, -p.getStandingEyeHeight(), 0);
                look = tg.add(Vec3d.fromPolar(0, yaw).multiply(2));
            }
            case 5 -> {
                if (tg == null) { stop("Aucun tribut suivi"); return; }
                double a = angle0 + s / 60 * Math.PI * 2;
                want = new Vec3d(tg.x + Math.cos(a) * 6, tg.y + 20, tg.z + Math.sin(a) * 6);
            }
            case 6 -> {
                float yaw = startYaw + (float) (s / 20.0 * 360);
                p.setYaw(yaw); p.setPitch(MathHelper.clamp(p.getPitch(), -20, 30));
                if (s > 20) stop(null);
                return;
            }
            case 7 -> {
                if (tg == null) { stop("Aucun tribut suivi"); return; }
                aim(p, p.getEyePos(), tg);
                return;
            }
            default -> { }
        }
        // lissage : la caméra glisse vers sa position voulue
        double k = move == 4 ? 0.12 : move == 2 || move == 3 ? 1.0 : 0.25;
        cur = cur.lerp(want, k);
        CamMode.moveTo(p, cur);
        cur = p.getPos(); // bloqué par un mur : on repart de là où l'on est vraiment
        if (look != null) aim(p, cur.add(0, p.getStandingEyeHeight(), 0), look);
    }

    private static void aim(ClientPlayerEntity p, Vec3d eye, Vec3d at) {
        Vec3d d = at.subtract(eye);
        double flat = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) (MathHelper.atan2(d.z, d.x) * 180 / Math.PI) - 90f;
        float pitch = (float) -(MathHelper.atan2(d.y, flat) * 180 / Math.PI);
        float cy = p.getYaw();
        float ny = cy + MathHelper.wrapDegrees(yaw - cy) * 0.35f;
        float np = p.getPitch() + (pitch - p.getPitch()) * 0.35f;
        p.setYaw(ny); p.setPitch(MathHelper.clamp(np, -90, 90));
        p.setHeadYaw(ny);
    }

    public static void render(DrawContext ctx, TextRenderer tr, int sw, int sh) {
        if (picker) {
            int w = Math.min(sw - 20, 300), h = 16 + NAMES.length * 12 + 8, x = (sw - w) / 2, y = 26;
            fr.capitoltv.client.gui.Ui.box(ctx, x, y, x + w, y + h, 0xE00E0E12, 0xFFD4AF37);
            ctx.drawText(tr, "MOUVEMENTS DE CAMÉRA", x + 8, y + 5, 0xFFD4AF37, false);
            for (int i = 0; i < NAMES.length; i++) {
                int yy = y + 18 + i * 12;
                ctx.drawText(tr, String.valueOf(i + 1), x + 8, yy, 0xFFF2D27A, false);
                ctx.drawText(tr, NAMES[i], x + 20, yy, 0xFFEDE6D6, false);
                ctx.drawText(tr, tr.trimToWidth(HINTS[i], w - 124), x + 118, yy, 0xFF8A8478, false);
            }
        } else if (move >= 0) {
            String s = "◉ " + NAMES[move] + " · " + Keys.of(CapitolClient.movesKey) + " pour arrêter";
            int w = tr.getWidth(s) + 10;
            ctx.fill((sw - w) / 2, 4, (sw + w) / 2, 17, 0xC0101014);
            ctx.drawText(tr, s, (sw - w) / 2 + 5, 7, 0xFFF2D27A, false);
        }
    }
}
