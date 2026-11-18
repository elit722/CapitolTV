package fr.capitoltv.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Ce que la caméra voit autour d'elle : positions des tributs à portée, dégâts (avec la cause,
 * grâce au paquet de dégâts envoyé par le serveur), morts et totems. Tout est envoyé à la régie,
 * qui dédoublonne entre caméras (et ignore ces données si le mod serveur est installé).
 */
public final class Observer {
    private Observer() { }

    private static final class Pending { int entityId; String name; String cause; String by; boolean byP; String via; float hpBefore; int ticks; }

    private static final List<Pending> pending = new ArrayList<>();
    private static final List<JsonObject> out = new ArrayList<>();
    private static final Map<Integer, String> lastCause = new HashMap<>();
    private static final Map<Integer, Long> lastCauseAt = new HashMap<>();
    private static int tick;

    static boolean active() {
        ClientConfig c = ClientConfig.get();
        return c.autoReport && !"inactif".equals(c.state) && CapitolClient.online() && Data.ready;
    }

    public static void onDamage(int entityId, RegistryEntry<DamageType> type, int causeId, int directId) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || !active()) return;
        Entity e = mc.world.getEntityById(entityId);
        if (!(e instanceof PlayerEntity p)) return;
        String name = p.getGameProfile().getName();
        if (Data.tribByMc(name) == null) return;
        Pending pd = new Pending();
        pd.entityId = entityId;
        pd.name = name;
        pd.cause = type == null ? "generic" : type.value().msgId();
        Entity at = causeId >= 0 ? mc.world.getEntityById(causeId) : null;
        Entity dir = directId >= 0 ? mc.world.getEntityById(directId) : null;
        if (at instanceof PlayerEntity ap) { pd.by = ap.getGameProfile().getName(); pd.byP = true; }
        else if (at != null) pd.by = at.getName().getString();
        if (dir != null && dir != at) pd.via = Registries.ENTITY_TYPE.getId(dir.getType()).toString();
        pd.hpBefore = p.getHealth();
        pending.add(pd);
        lastCause.put(entityId, pd.cause + "|" + (pd.by == null ? "" : pd.by));
        lastCauseAt.put(entityId, System.currentTimeMillis());
    }

    public static void onStatus(Entity e, byte status) {
        if (!(e instanceof PlayerEntity p) || !active()) return;
        String name = p.getGameProfile().getName();
        if (Data.tribByMc(name) == null) return;
        if (status == 35) {
            JsonObject o = base(p, "totem");
            out.add(o);
        } else if (status == 3) {
            JsonObject o = base(p, "death");
            String lc = lastCause.get(p.getId());
            Long at = lastCauseAt.get(p.getId());
            if (lc != null && at != null && System.currentTimeMillis() - at < 8000) {
                String[] parts = lc.split("\\|", -1);
                o.addProperty("cause", parts[0]);
                if (parts.length > 1 && !parts[1].isEmpty()) o.addProperty("by", parts[1]);
            }
            out.add(o);
        }
    }

    private static JsonObject base(PlayerEntity p, String k) {
        JsonObject o = new JsonObject();
        o.addProperty("k", k);
        o.addProperty("n", p.getGameProfile().getName());
        o.addProperty("x", Math.round(p.getX()));
        o.addProperty("y", Math.round(p.getY()));
        o.addProperty("z", Math.round(p.getZ()));
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world != null) o.addProperty("d", mc.world.getRegistryKey().getValue().toString());
        return o;
    }

    public static void tick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) { pending.clear(); return; }
        tick++;
        // dégâts : on attend quelques ticks que la santé synchronisée arrive
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending pd = it.next();
            if (++pd.ticks < 4) continue;
            it.remove();
            Entity e = mc.world.getEntityById(pd.entityId);
            JsonObject o = new JsonObject();
            o.addProperty("k", "hit");
            o.addProperty("n", pd.name);
            o.addProperty("cause", pd.cause);
            if (pd.by != null) { o.addProperty("by", pd.by); if (pd.byP) o.addProperty("byP", true); }
            if (pd.via != null) o.addProperty("via", pd.via);
            if (e instanceof LivingEntity le) {
                float amt = pd.hpBefore - le.getHealth();
                if (amt > 0) o.addProperty("amt", Math.round(amt * 10) / 10.0);
                o.addProperty("hp", Math.round(le.getHealth() * 10) / 10.0);
                o.addProperty("x", Math.round(le.getX()));
                o.addProperty("y", Math.round(le.getY()));
                o.addProperty("z", Math.round(le.getZ()));
                o.addProperty("d", mc.world.getRegistryKey().getValue().toString());
            }
            out.add(o);
        }
        if (!active()) { out.clear(); return; }
        if (!out.isEmpty() && tick % 5 == 0) {
            JsonArray a = new JsonArray();
            for (JsonObject o : out) a.add(o);
            out.clear();
            JsonObject m = new JsonObject();
            m.addProperty("t", "ev");
            m.add("list", a);
            CapitolClient.send(m);
        }
        if (tick % 40 == 0) {
            JsonArray a = new JsonArray();
            String dim = mc.world.getRegistryKey().getValue().toString();
            for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
                if (p == mc.player || p.isSpectator()) continue;
                String n = p.getGameProfile().getName();
                if (Data.tribByMc(n) == null) continue;
                JsonObject o = new JsonObject();
                o.addProperty("n", n);
                o.addProperty("x", Math.round(p.getX() * 10) / 10.0);
                o.addProperty("y", Math.round(p.getY() * 10) / 10.0);
                o.addProperty("z", Math.round(p.getZ() * 10) / 10.0);
                o.addProperty("d", dim);
                o.addProperty("hp", Math.round(p.getHealth() * 10) / 10.0);
                a.add(o);
                if (a.size() >= 64) break;
            }
            if (!a.isEmpty()) {
                JsonObject m = new JsonObject();
                m.addProperty("t", "seen");
                m.add("list", a);
                CapitolClient.send(m);
            }
        }
    }

    /** Tribut visible le plus proche (pour le signal rapide). */
    public static Data.Trib nearestTribute() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return null;
        Data.Trib best = null;
        double bd = Double.MAX_VALUE;
        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            Data.Trib t = Data.tribByMc(p.getGameProfile().getName());
            if (t == null) continue;
            double d = p.squaredDistanceTo(mc.player);
            if (d < bd) { bd = d; best = t; }
        }
        return best;
    }
}
