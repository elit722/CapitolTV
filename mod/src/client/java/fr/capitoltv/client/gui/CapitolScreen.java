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
import net.minecraft.text.OrderedText;
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
        ACTIONS("Actions"), FEED("Annonces"), MAP("Carte"), SIGNAL("Signaler"), COMMS("Comms"), TEAM("Équipe"), REQS("Demandes"), ROLES("Rôles"), SETTINGS("Réglages");
        final String label;
        Tab(String l) { label = l; }
    }

    static final String[] TYPES = {"Combat", "Alliance", "Trahison", "Mort imminente", "Cadeau sponsor", "Moment drôle", "Révélation", "Autre"};
    static final String[] FEED_KINDS = {"", "hit", "death", "effect", "meet", "special"};
    static final String[] FEED_LABELS = {"Tout", "Combats & dégâts", "Morts", "Effets", "Rencontres", "Spécial"};
    static final String[] QUICK = {"Reçu ✔", "J'y vais", "Besoin d'aide", "Action terminée", "Je change de tribut", "Je fais une pause"};

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
    private boolean dragging, pressedOnMap;

    private record Row(String id, String l1, int c1, String r1, String l2, int c2) { }

    public CapitolScreen() { super(Text.literal("Capitol TV")); }

    @Override public boolean shouldPause() { return false; }

    static void say(String s) { status = s; statusAt = System.currentTimeMillis(); }

    /* ====================================================================== */
    @Override
    protected void init() {
        px = 8; py = 8; pw = width - 16; ph = height - 16;
        cx = px + 80; cy = py + 22; cw = pw - 86; ch = ph - 26;
        barY = py + ph - 20;
        listY = cy + 2; listBottom = barY - 4;
        detailField = msgField = reqField = tokenField = urlField = null;

        int ty = cy;
        if (tab == Tab.ROLES && !Data.can("roles")) tab = Tab.ACTIONS;
        for (Tab t : Tab.values()) {
            if (t == Tab.ROLES && !Data.can("roles")) continue;
            ButtonWidget b = btn(px + 4, ty, 72, (t == tab ? "▶ " : "") + t.label, () -> { tab = t; scroll = 0; selId = ""; clearAndInit(); });
            b.active = t != tab;
            ty += 18;
        }
        btn(px + 4, py + ph - 20, 72, "Fermer", this::close);

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
        }
    }

    private ButtonWidget btn(int x, int y, int w, String label, Runnable r) {
        ButtonWidget b = ButtonWidget.builder(Text.literal(label), x0 -> r.run()).dimensions(x, y, w, 16).build();
        addDrawableChild(b);
        return b;
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
        TextFieldWidget f = new TextFieldWidget(textRenderer, x, y, w, 16, Text.literal(placeholder));
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
                pendingOnly ? "Filtre : en attente" : "Filtre : toutes", (Runnable) () -> { pendingOnly = !pendingOnly; scroll = 0; clearAndInit(); },
                "TP au tribut", (Runnable) () -> { Data.Alert x = selAlert(); say(x == null ? "Sélectionne une action" : Teleport.toTribute(alertTrib(x))); },
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
            ButtonWidget b = btn(cx + (i % cols) * (w + gap), y + (i / cols) * 18, w, (i == sigType ? "● " : "") + TYPES[i], () -> { sigType = k; clearAndInit(); });
            b.active = i != sigType;
        }
        y += 40;
        bar(y,
                "Urgent : " + (sigUrgent ? "OUI" : "non"), (Runnable) () -> { sigUrgent = !sigUrgent; clearAndInit(); },
                "Je filme cette action : " + (sigRec ? "oui" : "non"), (Runnable) () -> { sigRec = !sigRec; clearAndInit(); });
        y += 30;
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
        top.get(0).active = !commsCh.equals("all");
        top.get(1).active = !commsCh.equals("cams");
        top.get(2).active = !commsCh.equals("dm");
        listY = cy + 20;
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
            String head = hhmm(m.ts) + " " + m.name + (m.k.equals("buzz") ? " [BUZZ]" : "") + " : ";
            int c = m.from.equals(Data.meId) ? Hud.DIM : m.sup ? Hud.GOLD : Hud.BLUE;
            for (OrderedText ot : textRenderer.wrapLines(Text.literal(head + m.text), cw - 8)) { lines.add(ot); cols.add(c); }
        }
        int lh = 10, vis = Math.max(1, (listBottom - listY - 4) / lh);
        int maxScroll = Math.max(0, lines.size() - vis);
        scroll = Math.min(scroll, maxScroll);
        int start = Math.max(0, lines.size() - vis - scroll);
        ctx.fill(cx, listY, cx + cw, listBottom, 0x60000000);
        for (int i = start, y = listY + 3; i < lines.size() && y + lh <= listBottom; i++, y += lh)
            ctx.drawText(textRenderer, lines.get(i), cx + 4, y, cols.get(i), false);
        if (lines.isEmpty()) ctx.drawText(textRenderer, "Aucun message pour l'instant.", cx + 6, listY + 6, Hud.DIM, false);
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
        int colW = Math.min(200, (cw - 10) / 2), x = cx, y = cy + 12;
        int step = height < 300 ? 17 : 18;
        String[] states = {"actif", "rec", "pause", "inactif"};
        String[] notifs = {"all", "sup", "none"};
        String[] notifL = {"toutes", "supérieurs seulement", "aucune"};
        String[] hpos = {"tl", "tr", "bl"};
        String[] hposL = {"haut gauche", "haut droite", "bas gauche"};
        btn(x, y, colW, "État : " + Hud.stateLabel(c.state), () -> { c.state = next(states, c.state); CapitolClient.setState(c.state); clearAndInit(); }); y += step;
        btn(x, y, colW, "Notifications : " + notifL[idx(notifs, c.notif)], () -> { c.notif = next(notifs, c.notif); ClientConfig.save(); CapitolClient.setState(c.state); clearAndInit(); }); y += step;
        btn(x, y, colW, "Sons : " + (c.sound ? "oui" : "non"), () -> { c.sound = !c.sound; ClientConfig.save(); clearAndInit(); }); y += step;
        btn(x, y, colW, "Bulles à l'écran : " + (c.toasts ? "oui" : "non"), () -> { c.toasts = !c.toasts; ClientConfig.save(); clearAndInit(); }); y += step;
        btn(x, y, colW, "Bulles des actions : " + (c.alertsToasts ? "oui" : "non"), () -> { c.alertsToasts = !c.alertsToasts; ClientConfig.save(); clearAndInit(); }); y += step;
        btn(x, y, colW, "Bulles des actes : " + (c.feedToasts ? "tous" : "mon tribut"), () -> { c.feedToasts = !c.feedToasts; ClientConfig.save(); clearAndInit(); }); y += step;
        btn(x, y, colW, "Encart HUD : " + (c.hud ? "oui" : "non"), () -> { c.hud = !c.hud; ClientConfig.save(); clearAndInit(); }); y += step;
        btn(x, y, colW, "Position HUD : " + hposL[idx(hpos, c.hudPos)], () -> { c.hudPos = next(hpos, c.hudPos); ClientConfig.save(); clearAndInit(); }); y += step;
        btn(x, y, colW, "Envoi de ce que je vois : " + (c.autoReport ? "oui" : "non"), () -> { c.autoReport = !c.autoReport; ClientConfig.save(); clearAndInit(); }); y += step;
        String[] tal = {"visible", "discret", "cache"};
        String[] talL = {"visible", "discret (point)", "caché"};
        btn(x, y, colW, "Mode caméra (F6) : " + (c.cameraMode ? "oui" : "non"), () -> { c.cameraMode = !c.cameraMode; ClientConfig.save(); clearAndInit(); }); y += step;
        btn(x, y, colW, "Flux propre à l'antenne : " + (c.cleanOnAir ? "oui" : "non"), () -> { c.cleanOnAir = !c.cleanOnAir; ClientConfig.save(); clearAndInit(); }); y += step;
        btn(x, y, colW, "Voyant d'antenne : " + talL[idx(tal, c.tally)], () -> { c.tally = next(tal, c.tally); ClientConfig.save(); clearAndInit(); }); y += step;
        btn(x, y, colW, "Spectateur auto : " + (c.autoSpectator ? "oui" : "non"), () -> { c.autoSpectator = !c.autoSpectator; ClientConfig.save(); if (c.autoSpectator) { fr.capitoltv.client.CamMode.resetSpectatorTries(); fr.capitoltv.client.CamMode.ensureSpectator(MinecraftClient.getInstance(), true); } clearAndInit(); });

        int x2 = cx + colW + 10, w2 = cw - colW - 10, y2 = cy + 24;
        if (tokenDraft == null) tokenDraft = c.token;
        if (urlDraft == null) urlDraft = c.url;
        tokenField = field(x2, y2, w2, tokenDraft, "Colle ici ton jeton (site › Mon compte › Mod caméra)", 2000, s -> tokenDraft = s);
        y2 += 20;
        int bw = (w2 - 6) / 3;
        btn(x2, y2, bw, "Coller", () -> { String s = MinecraftClient.getInstance().keyboard.getClipboard(); if (s != null) { tokenDraft = s.trim(); clearAndInit(); } });
        btn(x2 + bw + 3, y2, bw, "Connecter", () -> {
            c.token = tokenDraft == null ? "" : tokenDraft.trim(); c.url = urlDraft == null || urlDraft.isBlank() ? c.url : urlDraft.trim();
            ClientConfig.save(); CapitolClient.reconnect(); say("Connexion…");
        });
        btn(x2 + 2 * (bw + 3), y2, bw, "Déconnecter", () -> { CapitolClient.disconnect(); say("Déconnecté"); });
        y2 += 34;
        urlField = field(x2, y2, w2, urlDraft, "Adresse du site Capitol TV", 300, s -> urlDraft = s);
    }

    static int idx(String[] a, String v) { for (int i = 0; i < a.length; i++) if (a[i].equals(v)) return i; return 0; }
    static String next(String[] a, String v) { return a[(idx(a, v) + 1) % a.length]; }

    private void renderSettings(DrawContext ctx) {
        ClientConfig c = ClientConfig.get();
        int colW = Math.min(200, (cw - 10) / 2), x2 = cx + colW + 10, w2 = cw - colW - 10;
        ctx.drawText(textRenderer, "Préférences", cx, cy + 2, Hud.GOLD, false);
        ctx.drawText(textRenderer, "Liaison avec le site", x2, cy + 2, Hud.GOLD, false);
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
        y += 6;
        String help = "Touches : " + CapitolClient.menuKeyName() + " menu · " + CapitolClient.signalKey.getBoundKeyLocalizedText().getString()
                + " signal rapide · " + CapitolClient.cameraKey.getBoundKeyLocalizedText().getString() + " mode caméra (barre 1 à 9) · "
                + CapitolClient.peekKey.getBoundKeyLocalizedText().getString() + " maintenu : voir l'interface à l'antenne. Les sons sont coupés à l'antenne (ils partiraient dans ton stream).";
        for (OrderedText ot : textRenderer.wrapLines(Text.literal(help), w2)) { ctx.drawText(textRenderer, ot, x2, y, Hud.DIM, false); y += 10; }
        if (c.token.isEmpty()) {
            y += 4;
            for (OrderedText ot : textRenderer.wrapLines(Text.literal("Pour obtenir un jeton : connecte-toi sur le site Capitol TV, ouvre « Mon compte » puis « Mod caméra ». La régie doit t'avoir donné le rôle Caméra dans l'équipe."), w2)) {
                ctx.drawText(textRenderer, ot, x2, y, Hud.GOLD, false); y += 10;
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
        ctx.fill(0, 0, width, height, 0x90000000);
        ctx.fill(px, py, px + pw, py + ph, 0xF0101014);
        border(ctx, px, py, px + pw, py + ph, 0xFF3A3630);
        ctx.fill(px, py, px + pw, py + 18, 0xFF17161A);
        ctx.fill(px, py + 18, px + pw, py + 19, 0xFFD4AF37);
        ctx.fill(px + 78, py + 19, px + 79, py + ph, 0xFF2A2724);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        // en-tête
        ctx.drawText(textRenderer, "CAPITOL TV", px + 6, py + 5, Hud.GOLD, true);
        ctx.drawText(textRenderer, "· " + tab.label, px + 6 + textRenderer.getWidth("CAPITOL TV "), py + 5, Hud.INK, false);
        String right = (CapitolClient.online() && Data.ready ? "● " + Data.meName + " · " + Hud.stateLabel(ClientConfig.get().state) : "○ " + CapitolClient.stateText())
                + (Data.delay > 0 ? " · différé " + Data.delay / 60 + ":" + String.format(Locale.ROOT, "%02d", Data.delay % 60) : " · direct");
        right = textRenderer.trimToWidth(right, pw / 2);
        ctx.drawText(textRenderer, right, px + pw - 6 - textRenderer.getWidth(right), py + 5, CapitolClient.online() ? Hud.stateColor(ClientConfig.get().state) : Hud.ORANGE, false);

        if (Data.onAir()) {
            String w = "● À L'ANTENNE : ce menu est visible dans ton stream";
            int ww = textRenderer.getWidth(w) + 8, wx = px + (pw - ww) / 2;
            ctx.fill(wx, py + 2, wx + ww, py + 15, 0xE0B0182C);
            ctx.drawText(textRenderer, w, wx + 4, py + 5, 0xFFFFFFFF, false);
        }
        if (!CapitolClient.online() && tab != Tab.SETTINGS && tab != Tab.MAP) {
            ctx.drawText(textRenderer, "Pas connecté au site Capitol TV — onglet Réglages pour coller ton jeton.", cx, listBottom - 10, Hud.ORANGE, false);
        }

        switch (tab) {
            case MAP -> renderMap(ctx, mouseX, mouseY);
            case COMMS -> renderComms(ctx);
            case SETTINGS -> renderSettings(ctx);
            case SIGNAL -> renderSignal(ctx);
            default -> { }
        }
        if (listTab()) renderList(ctx, mouseX, mouseY);

        if (!status.isEmpty() && System.currentTimeMillis() - statusAt < 6000) {
            String s = textRenderer.trimToWidth(status, cw);
            int w = textRenderer.getWidth(s) + 8, x = cx + cw - w, y = barY - 14;
            if (tab == Tab.MAP) y = mapY0() + 2;
            ctx.fill(x, y, x + w, y + 12, 0xE0202026);
            ctx.drawText(textRenderer, s, x + 4, y + 2, Hud.GOLD, false);
        }
    }

    private void renderSignal(DrawContext ctx) {
        ctx.drawText(textRenderer, "Tribut concerné", cx, cy + 2, Hud.GOLD, false);
        ctx.drawText(textRenderer, "Type d'action", cx, cy + 36, Hud.GOLD, false);
        ctx.drawText(textRenderer, "Détails", cx, cy + 144, Hud.DIM, false);
        int y = cy + 200;
        List<String> info = new ArrayList<>();
        if (Data.delay > 0) {
            long at = Data.now() + Data.delay * 1000L;
            info.add("Différé de " + Data.delay / 60 + ":" + String.format(Locale.ROOT, "%02d", Data.delay % 60) + " : ce que tu signales maintenant passera à l'écran (site et réact) vers " + hhmmss(at) + ".");
        } else info.add("Pas de différé : la régie et le réacteur voient l'action en direct.");
        if (!sigInfo.isEmpty()) info.add(sigInfo + (Data.lastReportTs > 0 && Data.delay > 0 ? " — " + Data.airLabel(Data.lastReportTs) : ""));
        info.add("Astuce : la touche " + CapitolClient.signalKey.getBoundKeyLocalizedText().getString() + " envoie un signal rapide sur le tribut suivi (ou le plus proche), sans ouvrir le menu.");
        for (String s : info) for (OrderedText ot : textRenderer.wrapLines(Text.literal(s), cw)) { ctx.drawText(textRenderer, ot, cx, y, Hud.INK, false); y += 10; }
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
        int rh = 22, vis = Math.max(1, (listBottom - listY) / rh);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - vis)));
        ctx.fill(cx, listY, cx + cw, listBottom, 0x60000000);
        ctx.enableScissor(cx, listY, cx + cw, listBottom);
        for (int i = scroll, y = listY; i < rows.size() && y < listBottom; i++, y += rh) {
            Row r = rows.get(i);
            boolean sel = r.id.equals(selId), hov = mouseX >= cx && mouseX < cx + cw && mouseY >= y && mouseY < y + rh;
            if (sel) ctx.fill(cx, y, cx + cw, y + rh, 0x50D4AF37);
            else if (hov) ctx.fill(cx, y, cx + cw, y + rh, 0x20FFFFFF);
            ctx.fill(cx, y + rh - 1, cx + cw, y + rh, 0x30FFFFFF);
            int rw = r.r1 == null || r.r1.isEmpty() ? 0 : textRenderer.getWidth(r.r1) + 8;
            ctx.drawText(textRenderer, textRenderer.trimToWidth(r.l1, cw - 8 - rw), cx + 4, y + 2, r.c1, false);
            if (rw > 0) ctx.drawText(textRenderer, r.r1, cx + cw - rw + 4, y + 2, r.r1.startsWith("écran") ? Hud.GOLD : Hud.DIM, false);
            ctx.drawText(textRenderer, textRenderer.trimToWidth(r.l2, cw - 8), cx + 4, y + 12, r.c2, false);
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
            ctx.drawText(textRenderer, e, cx + 6, listY + 6, Hud.DIM, false);
        }
        if (rows.size() > vis) {
            int track = listBottom - listY, th = Math.max(10, track * vis / rows.size());
            int ty = listY + (track - th) * scroll / Math.max(1, rows.size() - vis);
            ctx.fill(cx + cw - 2, ty, cx + cw, ty + th, 0x80D4AF37);
        }
    }

    /* ====================================================================== */
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (inMap(mx, my)) { pressX = mx; pressY = my; dragging = false; pressedOnMap = true; return true; }
        if (listTab() && mx >= cx && mx < cx + cw && my >= listY && my < listBottom) {
            int i = (int) ((my - listY) / 22) + scroll;
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
        if (pressedOnMap && tab == Tab.MAP) {
            if (Math.abs(mx - pressX) + Math.abs(my - pressY) > 3) dragging = true;
            if (dragging) { camX -= dx / scale; camZ -= dy / scale; }
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
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
    }
}
