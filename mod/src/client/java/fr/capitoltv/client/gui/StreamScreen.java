package fr.capitoltv.client.gui;

import fr.capitoltv.client.ClientConfig;
import fr.capitoltv.client.Data;
import fr.capitoltv.client.Quality;
import fr.capitoltv.client.Streamer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * « Ma diffusion » : le joueur colle sa clé et choisit la qualité de son POV
 * (ou aucun POV). Ouvert à la première connexion, puis avec la touche K.
 */
public class StreamScreen extends Screen {
    private final Screen parent;
    private final boolean first;
    private TextFieldWidget keyField;
    private String keyDraft;
    private int px, py, pw, ph;
    private String msg = "";
    private long msgUntil;

    public StreamScreen(Screen parent, boolean first) {
        super(Text.literal("Ma diffusion"));
        this.parent = parent;
        this.first = first;
    }

    @Override public boolean shouldPause() { return false; }

    private boolean hasKey() { String k = Streamer.key(); return k != null && !k.isBlank(); }

    @Override
    protected void init() {
        ClientConfig c = ClientConfig.get();
        pw = Math.min(360, width - 20);
        int cards = Quality.ALL.size();
        ph = Math.min(height - 16, 74 + cards * 26 + 58);
        px = (width - pw) / 2; py = (height - ph) / 2;
        int x = px + 12, w = pw - 24, y = py + 40;

        // clé de diffusion (donnée sur le site, page Mon compte)
        boolean siteKey = !Data.streamKey.isEmpty() && (c.streamKey == null || c.streamKey.isBlank());
        if (keyDraft == null) keyDraft = c.streamKey;
        if (!siteKey) {
            keyField = new TextFieldWidget(textRenderer, x + 5, y + 4, w - 70, 12, Text.literal("Clé de diffusion"));
            keyField.setDrawsBackground(false);
            keyField.setMaxLength(64);
            keyField.setText(keyDraft == null ? "" : keyDraft);
            keyField.setPlaceholder(Text.literal("Colle ici ta clé (site › Mon compte)"));
            keyField.setChangedListener(s -> keyDraft = s);
            addDrawableChild(keyField);
            addDrawableChild(new CtvButton(x + w - 58, y, 58, 18, "Coller", () -> {
                String s = MinecraftClient.getInstance().keyboard.getClipboard();
                if (s != null) { keyDraft = s.trim(); saveKey(); clearAndInit(); }
            }));
        }
        y += 26;

        String cur = Quality.chosen();
        int capRank = Quality.cap.isEmpty() ? 99 : Quality.rank(Quality.cap);
        for (Quality.Level l : Quality.ALL) {
            final String id = l.id();
            boolean over = !"off".equals(id) && Quality.rank(id) > capRank;
            CtvButton b = new CtvButton(x, y, w, 22, l.name() + "   " + l.spec(), () -> {
                saveKey();
                Quality.choose(id);
                say("off".equals(id) ? "Aucune image ne sera envoyée" : "Qualité enregistrée : " + Quality.of(id).name());
                if (first) close(); else clearAndInit();
            }).kind(CtvButton.Kind.CHIP);
            b.selected = id.equals(cur);
            if (over) b.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("Au-dessus du maximum fixé par la régie : tu enverras " + Quality.of(Quality.cap).spec())));
            addDrawableChild(b);
            y += 26;
        }
        y += 4;
        int bw = (w - 6) / 2;
        addDrawableChild(new CtvButton(x, y, bw, 18, "Micro : " + (c.streamMic ? "oui" : "non"), () -> {
            c.streamMic = !c.streamMic; ClientConfig.save(); Streamer.stop(null); clearAndInit();
        }));
        addDrawableChild(new CtvButton(x + bw + 6, y, bw, 18, first && cur.isEmpty() ? "Plus tard" : "Fermer", this::close).kind(CtvButton.Kind.PRIMARY));
        if (hasKey()) Streamer.announce();
    }

    private void saveKey() {
        ClientConfig c = ClientConfig.get();
        String k = keyDraft == null ? "" : keyDraft.trim();
        if (keyField != null && !k.equals(c.streamKey == null ? "" : c.streamKey)) { c.streamKey = k; ClientConfig.save(); Streamer.stop(null); }
    }

    private void say(String s) { msg = s; msgUntil = System.currentTimeMillis() + 3000; }

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
        ctx.drawText(textRenderer, Text.literal("MA DIFFUSION").formatted(Formatting.BOLD), px + 30, py + 7, Ui.GOLD, true);
        ctx.drawText(textRenderer, first ? "Choisis la qualité de ton POV pour la régie" : "Ce que la régie Capitol TV reçoit de ton écran", px + 30, py + 18, Ui.DIM, false);
        int x = px + 12, w = pw - 24;
        if (keyField == null) ctx.drawText(textRenderer, "Clé de diffusion reçue du site ✔", x, py + 45, Ui.GREEN, false);
        // statut
        String st;
        int col;
        if (!hasKey()) { st = "Colle ta clé pour envoyer ton image"; col = Ui.DIM; }
        else if ("off".equals(Quality.chosen())) { st = "Aucune image envoyée"; col = Ui.DIM; }
        else if (Streamer.live()) {
            String ap = Quality.applied.isEmpty() ? "" : " · " + Quality.of(Quality.applied).spec();
            st = "● En direct vers la régie" + ap; col = Ui.GREEN;
        } else { st = Streamer.status(); col = Ui.GOLD_HI; }
        if (System.currentTimeMillis() < msgUntil) { st = msg; col = Ui.GOLD_HI; }
        ctx.drawText(textRenderer, textRenderer.trimToWidth(st, w), x, py + ph - 14, col, false);
        // pastilles « conseillé » / « limite régie »
        int y = py + 66;
        int capRank = Quality.cap.isEmpty() ? 99 : Quality.rank(Quality.cap);
        for (Quality.Level l : Quality.ALL) {
            String tag = "std".equals(l.id()) ? "CONSEILLÉ" : !"off".equals(l.id()) && Quality.rank(l.id()) > capRank ? "LIMITÉ" : "";
            if (!tag.isEmpty()) {
                int tw = textRenderer.getWidth(tag) + 8;
                Ui.pill(ctx, tag, x + w - tw - 4, y + 5, tag.startsWith("C") ? 0xFF2A2620 : 0xFF3A1A1C, tag.startsWith("C") ? Ui.GOLD_HI : 0xFFE89A90);
            }
            y += 26;
        }
    }
}
