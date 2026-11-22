package fr.capitoltv.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.capitoltv.client.gui.CapitolScreen;
import fr.capitoltv.net.CtvSocket;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.util.Map;

public class CapitolClient implements ClientModInitializer {
    public static KeyBinding menuKey, signalKey, stateKey, followKey, replyKey, cameraKey, peekKey, movesKey, talkKey;
    private static CtvSocket sock;
    /** Tribut suivi par cette caméra (id Capitol TV). */
    public static String follow = "";
    private static int tick;
    private static boolean stDirty = true;
    private static String lastMsgFrom = "";

    @Override
    public void onInitializeClient() {
        ClientConfig.load();
        String cat = "category.capitoltv";
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.capitoltv.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, cat));
        signalKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.capitoltv.signal", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, cat));
        stateKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.capitoltv.state", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, cat));
        followKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.capitoltv.follow", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, cat));
        replyKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.capitoltv.reply", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, cat));
        cameraKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.capitoltv.camera", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F6, cat));
        peekKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.capitoltv.peek", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, cat));
        movesKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.capitoltv.moves", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, cat));
        talkKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.capitoltv.talk", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, cat));
        try { CamMode.install(); } catch (Throwable t) { fr.capitoltv.CapitolTV.LOG.warn("Capitol TV : HUD vanilla non géré ({})", t.toString()); }
        ClientTickEvents.START_CLIENT_TICK.register(mc -> { if (!CamMoves.startTick(mc)) CamMode.startTick(mc); });
        ClientTickEvents.END_CLIENT_TICK.register(CamMoves::endTick);
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> Streamer.stop(null));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((h, sender, client) -> { CamMode.resetSpectatorTries(); stDirty = true; });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((h, client) -> {
            CamMode.serverCam = false;
            if (!Data.serverKey.isEmpty()) { Data.serverKey = ""; Data.serverUrl = ""; Data.serverWho = ""; Streamer.stop("déconnecté du serveur"); }
        });
        // clé de diffusion donnée par le serveur de l'event : rien à copier pour le joueur
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(fr.capitoltv.net.StreamKeyPayload.ID, (payload, ctx) -> {
            boolean changed = !payload.key().equals(Data.serverKey);
            Data.serverKey = payload.key(); Data.serverUrl = payload.url(); Data.serverWho = payload.who();
            if (changed) { Streamer.stop(null); Streamer.announce(); }
            // caméra : jeton du site reçu du serveur de l'event, plus besoin de le coller depuis « Mon compte »
            String tok = payload.tok();
            if (tok != null && !tok.isBlank()) {
                ClientConfig c = ClientConfig.get();
                boolean was = !c.token.isBlank();
                if (!tok.equals(c.token) && (!was || !sock.online() || !payload.url().equalsIgnoreCase(c.url))) {
                    c.token = tok; if (!payload.url().isBlank()) c.url = payload.url(); ClientConfig.save(); reconnect();
                    if (!was) Hud.toast("Capitol TV", "Caméra reliée au site automatiquement.", Hud.GOLD, 5);
                }
            }
        });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(fr.capitoltv.net.CamAccessPayload.ID, (payload, ctx) -> {
            boolean was = CamMode.serverCam;
            CamMode.serverCam = payload.on();
            if (payload.on() && !was) Hud.toast("Capitol TV", "Tu es caméra sur ce serveur : F6 pour le mode caméra.", Hud.GOLD, 6);
        });

        sock = new CtvSocket(m -> MinecraftClient.getInstance().execute(() -> onMessage(m)), st -> MinecraftClient.getInstance().execute(() -> {
            if (st != CtvSocket.State.ONLINE) Data.ready = false;
            if (st == CtvSocket.State.REFUSED) Hud.toast("Capitol TV", sock.detail(), Hud.RED, 8);
        }));
        if (!ClientConfig.get().token.isBlank()) reconnect();

        ClientTickEvents.END_CLIENT_TICK.register(CapitolClient::onTick);
        HudRenderCallback.EVENT.register(Hud::render);
    }

    /* ---------- liaison ---------- */
    public static void reconnect() {
        ClientConfig c = ClientConfig.get();
        if (c.token.isBlank()) { sock.disconnect(); return; }
        sock.connect(c.url, Map.of("Authorization", "Bearer " + c.token.trim(), "User-Agent", "CapitolTV-Cam/1.0"));
        stDirty = true;
    }
    public static void disconnect() { sock.disconnect(); Data.reset(); }
    private static void reconnectLater() { new Thread(() -> { try { Thread.sleep(2000); } catch (InterruptedException ignored) { } MinecraftClient.getInstance().execute(CapitolClient::reconnect); }, "CapitolTV-move").start(); }
    /** Mode démonstration (tests automatiques) : l'interface se comporte comme connectée. */
    public static boolean demo;
    public static boolean online() { return demo || (sock != null && sock.online()); }
    public static String stateText() {
        if (sock == null) return "hors ligne";
        return switch (sock.state()) {
            case ONLINE -> Data.ready ? "connecté" : "connexion…";
            case CONNECTING -> "connexion…";
            case RETRY -> "reconnexion…" + (sock.detail().isEmpty() ? "" : " (" + sock.detail() + ")");
            case REFUSED -> "refusé : " + sock.detail();
            default -> "déconnecté";
        };
    }
    public static String menuKeyName() { return menuKey.getBoundKeyLocalizedText().getString(); }
    public static void send(JsonObject o) { if (online()) sock.send(o); }

    /* ---------- actions ---------- */
    public static void setState(String st) {
        ClientConfig.get().state = st;
        ClientConfig.save();
        stDirty = true;
    }
    public static void setFollow(String trId) { follow = trId == null ? "" : trId; stDirty = true; }

    public static void report(String trId, String type, String detail, boolean urgent, boolean rec) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "report");
        o.addProperty("tr", trId);
        o.addProperty("type", type);
        o.addProperty("detail", detail);
        o.addProperty("urgent", urgent);
        o.addProperty("rec", rec);
        send(o);
        Data.lastReportTs = Data.now();
        if (rec) { setFollow(trId); setState("rec"); }
    }
    public static void message(String ch, String to, String text) {
        if (text == null || text.isBlank()) return;
        JsonObject o = new JsonObject();
        o.addProperty("t", "msg");
        o.addProperty("ch", ch);
        if (to != null && !to.isEmpty()) o.addProperty("to", to);
        o.addProperty("text", text.trim());
        send(o);
    }
    public static void buzz(String to, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "buzz");
        o.addProperty("to", to);
        o.addProperty("text", text);
        send(o);
    }
    public static void request(String trId, String text, String to) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "req");
        o.addProperty("tr", trId);
        o.addProperty("text", text);
        if (to != null && !to.isEmpty()) o.addProperty("to", to);
        send(o);
    }
    public static void reqUpd(String id, String op) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "reqUpd");
        o.addProperty("id", id);
        o.addProperty("op", op);
        send(o);
    }
    public static void setRole(String uid, String rid) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "setRole");
        o.addProperty("uid", uid);
        o.addProperty("role", rid);
        send(o);
    }
    public static void saveRoles(java.util.List<Data.Role> roles) {
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        for (Data.Role r : roles) {
            JsonObject x = new JsonObject();
            x.addProperty("id", r.id); x.addProperty("name", r.name); x.addProperty("color", r.color);
            com.google.gson.JsonArray p = new com.google.gson.JsonArray();
            for (String k : r.perms) p.add(k);
            x.add("perms", p);
            a.add(x);
        }
        JsonObject o = new JsonObject();
        o.addProperty("t", "roles");
        o.add("roles", a);
        send(o);
    }
    public static void alertUpd(String id, String key, boolean val) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "alertUpd");
        o.addProperty("id", id);
        JsonObject p = new JsonObject();
        if (key.equals("claim")) p.addProperty("claimedBy", val ? Data.meName : "");
        else p.addProperty("handled", val);
        o.add("p", p);
        send(o);
    }

    private static int firstAskTicks;

    /* ---------- boucle ---------- */
    private static void onTick(MinecraftClient mc) {
        // K : tant que le joueur n'a pas répondu à la demande de diffusion, elle s'ouvre ; ensuite, le menu
        while (menuKey.wasPressed()) if (mc.currentScreen == null) {
            ClientConfig c0 = ClientConfig.get();
            mc.setScreen(c0.token.isBlank() && !demo && !c0.streamAsked ? new fr.capitoltv.client.gui.StreamScreen(null, false) : new CapitolScreen());
        }
        // première connexion à un serveur : proposer le choix de qualité du POV
        // première fois qu'une clé est disponible : demander l'accord du joueur (et sa qualité)
        String k0 = Streamer.key();
        if (!ClientConfig.get().streamAsked && k0 != null && !k0.isBlank() && mc.world != null && mc.player != null && mc.currentScreen == null && !demo) {
            if (++firstAskTicks > 60) { firstAskTicks = 0; mc.setScreen(new fr.capitoltv.client.gui.StreamScreen(null, true)); }
        } else firstAskTicks = 0;
        while (signalKey.wasPressed()) quickSignal();
        while (stateKey.wasPressed()) {
            String[] order = {"actif", "rec", "pause", "inactif"};
            String cur = ClientConfig.get().state;
            int i = 0;
            for (int k = 0; k < order.length; k++) if (order[k].equals(cur)) i = k;
            setState(order[(i + 1) % order.length]);
            Hud.toast("État", Hud.stateLabel(ClientConfig.get().state), Hud.stateColor(ClientConfig.get().state), 3);
        }
        while (followKey.wasPressed()) {
            Data.Trib t = Data.tribById(follow);
            Hud.toast("Téléportation", t == null ? "Aucun tribut suivi" : Teleport.toTribute(t), Hud.BLUE, 3);
        }
        while (cameraKey.wasPressed()) {
            ClientConfig c = ClientConfig.get();
            if (!c.cameraMode && CamMode.denied()) {
                Hud.toast("Mode caméra", "Réservé aux caméras : un admin doit faire /capitoltv camera add " + (mc.player == null ? "<pseudo>" : mc.player.getGameProfile().getName()) + " ou lier ton compte Minecraft sur le site.", Hud.ORANGE, 6);
                continue;
            }
            c.cameraMode = !c.cameraMode;
            ClientConfig.save();
            Hud.toast("Mode caméra", c.cameraMode ? "activé : HUD du jeu masqué, barre caméra (touches 1 à 9)" : "désactivé", Hud.GOLD, 4);
        }
        Talk.tick(talkKey.isPressed() && mc.currentScreen == null);
        while (talkKey.wasPressed()) { }
        while (movesKey.wasPressed()) {
            if (mc.player == null) continue;
            if (!CamMode.allowed() && !mc.player.isSpectator()) { Hud.toast("Mouvements de caméra", "Réservés aux caméras en spectateur", Hud.ORANGE, 3); continue; }
            CamMoves.togglePicker();
        }
        while (replyKey.wasPressed()) {
            if (lastMsgFrom.isEmpty()) Hud.toast("Comms", "Aucun message auquel répondre", Hud.DIM, 3);
            else { message("dm", lastMsgFrom, "Reçu ✔"); Hud.toast("Comms", "« Reçu » envoyé à " + Data.memberName(lastMsgFrom), Hud.GREEN, 3); }
        }
        Observer.tick(mc);
        tick++;
        if (online() && Data.ready && tick % 100 == 50) CamMode.ensureSpectator(mc, false);
        if (online() && Data.ready && (stDirty || tick % 40 == 0)) sendState(mc);
    }

    private static void sendState(MinecraftClient mc) {
        stDirty = false;
        JsonObject o = new JsonObject();
        o.addProperty("t", "st");
        o.addProperty("state", ClientConfig.get().state);
        o.addProperty("follow", follow);
        o.addProperty("notif", ClientConfig.get().notif);
        if (mc.player != null && mc.world != null) {
            o.addProperty("mc", mc.player.getGameProfile().getName());
            JsonObject p = new JsonObject();
            p.addProperty("x", Math.round(mc.player.getX() * 10) / 10.0);
            p.addProperty("y", Math.round(mc.player.getY() * 10) / 10.0);
            p.addProperty("z", Math.round(mc.player.getZ() * 10) / 10.0);
            p.addProperty("d", mc.world.getRegistryKey().getValue().toString());
            o.add("pos", p);
        }
        send(o);
    }

    public static void quickSignal() {
        if (!online()) { Hud.toast("Capitol TV", "Pas connecté au site (" + stateText() + ")", Hud.RED, 4); return; }
        Data.Trib t = Data.tribById(follow);
        if (t == null) t = Observer.nearestTribute();
        if (t == null) { Hud.toast("Signal rapide", "Aucun tribut suivi ni visible : ouvre le menu (" + menuKeyName() + ")", Hud.ORANGE, 4); return; }
        report(t.id, "Autre", "Action en cours (signal rapide depuis le jeu)", false, true);
        Hud.toast("Signal envoyé", Data.tribLabel(t.id) + (Data.delay > 0 ? " — " + Data.airLabel(Data.now()) : ""), Hud.GOLD, 5);
    }

    /* ---------- messages du site ---------- */
    private static boolean wants(boolean fromSup, boolean direct) {
        String n = ClientConfig.get().notif;
        if (n.equals("none")) return false;
        if (n.equals("sup")) return fromSup;
        return true;
    }

    private static void onMessage(JsonObject m) {
        String t = Data.s(m, "t");
        if (Talk.onReply(m)) return;
        switch (t) {
            case "talkCh" -> Talk.onChannel(Data.b(m, "open"), Data.b(m, "regie"), Data.s(m, "by"));
            case "talkSpeak" -> Talk.onSpeak(Data.s(m, "who"), Data.b(m, "on"), Data.s(m, "name"));
            case "moved" -> {
                // bascule vers le site de secours : nouvelle adresse retenue et reconnexion
                String u = Data.s(m, "url");
                if (u.startsWith("https://")) { Streamer.moveTo(u); Hud.toast("Capitol TV", "Le site a changé de serveur : reconnexion…", Hud.GOLD, 5); reconnectLater(); }
            }
            case "hello" -> {
                Data.hello(m);
                stDirty = true;
                if (!follow.isEmpty() && Data.tribById(follow) == null) follow = "";
                Hud.toast("Capitol TV", "Connecté en tant que " + Data.meName + (Data.serverMod ? " · mod serveur actif" : ""), Hud.GOLD, 4);
                CamMode.resetSpectatorTries();
                CamMode.ensureSpectator(MinecraftClient.getInstance(), true);
                JsonObject tk = Data.obj(m, "talk");
                if (tk != null && Data.b(tk, "open")) Talk.onChannel(true, Data.b(tk, "regie"), "");
            }
            case "tribs" -> {
                Data.setTribs(Data.arr(m, "tribs"));
                if (m.has("delay")) Data.delay = (int) Data.l(m, "delay");
            }
            case "delay" -> Data.delay = (int) Data.l(m, "delay");
            case "srv" -> Data.serverMod = Data.b(m, "on");
            case "cams" -> Data.setCams(Data.arr(m, "cams"));
            case "roles" -> Data.setRoles(Data.arr(m, "roles"));
            case "onair" -> {
                boolean was = Data.onAir(), wasPrep = Data.onPrep();
                Data.setOnAir(Data.arr(m, "ids"));
                Data.setPrep(m.has("prep") ? Data.arr(m, "prep") : null);
                if (!wasPrep && Data.onPrep() && !Data.onAir() && CamMode.active()) Hud.toast("EN PRÉPARATION", "La régie prépare un plan avec ta caméra : flux propre dès maintenant (différé).", Hud.ORANGE, 6);
                if (!was && Data.onAir()) Hud.toast("À L'ANTENNE", "La régie affiche ta caméra" + (ClientConfig.get().cameraMode && ClientConfig.get().cleanOnAir ? " : flux propre (Alt gauche pour voir l'interface)" : ""), Hud.RED, 6);
                else if (was && !Data.onAir()) Hud.toast("Hors antenne", "Ta caméra n'est plus affichée", Hud.DIM, 4);
            }
            case "team" -> Data.setTeam(Data.arr(m, "team"));
            case "pos" -> Data.mergePos(Data.obj(m, "p"));
            case "alert" -> {
                Data.Alert a = Data.alert(Data.s(m, "id"), Data.obj(m, "a"));
                boolean isNew = !Data.alerts.containsKey(a.id);
                Data.alerts.put(a.id, a);
                if (isNew && !a.uid.equals(Data.meId) && ClientConfig.get().alertsToasts && wants(false, false)) {
                    Data.Trib tr = Data.tribByLogin(a.ch);
                    Hud.toast((a.urgent ? "URGENT · " : "") + a.type + " — " + (tr == null ? a.ch : tr.name), a.detail + " (" + a.by + ")", a.urgent ? Hud.RED : Hud.GOLD, 7);
                    Hud.sound(a.urgent);
                }
            }
            case "alertUpd" -> {
                Data.Alert a = Data.alerts.get(Data.s(m, "id"));
                if (a != null) {
                    JsonObject p = Data.obj(m, "p");
                    if (p.has("handled")) a.handled = Data.b(p, "handled");
                    if (p.has("claimedBy")) a.claimedBy = Data.s(p, "claimedBy");
                }
            }
            case "alerts" -> {
                Data.alerts.clear();
                for (Map.Entry<String, JsonElement> e : Data.obj(m, "all").entrySet())
                    if (e.getValue().isJsonObject()) Data.alerts.put(e.getKey(), Data.alert(e.getKey(), e.getValue().getAsJsonObject()));
            }
            case "feed" -> {
                for (JsonElement e : Data.arr(m, "list")) {
                    if (!e.isJsonObject()) continue;
                    Data.Feed f = Data.feedOf(e.getAsJsonObject());
                    Data.addFeed(f);
                    boolean mine = !follow.isEmpty() && follow.equals(f.tr);
                    boolean big = f.k.equals("death") || f.k.equals("special") || f.k.equals("totem") || f.k.equals("meet") || f.k.equals("lowhp");
                    if ((mine || (big && ClientConfig.get().feedToasts) || (ClientConfig.get().feedToasts && f.k.equals("hit"))) && wants(false, false) && !f.text.isEmpty()) {
                        Hud.toast(mine ? "Ton tribut" : "Acte annonciateur", f.text, f.k.equals("death") ? Hud.RED : f.k.equals("special") ? Hud.PURPLE : Hud.ORANGE, 6);
                        if (mine || f.k.equals("death")) Hud.sound(false);
                    }
                }
            }
            case "msg" -> {
                Data.Msg msg = Data.msg(Data.obj(m, "m"));
                Data.addMsg(msg);
                if (msg.from.equals(Data.meId)) break;
                boolean direct = Data.meId.equals(msg.to);
                if (direct) lastMsgFrom = msg.from;
                if (msg.k.equals("buzz") && direct) {
                    Hud.toast("BUZZ · " + msg.name, msg.text, Hud.RED, 10);
                    Hud.sound(true);
                } else if (wants(msg.sup, direct)) {
                    String where = direct ? "Privé" : msg.ch.equals("cams") ? "Caméras" : "Staff";
                    Hud.toast(where + " · " + msg.name, msg.text, msg.sup ? Hud.GOLD : Hud.BLUE, 7);
                    Hud.sound(false);
                }
            }
            case "req" -> {
                Data.Req r = Data.req(Data.obj(m, "r"));
                Data.Req old = Data.reqs.put(r.id, r);
                boolean forMe = Data.meId.equals(r.to);
                if ((old == null || !Data.meId.equals(old.to)) && forMe && !"done".equals(r.state)) {
                    Hud.toast("Demande pour toi · " + r.byName, Data.tribLabel(r.tr) + (r.text.isEmpty() ? "" : " — " + r.text), Hud.PURPLE, 10);
                    Hud.sound(true);
                } else if (old == null && r.to.isEmpty() && wants(r.bySup, false) && !r.by.equals(Data.meId)) {
                    Hud.toast("Demande de couverture · " + r.byName, Data.tribLabel(r.tr) + (r.text.isEmpty() ? "" : " — " + r.text), Hud.PURPLE, 7);
                    Hud.sound(false);
                }
            }
            case "reqDel" -> Data.reqs.remove(Data.s(m, "id"));
            case "commsClear" -> { Data.msgs.clear(); Data.feed.clear(); Data.reqs.clear(); }
            case "err" -> Hud.toast("Capitol TV", Data.s(m, "msg"), Hud.RED, 6);
            case "info" -> Hud.toast("Capitol TV", Data.s(m, "msg"), Hud.GOLD, 5);
            default -> { }
        }
    }
}
