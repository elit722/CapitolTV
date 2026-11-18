package fr.capitoltv.client.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** Bouton plat aux couleurs de Capitol TV (remplace le bouton gris de Minecraft). */
public class CtvButton extends ButtonWidget {
    public enum Kind { NORMAL, TAB, PRIMARY, DANGER, CHIP }

    public Kind kind = Kind.NORMAL;
    /** onglet ou puce actuellement choisi */
    public boolean selected;
    /** pictogramme affiché à gauche (onglets) */
    public String icon = "";

    public CtvButton(int x, int y, int w, int h, String label, Runnable r) {
        super(x, y, w, h, Text.literal(label), b -> r.run(), DEFAULT_NARRATION_SUPPLIER);
    }

    public CtvButton kind(Kind k) { kind = k; return this; }

    @Override
    protected void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
        TextRenderer t = Ui.tr();
        int x0 = getX(), y0 = getY(), x1 = x0 + width, y1 = y0 + height;
        boolean hov = isHovered() && active;
        String label = getMessage().getString();
        int ty = y0 + (height - 8) / 2;

        if (kind == Kind.TAB) {
            if (selected) {
                ctx.fill(x0, y0, x1, y1, 0x30D4AF37);
                ctx.fill(x0, y0, x0 + 2, y1, Ui.GOLD);
            } else if (hov) ctx.fill(x0, y0, x1, y1, 0x18FFFFFF);
            int tx = x0 + 7;
            if (!icon.isEmpty()) { ctx.drawText(t, icon, tx, ty, selected ? Ui.GOLD : hov ? Ui.GOLD_HI : Ui.MUTE, false); tx += 12; }
            ctx.drawText(t, t.trimToWidth(label, x1 - tx - 2), tx, ty, selected ? Ui.GOLD_HI : hov ? Ui.INK : Ui.DIM, false);
            return;
        }

        int bg, border, fg;
        switch (kind) {
            case PRIMARY -> { bg = hov ? 0xFFE6C452 : Ui.GOLD; border = Ui.GOLD_HI; fg = 0xFF16120A; }
            case DANGER -> { bg = hov ? 0xFFB02532 : Ui.RED_DK; border = Ui.RED; fg = 0xFFFFFFFF; }
            case CHIP -> {
                bg = selected ? 0x40D4AF37 : hov ? Ui.BG3 : Ui.BG2;
                border = selected ? Ui.GOLD : hov ? Ui.LINE_HI : Ui.LINE;
                fg = selected ? Ui.GOLD_HI : Ui.INK;
            }
            default -> { bg = hov ? Ui.BG3 : Ui.BG2; border = hov ? Ui.GOLD_DK : Ui.LINE; fg = Ui.INK; }
        }
        if (!active && kind != Kind.CHIP) { bg = 0xFF141317; border = 0xFF24221F; fg = Ui.MUTE; }
        Ui.box(ctx, x0, y0, x1, y1, bg, border);
        if (kind == Kind.NORMAL && active) ctx.fill(x0 + 1, y0 + 1, x1 - 1, y0 + 2, hov ? 0x30FFFFFF : 0x14FFFFFF);

        // « Clé : valeur » → clé à gauche, valeur dorée à droite (réglages)
        int sep = label.indexOf(" : ");
        if (sep > 0 && kind == Kind.NORMAL) {
            String k = label.substring(0, sep), v = label.substring(sep + 3);
            int vw = t.getWidth(v), kw = t.getWidth(k);
            if (kw + vw + 18 <= width) {
                ctx.drawText(t, k, x0 + 6, ty, active ? Ui.DIM : Ui.MUTE, false);
                int vc = !active ? Ui.MUTE : v.equals("oui") || v.equals("OUI") ? Ui.GREEN : v.equals("non") ? 0xFFB06A62 : Ui.GOLD_HI;
                ctx.drawText(t, v, x1 - 6 - vw, ty, vc, false);
                return;
            }
            label = v.isEmpty() ? k : v;
        }
        String s = t.trimToWidth(label, width - 6);
        if (s.length() < label.length() && s.length() > 1) s = t.trimToWidth(label, width - 6 - t.getWidth("…")) + "…";
        ctx.drawText(t, s, x0 + (width - t.getWidth(s)) / 2, ty, fg, false);
    }
}
