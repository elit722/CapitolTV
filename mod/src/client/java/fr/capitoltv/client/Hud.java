package fr.capitoltv.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** Encart permanent (état, tribut suivi, différé, demande en cours) + notifications. */
public final class Hud {
    private Hud() { }

    public static final int GOLD = 0xFFD4AF37, INK = 0xFFEDE6D6, DIM = 0xFF9A9284, RED = 0xFFE0514A, GREEN = 0xFF5FBF6A,
            BLUE = 0xFF5AA0E6, ORANGE = 0xFFE89A3C, PURPLE = 0xFFB07CE8, BG = 0xD0101014, BG2 = 0xE0181820;

    private static final class Toast { String title, text; int color; long until; }
    private static final List<Toast> toasts = new ArrayList<>();

    public static void toast(String title, String text, int color, int seconds) {
        if (!ClientConfig.get().toasts) return;
        Toast t = new Toast();
        t.title = title; t.text = text == null ? "" : text; t.color = color;
        t.until = System.currentTimeMillis() + seconds * 1000L;
        synchronized (toasts) {
            toasts.add(0, t);
            while (toasts.size() > 5) toasts.remove(toasts.size() - 1);
        }
    }

    public static void sound(boolean strong) {
        if (!ClientConfig.get().sound) return;
        // à l'antenne, les sons du jeu partent dans le stream : on se tait
        if ((Data.onAir() || Data.onPrep()) && ClientConfig.get().cleanOnAir && CamMode.active()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.getSoundManager().play(PositionedSoundInstance.master(strong ? SoundEvents.BLOCK_NOTE_BLOCK_BELL : SoundEvents.BLOCK_NOTE_BLOCK_PLING, strong ? 1.2f : 1.6f));
    }

    public static int stateColor(String st) {
        return switch (st == null ? "" : st) {
            case "rec" -> RED;
            case "pause" -> ORANGE;
            case "inactif" -> DIM;
            default -> GREEN;
        };
    }

    public static String stateLabel(String st) {
        return switch (st == null ? "" : st) {
            case "rec" -> "EN REC";
            case "pause" -> "PAUSE";
            case "inactif" -> "INACTIF";
            default -> "ACTIF";
        };
    }

    /* ---------- zones interdites aux caméras ---------- */
    private static Data.Zone inZone;
    private static boolean nearZone;
    static void zoneTick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null || Data.zones.isEmpty() || Data.meCam.isEmpty() && !CamMode.allowed()) { inZone = null; return; }
        String dim = mc.world.getRegistryKey().getValue().toString();
        double x = mc.player.getX(), z = mc.player.getZ();
        Data.Zone hit = null; boolean near = false;
        for (Data.Zone q : Data.zones) {
            if (!q.d.isEmpty() && !q.d.equals(dim)) continue;
            if (x >= q.x1 && x <= q.x2 && z >= q.z1 && z <= q.z2) hit = q;
            else if (x >= q.x1 - 12 && x <= q.x2 + 12 && z >= q.z1 - 12 && z <= q.z2 + 12) near = true;
        }
        if (hit != null && inZone != hit) { toast("Zone interdite", "« " + hit.name + " » : la régie ne veut pas d'images ici. Ressors de la zone.", RED, 6); sound(true); }
        inZone = hit; nearZone = near;
    }

    public static void render(DrawContext ctx, RenderTickCounter tc) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden || mc.currentScreen instanceof fr.capitoltv.client.gui.CapitolScreen) return;
        TextRenderer tr = mc.textRenderer;
        ClientConfig c = ClientConfig.get();
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        if (mc.currentScreen == null) Overlays.renderRecap(ctx, sw, sh);
        CamDrone.render(ctx, tr, sw);
        CamPath.render(ctx, tr, sw);
        if (inZone != null || nearZone) {
            String s = inZone != null ? "⛔ ZONE INTERDITE · " + inZone.name : "⚠ Zone interdite toute proche";
            int w = tr.getWidth(s) + 14, x = (sw - w) / 2, y = sh / 2 - 40;
            boolean blink = inZone != null && (System.currentTimeMillis() / 400) % 2 == 0;
            ctx.fill(x, y, x + w, y + 15, inZone != null ? (blink ? 0xF0B0182C : 0xE0801020) : 0xB0503010);
            ctx.drawText(tr, s, x + 7, y + 4, 0xFFFFFFFF, false);
        }
        CamMode.renderTally(ctx, tr, sw);
        CamMoves.render(ctx, tr, sw, sh);
        if (Talk.open) {
            String s = Talk.speaking ? "● TU PARLES À LA RÉGIE" : Talk.regieSpeaks ? "◉ LA RÉGIE PARLE" + (Talk.regieName.isEmpty() ? "" : " · " + Talk.regieName) : "INTERPHONE · " + CapitolClient.talkKey.getBoundKeyLocalizedText().getString() + " pour parler";
            int w = tr.getWidth(s) + 12, x = (sw - w) / 2, y = sh - 74;
            ctx.fill(x, y, x + w, y + 14, Talk.speaking ? 0xE0B0182C : Talk.regieSpeaks ? 0xE0806010 : 0xB0101014);
            ctx.drawText(tr, s, x + 6, y + 3, 0xFFFFFFFF, false);
        }
        if (Streamer.live() && ClientConfig.get().streamOn) { String s = "● DIFFUSION"; int w = tr.getWidth(s) + 8; ctx.fill(sw - w - 4, sh - 16, sw - 4, sh - 4, 0xA0101014); ctx.drawText(tr, s, sw - w, sh - 14, 0xFFE0514A, false); }
        if (CamMode.clean()) return;
        if (CamMode.active() && CapitolClient.online()) CamMode.renderToolbar(ctx, tr, sw, sh);
        if (!CamMoves.picker) Radar.render(ctx, tr, sw);
        if (!Data.meCam.isEmpty() && mc.player != null && CapitolClient.online()) {
            boolean sp = mc.player.isSpectator();
            String s = sp ? "SPECTATEUR" : "PAS EN SPECTATEUR";
            int w = tr.getWidth(s) + 8, y = Data.onAir() && !"cache".equals(c.tally) ? 21 : 4;
            if (!(sp && c.hudPos.equals("tr"))) {
                ctx.fill(sw - w - 4, y, sw - 4, y + 12, sp ? 0xA0101014 : 0xE0503010);
                ctx.drawText(tr, s, sw - w, y + 2, sp ? DIM : ORANGE, false);
            }
        }

        if (c.hud) {
            List<String> lines = new ArrayList<>();
            List<Integer> colors = new ArrayList<>();
            String head;
            int headColor;
            if (!CapitolClient.online()) {
                head = c.token.isEmpty() ? "CAPITOL TV · non lié (touche " + CapitolClient.menuKeyName() + ")" : "CAPITOL TV · " + CapitolClient.stateText();
                headColor = c.token.isEmpty() ? DIM : ORANGE;
            } else {
                head = "CAPITOL TV · " + stateLabel(c.state);
                headColor = stateColor(c.state);
                String f = CapitolClient.follow;
                if (f != null && !f.isEmpty()) {
                    Data.Trib t = Data.tribById(f);
                    String extra = "";
                    if (t != null && mc.player != null && mc.world != null) {
                        for (var p : mc.world.getPlayers()) if (!t.mc.isEmpty() && p.getGameProfile().getName().equalsIgnoreCase(t.mc)) {
                            extra = " · " + Math.round(p.distanceTo(mc.player)) + " m · " + Math.round(p.getHealth() / 2 * 10) / 10.0 + "♥";
                        }
                        if (extra.isEmpty()) { Data.Pos p = Data.pos.get(t.id); if (p != null) extra = " · vu il y a " + Data.ago(p.ts); }
                    }
                    lines.add("Suit : " + Data.tribLabel(f) + extra); colors.add(INK);
                }
                if (Data.delay > 0) { lines.add("Différé : " + Data.delay / 60 + ":" + String.format("%02d", Data.delay % 60)); colors.add(DIM); }
                if (Data.lastReportTs > 0 && Data.airIn(Data.lastReportTs) > 0) { lines.add("Ton signalement : " + Data.airLabel(Data.lastReportTs)); colors.add(GOLD); }
                Data.Req r = Data.myOpenAssignment();
                if (r != null) { lines.add("Demande : " + Data.tribLabel(r.tr) + (r.text.isEmpty() ? "" : " — " + r.text)); colors.add(PURPLE); }
                long pend = Data.alerts.values().stream().filter(a -> !a.handled).count();
                if (pend > 0) { lines.add(pend + " action(s) en attente"); colors.add(DIM); }
            }
            if (CamMode.active() && Data.onAir()) { lines.add(0, "À L'ANTENNE · flux propre (Alt gauche : voir)"); colors.add(0, RED); }
            int w = tr.getWidth(head);
            for (String l : lines) w = Math.max(w, tr.getWidth(l));
            w = Math.min(w, 260);
            int h = 6 + 10 * (1 + lines.size());
            int x = c.hudPos.equals("tr") ? sw - w - 12 : 4;
            int y = c.hudPos.equals("bl") ? sh - h - (CamMode.active() ? 40 : 40) : (c.hudPos.equals("tr") ? 36 : 4);
            ctx.fill(x, y, x + w + 8, y + h, BG);
            ctx.fill(x, y, x + 2, y + h, headColor);
            ctx.drawText(tr, tr.trimToWidth(head, w), x + 5, y + 4, headColor, true);
            for (int i = 0; i < lines.size(); i++) ctx.drawText(tr, tr.trimToWidth(lines.get(i), w), x + 5, y + 14 + i * 10, colors.get(i), true);
        }

        long now = System.currentTimeMillis();
        int ty = c.hudPos.equals("tr") ? 90 : 38;
        synchronized (toasts) {
            toasts.removeIf(t -> t.until < now);
            if (!CamMoves.picker) for (Toast t : toasts) {
                int w = 200;
                List<OrderedText> wrapped = tr.wrapLines(Text.literal(t.text), w - 10);
                if (wrapped.size() > 4) wrapped = wrapped.subList(0, 4);
                int h = 16 + wrapped.size() * 10;
                int x = sw - w - 6;
                ctx.fill(x, ty, x + w, ty + h, BG2);
                ctx.fill(x, ty, x + 2, ty + h, t.color);
                ctx.drawText(tr, tr.trimToWidth(t.title, w - 10), x + 6, ty + 4, t.color, true);
                for (int i = 0; i < wrapped.size(); i++) ctx.drawText(tr, wrapped.get(i), x + 6, ty + 15 + i * 10, INK, false);
                ty += h + 4;
                if (ty > sh - 40) break;
            }
        }
    }
}
