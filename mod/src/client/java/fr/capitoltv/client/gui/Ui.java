package fr.capitoltv.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/** Petite charte graphique Capitol TV pour les écrans du mod (fond sombre, or, rouge antenne). */
public final class Ui {
    private Ui() { }

    public static final Identifier EMBLEM = Identifier.of("capitoltv", "textures/gui/emblem.png");

    public static final int GOLD = 0xFFD4AF37, GOLD_HI = 0xFFF2D27A, GOLD_DK = 0xFF8A6F1E,
            INK = 0xFFEDE6D6, DIM = 0xFF9A9284, MUTE = 0xFF6A655C,
            RED = 0xFFE0514A, RED_DK = 0xFF8E1B24, GREEN = 0xFF5FBF6A,
            BG0 = 0xF00B0B0E, BG1 = 0xF014141A, BG2 = 0xFF1C1B21, BG3 = 0xFF26242B,
            LINE = 0xFF34312C, LINE_HI = 0xFF4A453D;

    public static TextRenderer tr() { return MinecraftClient.getInstance().textRenderer; }

    /** Rectangle aux coins adoucis (le pixel d'angle est laissé vide). */
    public static void box(DrawContext ctx, int x0, int y0, int x1, int y1, int bg, int border) {
        if (x1 - x0 < 3 || y1 - y0 < 3) { ctx.fill(x0, y0, x1, y1, bg); return; }
        ctx.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, bg);
        if (border != 0) {
            ctx.fill(x0 + 1, y0, x1 - 1, y0 + 1, border);
            ctx.fill(x0 + 1, y1 - 1, x1 - 1, y1, border);
            ctx.fill(x0, y0 + 1, x0 + 1, y1 - 1, border);
            ctx.fill(x1 - 1, y0 + 1, x1, y1 - 1, border);
        } else {
            ctx.fill(x0 + 1, y0, x1 - 1, y0 + 1, bg);
            ctx.fill(x0 + 1, y1 - 1, x1 - 1, y1, bg);
            ctx.fill(x0, y0 + 1, x0 + 1, y1 - 1, bg);
            ctx.fill(x1 - 1, y0 + 1, x1, y1 - 1, bg);
        }
    }

    public static void emblem(DrawContext ctx, int x, int y, int size) {
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, EMBLEM, x, y, 0f, 0f, size, size, size, size);
    }

    /** Pastille de texte (étiquette). Renvoie sa largeur. */
    public static int pill(DrawContext ctx, String text, int x, int y, int bg, int fg) {
        TextRenderer t = tr();
        int w = t.getWidth(text) + 8;
        box(ctx, x, y, x + w, y + 12, bg, 0);
        ctx.drawText(t, text, x + 4, y + 2, fg, false);
        return w;
    }

    /** Petit titre de section en capitales espacées, avec un filet doré. */
    public static void section(DrawContext ctx, String text, int x, int y, int w) {
        TextRenderer t = tr();
        String s = text.toUpperCase();
        ctx.drawText(t, s, x, y, GOLD, false);
        int sw = t.getWidth(s) + 6;
        if (w > sw + 10) ctx.fill(x + sw, y + 4, x + w, y + 5, 0x40D4AF37);
    }

    public static int mix(int a, int b, float f) {
        int aa = a >>> 24, ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255;
        int ba = b >>> 24, br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return Math.round(aa + (ba - aa) * f) << 24 | Math.round(ar + (br - ar) * f) << 16 | Math.round(ag + (bg - ag) * f) << 8 | Math.round(ab + (bb - ab) * f);
    }

    public static int alpha(int c, int a) { return (c & 0xFFFFFF) | (a << 24); }
}
