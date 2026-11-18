package fr.capitoltv.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import fr.capitoltv.CapitolTV;
import fr.capitoltv.net.CtvSocket;
import fr.capitoltv.net.TpPayload;
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
        public List<String> camNames = new ArrayList<>();
        public boolean reportEffects = true;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static Config cfg = new Config();
    private static CtvSocket sock;
    private static MinecraftServer server;
    private static final ConcurrentLinkedQueue<JsonObject> events = new ConcurrentLinkedQueue<>();
    private static int tick;

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

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                CommandManager.literal("capitol").requires(src -> src.hasPermissionLevel(2))
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
                        .then(CommandManager.literal("reload").executes(c -> {
                            loadConfig(); connect();
                            c.getSource().sendFeedback(() -> Text.literal("§6[Capitol TV]§r Configuration rechargée."), false);
                            return 1;
                        }))));
    }

    private static boolean mayTeleport(MinecraftServer s, ServerPlayerEntity p) {
        if (cfg.tpForSpectators && p.isSpectator()) return true;
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
        if (sock == null) sock = new CtvSocket(o -> { }, st -> CapitolTV.LOG.info("Capitol TV : liaison serveur {}", st));
        sock.connect(cfg.url, Map.of("X-CTV-Server-Key", cfg.key.trim(), "User-Agent", "CapitolTV-Server/1.0"));
    }

    private static void onTick(MinecraftServer s) {
        if (sock == null || !sock.online()) { if (events.size() > 200) events.clear(); return; }
        tick++;
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
