package fr.capitoltv.client;

import fr.capitoltv.CapitolTV;
import fr.capitoltv.client.gui.CapitolScreen;
import fr.capitoltv.net.SpecPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.Identifier;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * Mode caméra : l'image envoyée dans le stream reste propre.
 *  - le HUD du jeu (barre d'objets, viseur, chat, F3…) est masqué ;
 *  - une barre d'outils caméra remplace la barre du spectateur (touches 1 à 9) ;
 *  - quand la régie affiche cette caméra, plus rien ne s'affiche (maintenir Alt gauche pour voir l'interface).
 */
public final class CamMode {
    private CamMode() { }

    private static int baseFov = -1;
    private static int zoom;
    private static final int[] ZOOMS = {0, 50, 30};
    private static int specTry;

    public static boolean active() { return ClientConfig.get().cameraMode; }
    public static boolean peek() { return CapitolClient.peekKey != null && CapitolClient.peekKey.isPressed(); }

    /** Plus rien à l'écran (sauf le voyant d'antenne) : la caméra est à l'antenne ou en mode propre. */
    public static boolean clean() {
        ClientConfig c = ClientConfig.get();
        if (!c.cameraMode || peek()) return false;
        return c.cleanAlways || (c.cleanOnAir && Data.onAir());
    }

    /** Masque tous les éléments du HUD vanilla quand le mode caméra est actif. */
    public static void install() {
        int n = 0;
        for (Field f : VanillaHudElements.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != Identifier.class) continue;
            try {
                Identifier id = (Identifier) f.get(null);
                HudElementRegistry.replaceElement(id, old -> (HudElement) (ctx, tc) -> { if (!active()) old.render(ctx, tc); });
                n++;
            } catch (Throwable t) {
                CapitolTV.LOG.debug("Capitol TV : élément HUD {} non remplacé ({})", f.getName(), t.toString());
            }
        }
        CapitolTV.LOG.info("Capitol TV : mode caméra prêt ({} éléments du HUD gérés)", n);
    }

    /* ---------- barre d'outils (touches 1 à 9) ---------- */
    public static String[] labels() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientConfig c = ClientConfig.get();
        return new String[]{
                "TP suivi",
                "◀ Tribut",
                "Tribut ▶",
                "Signal",
                "rec".equals(c.state) ? "REC ●" : "Actif",
                mc.options.smoothCameraEnabled ? "Fluide ✔" : "Fluide",
                zoom == 0 ? "Zoom" : "Zoom x" + zoom,
                "Menu",
                c.cleanAlways ? "Propre ✔" : "Propre"
        };
    }

    public static void slot(int i) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientConfig c = ClientConfig.get();
        switch (i) {
            case 0 -> {
                Data.Trib t = Data.tribById(CapitolClient.follow);
                Hud.toast("Téléportation", t == null ? "Aucun tribut suivi (touches 2 / 3)" : Teleport.toTribute(t), Hud.BLUE, 3);
            }
            case 1, 2 -> {
                List<Data.Trib> l = Data.aliveTribs();
                if (l.isEmpty()) { Hud.toast("Tributs", "Aucun tribut en vie", Hud.DIM, 3); return; }
                int idx = -1;
                for (int k = 0; k < l.size(); k++) if (l.get(k).id.equals(CapitolClient.follow)) idx = k;
                idx = ((idx + (i == 2 ? 1 : -1)) % l.size() + l.size()) % l.size();
                Data.Trib t = l.get(idx);
                CapitolClient.setFollow(t.id);
                Hud.toast("Suivi : " + Data.tribLabel(t.id), Teleport.toTribute(t), Hud.GOLD, 3);
            }
            case 3 -> CapitolClient.quickSignal();
            case 4 -> {
                CapitolClient.setState("rec".equals(c.state) ? "actif" : "rec");
                Hud.toast("État", Hud.stateLabel(c.state), Hud.stateColor(c.state), 2);
            }
            case 5 -> {
                mc.options.smoothCameraEnabled = !mc.options.smoothCameraEnabled;
                Hud.toast("Caméra fluide", mc.options.smoothCameraEnabled ? "activée (mouvements cinématiques)" : "désactivée", Hud.BLUE, 2);
            }
            case 6 -> {
                if (baseFov < 0) baseFov = mc.options.getFov().getValue();
                zoom = (zoom + 1) % ZOOMS.length;
                int fov = zoom == 0 ? baseFov : ZOOMS[zoom];
                mc.options.getFov().setValue(fov);
                Hud.toast("Zoom", zoom == 0 ? "normal (" + fov + "°)" : "champ de vision " + fov + "°", Hud.BLUE, 2);
            }
            case 7 -> mc.setScreen(new CapitolScreen());
            case 8 -> {
                c.cleanAlways = !c.cleanAlways;
                ClientConfig.save();
                Hud.toast("Flux propre", c.cleanAlways ? "tout est masqué (maintiens Alt gauche pour voir)" : "désactivé", Hud.GOLD, 3);
            }
            default -> { }
        }
    }

    /** Appelé au tout début de chaque tick, avant que le jeu ne lise les touches 1 à 9. */
    public static void startTick(MinecraftClient mc) {
        if (!active() || mc.player == null || mc.currentScreen != null) return;
        KeyBinding[] keys = mc.options.hotbarKeys;
        for (int i = 0; i < keys.length && i < 9; i++) while (keys[i].wasPressed()) slot(i);
    }

    /** Passage automatique en spectateur pour les caméras. */
    public static void ensureSpectator(MinecraftClient mc, boolean force) {
        if (mc.player == null || mc.getNetworkHandler() == null || !ClientConfig.get().autoSpectator) return;
        if (Data.meCam.isEmpty() || mc.player.isSpectator()) { specTry = 0; return; }
        if (!force && specTry >= 2) return;
        specTry++;
        if (ClientPlayNetworking.canSend(SpecPayload.ID)) ClientPlayNetworking.send(new SpecPayload(true));
        else mc.getNetworkHandler().sendChatCommand("gamemode spectator");
    }

    public static void resetSpectatorTries() { specTry = 0; }

    /* ---------- rendu ---------- */
    public static void renderToolbar(DrawContext ctx, TextRenderer tr, int sw, int sh) {
        String[] l = labels();
        int n = l.length, slotW = Math.min(52, (sw - 20) / n), h = 24, x0 = (sw - slotW * n) / 2, y = sh - h - 4;
        ctx.fill(x0 - 3, y - 3, x0 + slotW * n + 3, y + h + 3, 0xB0101014);
        for (int i = 0; i < n; i++) {
            int x = x0 + i * slotW;
            boolean on = (i == 4 && "rec".equals(ClientConfig.get().state)) || (i == 5 && MinecraftClient.getInstance().options.smoothCameraEnabled)
                    || (i == 6 && zoom != 0) || (i == 8 && ClientConfig.get().cleanAlways);
            ctx.fill(x + 1, y, x + slotW - 1, y + h, on ? 0xC0352B12 : 0xC01A1A20);
            ctx.fill(x + 1, y, x + slotW - 1, y + 1, on ? Hud.GOLD : 0xFF3A3630);
            ctx.drawText(tr, String.valueOf(i + 1), x + 4, y + 3, Hud.DIM, false);
            String s = tr.trimToWidth(l[i], slotW - 6);
            ctx.drawText(tr, s, x + (slotW - tr.getWidth(s)) / 2, y + 13, i == 4 && on ? Hud.RED : on ? Hud.GOLD : Hud.INK, false);
        }
    }

    public static void renderTally(DrawContext ctx, TextRenderer tr, int sw) {
        String mode = ClientConfig.get().tally;
        if (!Data.onAir() || "cache".equals(mode)) return;
        boolean blink = (System.currentTimeMillis() / 600) % 2 == 0;
        if ("discret".equals(mode)) {
            ctx.fill(sw - 7, 3, sw - 3, 7, blink ? 0xFFE0514A : 0xFF8A2A26);
            return;
        }
        String s = "● EN DIRECT";
        int w = tr.getWidth(s) + 10;
        ctx.fill(sw - w - 4, 4, sw - 4, 18, 0xE0B0182C);
        ctx.drawText(tr, s, sw - w + 1, 7, blink ? 0xFFFFFFFF : 0xFFFFC9CF, false);
    }
}
