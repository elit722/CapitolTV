package fr.capitoltv.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

/**
 * Drone cinéma : vol avec inertie (accélère et freine en douceur), roulis (Z / C),
 * molette = vitesse, Ctrl + molette = zoom fluide, X = remet roulis et zoom à zéro.
 * La position est calculée ici et appliquée à chaque tick : aucun coût côté rendu.
 */
public final class CamDrone {
    private CamDrone() { }

    public static boolean on;
    private static Vec3d pos, vel = Vec3d.ZERO;
    public static double speed = 1.0;
    /** roulis et zoom voulus (le rendu les rejoint en douceur, voir CameraMixin / GameRendererMixin) */
    public static float rollTarget, roll, fovTarget = 1f, fov = 1f;
    private static boolean smoothWas;
    private static final float SENTINEL = 0.05f;

    public static void toggle(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null) return;
        if (!on && !p.isSpectator()) { Hud.toast("Drone", "Réservé aux caméras en spectateur", Hud.ORANGE, 3); return; }
        on = !on;
        if (on) {
            CamMoves.stop(null); CamPath.stop(null);
            pos = p.getPos(); vel = Vec3d.ZERO;
            smoothWas = mc.options.smoothCameraEnabled; mc.options.smoothCameraEnabled = true;
            p.getAbilities().setFlySpeed(SENTINEL);
            Hud.toast("Drone cinéma", "déplacements : vol · Z/C : roulis · molette : vitesse · Ctrl+molette : zoom · X : reset · " + Keys.of(CapitolClient.droneKey) + " : quitter", Hud.GOLD, 6);
        } else off(mc);
    }

    public static void off(MinecraftClient mc) {
        if (!on && rollTarget == 0 && fovTarget == 1f) return;
        on = false; rollTarget = 0; fovTarget = 1f;
        mc.options.smoothCameraEnabled = smoothWas;
        if (mc.player != null) mc.player.getAbilities().setFlySpeed(SENTINEL);
    }

    private static boolean key(MinecraftClient mc, int k) { return InputUtil.isKeyPressed(mc.getWindow().getHandle(), k); }

    public static void endTick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (!on) return;
        if (p == null || !p.isSpectator()) { off(mc); return; }
        // molette : en spectateur, Minecraft change la vitesse de vol ; on la lit puis on la remet
        float fs = p.getAbilities().getFlySpeed();
        if (Math.abs(fs - SENTINEL) > 1e-4) {
            boolean up = fs > SENTINEL;
            if (key(mc, GLFW.GLFW_KEY_LEFT_CONTROL) || key(mc, GLFW.GLFW_KEY_RIGHT_CONTROL)) fovTarget = MathHelper.clamp(fovTarget * (up ? 0.88f : 1.12f), 0.15f, 1.3f);
            else speed = MathHelper.clamp(speed * (up ? 1.2 : 0.83), 0.15, 6);
            p.getAbilities().setFlySpeed(SENTINEL);
        }
        boolean free = mc.currentScreen == null;
        if (free) {
            if (key(mc, GLFW.GLFW_KEY_Z) && !mc.options.forwardKey.matchesKey(GLFW.GLFW_KEY_Z, 0)) rollTarget = MathHelper.clamp(rollTarget - 1.5f, -45, 45);
            if (key(mc, GLFW.GLFW_KEY_C)) rollTarget = MathHelper.clamp(rollTarget + 1.5f, -45, 45);
            if (key(mc, GLFW.GLFW_KEY_X)) { rollTarget = 0; fovTarget = 1f; }
        }
        GameOptions o = mc.options;
        double f = 0, s = 0, u = 0;
        if (free) {
            if (o.forwardKey.isPressed()) f++;
            if (o.backKey.isPressed()) f--;
            if (o.leftKey.isPressed()) s++;
            if (o.rightKey.isPressed()) s--;
            if (o.jumpKey.isPressed()) u++;
            if (o.sneakKey.isPressed()) u--;
        }
        Vec3d look = Vec3d.fromPolar(p.getPitch(), p.getYaw());
        Vec3d side = Vec3d.fromPolar(0, p.getYaw() - 90);
        Vec3d acc = look.multiply(f).add(side.multiply(s)).add(0, u, 0);
        if (acc.lengthSquared() > 1) acc = acc.normalize();
        // inertie : accélération douce et freinage progressif
        vel = vel.multiply(0.86).add(acc.multiply(0.09 * speed));
        if (pos == null) pos = p.getPos();
        pos = pos.add(vel);
        p.setVelocity(Vec3d.ZERO);
        p.setPosition(pos.x, pos.y, pos.z);
    }

    /** appelé à chaque image : rejoint le roulis et le zoom voulus */
    public static void frame(float dt) {
        float k = Math.min(1f, dt * 6f);
        roll += (rollTarget - roll) * k;
        fov += (fovTarget - fov) * k;
        if (Math.abs(roll) < 0.01f && rollTarget == 0) roll = 0;
        if (Math.abs(fov - 1f) < 0.001f && fovTarget == 1f) fov = 1f;
    }

    public static void render(DrawContext ctx, TextRenderer tr, int sw) {
        if (!on) return;
        String s = "◉ DRONE · vitesse x" + String.format("%.1f", speed) + (fovTarget != 1f ? " · zoom x" + String.format("%.1f", 1 / fovTarget) : "") + (Math.abs(rollTarget) > 0.5 ? " · roulis " + Math.round(rollTarget) + "°" : "") + " · " + Keys.of(CapitolClient.droneKey) + " pour quitter";
        int w = tr.getWidth(s) + 10;
        ctx.fill((sw - w) / 2, 4, (sw + w) / 2, 17, 0xC0101014);
        ctx.drawText(tr, s, (sw - w) / 2 + 5, 7, 0xFF8AD8FF, false);
    }
}
