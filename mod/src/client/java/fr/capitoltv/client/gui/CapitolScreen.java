package fr.capitoltv.client.gui;

import fr.capitoltv.client.CapitolClient;
import fr.capitoltv.client.ClientConfig;
import fr.capitoltv.client.Data;
import fr.capitoltv.client.Hud;
import fr.capitoltv.client.Observer;
import fr.capitoltv.client.Teleport;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.util.Formatting;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Menu caméra Capitol TV (touche K par défaut). */
public class CapitolScreen extends Screen {
    enum Tab {
        ACTIONS("Actions", "⚑"), FEED("Annonces", "≡"), MAP("Carte", "◎"), SIGNAL("Signaler", "✚"), COMMS("Comms", "✉"), TEAM("Équipe", "☺"), REQS("Demandes", "☰"), ROLES("Rôles", "★"), SETTINGS("Réglages", "⚙"), WIKI("Wiki", "?");
        final String label, icon;
        Tab(String l, String i) { label = l; icon = i; }
    }

    static final String[] TYPES = {"Combat", "Alliance", "Trahison", "Mort imminente", "Cadeau sponsor", "Moment drôle", "Révélation", "Autre"};
    static final String[] FEED_KINDS = {"", "hit", "death", "effect", "meet", "special"};
    static final String[] FEED_LABELS = {"Tout", "Combats & dégâts", "Morts", "Effets", "Rencontres", "Spécial"};
    static final String[] QUICK = {"Reçu ✔", "J'y vais", "Besoin d'aide", "Terminé", "Je change", "Pause"};
    public static int setPage;

    /* état conservé entre deux ouvertures du menu */
    static Tab tab = Tab.ACTIONS;
    static boolean pendingOnly = true;
    static String selId = "";
    static int scroll;
    static int feedKind;
    static String feedTrib = "";
    static String sigTrib = "";
    static int sigType;
    static boolean sigUrgent, sigRec = true;
    static String sigDetail = "";
    static String sigInfo = "";
    static String commsCh = "all";
    static String dmTo = "";
    static String msgDraft = "";
    static String reqTrib = "";
    static String reqText = "";
    static String reqTo = "";
    static String assignTrib = "";
    static String tokenDraft = null;
    static String urlDraft = null;
    static String status = "";
    static boolean rolesView;
    static List<Data.Role> roleDraft = null;
    static final String[] PERMS = {"admin", "mod", "program", "roles", "chef", "cam", "special"};
    static final String[] PERM_L = {"Régie", "Modé", "Réal.", "Rôles", "Chef cam", "Caméra", "Annonces"};
    static long statusAt;
    // carte
    static double camX, camZ, scale = 1.0;
    static boolean fitted;
    static String mapDim = "";
    static String mapSel = "";
    static boolean pickSet;
    static double pickX, pickZ;

    private int px, py, pw, ph, cx, cy, cw, ch, listY, listBottom, barY;
    private final List<Row> rows = new ArrayList<>();
    private TextFieldWidget detailField, msgField, reqField, tokenField, urlField;
    private double pressX, pressY;
    private boolean dragging, pressedOnMap, looking;

    private boolean inPanel(double x, double y) { return x >= px && x < px + pw && y >= py && y < py + ph; }

    private record Row(String id, String l1, int c1, String r1, String l2, int c2) { }
    private final List<TextFieldWidget> fields = new ArrayList<>();
    static final int HEAD = 26, RH = 26;
    /** largeur de la colonne des onglets (réduite en mode « menu sur le côté ») */
    private int SIDE = 96;
    static int wikiSec, wikiScroll;
    static String keyDraft = null;

    public CapitolScreen() { super(Text.literal("Capitol TV")); }

    /** Ouvre le menu sur un onglet donné (ACTIONS, FEED, MAP, SIGNAL, COMMS, TEAM, REQS, ROLES, SETTINGS). */
    public static CapitolScreen on(String t) { tab = Tab.valueOf(t); scroll = 0; selId = ""; fitted = false; return new CapitolScreen(); }

    @Override public boolean shouldPause() { return false; }

    static void say(String s) { status = s; statusAt = System.currentTimeMillis(); }

    /* ====================================================================== */
    @Override
    protected void init() {
        int m = width > 520 ? 14 : 6;
        boolean dock = ClientConfig.get().dockMenu;
        SIDE = dock ? 24 : 96;
        px = m; py = m; pw = width - 2 * m; ph = height - 2 * m;
        if (dock) { pw = Math.max(250, (int) (width * 0.46)); px = width - pw - m; }
        cx = px + SIDE + 8; cy = py + HEAD + 8; cw = pw - SIDE - 16; ch = ph - HEAD - 14;
        barY = py + ph - 22;
        listY = cy; listBottom = barY - 6;
        detailField = msgField = reqField = tokenField = urlField = null;
        fields.clear();

        int ty = py + HEAD + 8;
        int th = height < 300 ? 17 : 19;
        if (tab == Tab.ROLES && !Data.can("roles")) tab = Tab.ACTIONS;
        for (Tab t : Tab.values()) {
            if (t == Tab.ROLES && !Data.can("roles")) continue;
            CtvButton b = new CtvButton(px + 1, ty, SIDE - 1, th - 1, dock ? "" : t.label, () -> { tab = t; scroll = 0; selId = ""; clearAndInit(); }).kind(CtvButton.Kind.TAB);
            b.icon = t.icon; b.selected = t == tab;
            addDrawableChild(b);
            ty += th;
        }
        if (!dock) btn(px + 6, py + ph - 22, SIDE - 12, "Fermer", this::close);
        // bouton « menu sur le côté » : on voit le jeu, on bouge (ZQSD) et on tourne (clic droit maintenu)
        CtvButton dk = btn(px + pw - 22, py + 6, 16, dock ? "▣" : "◧", () -> { ClientConfig.get().dockMenu = !ClientConfig.get().dockMenu; ClientConfig.save(); clearAndInit(); });
        dk.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(dock ? "Agrandir le menu" : "Menu sur le côté : tu vois le jeu et tu peux bouger")));

        switch (tab) {
            case ACTIONS -> initActions();
            case FEED -> initFeed();
            case MAP -> initMap();
            case SIGNAL -> initSignal();
            case COMMS -> initComms();
            case TEAM -> initTeam();
            case REQS -> initReqs();
            case ROLES -> initRoles();
            case SETTINGS -> initSettings();
            case WIKI -> initWiki();
        }
    }

    private CtvButton btn(int x, int y, int w, String label, Runnable r) {
        CtvButton b = new CtvButton(x, y, w, 16, label, r);
        if (label.startsWith("Envoyer") || label.equals("Connecter") || label.equals("Créer") || label.startsWith("Enregistrer")) b.kind = CtvButton.Kind.PRIMARY;
        else if (label.equals("BUZZ") || label.equals("Buzz") || label.equals("Déconnecter")) b.kind = CtvButton.Kind.DANGER;
        addDrawableChild(b);
        return b;
    }

    private static CtvButton chip(ButtonWidget b, boolean sel) {
        CtvButton c = (CtvButton) b; c.kind = CtvButton.Kind.CHIP; c.selected = sel; c.active = true; return c;
    }

    /** Une rangée de boutons répartis sur toute la largeur du contenu. */
    private List<ButtonWidget> bar(int y, Object... pairs) {
        int n = pairs.length / 2;
        List<ButtonWidget> out = new ArrayList<>();
        if (n == 0) return out;
        int gap = 3, w = (cw - gap * (n - 1)) / n;
        for (int i = 0; i < n; i++) out.add(btn(cx + i * (w + gap), y, w, (String) pairs[i * 2], (Runnable) pairs[i * 2 + 1]));
        return out;
    }

    private TextFieldWidget field(int x, int y, int w, String value, String placeholder, int max, java.util.function.Consumer<String> onChange) {
        TextFieldWidget f = new TextFieldWidget(textRenderer, x + 5, y + 4, w - 10, 12, Text.literal(placeholder));
        f.setDrawsBackground(false);
        fields.add(f);
        f.setMaxLength(max);
        f.setText(value == null ? "" : value);
        f.setPlaceholder(Text.literal(placeholder));
        f.setChangedListener(onChange);
        addDrawableChild(f);
        return f;
    }

    /* ---------------------------------------------------------------- actions */
    private Data.Alert selAlert() { return Data.alerts.get(selId); }
    private Data.Trib alertTrib(Data.Alert a) { return a == null ? null : Data.tribByLogin(a.ch); }

    private void initActions() {
        Data.Alert a = selAlert();
        bar(barY,
                pendingOnly ? "En attente" : "Toutes", (Runnable) () -> { pendingOnly = !pendingOnly; scroll = 0; clearAndInit(); },
                "TP tribut", (Runnable) () -> { Data.Alert x = selAlert(); say(x == null ? "Sélectionne une action" : Teleport.toTribute(alertTrib(x))); },
                "Je filme", (Runnable) () -> {
                    Data.Alert x = selAlert(); Data.Trib t = alertTrib(x);
                    if (t == null) { say("Sélectionne une action"); return; }
                    CapitolClient.setFollow(t.id); CapitolClient.setState("rec");
                    CapitolClient.alertUpd(x.id, "claim", true);
                    CapitolClient.message("cams", "", "Je filme " + t.name + " (" + x.type + ")");
                    say("Tu filmes " + t.name);
                },
                a != null && !a.claimedBy.isEmpty() ? "Libérer" : "Je prends", (Runnable) () -> {
                    Data.Alert x = selAlert(); if (x == null) { say("Sélectionne une action"); return; }
                    CapitolClient.alertUpd(x.id, "claim", x.claimedBy.isEmpty());
                    say(x.claimedBy.isEmpty() ? "Tu t'en occupes" : "Action libérée");
                },
                a != null && a.handled ? "Rouvrir" : "Traité", (Runnable) () -> {
                    Data.Alert x = selAlert(); if (x == null) { say("Sélectionne une action"); return; }
                    CapitolClient.alertUpd(x.id, "handled", !x.handled);
                });
    }

    private void rowsActions() {
        for (Data.Alert a : Data.alertList(pendingOnly)) {
            Data.Trib t = Data.tribByLogin(a.ch);
            String who = t == null ? "@" + a.ch : String.format(Locale.ROOT, "%03d %s", t.num, t.name);
            String air = Data.airLabel(a.ts);
            String right = hhmm(a.ts) + (air.isEmpty() ? "" : " · " + air);
            String l2 = a.detail + " — " + a.by + (a.claimedBy.isEmpty() ? "" : " · ✓ " + a.claimedBy) + (a.rec ? " · filmé" : "");
            rows.add(new Row(a.id, (a.urgent ? "URGENT · " : "") + a.type + " · " + who, a.handled ? Hud.DIM : a.urgent ? Hud.RED : Hud.GOLD, right, l2, a.handled ? 0xFF6A655C : Hud.INK));
        }
    }

    /* ---------------------------------------------------------------- annonces */
    private Data.Feed selFeed() { for (Data.Feed f : Data.feed) if (f.id.equals(selId)) return f; return null; }

    private void initFeed() {
        List<Data.Trib> list = Data.tribList();
        bar(barY,
                "Type : " + FEED_LABELS[feedKind], (Runnable) () -> { feedKind = (feedKind + 1) % FEED_KINDS.length; scroll = 0; clearAndInit(); },
                "Tribut : " + (feedTrib.isEmpty() ? "tous" : Data.tribById(feedTrib) == null ? "?" : Data.tribById(feedTrib).name), (Runnable) () -> { feedTrib = cycleTrib(list, feedTrib, true); scroll = 0; clearAndInit(); },
                "TP au lieu", (Runnable) () -> { Data.Feed f = selFeed(); say(f == null || !f.hasPos ? "Pas de position pour cet acte" : Teleport.toCoords(f.x, f.y, f.z, f.d)); },
                "TP au tribut", (Runnable) () -> { Data.Feed f = selFeed(); say(f == null ? "Sélectionne un acte" : Teleport.toTribute(Data.tribById(f.tr))); },
                "Suivre", (Runnable) () -> { Data.Feed f = selFeed(); if (f != null && Data.tribById(f.tr) != null) { CapitolClient.setFollow(f.tr); say("Tu suis " + Data.tribLabel(f.tr)); } });
    }

    private void rowsFeed() {
        String k = FEED_KINDS[feedKind];
        for (int i = Data.feed.size() - 1; i >= 0; i--) {
            Data.Feed f = Data.feed.get(i);
            if (!k.isEmpty() && !(f.k.equals(k) || (k.equals("hit") && (f.k.equals("lowhp") || f.k.equals("totem"))))) continue;
            if (!feedTrib.isEmpty() && !feedTrib.equals(f.tr)) continue;
            int c = switch (f.k) {
                case "death" -> Hud.RED;
                case "hit", "lowhp" -> Hud.ORANGE;
                case "effect" -> Hud.BLUE;
                case "special" -> Hud.PURPLE;
                case "totem" -> Hud.GOLD;
                case "meet" -> Hud.GREEN;
                default -> Hud.INK;
            };
            String air = Data.airLabel(f.ts);
            String meta = hhmmss(f.ts) + (f.src.isEmpty() ? "" : " · " + f.src) + (f.hasPos ? String.format(Locale.ROOT, " · %d %d %d", Math.round(f.x), Math.round(f.y), Math.round(f.z)) : "");
            rows.add(new Row(f.id, f.text, c, air, meta, Hud.DIM));
        }
    }

    /* ---------------------------------------------------------------- carte */
    private int mapX0() { return cx; }
    private int mapY0() { return cy; }
    private int mapX1() { return cx + cw; }
    private int mapY1() { return barY - 16; }

    private String myDim() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.world == null ? "minecraft:overworld" : mc.world.getRegistryKey().getValue().toString();
    }

    private void initMap() {
        if (mapDim.isEmpty()) mapDim = myDim();
        if (!fitted) fitMap();
        bar(barY,
                "Dim : " + dimShort(mapDim), (Runnable) () -> { mapDim = nextDim(mapDim); fitted = false; fitMap(); clearAndInit(); },
                "Sur moi", (Runnable) () -> { MinecraftClient mc = MinecraftClient.getInstance(); if (mc.player != null) { camX = mc.player.getX(); camZ = mc.player.getZ(); mapDim = myDim(); } },
                "Tout voir", (Runnable) this::fitMap,
                "Caméras : " + (ClientConfig.get().showCamsOnMap ? "oui" : "non"), (Runnable) () -> { ClientConfig.get().showCamsOnMap = !ClientConfig.get().showCamsOnMap; ClientConfig.save(); clearAndInit(); },
                "TP", (Runnable) () -> {
                    if (!mapSel.isEmpty()) { Data.Cam c = Data.cams.get(mapSel); say(c != null ? Teleport.toPlayer(c.mc) : Teleport.toTribute(Data.tribById(mapSel))); }
                    else if (pickSet) { MinecraftClient mc = MinecraftClient.getInstance(); say(Teleport.toCoords(pickX, mc.player == null ? 100 : mc.player.getY(), pickZ, mapDim)); }
                    else say("Clique sur un tribut ou un point de la carte");
                },
                "Suivre", (Runnable) () -> { if (Data.tribById(mapSel) != null) { CapitolClient.setFollow(mapSel); say("Tu suis " + Data.tribLabel(mapSel)); } else say("Sélectionne un tribut"); },
                "Signaler", (Runnable) () -> { if (Data.tribById(mapSel) != null) { sigTrib = mapSel; tab = Tab.SIGNAL; clearAndInit(); } else say("Sélectionne un tribut"); });
    }

    static String dimShort(String d) {
        return switch (d) {
            case "minecraft:overworld" -> "Surface";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "End";
            default -> d.contains(":") ? d.substring(d.indexOf(':') + 1) : d;
        };
    }

    private String nextDim(String cur) {
        List<String> dims = new ArrayList<>();
        dims.add("minecraft:overworld");
        for (Data.Pos p : Data.pos.values()) if (!p.d.isEmpty() && !dims.contains(p.d)) dims.add(p.d);
        for (Data.Cam c : Data.cams.values()) if (c.hasPos && !c.d.isEmpty() && !dims.contains(c.d)) dims.add(c.d);
        String my = myDim();
        if (!dims.contains(my)) dims.add(my);
        int i = dims.indexOf(cur);
        return dims.get((i + 1) % dims.size());
    }

    private void fitMap() {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        int n = 0;
        for (var e : Data.pos.entrySet()) {
            Data.Pos p = e.getValue();
            if (!p.d.equals(mapDim)) continue;
            minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x); minZ = Math.min(minZ, p.z); maxZ = Math.max(maxZ, p.z); n++;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && myDim().equals(mapDim)) {
            minX = Math.min(minX, mc.player.getX()); maxX = Math.max(maxX, mc.player.getX());
            minZ = Math.min(minZ, mc.player.getZ()); maxZ = Math.max(maxZ, mc.player.getZ()); n++;
        }
        if (n == 0) { camX = 0; camZ = 0; scale = 0.5; fitted = true; return; }
        camX = (minX + maxX) / 2; camZ = (minZ + maxZ) / 2;
        double spanX = Math.max(64, maxX - minX) * 1.25, spanZ = Math.max(64, maxZ - minZ) * 1.25;
        int w = Math.max(50, mapX1() - mapX0()), h = Math.max(50, mapY1() - mapY0());
        scale = Math.max(0.01, Math.min(8, Math.min(w / spanX, h / spanZ)));
        fitted = true;
    }

    private int sx(double x) { return (int) Math.round((mapX0() + mapX1()) / 2.0 + (x - camX) * scale); }
    private int sz(double z) { return (int) Math.round((mapY0() + mapY1()) / 2.0 + (z - camZ) * scale); }
    private double wx(double sx) { return camX + (sx - (mapX0() + mapX1()) / 2.0) / scale; }
    private double wz(double sz) { return camZ + (sz - (mapY0() + mapY1()) / 2.0) / scale; }

    private void renderMap(DrawContext ctx, int mouseX, int mouseY) {
        int x0 = mapX0(), y0 = mapY0(), x1 = mapX1(), y1 = mapY1();
        ctx.fill(x0, y0, x1, y1, 0xFF0C0D10);
        ctx.enableScissor(x0, y0, x1, y1);
        // quadrillage
        int[] steps = {8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192};
        int step = steps[steps.length - 1];
        for (int s : steps) if (s * scale >= 46) { step = s; break; }
        double ax = Math.floor(wx(x0) / step) * step, az = Math.floor(wz(y0) / step) * step;
        for (double gx = ax; sx(gx) <= x1; gx += step) {
            int X = sx(gx);
            ctx.fill(X, y0, X + 1, y1, gx == 0 ? 0x55D4AF37 : 0x22FFFFFF);
            ctx.drawText(textRenderer, String.valueOf((long) gx), X + 2, y0 + 2, 0xFF5C574E, false);
        }
        for (double gz = az; sz(gz) <= y1; gz += step) {
            int Z = sz(gz);
            ctx.fill(x0, Z, x1, Z + 1, gz == 0 ? 0x55D4AF37 : 0x22FFFFFF);
            ctx.drawText(textRenderer, String.valueOf((long) gz), x0 + 2, Z + 2, 0xFF5C574E, false);
        }
        long now = Data.now();
        // caméras
        if (ClientConfig.get().showCamsOnMap) for (Data.Cam c : Data.cams.values()) {
            if (!c.hasPos || !c.d.equals(mapDim) || c.id.equals(Data.meId)) continue;
            int X = sx(c.x), Z = sz(c.z);
            boolean sel = c.id.equals(mapSel);
            ctx.fill(X - 2, Z - 2, X + 2, Z + 2, sel ? 0xFFFFFFFF : 0xFF5AA0E6);
            if (scale > 0.25 || sel) ctx.drawText(textRenderer, "▣ " + c.name, X + 4, Z - 4, 0xFF7FB6EE, true);
        }
        // tributs
        for (Data.Trib t : Data.tribList()) {
            Data.Pos p = Data.pos.get(t.id);
            if (p == null || !p.d.equals(mapDim)) continue;
            int X = sx(p.x), Z = sz(p.z);
            boolean stale = now - p.ts > 60000;
            int col = switch (t.status) { case "dead" -> 0xFF8A3A36; case "injured" -> 0xFFE89A3C; case "missing" -> 0xFF5AA0E6; default -> 0xFF5FBF6A; };
            if (stale) col = (col & 0x00FFFFFF) | 0x88000000;
            boolean sel = t.id.equals(mapSel), fol = t.id.equals(CapitolClient.follow);
            if (sel || fol) ctx.fill(X - 5, Z - 5, X + 5, Z + 5, sel ? 0xFFFFFFFF : 0xFFD4AF37);
            ctx.fill(X - 3, Z - 3, X + 3, Z + 3, col);
            String lab = t.name + (p.hp >= 0 && !"dead".equals(t.status) ? " " + fmt1(p.hp / 2) + "♥" : "");
            ctx.drawText(textRenderer, lab, X + 6, Z - 4, stale ? 0xFF8A8478 : 0xFFEDE6D6, true);
        }
        // moi
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && myDim().equals(mapDim)) {
            int X = sx(mc.player.getX()), Z = sz(mc.player.getZ());
            ctx.fill(X - 3, Z - 3, X + 3, Z + 3, 0xFFFFFFFF);
            ctx.fill(X - 2, Z - 2, X + 2, Z + 2, 0xFFD4AF37);
            ctx.drawText(textRenderer, "moi", X + 5, Z + 2, 0xFFD4AF37, true);
        }
        if (pickSet) {
            int X = sx(pickX), Z = sz(pickZ);
            ctx.fill(X - 4, Z, X + 5, Z + 1, 0xFFFFFFFF);
            ctx.fill(X, Z - 4, X + 1, Z + 5, 0xFFFFFFFF);
        }
        ctx.disableScissor();
        border(ctx, x0, y0, x1, y1, 0xFF3A3630);
        // ligne d'info
        String info;
        if (!mapSel.isEmpty() && Data.tribById(mapSel) != null) {
            Data.Trib t = Data.tribById(mapSel);
            Data.Pos p = Data.pos.get(t.id);
            info = Data.tribLabel(t.id) + (t.district > 0 ? " · D" + t.district : "") + " · " + statusFr(t.status)
                    + (p == null ? "" : String.format(Locale.ROOT, " · %d %d %d · %s · vu il y a %s (%s)", Math.round(p.x), Math.round(p.y), Math.round(p.z),
                    p.hp >= 0 ? fmt1(p.hp / 2) + "♥" : "?♥", Data.ago(p.ts), p.src));
        } else if (!mapSel.isEmpty() && Data.cams.get(mapSel) != null) {
            Data.Cam c = Data.cams.get(mapSel);
            info = "Caméra " + c.name + " · " + Hud.stateLabel(c.st) + (c.follow.isEmpty() ? "" : " · suit " + Data.tribLabel(c.follow));
        } else if (pickSet) {
            info = String.format(Locale.ROOT, "Point choisi : %d ~ %d (%s) — bouton TP pour y aller", Math.round(pickX), Math.round(pickZ), dimShort(mapDim));
        } else {
            info = "Glisser : déplacer · Molette : zoom · Clic : sélectionner · " + Math.round(wx(mouseX)) + " / " + Math.round(wz(mouseY));
        }
        ctx.drawText(textRenderer, textRenderer.trimToWidth(info, cw), cx, y1 + 4, Hud.INK, false);
    }

    private Object mapHit(double mx, double my) {
        String best = null;
        double bd = 49;
        for (Data.Trib t : Data.tribList()) {
            Data.Pos p = Data.pos.get(t.id);
            if (p == null || !p.d.equals(mapDim)) continue;
            double dx = sx(p.x) - mx, dz = sz(p.z) - my, d = dx * dx + dz * dz;
            if (d < bd) { bd = d; best = t.id; }
        }
        if (ClientConfig.get().showCamsOnMap) for (Data.Cam c : Data.cams.values()) {
            if (!c.hasPos || !c.d.equals(mapDim)) continue;
            double dx = sx(c.x) - mx, dz = sz(c.z) - my, d = dx * dx + dz * dz;
            if (d < bd) { bd = d; best = c.id; }
        }
        return best;
    }

    private boolean inMap(double x, double y) { return tab == Tab.MAP && x >= mapX0() && x < mapX1() && y >= mapY0() && y < mapY1(); }

    /* ---------------------------------------------------------------- signaler */
    private void initSignal() {
        List<Data.Trib> list = Data.aliveTribs();
        if (sigTrib.isEmpty() && !CapitolClient.follow.isEmpty()) sigTrib = CapitolClient.follow;
        int y = cy + 14;
        int bw = 20;
        btn(cx, y, bw, "◀", () -> { sigTrib = cycleTrib(list, sigTrib, false, false); clearAndInit(); });
        ButtonWidget lab = btn(cx + bw + 3, y, cw - 2 * bw - 6 - 2 * 92, sigTrib.isEmpty() ? "— choisis un tribut —" : Data.tribLabel(sigTrib), () -> { sigTrib = cycleTrib(list, sigTrib, true, false); clearAndInit(); });
        lab.active = true;
        btn(lab.getX() + lab.getWidth() + 3, y, bw, "▶", () -> { sigTrib = cycleTrib(list, sigTrib, true, false); clearAndInit(); });
        btn(cx + cw - 2 * 92 + 3, y, 89, "Le plus proche", () -> { Data.Trib t = Observer.nearestTribute(); if (t != null) sigTrib = t.id; else say("Aucun tribut visible autour de toi"); clearAndInit(); });
        btn(cx + cw - 89, y, 89, "Mon suivi", () -> { if (!CapitolClient.follow.isEmpty()) sigTrib = CapitolClient.follow; else say("Tu ne suis aucun tribut"); clearAndInit(); });

        y += 32;
        int cols = 4, gap = 3, w = (cw - gap * (cols - 1)) / cols;
        for (int i = 0; i < TYPES.length; i++) {
            final int k = i;
            chip(btn(cx + (i % cols) * (w + gap), y + (i / cols) * 18, w, TYPES[i], () -> { sigType = k; clearAndInit(); }), i == sigType);
        }
        y += 42;
        bar(y,
                "Urgent : " + (sigUrgent ? "OUI" : "non"), (Runnable) () -> { sigUrgent = !sigUrgent; clearAndInit(); },
                "Je filme cette action : " + (sigRec ? "oui" : "non"), (Runnable) () -> { sigRec = !sigRec; clearAndInit(); });
        y += 36;
        detailField = field(cx, y, cw, sigDetail, "Détails pour la régie et le réacteur (facultatif)", 300, s -> sigDetail = s);
        y += 22;
        bar(y, "Envoyer le signalement", (Runnable) this::sendSignal);
    }

    private void sendSignal() {
        if (!CapitolClient.online()) { sigInfo = "Pas connecté au site : vérifie l'onglet Réglages."; return; }
        if (Data.tribById(sigTrib) == null) { sigInfo = "Choisis d'abord un tribut."; return; }
        CapitolClient.report(sigTrib, TYPES[sigType], sigDetail.trim(), sigUrgent, sigRec);
        sigInfo = "Envoyé : " + TYPES[sigType] + " · " + Data.tribLabel(sigTrib);
        sigDetail = ""; sigUrgent = false;
        clearAndInit();
    }

    /* ---------------------------------------------------------------- comms */
    private List<Data.Member> dmTargets() {
        List<Data.Member> l = new ArrayList<>();
        for (Data.Member m : Data.team) if (!m.id.equals(Data.meId)) l.add(m);
        l.sort((a, b) -> Boolean.compare(b.online, a.online));
        return l;
    }

    private void initComms() {
        List<Data.Member> targets = dmTargets();
        if (dmTo.isEmpty() && !targets.isEmpty()) dmTo = targets.get(0).id;
        String dmName = dmTo.isEmpty() ? "personne" : Data.memberName(dmTo);
        List<ButtonWidget> top = new ArrayList<>(Data.meSup
                ? bar(cy, "Staff", (Runnable) () -> setCh("all"), "Caméras", (Runnable) () -> setCh("cams"), "Privé", (Runnable) () -> setCh("dm"),
                "À : " + dmName + " ▶", (Runnable) () -> { dmTo = cycleMember(targets, dmTo); commsCh = "dm"; clearAndInit(); },
                "BUZZ", (Runnable) () -> { if (dmTo.isEmpty()) return; CapitolClient.buzz(dmTo, msgDraft.isBlank() ? "La régie a besoin de toi !" : msgDraft.trim()); say("Buzz envoyé à " + Data.memberName(dmTo)); })
                : bar(cy, "Staff", (Runnable) () -> setCh("all"), "Caméras", (Runnable) () -> setCh("cams"), "Privé", (Runnable) () -> setCh("dm"),
                "À : " + dmName + " ▶", (Runnable) () -> { dmTo = cycleMember(targets, dmTo); commsCh = "dm"; clearAndInit(); }));
        chip(top.get(0), commsCh.equals("all"));
        chip(top.get(1), commsCh.equals("cams"));
        chip(top.get(2), commsCh.equals("dm"));
        listY = cy + 22;
        listBottom = barY - 44;
        msgField = field(cx, barY - 40, cw - 74, msgDraft, commsCh.equals("dm") ? "Message à " + dmName : commsCh.equals("cams") ? "Message aux caméras" : "Message à tout le staff", 400, s -> msgDraft = s);
        btn(cx + cw - 70, barY - 40, 70, "Envoyer", this::sendMsg);
        Object[] q = new Object[QUICK.length * 2];
        for (int i = 0; i < QUICK.length; i++) { final String s = QUICK[i]; q[i * 2] = s; q[i * 2 + 1] = (Runnable) () -> { CapitolClient.message(commsCh, commsCh.equals("dm") ? dmTo : "", s); }; }
        bar(barY - 20, q);
        // la dernière rangée sert d'indication
        listBottom = barY - 44;
    }

    private void setCh(String c) { commsCh = c; scroll = 0; clearAndInit(); }

    private void sendMsg() {
        if (msgDraft.isBlank()) return;
        if (commsCh.equals("dm") && dmTo.isEmpty()) { say("Choisis un destinataire"); return; }
        CapitolClient.message(commsCh, commsCh.equals("dm") ? dmTo : "", msgDraft);
        msgDraft = "";
        if (msgField != null) msgField.setText("");
    }

    private void renderComms(DrawContext ctx) {
        List<Data.Msg> list = new ArrayList<>();
        for (Data.Msg m : Data.msgs) {
            boolean ok = switch (commsCh) {
                case "dm" -> (m.from.equals(Data.meId) && m.to.equals(dmTo)) || (m.from.equals(dmTo) && m.to.equals(Data.meId));
                case "cams" -> m.ch.equals("cams");
                default -> m.ch.equals("all");
            };
            if (ok) list.add(m);
        }
        List<OrderedText> lines = new ArrayList<>();
        List<Integer> cols = new ArrayList<>();
        for (Data.Msg m : list) {
            int c = m.from.equals(Data.meId) ? Ui.GOLD_HI : m.sup ? Hud.GOLD : Hud.BLUE;
            MutableText t = Text.literal(hhmm(m.ts) + "  ").withColor(Ui.MUTE & 0xFFFFFF)
                    .append(Text.literal(m.name).withColor(c & 0xFFFFFF).formatted(Formatting.BOLD))
                    .append(Text.literal(m.k.equals("buzz") ? "  BUZZ" : "").withColor(Ui.RED & 0xFFFFFF))
                    .append(Text.literal("  " + m.text).withColor(Ui.INK & 0xFFFFFF));
            for (OrderedText ot : textRenderer.wrapLines(t, cw - 14)) { lines.add(ot); cols.add(Ui.INK); }
        }
        int lh = 10, vis = Math.max(1, (listBottom - listY - 4) / lh);
        int maxScroll = Math.max(0, lines.size() - vis);
        scroll = Math.min(scroll, maxScroll);
        int start = Math.max(0, lines.size() - vis - scroll);
        Ui.box(ctx, cx, listY, cx + cw, listBottom, Ui.BG0, Ui.LINE);
        for (int i = start, y = listY + 4; i < lines.size() && y + lh <= listBottom; i++, y += lh)
            ctx.drawText(textRenderer, lines.get(i), cx + 7, y, cols.get(i), false);
        if (lines.isEmpty()) ctx.drawText(textRenderer, "Aucun message pour l'instant.", cx + 8, listY + 8, Ui.DIM, false);
    }

    /* ---------------------------------------------------------------- équipe */
    private void initTeam() {
        List<Data.Trib> list = Data.aliveTribs();
        if (Data.meSup) {
            bar(barY - 20,
                    "Assigner : " + (assignTrib.isEmpty() ? "choisir ▶" : Data.tribLabel(assignTrib) + " ▶"), (Runnable) () -> { assignTrib = cycleTrib(list, assignTrib, true, false); clearAndInit(); },
                    "Envoyer l'assignation", (Runnable) () -> {
                        Data.Cam c = Data.cams.get(selId);
                        if (c == null || Data.tribById(assignTrib) == null) { say("Sélectionne une caméra et un tribut"); return; }
                        CapitolClient.request(assignTrib, "Assignation de " + Data.meName, c.id);
                        say(c.name + " → " + Data.tribLabel(assignTrib));
                    },
                    "Buzz", (Runnable) () -> { Data.Cam c = Data.cams.get(selId); if (c != null) { CapitolClient.buzz(c.id, "On a besoin de toi !"); say("Buzz envoyé à " + c.name); } });
            listBottom = barY - 24;
        }
        bar(barY,
                "TP vers la caméra", (Runnable) () -> { Data.Cam c = Data.cams.get(selId); say(c == null ? "Sélectionne une caméra" : Teleport.toPlayer(c.mc)); },
                "TP vers son tribut", (Runnable) () -> { Data.Cam c = Data.cams.get(selId); say(c == null || c.follow.isEmpty() ? "Cette caméra ne suit personne" : Teleport.toTribute(Data.tribById(c.follow))); },
                "Message direct", (Runnable) () -> { if (!selId.isEmpty()) { dmTo = selId; commsCh = "dm"; tab = Tab.COMMS; clearAndInit(); } });
    }

    private void rowsTeam() {
        List<Data.Cam> list = new ArrayList<>(Data.cams.values());
        list.sort((a, b) -> Boolean.compare(b.online, a.online));
        for (Data.Cam c : list) {
            String role = "chef".equals(c.cam) ? "Responsable caméra" : "Caméra";
            String l1 = (c.online ? "● " : "○ ") + c.name + (c.mc.isEmpty() || c.mc.equalsIgnoreCase(c.name) ? "" : " (" + c.mc + ")") + " · " + role;
            String r1 = c.online ? Hud.stateLabel(c.st) : "hors ligne";
            String l2 = (c.follow.isEmpty() ? "ne suit personne" : "suit " + Data.tribLabel(c.follow))
                    + (c.hasPos ? String.format(Locale.ROOT, " · %d %d %d %s", Math.round(c.x), Math.round(c.y), Math.round(c.z), dimShort(c.d)) : "")
                    + (c.at > 0 ? " · maj il y a " + Data.ago(c.at) : "");
            rows.add(new Row(c.id, l1, c.online ? Hud.stateColor(c.st) : Hud.DIM, r1, l2, Hud.INK));
        }
        for (Data.Member m : Data.team) {
            if (Data.cams.containsKey(m.id)) continue;
            rows.add(new Row(m.id, (m.online ? "● " : "○ ") + m.name + " · " + ("admin".equals(m.perm) ? "Régie" : "mod".equals(m.perm) ? "Modération" : "Équipe"), m.online ? Hud.GOLD : Hud.DIM,
                    m.online ? "sur le site" : "", "Message direct possible depuis l'onglet Comms", Hud.DIM));
        }
    }

    /* ---------------------------------------------------------------- demandes */
    private void initReqs() {
        List<Data.Trib> list = Data.aliveTribs();
        int y = barY - 20;
        int lw = 150;
        btn(cx, y, lw, reqTrib.isEmpty() ? "Tribut ▶" : Data.tribLabel(reqTrib) + " ▶", () -> { reqTrib = cycleTrib(list, reqTrib, true, false); clearAndInit(); });
        int extra = Data.meSup ? 110 : 0;
        reqField = field(cx + lw + 3, y, cw - lw - 3 - 64 - extra, reqText, "Demande de couverture (facultatif)", 200, s -> reqText = s);
        if (Data.meSup) {
            List<Data.Member> cams = new ArrayList<>();
            for (Data.Member m : Data.team) if (Data.cams.containsKey(m.id)) cams.add(m);
            btn(cx + cw - 64 - extra, y, extra - 3, "Pour : " + (reqTo.isEmpty() ? "tous" : Data.memberName(reqTo)), () -> { reqTo = cycleMemberOrAll(cams, reqTo); clearAndInit(); });
        }
        btn(cx + cw - 61, y, 61, "Créer", () -> {
            if (Data.tribById(reqTrib) == null) { say("Choisis un tribut"); return; }
            CapitolClient.request(reqTrib, reqText.trim(), reqTo);
            reqText = ""; say("Demande envoyée"); clearAndInit();
        });
        listBottom = y - 4;
        bar(barY,
                "Je prends", (Runnable) () -> upd("take"),
                "Terminé", (Runnable) () -> upd("done"),
                "Libérer", (Runnable) () -> upd("drop"),
                "TP au tribut", (Runnable) () -> { Data.Req r = Data.reqs.get(selId); say(r == null ? "Sélectionne une demande" : Teleport.toTribute(Data.tribById(r.tr))); },
                "Suivre", (Runnable) () -> { Data.Req r = Data.reqs.get(selId); if (r != null) { CapitolClient.setFollow(r.tr); say("Tu suis " + Data.tribLabel(r.tr)); } });
    }

    private void upd(String op) {
        Data.Req r = Data.reqs.get(selId);
        if (r == null) { say("Sélectionne une demande"); return; }
        CapitolClient.reqUpd(r.id, op);
        if (op.equals("take")) { CapitolClient.setFollow(r.tr); CapitolClient.setState("rec"); }
    }

    private void rowsReqs() {
        List<Data.Req> list = new ArrayList<>(Data.reqs.values());
        list.sort((a, b) -> {
            int sa = "done".equals(a.state) ? 1 : 0, sb = "done".equals(b.state) ? 1 : 0;
            return sa != sb ? Integer.compare(sa, sb) : Long.compare(b.ts, a.ts);
        });
        for (Data.Req r : list) {
            String st = switch (r.state) { case "taken" -> "PRISE · " + r.takerName; case "done" -> "TERMINÉE"; default -> "OUVERTE"; };
            int c = switch (r.state) { case "taken" -> Hud.BLUE; case "done" -> Hud.DIM; default -> Hud.PURPLE; };
            boolean mine = Data.meId.equals(r.to);
            String l2 = (r.text.isEmpty() ? "" : r.text + " · ") + "par " + r.byName + (r.to.isEmpty() ? "" : " · pour " + (mine ? "TOI" : r.toName)) + " · " + hhmm(r.ts);
            rows.add(new Row(r.id, (mine ? "★ " : "") + Data.tribLabel(r.tr), c, st, l2, "done".equals(r.state) ? Hud.DIM : Hud.INK));
        }
    }


    /* ---------------------------------------------------------------- rôles */
    private Data.Role draftRole(String id) { if (roleDraft == null) return null; for (Data.Role r : roleDraft) if (r.id.equals(id)) return r; return null; }

    private void initRoles() {
        if (roleDraft == null) resetDraft();
        boolean admin = "admin".equals(Data.mePerm) || Data.mePerms.contains("admin");
        if (!rolesView) {
            Data.Member m = null;
            for (Data.Member x : Data.team) if (x.id.equals(selId)) m = x;
            final Data.Member sel = m;
            Data.Role cur = sel == null ? null : Data.role(sel.rid);
            bar(barY,
                    "Vue : membres", (Runnable) () -> { rolesView = true; selId = ""; scroll = 0; clearAndInit(); },
                    sel == null ? "Choisis un membre" : "Rôle : " + (cur == null ? "?" : cur.name) + " ▶", (Runnable) () -> {
                        if (sel == null || Data.roles.isEmpty()) return;
                        int i = -1;
                        for (int k = 0; k < Data.roles.size(); k++) if (Data.roles.get(k).id.equals(sel.rid)) i = k;
                        Data.Role nx = Data.roles.get((i + 1) % Data.roles.size());
                        if (!admin && (nx.perms.contains("admin") || nx.perms.contains("roles"))) nx = Data.roles.get((i + 2) % Data.roles.size());
                        CapitolClient.setRole(sel.id, nx.id);
                        say(sel.name + " → " + nx.name);
                    });
        } else {
            Data.Role r = draftRole(selId);
            if (r != null) {
                Object[] pairs = new Object[PERMS.length * 2];
                for (int i = 0; i < PERMS.length; i++) {
                    final String k = PERMS[i];
                    pairs[i * 2] = (r.perms.contains(k) ? "✔ " : "") + PERM_L[i];
                    pairs[i * 2 + 1] = (Runnable) () -> {
                        if (r.locked || ((k.equals("admin") || k.equals("roles")) && !admin)) { say("Réservé à la régie"); return; }
                        if (r.perms.contains(k)) r.perms.remove(k); else r.perms.add(k);
                        clearAndInit();
                    };
                }
                bar(barY - 20, pairs);
                listBottom = barY - 24;
            }
            bar(barY,
                    "Vue : rôles", (Runnable) () -> { rolesView = false; selId = ""; scroll = 0; clearAndInit(); },
                    "Annuler", (Runnable) () -> { resetDraft(); clearAndInit(); },
                    "Enregistrer les rôles", (Runnable) () -> { CapitolClient.saveRoles(roleDraft); say("Rôles envoyés"); roleDraft = null; });
        }
    }

    private static void resetDraft() {
        roleDraft = new ArrayList<>();
        for (Data.Role r : Data.roles) {
            Data.Role c = new Data.Role();
            c.id = r.id; c.name = r.name; c.color = r.color; c.locked = r.locked; c.perms = new ArrayList<>(r.perms);
            roleDraft.add(c);
        }
    }

    private static int color(String hex) {
        try { return 0xFF000000 | Integer.parseInt(hex.replace("#", ""), 16); } catch (Exception e) { return Hud.INK; }
    }

    private void rowsRoles() {
        if (!rolesView) {
            for (Data.Member m : Data.team) {
                Data.Role r = Data.role(m.rid);
                rows.add(new Row(m.id, (m.online ? "● " : "○ ") + m.name, r == null ? Hud.INK : color(r.color), r == null ? "?" : r.name,
                        (m.twitch.isEmpty() ? "" : "twitch.tv/" + m.twitch + " · ") + (r == null || r.perms.isEmpty() ? "aucune permission" : permsText(r.perms)), Hud.DIM));
            }
        } else if (roleDraft != null) {
            for (Data.Role r : roleDraft) {
                int n = 0;
                for (Data.Member m : Data.team) if (m.rid.equals(r.id)) n++;
                rows.add(new Row(r.id, r.name + (r.locked ? " (verrouillé)" : ""), color(r.color), n + " membre(s)", r.perms.isEmpty() ? "aucune permission" : permsText(r.perms), Hud.DIM));
            }
        }
    }

    private static String permsText(List<String> perms) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < PERMS.length; i++) if (perms.contains(PERMS[i])) out.add(PERM_L[i]);
        return String.join(", ", out);
    }

    /* ---------------------------------------------------------------- réglages */
    private void initSettings() {
        ClientConfig c = ClientConfig.get();
        int colW = Math.min(200, (cw - 10) / 2), x = cx, y = cy;
        int step = height < 300 ? 17 : 18;
        int cwid = (colW - 6) / 3;
        String[] pages = {"Général", "Bulles", "Caméra", "Diffusion"};
        cwid = (colW - 9) / 4;
        for (int i = 0; i < 4; i++) { final int k = i; chip(btn(x + i * (cwid + 3), y, cwid, pages[i], () -> { setPage = k; clearAndInit(); }), setPage == i); }
        y += 22;
        String[] states = {"actif", "rec", "pause", "inactif"};
        String[] notifs = {"all", "sup", "none"};
        String[] notifL = {"toutes", "supérieurs seulement", "aucune"};
        String[] hpos = {"tl", "tr", "bl"};
        String[] hposL = {"haut gauche", "haut droite", "bas gauche"};
        if (setPage == 0) {
        btn(x, y, colW, "État : " + Hud.stateLabel(c.state), () -> { c.state = next(states, c.state); CapitolClient.setState(c.state); clearAndInit(); }); y += step;
        btn(x, y, colW, "Notifications : " + notifL[idx(notifs, c.notif)], () -> { c.notif = next(notifs, c.notif); ClientConfig.save(); CapitolClient.setState(c.state); clearAndInit(); }); y += step;
        btn(x, y, colW, "Sons : " + (c.sound ? "oui" : "non"), () -> { c.sound = !c.sound; ClientConfig.save(); clearAndInit(); }); y += step;
        btn(x, y, colW, "Encart HUD : " + (c.hud ? "oui" : "non"), () -> { c.hud = !c.hud; ClientConfig.save(); clearAndInit(); }); y += step;
        btn(x, y, colW, "Position HUD : " + hposL[idx(hpos, c.hudPos)], () -> { c.hudPos = next(hpos, c.hudPos); ClientConfig.save(); clearAndInit(); }); y += step;
        btn(x, y, colW, "Envoi de ce que je vois : " + (c.autoReport ? "oui" : "non"), () -> { c.autoReport = !c.autoReport; ClientConfig.save(); clearAndInit(); }); y += step;
        } else if (setPage == 1) {
        btn(x, y, colW, "Bulles à l'écran : " + (c.toasts ? "oui" : "non"), () -> { c.toasts = !c.toasts; ClientConfig.save(); clearAndInit(); }); y += step;
        btn(x, y, colW, "Bulles des actions : " + (c.alertsToasts ? "oui" : "non"), () -> { c.alertsToasts = !c.alertsToasts; ClientConfig.save(); clearAndInit(); }); y += step;
        btn(x, y, colW, "Bulles des actes : " + (c.feedToasts ? "tous" : "mon tribut"), () -> { c.feedToasts = !c.feedToasts; ClientConfig.save(); clearAndInit(); }); y += step;
        } else if (setPage == 3) {
        btn(x, y, colW, "Diffuser mon image : " + (c.streamOn ? "oui" : "non"), () -> { c.streamOn = !c.streamOn; ClientConfig.save(); if (!c.streamOn) fr.capitoltv.client.Streamer.stop("coupée"); clearAndInit(); }); y += step;
        btn(x, y, colW, "Micro : " + (c.streamMic ? "oui" : "non"), () -> { c.streamMic = !c.streamMic; ClientConfig.save(); fr.capitoltv.client.Streamer.stop(null); clearAndInit(); }); y += step;
        int[] hs = {480, 720, 1080};
        btn(x, y, colW, "Qualité : " + c.streamHeight + "p", () -> { int i = 0; for (int k = 0; k < hs.length; k++) if (hs[k] == c.streamHeight) i = k; c.streamHeight = hs[(i + 1) % hs.length]; c.streamKbps = c.streamHeight >= 1080 ? 6000 : c.streamHeight >= 720 ? 4000 : 2000; ClientConfig.save(); fr.capitoltv.client.Streamer.stop(null); clearAndInit(); }); y += step;
        btn(x, y, colW, "Images / s : " + c.streamFps, () -> { c.streamFps = c.streamFps >= 30 ? 20 : c.streamFps + 5; ClientConfig.save(); clearAndInit(); }); y += step + 4;
        if (keyDraft == null) keyDraft = c.streamKey;
        field(x, y, colW - 52, keyDraft, Data.streamKey.isEmpty() ? "Clé de diffusion" : "Clé reçue du site", 64, s -> keyDraft = s);
        btn(x + colW - 49, y, 49, "Coller", () -> { String s = MinecraftClient.getInstance().keyboard.getClipboard(); if (s != null) { keyDraft = s.trim(); c.streamKey = keyDraft; ClientConfig.save(); fr.capitoltv.client.Streamer.stop(null); clearAndInit(); } });
        } else {
        String[] tal = {"visible", "discret", "cache"};
        String[] talL = {"visible", "discret (point)", "caché"};
        boolean camOk = fr.capitoltv.client.CamMode.allowed();
        btn(x, y, colW, "Mode caméra (F6) : " + (!camOk ? "réservé" : c.cameraMode ? "oui" : "non"), () -> {
            if (!fr.capitoltv.client.CamMode.allowed()) { say("Réservé aux caméras désignées par un admin"); return; }
            c.cameraMode = !c.cameraMode; ClientConfig.save(); clearAndInit();
        }).active = camOk; y += step;
        btn(x, y, colW, "Flux propre à l'antenne : " + (c.cleanOnAir ? "oui" : "non"), () -> { c.cleanOnAir = !c.cleanOnAir; ClientConfig.save(); clearAndInit(); }); y += step;
        btn(x, y, colW, "Voyant d'antenne : " + talL[idx(tal, c.tally)], () -> { c.tally = next(tal, c.tally); ClientConfig.save(); clearAndInit(); }); y += step;
        btn(x, y, colW, "Spectateur auto : " + (c.autoSpectator ? "oui" : "non"), () -> { c.autoSpectator = !c.autoSpectator; ClientConfig.save(); if (c.autoSpectator) { fr.capitoltv.client.CamMode.resetSpectatorTries(); fr.capitoltv.client.CamMode.ensureSpectator(MinecraftClient.getInstance(), true); } clearAndInit(); });
        }

        int x2 = cx + colW + 10, w2 = cw - colW - 10, y2 = cy + 24;
        if (tokenDraft == null) tokenDraft = c.token;
        if (urlDraft == null) urlDraft = c.url;
        tokenField = field(x2, y2, w2, tokenDraft, "Colle ton jeton ici", 2000, s -> tokenDraft = s);
        y2 += 20;
        int bw = (w2 - 6) / 4, bw2 = w2 - 6 - 2 * bw;
        btn(x2, y2, bw, "Coller", () -> { String s = MinecraftClient.getInstance().keyboard.getClipboard(); if (s != null) { tokenDraft = s.trim(); clearAndInit(); } });
        btn(x2 + bw + 3, y2, bw2, "Connecter", () -> {
            c.token = tokenDraft == null ? "" : tokenDraft.trim(); c.url = urlDraft == null || urlDraft.isBlank() ? c.url : urlDraft.trim();
            ClientConfig.save(); CapitolClient.reconnect(); say("Connexion…");
        });
        btn(x2 + bw + 3 + bw2 + 3, y2, bw, "Couper", () -> { CapitolClient.disconnect(); say("Déconnecté"); }).kind = CtvButton.Kind.DANGER;
        y2 += 34;
        urlField = field(x2, y2, w2, urlDraft, "Adresse du site", 300, s -> urlDraft = s);
    }

    /* ---------------------------------------------------------------- wiki */
    static final String[][] WIKI = {
        {"Démarrer",
         "1. Sur le site Capitol TV, ouvre « Mon compte » et génère ton jeton du mod.\n2. En jeu : touche K, onglet Réglages, bouton Coller puis Connecter.\n3. L'en-tête du menu passe au vert : tu reçois les actions, les comms et la carte.\n\nLe bouton ◧ en haut à droite du menu le range sur le côté : tu vois le jeu, tu te déplaces (ZQSD, espace, maj) et tu tournes la tête en maintenant le clic droit sur le jeu."},
        {"Mode caméra",
         "Réservé aux caméras : un admin fait /capitoltv camera add <pseudo> en jeu, ou te donne le rôle Caméra sur le site avec ton compte Minecraft dans ta fiche.\n\nF6 : active le mode caméra. Tu passes en spectateur, le HUD du jeu disparaît et une barre caméra apparaît en bas (touches 1 à 9) : TP sur le tribut suivi, tribut précédent / suivant, signaler, REC, caméra fluide, zoom, menu, écran propre.\n\nV : mouvements de caméra (voir l'article)."},
        {"Diffusion vidéo",
         "Ton jeu peut envoyer lui-même son image à Capitol TV, sans OBS : Réglages › Diffusion › Diffuser mon image.\n\nL'image part juste après le dessin du monde : tes menus, ton HUD et ton chat n'apparaissent jamais dans le flux. Tu peux donc utiliser le menu en filmant.\n\nClé : les membres de l'équipe la reçoivent automatiquement. Les tributs la trouvent sur le site (Mon compte) ou la demandent à la régie, puis la collent dans Réglages › Diffusion.\n\nMicro : facultatif (ta voix part avec l'image). Le différé est ajouté par le site, tu n'as rien à régler.\n\nAlternatives : OBS (service WHIP, lien donné sur le site) ou Twitch, si la régie le choisit."},
        {"Mouvements de caméra",
         "V ouvre la liste, puis une touche de 1 à 8 :\n1 Orbite autour du tribut suivi\n2 Orbite large en hauteur\n3 Travelling avant\n4 Grue (s'élève en le regardant)\n5 Suivi de dos\n6 Vue du ciel en rotation\n7 Panoramique sur place\n8 Regard verrouillé (tu bouges, la caméra le fixe)\n\nV arrête le mouvement. Bouger (ZQSD) l'arrête aussi, sauf le 8. Si le tribut est loin, tu es d'abord téléporté."},
        {"Antenne et différé",
         "Le site retarde tout ce qu'il montre (annonces, statuts, vidéos) du même délai. « Écran dans 1:42 » indique quand une action passera à l'antenne.\n\nQuand la régie affiche ta caméra, un voyant « EN DIRECT » s'allume en haut à droite. « PRÊT » : la régie prépare un plan avec toi.\n\nAvec OBS ou Twitch, l'écran devient propre à l'antenne (Alt gauche maintenu pour voir l'interface) et les sons du jeu sont coupés. Avec la diffusion du mod, ce n'est pas nécessaire."},
        {"Signaler et comms",
         "Signaler : choisis le tribut, le type d'action et des détails ; la régie et le réacteur le voient tout de suite.\nJ : signal rapide sur le tribut suivi ou le plus proche.\n\nComms : Staff (toute l'équipe), Caméras, Privé. Les messages passent aussi sur le site. BUZZ fait sonner un membre."},
        {"Carte et TP",
         "La carte montre les tributs (et les caméras). Glisser : déplacer · molette : zoom · clic : sélectionner, puis TP ou Suivre.\nEn spectateur, la téléportation est directe. Sinon le mod serveur ou la commande /tp est utilisé."},
        {"Commandes (admins)",
         "/capitoltv camera add <joueur> · remove <nom> · list\n/capitoltv annonce <texte> : annonce spéciale à la régie\n/capitoltv status · reload"}
    };

    private void initWiki() { }

    private void renderWiki(DrawContext ctx) {
        int lw = Math.min(118, cw / 3), x = cx, y = cy;
        Ui.box(ctx, x, y, x + lw, listBottom, Ui.BG0, Ui.LINE);
        for (int i = 0; i < WIKI.length; i++) {
            int yy = y + 4 + i * 16;
            if (i == wikiSec) { ctx.fill(x + 1, yy - 2, x + lw - 1, yy + 12, 0x30D4AF37); ctx.fill(x + 1, yy - 2, x + 3, yy + 12, Ui.GOLD); }
            ctx.drawText(textRenderer, textRenderer.trimToWidth(WIKI[i][0], lw - 12), x + 8, yy + 1, i == wikiSec ? Ui.GOLD_HI : Ui.DIM, false);
        }
        int tx = x + lw + 10, tw = cw - lw - 10;
        Ui.section(ctx, WIKI[wikiSec][0], tx, y + 2, tw);
        List<OrderedText> lines = new ArrayList<>();
        for (String para : WIKI[wikiSec][1].split("\n", -1)) {
            if (para.isEmpty()) { lines.add(OrderedText.EMPTY); continue; }
            lines.addAll(textRenderer.wrapLines(Text.literal(para), tw - 6));
        }
        int vis = Math.max(1, (listBottom - y - 18) / 10);
        wikiScroll = Math.max(0, Math.min(wikiScroll, Math.max(0, lines.size() - vis)));
        for (int i = wikiScroll, yy = y + 16; i < lines.size() && yy + 9 <= listBottom; i++, yy += 10) ctx.drawText(textRenderer, lines.get(i), tx, yy, Ui.INK, false);
    }

    private boolean wikiClick(double mx, double my) {
        if (tab != Tab.WIKI) return false;
        int lw = Math.min(118, cw / 3);
        if (mx < cx || mx >= cx + lw || my < cy) return false;
        int i = (int) ((my - cy - 2) / 16);
        if (i >= 0 && i < WIKI.length) { wikiSec = i; wikiScroll = 0; return true; }
        return false;
    }

    static int idx(String[] a, String v) { for (int i = 0; i < a.length; i++) if (a[i].equals(v)) return i; return 0; }
    static String next(String[] a, String v) { return a[(idx(a, v) + 1) % a.length]; }

    private void renderSettings(DrawContext ctx) {
        ClientConfig c = ClientConfig.get();
        int colW = Math.min(200, (cw - 10) / 2), x2 = cx + colW + 10, w2 = cw - colW - 10;
        Ui.section(ctx, "Liaison avec le site", x2, cy + 2, w2);
        int hy = cy + 22 + (setPage == 3 ? 4 * (height < 300 ? 17 : 18) + 28 : 5 * (height < 300 ? 17 : 18) + 4);
        String h = setPage == 2 ? (fr.capitoltv.client.CamMode.allowed() ? "" : "Réservé aux caméras désignées (Wiki › Mode caméra).")
                : setPage == 3 ? "État : " + fr.capitoltv.client.Streamer.status() + (fr.capitoltv.client.Streamer.live() ? " · " + fr.capitoltv.client.Streamer.sent() + " images" : "") : "";
        if (setPage == 3 && Data.streamKey.isEmpty() && c.streamKey.isBlank()) h += " · Pas de clé : Wiki › Diffusion.";
        for (OrderedText ot : textRenderer.wrapLines(Text.literal(h), colW)) { if (hy > barY - 4) break; ctx.drawText(textRenderer, ot, cx, hy, fr.capitoltv.client.Streamer.live() && setPage == 3 ? Ui.GREEN : Ui.DIM, false); hy += 10; }
        ctx.drawText(textRenderer, "Jeton du mod :", x2, cy + 14, Hud.DIM, false);
        ctx.drawText(textRenderer, "Adresse du site :", x2, cy + 70, Hud.DIM, false);
        int y = cy + 104;
        String st = "État : " + CapitolClient.stateText();
        for (OrderedText ot : textRenderer.wrapLines(Text.literal(st), w2)) { ctx.drawText(textRenderer, ot, x2, y, CapitolClient.online() ? Hud.GREEN : Hud.ORANGE, false); y += 10; }
        if (CapitolClient.online() && Data.ready) {
            ctx.drawText(textRenderer, "Compte : " + Data.meName + " · " + ("chef".equals(Data.meCam) ? "responsable caméra" : Data.meSup ? "régie" : "caméra"), x2, y, Hud.INK, false); y += 10;
            ctx.drawText(textRenderer, "Mod serveur : " + (Data.serverMod ? "actif (données complètes)" : "absent (données des caméras)"), x2, y, Hud.INK, false); y += 10;
            ctx.drawText(textRenderer, "TP : " + (MinecraftClient.getInstance().player != null && MinecraftClient.getInstance().player.isSpectator() ? "spectateur (vanilla)" : Teleport.serverSide() ? "via le mod serveur" : "commande /tp (droits requis)"), x2, y, Hud.INK, false); y += 10;
        }
        if (c.token.isEmpty()) {
            y += 4;
            for (OrderedText ot : textRenderer.wrapLines(Text.literal("Jeton : site Capitol TV › Mon compte (voir Wiki)."), w2)) {
                if (y > py + ph - 12) break; ctx.drawText(textRenderer, ot, x2, y, Hud.GOLD, false); y += 10;
            }
        }
    }

    /* ---------------------------------------------------------------- utilitaires */
    static String cycleTrib(List<Data.Trib> list, String cur, boolean fwd) { return cycleTrib(list, cur, fwd, true); }

    static String cycleTrib(List<Data.Trib> list, String cur, boolean fwd, boolean allowNone) {
        List<String> ids = new ArrayList<>();
        if (allowNone) ids.add("");
        for (Data.Trib t : list) ids.add(t.id);
        if (ids.isEmpty()) return "";
        int i = ids.indexOf(cur);
        if (i < 0) return ids.get(0);
        return ids.get(((i + (fwd ? 1 : -1)) % ids.size() + ids.size()) % ids.size());
    }

    static String cycleMember(List<Data.Member> list, String cur) {
        if (list.isEmpty()) return "";
        int i = -1;
        for (int k = 0; k < list.size(); k++) if (list.get(k).id.equals(cur)) i = k;
        return list.get((i + 1) % list.size()).id;
    }

    static String cycleMemberOrAll(List<Data.Member> list, String cur) {
        List<String> ids = new ArrayList<>();
        ids.add("");
        for (Data.Member m : list) ids.add(m.id);
        int i = ids.indexOf(cur);
        return ids.get((i + 1) % ids.size());
    }

    static String statusFr(String s) {
        return switch (s) { case "injured" -> "blessé"; case "missing" -> "disparu"; case "dead" -> "décédé"; default -> "en vie"; };
    }

    static String fmt1(double v) { return (Math.round(v * 10) / 10.0 + "").replace(".0", ""); }
    static String hhmm(long ts) { return new SimpleDateFormat("HH:mm").format(new Date(ts)); }
    static String hhmmss(long ts) { return new SimpleDateFormat("HH:mm:ss").format(new Date(ts)); }

    static void border(DrawContext ctx, int x0, int y0, int x1, int y1, int c) {
        ctx.fill(x0, y0, x1, y0 + 1, c);
        ctx.fill(x0, y1 - 1, x1, y1, c);
        ctx.fill(x0, y0, x0 + 1, y1, c);
        ctx.fill(x1 - 1, y0, x1, y1, c);
    }

    private boolean listTab() { return tab == Tab.ACTIONS || tab == Tab.FEED || tab == Tab.TEAM || tab == Tab.REQS || tab == Tab.ROLES; }

    /* ====================================================================== */
    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!ClientConfig.get().dockMenu) ctx.fillGradient(0, 0, width, height, 0xB0050506, 0xD0050506);
        int x1 = px + pw, y1 = py + ph;
        // ombre portée + panneau
        ctx.fill(px + 3, py + 3, x1 + 3, y1 + 3, 0x60000000);
        Ui.box(ctx, px, py, x1, y1, Ui.BG1, Ui.LINE);
        ctx.fillGradient(px + 1, py + 1, x1 - 1, py + HEAD, 0xFF1E1B16, 0xFF141317);
        ctx.fill(px + 1, py + HEAD, x1 - 1, py + HEAD + 1, Ui.GOLD);
        ctx.fill(px + 1, py + HEAD + 1, x1 - 1, py + HEAD + 2, 0x40D4AF37);
        // colonne des onglets
        ctx.fill(px + 1, py + HEAD + 2, px + SIDE, y1 - 1, Ui.BG0);
        ctx.fill(px + SIDE, py + HEAD + 2, px + SIDE + 1, y1 - 1, Ui.LINE);
        // champs de saisie
        for (TextFieldWidget f : fields) {
            boolean foc = f.isFocused();
            Ui.box(ctx, f.getX() - 5, f.getY() - 4, f.getX() + f.getWidth() + 5, f.getY() + 12, 0xFF0D0D10, foc ? Ui.GOLD : Ui.LINE_HI);
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        // en-tête : emblème, titre, onglet
        Ui.emblem(ctx, px + 7, py + 5, 16);
        if (pw > 330) {
            Text title = Text.literal("CAPITOL TV").formatted(Formatting.BOLD);
            ctx.drawText(textRenderer, title, px + 28, py + 5, Ui.GOLD, true);
            ctx.drawText(textRenderer, tab.label.toUpperCase(), px + 28, py + 15, Ui.DIM, false);
        } else ctx.drawText(textRenderer, tab.label.toUpperCase(), px + 28, py + 9, Ui.GOLD, false);
        // à droite : compte, état, différé
        int rx = px + pw - 28;
        String delayTxt = Data.delay > 0 ? "DIFFÉRÉ " + Data.delay / 60 + ":" + String.format(Locale.ROOT, "%02d", Data.delay % 60) : "DIRECT";
        rx -= textRenderer.getWidth(delayTxt) + 8;
        Ui.pill(ctx, delayTxt, rx, py + 7, 0xFF2A2620, Ui.GOLD_HI);
        String who = CapitolClient.online() && Data.ready ? Data.meName + " · " + Hud.stateLabel(ClientConfig.get().state) : CapitolClient.stateText();
        if (fr.capitoltv.client.Streamer.live()) who = "● DIFF. · " + who;
        who = textRenderer.trimToWidth(who, Math.max(40, pw - 200));
        int col = CapitolClient.online() ? Hud.stateColor(ClientConfig.get().state) : Hud.ORANGE;
        int ww = textRenderer.getWidth(who) + 20;
        rx -= ww + 4;
        Ui.box(ctx, rx, py + 7, rx + ww, py + 19, 0xFF141317, Ui.alpha(col, 0x90));
        ctx.fill(rx + 6, py + 11, rx + 10, py + 15, col);
        ctx.drawText(textRenderer, who, rx + 14, py + 9, Ui.INK, false);

        if (Data.onAir()) {
            String w = "● À L'ANTENNE · ce menu est visible dans ton stream";
            int w2 = textRenderer.getWidth(w) + 12, wx = cx + (cw - w2) / 2;
            Ui.box(ctx, wx, py + HEAD - 8, wx + w2, py + HEAD + 6, 0xF0B0182C, Ui.RED);
            ctx.drawText(textRenderer, w, wx + 6, py + HEAD - 5, 0xFFFFFFFF, false);
        }

        if (!CapitolClient.online() && tab != Tab.SETTINGS && tab != Tab.MAP && tab != Tab.WIKI) {
            String w = "Pas connecté au site Capitol TV · onglet Réglages pour coller ton jeton";
            int w2 = Math.min(cw, textRenderer.getWidth(w) + 12);
            Ui.box(ctx, cx, listBottom - 16, cx + w2, listBottom - 2, 0xE0301C0C, Hud.ORANGE);
            ctx.drawText(textRenderer, textRenderer.trimToWidth(w, w2 - 10), cx + 6, listBottom - 13, Hud.ORANGE, false);
        }

        switch (tab) {
            case MAP -> renderMap(ctx, mouseX, mouseY);
            case COMMS -> renderComms(ctx);
            case SETTINGS -> renderSettings(ctx);
            case WIKI -> renderWiki(ctx);
            case SIGNAL -> renderSignal(ctx);
            default -> { }
        }
        if (listTab()) renderList(ctx, mouseX, mouseY);

        if (!status.isEmpty() && System.currentTimeMillis() - statusAt < 6000) {
            String s = textRenderer.trimToWidth(status, cw - 20);
            int w = textRenderer.getWidth(s) + 16, x = cx + cw - w, y = barY - 20;
            if (tab == Tab.MAP) y = mapY0() + 4;
            Ui.box(ctx, x, y, x + w, y + 14, 0xF0201C14, Ui.GOLD);
            ctx.drawText(textRenderer, s, x + 8, y + 3, Ui.GOLD_HI, false);
        }
    }

    private void renderSignal(DrawContext ctx) {
        Ui.section(ctx, "Tribut concerné", cx, cy + 2, cw);
        Ui.section(ctx, "Type d'action", cx, cy + 34, cw);
        Ui.section(ctx, "Détails", cx, cy + 112, cw);
        int y = cy + 170;
        List<String> info = new ArrayList<>();
        if (Data.delay > 0) {
            long at = Data.now() + Data.delay * 1000L;
            info.add("À l'écran vers " + hhmmss(at) + " (différé " + Data.delay / 60 + ":" + String.format(Locale.ROOT, "%02d", Data.delay % 60) + ").");
        }
        if (!sigInfo.isEmpty()) info.add(sigInfo + (Data.lastReportTs > 0 && Data.delay > 0 ? " — " + Data.airLabel(Data.lastReportTs) : ""));

        for (String s : info) for (OrderedText ot : textRenderer.wrapLines(Text.literal(s), cw)) { if (y > py + ph - 12) return; ctx.drawText(textRenderer, ot, cx, y, Ui.DIM, false); y += 10; }
    }

    private void renderList(DrawContext ctx, int mouseX, int mouseY) {
        rows.clear();
        switch (tab) {
            case ACTIONS -> rowsActions();
            case FEED -> rowsFeed();
            case TEAM -> rowsTeam();
            case REQS -> rowsReqs();
            case ROLES -> rowsRoles();
            default -> { }
        }
        int rh = RH, vis = Math.max(1, (listBottom - listY) / rh);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - vis)));
        int iw = cw - (rows.size() > vis ? 6 : 0);
        ctx.enableScissor(cx, listY, cx + cw, listBottom);
        for (int i = scroll, y = listY; i < rows.size() && y < listBottom; i++, y += rh) {
            Row r = rows.get(i);
            boolean sel = r.id.equals(selId), hov = mouseX >= cx && mouseX < cx + iw && mouseY >= y && mouseY < y + rh - 2;
            Ui.box(ctx, cx, y, cx + iw, y + rh - 2, sel ? 0xFF2A2416 : hov ? Ui.BG3 : Ui.BG2, sel ? Ui.GOLD : hov ? Ui.LINE_HI : Ui.LINE);
            ctx.fill(cx + 1, y + 2, cx + 3, y + rh - 4, r.c1);
            int rw = r.r1 == null || r.r1.isEmpty() ? 0 : textRenderer.getWidth(r.r1) + 10;
            ctx.drawText(textRenderer, textRenderer.trimToWidth(r.l1, iw - 14 - rw), cx + 8, y + 3, r.c1, false);
            if (rw > 0) {
                boolean air = r.r1.contains("écran");
                ctx.drawText(textRenderer, r.r1, cx + iw - rw + 2, y + 3, air ? Ui.GOLD_HI : Ui.DIM, false);
            }
            ctx.drawText(textRenderer, textRenderer.trimToWidth(r.l2, iw - 14), cx + 8, y + 13, r.c2, false);
        }
        ctx.disableScissor();
        if (rows.isEmpty()) {
            String e = switch (tab) {
                case ACTIONS -> pendingOnly ? "Aucune action en attente." : "Aucune action signalée.";
                case FEED -> "Aucun acte annonciateur pour l'instant.";
                case TEAM -> "Aucune caméra dans l'équipe (la régie attribue le rôle Caméra sur le site).";
                case ROLES -> "Aucun membre.";
                default -> "Aucune demande.";
            };
            Ui.box(ctx, cx, listY, cx + cw, listY + 40, Ui.BG0, Ui.LINE);
            Ui.emblem(ctx, cx + 10, listY + 8, 24);
            ctx.drawText(textRenderer, textRenderer.trimToWidth(e, cw - 50), cx + 42, listY + 16, Ui.DIM, false);
        }
        if (rows.size() > vis) {
            int track = listBottom - listY, th = Math.max(10, track * vis / rows.size());
            int ty = listY + (track - th) * scroll / Math.max(1, rows.size() - vis);
            ctx.fill(cx + cw - 3, listY, cx + cw - 1, listBottom, 0x30FFFFFF);
            ctx.fill(cx + cw - 3, ty, cx + cw - 1, ty + th, Ui.GOLD);
        }
    }

    /* ====================================================================== */
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (wikiClick(mx, my)) return true;
        if (button == 1 && !inPanel(mx, my)) { looking = true; return true; }
        if (inMap(mx, my)) { pressX = mx; pressY = my; dragging = false; pressedOnMap = true; return true; }
        if (listTab() && mx >= cx && mx < cx + cw && my >= listY && my < listBottom) {
            int i = (int) ((my - listY) / RH) + scroll;
            if (i >= 0 && i < rows.size()) {
                String id = rows.get(i).id;
                selId = id.equals(selId) ? "" : id;
                clearAndInit();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (looking && button == 1) {
            var pl = MinecraftClient.getInstance().player;
            if (pl != null) pl.changeLookDirection(dx * 4, dy * 4);
            return true;
        }
        if (pressedOnMap && tab == Tab.MAP) {
            if (Math.abs(mx - pressX) + Math.abs(my - pressY) > 3) dragging = true;
            if (dragging) { camX -= dx / scale; camZ -= dy / scale; }
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 1 && looking) { looking = false; return true; }
        if (pressedOnMap && tab == Tab.MAP) {
            pressedOnMap = false;
            if (!dragging) {
                Object hit = mapHit(mx, my);
                if (hit != null) { mapSel = (String) hit; pickSet = false; }
                else { mapSel = ""; pickSet = true; pickX = wx(mx); pickZ = wz(my); }
            }
            dragging = false;
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if (inMap(mx, my)) {
            double bx = wx(mx), bz = wz(my);
            scale = Math.max(0.01, Math.min(16, scale * (v > 0 ? 1.25 : 0.8)));
            camX = bx - (mx - (mapX0() + mapX1()) / 2.0) / scale;
            camZ = bz - (my - (mapY0() + mapY1()) / 2.0) / scale;
            return true;
        }
        if (tab == Tab.COMMS) { scroll = Math.max(0, scroll + (v > 0 ? 3 : -3)); return true; }
        if (tab == Tab.WIKI) { wikiScroll = Math.max(0, wikiScroll + (v > 0 ? -2 : 2)); return true; }
        if (listTab()) { scroll = Math.max(0, scroll + (v > 0 ? -1 : 1)); return true; }
        return super.mouseScrolled(mx, my, h, v);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean enter = keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER;
        if (enter && msgField != null && msgField.isFocused()) { sendMsg(); return true; }
        if (enter && detailField != null && detailField.isFocused()) { sendSignal(); return true; }
        boolean typing = (detailField != null && detailField.isFocused()) || (msgField != null && msgField.isFocused())
                || (reqField != null && reqField.isFocused()) || (tokenField != null && tokenField.isFocused()) || (urlField != null && urlField.isFocused());
        if (!typing && CapitolClient.menuKey.matchesKey(keyCode, scanCode)) { close(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void tick() {
        super.tick();
        if (tab == Tab.MAP && !fitted) fitMap();
        // on peut se déplacer pendant que le menu est ouvert (sauf pendant la saisie de texte)
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean typing = getFocused() instanceof TextFieldWidget;
        long win = mc.getWindow().getHandle();
        for (net.minecraft.client.option.KeyBinding kb : new net.minecraft.client.option.KeyBinding[]{mc.options.forwardKey, mc.options.backKey, mc.options.leftKey, mc.options.rightKey, mc.options.jumpKey, mc.options.sneakKey, mc.options.sprintKey}) {
            net.minecraft.client.util.InputUtil.Key k = net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.getBoundKeyOf(kb);
            boolean down = !typing && k.getCategory() == net.minecraft.client.util.InputUtil.Type.KEYSYM && k.getCode() > 0 && net.minecraft.client.util.InputUtil.isKeyPressed(win, k.getCode());
            kb.setPressed(down);
        }
    }

    @Override
    public void removed() {
        super.removed();
        net.minecraft.client.option.KeyBinding.unpressAll();
    }
}
