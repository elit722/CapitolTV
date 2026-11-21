package fr.capitoltv.client.gui;

import fr.capitoltv.client.ClientConfig;
import fr.capitoltv.client.Data;
import fr.capitoltv.client.GameAudio;
import fr.capitoltv.client.Quality;
import fr.capitoltv.client.Streamer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * « Ma POV » : accord du joueur (à la première connexion, puis touche K).
 * Pas de choix de qualité : c'est toujours le maximum, baissé automatiquement si le jeu ralentit.
 */
public class StreamScreen extends Screen {
    private final Screen parent;
    private final boolean first;
    private TextFieldWidget keyField;
    private String keyDraft;
    private int px, py, pw, ph;

    public StreamScreen(Screen parent, boolean first) {
        super(Text.literal("Ma POV"));
        this.parent = parent;
        this.first = first;
    }

    @Override public boolean shouldPause() { return false; }

    private boolean hasKey() { String k = Streamer.key(); return k != null && !k.isBlank(); }

    @Override
    protected void init() {
        ClientConfig c = ClientConfig.get();
        pw = Math.min(360, width - 20);
        ph = Math.min(height - 16, 214);
        px = (width - pw) / 2; py = (height - ph) / 2;
        int x = px + 12, w = pw - 24, y = py + 92;
        boolean auto = (!Data.streamKey.isEmpty() || !Data.serverKey.isEmpty()) && (c.streamKey == null || c.streamKey.isBlank());
        if (keyDraft == null) keyDraft = c.streamKey;
        if (!auto) {
            keyField = new TextFieldWidget(textRenderer, x + 5, y + 4, w - 70, 12, Text.literal("Clé de diffusion"));
            keyField.setDrawsBackground(false);
            keyField.setMaxLength(64);
            keyField.setText(keyDraft == null ? "" : keyDraft);
            keyField.setPlaceholder(Text.literal("Clé (si le serveur ne l'envoie pas)"));
            keyField.setChangedListener(s -> keyDraft = s);
            addDrawableChild(keyField);
            addDrawableChild(new CtvButton(x + w - 58, y, 58, 18, "Coller", () -> {
                String s = MinecraftClient.getInstance().keyboard.getClipboard();
                if (s != null) { keyDraft = s.trim(); saveKey(); clearAndInit(); }
            }));
        }
        y += 26;
        String cur = Quality.chosen();
        int bw = (w - 6) / 2;
        CtvButton yes = new CtvButton(x, y, bw, 24, "✔ J'accepte", () -> { saveKey(); Quality.choose(true); close(); }).kind(CtvButton.Kind.PRIMARY);
        yes.selected = "on".equals(cur);
        addDrawableChild(yes);
        CtvButton no = new CtvButton(x + bw + 6, y, bw, 24, "Je refuse", () -> { saveKey(); Quality.choose(false); close(); });
        no.selected = "off".equals(cur);
        addDrawableChild(no);
        y += 32;
        int sw = (w - 6) / 2;
        String snd = c.gameAudio ? "jeu" : c.streamMic ? "micro" : "aucun";
        addDrawableChild(new CtvButton(x, y, sw, 18, "Son envoyé : " + snd, () -> {
            boolean before = GameAudio.wanted();
            if (c.gameAudio) { c.gameAudio = false; c.streamMic = true; } else if (c.streamMic) { c.streamMic = false; } else c.gameAudio = true;
            ClientConfig.save(); Streamer.stop(null);
            if (before != GameAudio.wanted()) GameAudio.reload();
            clearAndInit();
        }));
        addDrawableChild(new CtvButton(x + sw + 6, y, w - sw - 6, 18, first && cur.isEmpty() ? "Plus tard" : "Fermer", this::close));
        if (hasKey()) Streamer.announce();
    }

    private void saveKey() {
        ClientConfig c = ClientConfig.get();
        String k = keyDraft == null ? "" : keyDraft.trim();
        if (keyField != null && !k.equals(c.streamKey == null ? "" : c.streamKey)) { c.streamKey = k; ClientConfig.save(); Streamer.stop(null); }
    }

    @Override
    public void close() {
        saveKey();
        if (first) { ClientConfig.get().streamAsked = true; ClientConfig.save(); }
        if (client != null) client.setScreen(parent);
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fillGradient(0, 0, width, height, 0xB0050506, 0xD0050506);
        int x1 = px + pw, y1 = py + ph;
        ctx.fill(px + 3, py + 3, x1 + 3, y1 + 3, 0x60000000);
        Ui.box(ctx, px, py, x1, y1, Ui.BG1, Ui.LINE);
        ctx.fillGradient(px + 1, py + 1, x1 - 1, py + 30, 0xFF1E1B16, 0xFF141317);
        ctx.fill(px + 1, py + 30, x1 - 1, py + 31, Ui.GOLD);
        if (keyField != null) {
            boolean foc = keyField.isFocused();
            Ui.box(ctx, keyField.getX() - 5, keyField.getY() - 4, keyField.getX() + keyField.getWidth() + 5, keyField.getY() + 12, 0xFF0D0D10, foc ? Ui.GOLD : Ui.LINE_HI);
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        Ui.emblem(ctx, px + 8, py + 7, 16);
        ctx.drawText(textRenderer, Text.literal(first ? "PARTAGER TA POV ?" : "MA POV").formatted(Formatting.BOLD), px + 30, py + 7, Ui.GOLD, true);
        ctx.drawText(textRenderer, "Capitol TV aimerait montrer ton écran de jeu", px + 30, py + 18, Ui.DIM, false);
        int x = px + 12, w = pw - 24, ty = py + 38;
        for (var line : textRenderer.wrapLines(Text.literal("Seulement le jeu et son son (ni HUD, ni chat, ni menus). Visible par la régie, peut passer dans l'émission. Qualité automatique : la meilleure que ton PC supporte sans ralentir. Touche K pour changer d'avis."), w)) {
            if (ty > py + 84) break;
            ctx.drawText(textRenderer, line, x, ty, Ui.INK, false); ty += 9;
        }
        if (keyField == null) ctx.drawText(textRenderer, !Data.serverKey.isEmpty() ? "Clé reçue automatiquement du serveur ✔" + (Data.serverWho.isEmpty() ? "" : " · " + Data.serverWho) : "Clé de diffusion reçue du site ✔", x, py + 97, Ui.GREEN, false);
        String st; int col;
        if (Quality.capOff) { st = "Capture coupée par la régie pour le moment"; col = Ui.DIM; }
        else if (Quality.blocked) { st = "La régie a coupé ta POV (ton jeu ramait trop)"; col = 0xFFE89A90; }
        else if (!hasKey()) { st = "En attente de la clé du serveur de l'event…"; col = Ui.DIM; }
        else if ("off".equals(Quality.chosen())) { st = "POV refusée : rien n'est envoyé"; col = Ui.DIM; }
        else if (Streamer.live()) { st = "● En direct vers la régie · " + Streamer.sending(); col = Ui.GREEN; }
        else { st = Streamer.status(); col = Ui.GOLD_HI; }
        ctx.drawText(textRenderer, textRenderer.trimToWidth(st, w), x, py + ph - 13, col, false);
    }
}
