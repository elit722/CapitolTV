package fr.capitoltv.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import fr.capitoltv.CapitolTV;
import fr.capitoltv.net.CtvSocket;
import fr.capitoltv.net.CamAccessPayload;
import fr.capitoltv.net.StreamKeyPayload;
import fr.capitoltv.net.SpecPayload;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.command.argument.GameProfileArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import fr.capitoltv.net.TpPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Partie serveur (facultative) : installée sur le serveur de l'event, elle envoie à Capitol TV
 * les positions de tous les joueurs, les dégâts (avec la cause), les morts et les effets,
 * et autorise la téléportation des caméramans depuis la carte.
 */
public final class ServerBridge {
    public static final class Config {
        public String url = "https://capitol-tv.refugeemeraudien-direction.workers.dev";
        public String key = "";
        public int intervalTicks = 20;
        public boolean tpForSpectators = true;
        public boolean tpForOps = true;
        /** caméras ajoutées en jeu par un admin : /capitoltv camera add <joueur> */
        public List<String> camNames = new ArrayList<>();
        public boolean reportEffects = true;
        /** les caméras (déclarées sur Capitol TV) passent automatiquement en spectateur */
        public boolean autoSpectator = true;
        /** envoie à chaque joueur sa clé de diffusion (POV) : rien à configurer côté joueur */
        public boolean giveStreamKeys = true;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static Config cfg = new Config();
    private static CtvSocket sock;
    private static MinecraftServer server;
    private static final ConcurrentLinkedQueue<JsonObject> events = new ConcurrentLinkedQueue<>();
    private static int tick;
    /** pseudos Minecraft des caméras, envoyés par Capitol TV */
    private static final Set<String> remoteCams = ConcurrentHashMap.newKeySet();

    private static boolean isCam(String name) {
        for (String n : remoteCams) if (n.equalsIgnoreCase(name)) return true;
        return cfg.camNames.stream().anyMatch(x -> x.equalsIgnoreCase(name));
    }

    private static void setSpectator(MinecraftServer s, ServerPlayerEntity p, boolean on) {
        if (p.isSpectator() == on) return;
        s.getCommandManager().executeWithPrefix(s.getCommandSource(), "gamemode " + (on ? "spectator " : "adventure ") + p.getGameProfile().getName());
        p.sendMessage(Text.literal("§6[Capitol TV]§r " + (on ? "Mode caméra : tu es en spectateur." : "Tu quittes le mode spectateur.")), false);
    }

    /** joueurs dont on attend la clé de diffusion */
    private static final Set<String> wantKeys = ConcurrentHashMap.newKeySet();

    private static void onRemote(JsonObject m) {
        String t = m.has("t") ? m.get("t").getAsString() : "";
        if (t.equals("pkeys") && m.has("keys")) { giveKeys(m.getAsJsonObject("keys")); return; }
        if (t.equals("moved") && m.has("url")) {
            // bascule vers le site de secours : la nouvelle adresse est enregistrée pour toujours
            String u = m.get("url").getAsString().replaceAll("/+$", "");
            if (u.startsWith("https://") && !u.equals(cfg.url)) {
                CapitolTV.LOG.info("Capitol TV : le site a déménagé vers {}", u);
                cfg.url = u; saveConfig();
                MinecraftServer s0 = server;
                if (s0 != null) s0.execute(() -> { for (ServerPlayerEntity p : s0.getPlayerManager().getPlayerList()) wantKeys.add(p.getGameProfile().getName()); connect(); });
            }
            return;
        }
        if (t.equals("scan")) {
            MinecraftServer s0 = server; if (s0 == null) return;
            String dim = m.has("dim") ? m.get("dim").getAsString() : "minecraft:overworld";
            int cx = m.has("cx") ? m.get("cx").getAsInt() : 0, cz = m.has("cz") ? m.get("cz").getAsInt() : 0, r = m.has("r") ? m.get("r").getAsInt() : 256;
            s0.execute(() -> sayRemote(TerrainScan.start(s0, dim, cx, cz, r, ServerBridge::sayRemote)));
            return;
        }
        if (t.equals("hello")) { MinecraftServer s0 = server; if (s0 != null) for (ServerPlayerEntity p : s0.getPlayerManager().getPlayerList()) wantKeys.add(p.getGameProfile().getName()); }
        if (!(t.equals("hello") || t.equals("cams")) || !m.has("names")) return;
        remoteCams.clear();
        for (var e : m.getAsJsonArray("names")) remoteCams.add(e.getAsString());
        if (t.equals("hello")) reportLocalCams();
        MinecraftServer s = server;
        if (s == null) return;
        s.execute(() -> refreshAccess(s));
    }

    /** Transmet à chaque joueur connu de Capitol TV sa clé de diffusion. */
    private static void giveKeys(JsonObject keys) {
        MinecraftServer s = server;
        if (s == null) return;
        s.execute(() -> {
            for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
                String n = p.getGameProfile().getName();
                if (!keys.has(n)) continue;
                JsonObject k = keys.getAsJsonObject(n);
                if (ServerPlayNetworking.canSend(p, StreamKeyPayload.ID))
                    ServerPlayNetworking.send(p, new StreamKeyPayload(k.get("key").getAsString(), cfg.url, k.has("who") ? k.get("who").getAsString() : n));
            }
        });
    }

    /** Envoie à chaque joueur son droit au mode caméra et met les caméras en spectateur. */
    private static void refreshAccess(MinecraftServer s) {
        for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
            boolean cam = isCam(p.getGameProfile().getName());
            if (ServerPlayNetworking.canSend(p, CamAccessPayload.ID)) ServerPlayNetworking.send(p, new CamAccessPayload(cam));
            if (cam && cfg.autoSpectator) setSpectator(s, p, true);
        }
    }

    /** Signale au site les caméras ajoutées en jeu (pour les afficher côté régie). */
    /** envoi au site (utilisé par le relevé du terrain) */
    static boolean push(JsonObject o) {
        if (sock == null || !sock.online()) return false;
        sock.send(o); return true;
    }

    private static void sayRemote(String msg) {
        CapitolTV.LOG.info("Capitol TV : {}", msg);
        JsonObject o = new JsonObject(); o.addProperty("t", "terrainMsg"); o.addProperty("msg", msg); push(o);
    }

    private static void reportLocalCams() {
        if (sock == null || !sock.online()) return;
        JsonObject o = new JsonObject();
        o.addProperty("t", "srvCams");
        JsonArray a = new JsonArray();
        for (String n : cfg.camNames) a.add(n);
        o.add("names", a);
        sock.send(o);
    }

    private static void saveConfig() {
        try { Files.writeString(path(), GSON.toJson(cfg), StandardCharsets.UTF_8); }
        catch (Exception e) { CapitolTV.LOG.warn("Capitol TV : configuration serveur non enregistrée ({})", e.toString()); }
    }

    private static LiteralArgumentBuilder<ServerCommandSource> cameraCmd() {
        return CommandManager.literal("camera").requires(src -> src.hasPermissionLevel(3))
                .then(CommandManager.literal("add").then(CommandManager.argument("joueurs", GameProfileArgumentType.gameProfile()).executes(c -> {
                    int n = 0;
                    for (GameProfile gp : GameProfileArgumentType.getProfileArgument(c, "joueurs")) {
                        String name = gp.getName();
                        if (cfg.camNames.stream().noneMatch(x -> x.equalsIgnoreCase(name))) { cfg.camNames.add(name); n++; }
                    }
                    saveConfig(); reportLocalCams(); refreshAccess(c.getSource().getServer());
                    final int added = n;
                    c.getSource().sendFeedback(() -> Text.literal("§6[Capitol TV]§r " + added + " caméra(s) ajoutée(s) : spectateur automatique et mode caméra (F6) autorisé."), true);
                    return added;
                })))
                .then(CommandManager.literal("remove").then(CommandManager.argument("nom", StringArgumentType.word())
                        .suggests((c, b) -> { for (String n : cfg.camNames) b.suggest(n); return b.buildFuture(); })
                        .executes(c -> {
                            String name = StringArgumentType.getString(c, "nom");
                            boolean ok = cfg.camNames.removeIf(x -> x.equalsIgnoreCase(name));
                            saveConfig(); reportLocalCams(); refreshAccess(c.getSource().getServer());
                            boolean still = isCam(name);
                            c.getSource().sendFeedback(() -> Text.literal("§6[Capitol TV]§r " + (ok ? name + " n'est plus caméra (liste du serveur)." : name + " n'était pas dans la liste du serveur.")
                                    + (still ? " Il reste caméra via son compte Capitol TV (à retirer sur le site)." : "")), true);
                            return ok ? 1 : 0;
                        })))
                .then(CommandManager.literal("list").executes(c -> {
                    List<String> site = new ArrayList<>(remoteCams);
                    c.getSource().sendFeedback(() -> Text.literal("§6[Capitol TV]§r Caméras ajoutées en jeu : " + (cfg.camNames.isEmpty() ? "aucune" : String.join(", ", cfg.camNames))), false);
                    c.getSource().sendFeedback(() -> Text.literal("§6[Capitol TV]§r Caméras du site (comptes Minecraft liés) : " + (site.isEmpty() ? "aucune" : String.join(", ", site))), false);
                    return 1;
                }));
    }

    private ServerBridge() { }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> { server = s; loadConfig(); connect(); });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> { if (sock != null) sock.disconnect(); server = null; });
        ServerTickEvents.END_SERVER_TICK.register(ServerBridge::onTick);

        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (!(entity instanceof ServerPlayerEntity p) || taken <= 0) return;
            JsonObject e = base(p, "hit");
            cause(e, source);
            e.addProperty("amt", Math.round(taken * 10) / 10.0);
            e.addProperty("hp", Math.round(p.getHealth() * 10) / 10.0);
            events.add(e);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof ServerPlayerEntity p)) return;
            JsonObject e = base(p, "death");
            cause(e, source);
            events.add(e);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            ServerPlayerEntity p = handler.getPlayer();
            boolean cam = isCam(p.getGameProfile().getName());
            if (cfg.giveStreamKeys) wantKeys.add(p.getGameProfile().getName());
            s.execute(() -> {
                if (ServerPlayNetworking.canSend(p, CamAccessPayload.ID)) ServerPlayNetworking.send(p, new CamAccessPayload(cam));
                if (cam && cfg.autoSpectator) setSpectator(s, p, true);
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(SpecPayload.ID, (payload, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            MinecraftServer s = p.getServer();
            if (s == null) return;
            String n = p.getGameProfile().getName();
            if (!isCam(n) && !s.getPlayerManager().isOperator(p.getGameProfile())) {
                p.sendMessage(Text.literal("§6[Capitol TV]§r " + n + " n'est pas une caméra : un admin doit faire /capitoltv camera add " + n + " ou lier ce compte Minecraft à un membre Caméra sur le site."), false);
                return;
            }
            setSpectator(s, p, payload.on());
        });
        ServerPlayNetworking.registerGlobalReceiver(TpPayload.ID, (payload, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            MinecraftServer s = p.getServer();
            if (s == null) return;
            if (!mayTeleport(s, p)) {
                p.sendMessage(Text.literal("§6[Capitol TV]§r Téléportation refusée : passe en spectateur ou demande l'accès à la régie."), false);
                return;
            }
            String name = p.getGameProfile().getName();
            String cmd;
            if (payload.target() != null && !payload.target().isEmpty()) {
                if (!payload.target().matches("[A-Za-z0-9_]{1,16}")) return;
                cmd = "tp " + name + " " + payload.target();
            } else {
                String dim = payload.dim() == null || payload.dim().isEmpty() ? p.getWorld().getRegistryKey().getValue().toString() : payload.dim();
                if (!dim.matches("[a-z0-9_.\\-]+:[a-z0-9_./\\-]+")) return;
                cmd = String.format(Locale.ROOT, "execute in %s run tp %s %.2f %.2f %.2f", dim, name, payload.x(), payload.y(), payload.z());
            }
            s.getCommandManager().executeWithPrefix(s.getCommandSource(), cmd);
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            for (String rootName : new String[]{"capitoltv", "capitol"}) dispatcher.register(root(rootName));
        });
    }

    private static LiteralArgumentBuilder<ServerCommandSource> root(String rootName) {
        return CommandManager.literal(rootName).requires(src -> src.hasPermissionLevel(2))
                        .then(CommandManager.literal("annonce").then(CommandManager.argument("texte", StringArgumentType.greedyString()).executes(c -> {
                            JsonObject e = new JsonObject();
                            e.addProperty("k", "special");
                            e.addProperty("text", StringArgumentType.getString(c, "texte"));
                            e.addProperty("by", c.getSource().getName());
                            events.add(e);
                            c.getSource().sendFeedback(() -> Text.literal("§6[Capitol TV]§r Annonce envoyée à la régie."), false);
                            return 1;
                        })))
                        .then(CommandManager.literal("status").executes(c -> {
                            String st = sock == null ? "désactivé (clé absente)" : sock.state() + (sock.detail().isEmpty() ? "" : " — " + sock.detail());
                            c.getSource().sendFeedback(() -> Text.literal("§6[Capitol TV]§r Liaison : " + st), false);
                            return 1;
                        }))
                        .then(CommandManager.literal("carte")
                                .executes(c -> scanCmd(c.getSource(), 256))
                                .then(CommandManager.argument("rayon", com.mojang.brigadier.arguments.IntegerArgumentType.integer(16, 2048)).executes(c -> scanCmd(c.getSource(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "rayon")))))
                        .then(CommandManager.literal("reload").executes(c -> {
                            loadConfig(); connect();
                            c.getSource().sendFeedback(() -> Text.literal("§6[Capitol TV]§r Configuration rechargée."), false);
                            return 1;
                        }))
                .then(cameraCmd());
    }

    /** /capitol carte [rayon] : relève le terrain autour de soi pour la carte du site */
    private static int scanCmd(ServerCommandSource src, int r) {
        var pos = src.getPosition();
        String dim = src.getWorld().getRegistryKey().getValue().toString();
        MinecraftServer s = src.getServer();
        String msg = TerrainScan.start(s, dim, (int) Math.floor(pos.x), (int) Math.floor(pos.z), r, txt -> { src.sendFeedback(() -> Text.literal("§6[Capitol TV]§r " + txt), false); sayRemote(txt); });
        src.sendFeedback(() -> Text.literal("§6[Capitol TV]§r " + msg), false);
        return 1;
    }

    private static boolean mayTeleport(MinecraftServer s, ServerPlayerEntity p) {
        if (cfg.tpForSpectators && p.isSpectator()) return true;
        if (isCam(p.getGameProfile().getName())) return true;
        if (cfg.tpForOps && s.getPlayerManager().isOperator(p.getGameProfile())) return true;
        String n = p.getGameProfile().getName();
        return cfg.camNames.stream().anyMatch(x -> x.equalsIgnoreCase(n));
    }

    private static Path path() { return FabricLoader.getInstance().getConfigDir().resolve("capitoltv-server.json"); }

    private static void loadConfig() {
        try {
            Path p = path();
            if (Files.exists(p)) cfg = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), Config.class);
            if (cfg == null) cfg = new Config();
            if (cfg.camNames == null) cfg.camNames = new ArrayList<>();
            Files.writeString(p, GSON.toJson(cfg), StandardCharsets.UTF_8);
        } catch (Exception e) {
            CapitolTV.LOG.warn("Capitol TV : configuration serveur illisible ({})", e.toString());
        }
    }

    private static void connect() {
        if (sock != null) sock.disconnect();
        if (cfg.key == null || cfg.key.isBlank()) {
            CapitolTV.LOG.info("Capitol TV : partie serveur inactive (renseigne la clé serveur dans config/capitoltv-server.json).");
            return;
        }
        if (sock == null) sock = new CtvSocket(ServerBridge::onRemote, st -> CapitolTV.LOG.info("Capitol TV : liaison serveur {}", st));
        sock.connect(cfg.url, Map.of("X-CTV-Server-Key", cfg.key.trim(), "User-Agent", "CapitolTV-Server/1.0"));
    }

    private static void onTick(MinecraftServer s) {
        TerrainScan.tick(s);
        if (sock == null || !sock.online()) { if (events.size() > 200) events.clear(); return; }
        tick++;
        if (cfg.giveStreamKeys && !wantKeys.isEmpty() && tick % 20 == 0) {
            JsonArray names = new JsonArray();
            for (String n : wantKeys) names.add(n);
            wantKeys.clear();
            JsonObject o = new JsonObject();
            o.addProperty("t", "pkeys");
            o.add("names", names);
            sock.send(o);
        }
        if (!events.isEmpty() && tick % 5 == 0) {
            JsonArray arr = new JsonArray();
            JsonObject e;
            while (arr.size() < 40 && (e = events.poll()) != null) arr.add(e);
            JsonObject o = new JsonObject();
            o.addProperty("t", "ev");
            o.add("list", arr);
            sock.send(o);
        }
        if (tick % Math.max(5, cfg.intervalTicks) != 0) return;
        JsonArray arr = new JsonArray();
        for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
            JsonObject o = new JsonObject();
            o.addProperty("n", p.getGameProfile().getName());
            o.addProperty("x", Math.round(p.getX() * 10) / 10.0);
            o.addProperty("y", Math.round(p.getY() * 10) / 10.0);
            o.addProperty("z", Math.round(p.getZ() * 10) / 10.0);
            o.addProperty("d", p.getWorld().getRegistryKey().getValue().toString());
            o.addProperty("hp", Math.round(p.getHealth() * 10) / 10.0);
            o.addProperty("sp", p.isSpectator());
            arr.add(o);
        }
        JsonObject o = new JsonObject();
        o.addProperty("t", "seen");
        o.add("list", arr);
        sock.send(o);
    }

    private static JsonObject base(ServerPlayerEntity p, String k) {
        JsonObject e = new JsonObject();
        e.addProperty("k", k);
        e.addProperty("n", p.getGameProfile().getName());
        e.addProperty("x", Math.round(p.getX()));
        e.addProperty("y", Math.round(p.getY()));
        e.addProperty("z", Math.round(p.getZ()));
        e.addProperty("d", p.getWorld().getRegistryKey().getValue().toString());
        return e;
    }

    private static void cause(JsonObject e, DamageSource source) {
        e.addProperty("cause", source.getName());
        Entity at = source.getAttacker();
        Entity direct = source.getSource();
        if (at != null) {
            if (at instanceof PlayerEntity pp) { e.addProperty("by", pp.getGameProfile().getName()); e.addProperty("byP", true); }
            else { e.addProperty("by", at.getName().getString()); e.addProperty("byT", Registries.ENTITY_TYPE.getId(at.getType()).toString()); }
        }
        if (direct != null && direct != at) e.addProperty("via", Registries.ENTITY_TYPE.getId(direct.getType()).toString());
    }

    /** Appelé par le mixin quand un effet est appliqué à un joueur. */
    public static void onEffect(ServerPlayerEntity p, StatusEffectInstance eff) {
        if (!cfg.reportEffects || sock == null || eff.isAmbient()) return;
        JsonObject e = base(p, "effect");
        Identifier id = Registries.STATUS_EFFECT.getId(eff.getEffectType().value());
        e.addProperty("eff", id == null ? "?" : id.toString());
        e.addProperty("effN", eff.getEffectType().value().getName().getString());
        e.addProperty("lvl", eff.getAmplifier() + 1);
        e.addProperty("dur", eff.isInfinite() ? -1 : eff.getDuration() / 20);
        events.add(e);
    }
}
