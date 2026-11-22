package fr.capitoltv.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.capitoltv.CapitolTV;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Carte de l'arène pour le site, gardée sur le disque du serveur :
 *  - relevé complet : chunk par chunk, jamais plus de ~5 ms par tick, et seulement les chunks
 *    déjà créés (rien n'est généré, rien ne bloque le serveur) ;
 *  - mise à jour continue : les chunks chargés (là où sont les joueurs) sont relus en tâche de fond ;
 *    si quelque chose a changé, la carte repart au site au plus toutes les 3 minutes ;
 *  - au redémarrage, la carte est relue depuis le disque (le site garde aussi la sienne).
 */
final class TerrainScan {
    private TerrainScan() { }

    static final class Area {
        String dim; int x0, z0, step, w, h;
        BufferedImage col, hgt;
        boolean dirty; long changedAt, sentAt; String sentId = "";
        int cx0() { return Math.floorDiv(x0, 16); }
        int cz0() { return Math.floorDiv(z0, 16); }
        int cw() { return Math.floorDiv(x0 + w * step - 1, 16) - cx0() + 1; }
        int ch() { return Math.floorDiv(z0 + h * step - 1, 16) - cz0() + 1; }
    }

    /** relevé complet en cours */
    private static final class Job {
        final Area a; final Consumer<String> say; final long started = System.currentTimeMillis();
        int next, done, skipped; final int total;
        final ArrayDeque<ChunkPos> toLoad = new ArrayDeque<>();
        final Map<Long, CompletableFuture<Boolean>> asking = new HashMap<>();
        int lastPct = -1;
        Job(Area a, Consumer<String> say) { this.a = a; this.say = say; this.total = a.cw() * a.ch(); }
    }

    private static final Map<String, Area> areas = new HashMap<>();
    private static Job job;
    private static int refreshIdx;
    private static final ConcurrentLinkedQueue<Runnable> mainQ = new ConcurrentLinkedQueue<>();

    static boolean busy() { return job != null; }

    static String start(MinecraftServer s, String dim, int cx, int cz, int radius, Consumer<String> say) {
        if (job != null) return "Un relevé est déjà en cours (" + (job.done * 100 / Math.max(1, job.total)) + " %).";
        ServerWorld w = world(s, dim);
        if (w == null) return "Dimension inconnue : " + dim;
        int span = Math.max(32, Math.min(4096, Math.max(16, Math.min(2048, radius)) * 2));
        Area a = new Area();
        a.dim = dim; a.step = Math.max(1, (int) Math.ceil(span / 1024.0)); a.w = a.h = span / a.step;
        a.x0 = cx - span / 2; a.z0 = cz - span / 2;
        a.col = new BufferedImage(a.w, a.h, BufferedImage.TYPE_INT_RGB);
        a.hgt = new BufferedImage(a.w, a.h, BufferedImage.TYPE_INT_RGB);
        // on garde ce qui était déjà relevé au même endroit (une zone pas encore créée reste comme avant)
        Area old = areas.get(dim);
        if (old != null) copy(old, a);
        areas.put(dim, a);
        job = new Job(a, say);
        return "Relevé du terrain lancé : " + span + " blocs de côté autour de " + cx + ", " + cz + " (" + job.total + " chunks ; les zones jamais explorées sont générées au passage, doucement, sans faire ramer le serveur ; la carte reste ensuite à jour toute seule).";
    }

    private static void copy(Area from, Area to) {
        for (int j = 0; j < to.h; j++) for (int i = 0; i < to.w; i++) {
            int x = to.x0 + i * to.step, z = to.z0 + j * to.step;
            int fi = Math.floorDiv(x - from.x0, from.step), fj = Math.floorDiv(z - from.z0, from.step);
            if (fi < 0 || fj < 0 || fi >= from.w || fj >= from.h) continue;
            to.col.setRGB(i, j, from.col.getRGB(fi, fj)); to.hgt.setRGB(i, j, from.hgt.getRGB(fi, fj));
        }
    }

    private static ServerWorld world(MinecraftServer s, String dim) {
        try { return s.getWorld(RegistryKey.of(RegistryKeys.WORLD, Identifier.of(dim))); } catch (Exception e) { return null; }
    }

    /** relit les colonnes de la carte qui tombent dans ce chunk ; true si un point a changé */
    private static boolean readChunk(ServerWorld w, Area a, WorldChunk ch) {
        int bx = ch.getPos().getStartX(), bz = ch.getPos().getStartZ();
        int i0 = Math.max(0, Math.floorDiv(bx - a.x0 + a.step - 1, a.step)), i1 = Math.min(a.w - 1, Math.floorDiv(bx + 15 - a.x0, a.step));
        int j0 = Math.max(0, Math.floorDiv(bz - a.z0 + a.step - 1, a.step)), j1 = Math.min(a.h - 1, Math.floorDiv(bz + 15 - a.z0, a.step));
        boolean changed = false;
        BlockPos.Mutable p = new BlockPos.Mutable();
        int bottom = w.getBottomY();
        for (int j = j0; j <= j1; j++) for (int i = i0; i <= i1; i++) {
            int x = a.x0 + i * a.step, z = a.z0 + j * a.step;
            int y = ch.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x & 15, z & 15) - 1;
            int rgb = 0, depth = 0;
            while (y > bottom) {
                p.set(x, y, z);
                BlockState st = ch.getBlockState(p);
                MapColor mc = st.getMapColor(w, p);
                if (mc != MapColor.CLEAR) {
                    rgb = mc.color;
                    if (st.getFluidState().isIn(FluidTags.WATER)) {
                        int yy = y - 1;
                        while (yy > bottom && depth < 60) { p.set(x, yy, z); if (!ch.getBlockState(p).getFluidState().isIn(FluidTags.WATER)) break; depth++; yy--; }
                    }
                    break;
                }
                y--;
            }
            if (y <= bottom) continue; // colonne vide (chunk pas fini) : on garde l'ancien point
            int hh = Math.max(0, Math.min(65535, y + 64));
            int hv = ((hh >> 8) & 255) << 16 | (hh & 255) << 8 | Math.min(255, depth);
            if ((a.col.getRGB(i, j) & 0xFFFFFF) != rgb || (a.hgt.getRGB(i, j) & 0xFFFFFF) != hv) { a.col.setRGB(i, j, rgb); a.hgt.setRGB(i, j, hv); changed = true; }
        }
        return changed;
    }

    /** appelé à chaque tick du serveur */
    static void tick(MinecraftServer s) {
        for (Runnable r; (r = mainQ.poll()) != null; ) r.run();
        long until = System.nanoTime() + 5_000_000L;
        if (job != null) stepJob(s, until);
        else refresh(s, until);
        long now = System.currentTimeMillis();
        for (Area a : areas.values()) if (a.dirty && job == null && now - a.sentAt > 180_000 && now - a.changedAt > 5_000) send(a, null);
    }

    /** chunks en cours de chargement / génération (maintenus le temps de les lire) */
    private static final Map<Long, int[]> forced = new HashMap<>();
    private static long lastTick;
    private static int slow;

    private static void stepJob(MinecraftServer s, long until) {
        Job jb = job; Area a = jb.a;
        ServerWorld w = world(s, a.dim);
        if (w == null) { job = null; return; }
        // serveur à la peine (tick > 60 ms) : on lève le pied pour ne jamais faire ramer les joueurs
        long nowN = System.nanoTime(); boolean lag = lastTick != 0 && nowN - lastTick > 60_000_000L; lastTick = nowN;
        slow = lag ? 40 : Math.max(0, slow - 1);
        int cw = a.cw(), cx0 = a.cx0(), cz0 = a.cz0();
        try {
            // 1) chunks demandés : lus dès qu'ils sont prêts (chargés du disque ou générés), puis relâchés
            for (var it = forced.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next(); ChunkPos cp = new ChunkPos(e.getKey());
                WorldChunk ch = w.getChunkManager().getWorldChunk(cp.x, cp.z);
                if (ch != null || ++e.getValue()[0] > 1200) {
                    if (ch != null) { if (readChunk(w, a, ch)) a.dirty = true; } else jb.skipped++;
                    w.setChunkForced(cp.x, cp.z, false);
                    it.remove(); jb.done++;
                }
                if (System.nanoTime() > until) break;
            }
            // 2) chunks déjà en mémoire : lus tout de suite ; les autres : demandés au serveur (génération en tâche de fond)
            int max = slow > 0 ? 2 : 12;
            while (jb.next < jb.total && System.nanoTime() < until) {
                int k = jb.next;
                ChunkPos cp = new ChunkPos(cx0 + k % cw, cz0 + k / cw);
                WorldChunk ch = w.getChunkManager().getWorldChunk(cp.x, cp.z);
                if (ch != null) { jb.next++; if (readChunk(w, a, ch)) a.dirty = true; jb.done++; continue; }
                if (forced.size() >= max) break;
                jb.next++;
                w.setChunkForced(cp.x, cp.z, true);
                forced.put(cp.toLong(), new int[]{0});
            }
        } catch (Exception e) {
            CapitolTV.LOG.warn("Capitol TV : relevé du terrain interrompu", e);
            jb.say.accept("Relevé interrompu : " + e.getMessage());
            release(s); job = null; return;
        }
        int pct = jb.done * 100 / Math.max(1, jb.total);
        if (pct / 10 != jb.lastPct / 10 && pct < 100) { jb.lastPct = pct; if (pct > 0) jb.say.accept("Relevé du terrain : " + pct + " %"); }
        if (jb.done < jb.total) return;
        job = null;
        a.dirty = true;
        send(a, jb);
    }

    /** arrêt du serveur ou relevé interrompu : on relâche les chunks demandés */
    static void release(MinecraftServer s) {
        Job jb = job;
        ServerWorld w = jb != null ? world(s, jb.a.dim) : null;
        if (w != null) for (long k : forced.keySet()) { ChunkPos cp = new ChunkPos(k); w.setChunkForced(cp.x, cp.z, false); }
        forced.clear();
    }

    /** mise à jour continue : relit quelques chunks chargés de la carte à chaque tick */
    private static void refresh(MinecraftServer s, long until) {
        if (areas.isEmpty()) return;
        long budget = Math.min(until, System.nanoTime() + 1_500_000L);
        for (Area a : areas.values()) {
            ServerWorld w = world(s, a.dim); if (w == null) continue;
            int cw = a.cw(), total = cw * a.ch(), cx0 = a.cx0(), cz0 = a.cz0();
            for (int n = 0; n < 256 && System.nanoTime() < budget; n++) {
                int k = Math.floorMod(refreshIdx++, total);
                WorldChunk ch = w.getChunkManager().getWorldChunk(cx0 + k % cw, cz0 + k / cw);
                if (ch != null && readChunk(w, a, ch)) { a.dirty = true; a.changedAt = System.currentTimeMillis(); }
            }
        }
    }

    /** le site a perdu la carte (ou c'est un autre site) : on la renvoie */
    static void siteHas(JsonObject ids) {
        mainQ.add(() -> { for (Area a : areas.values()) { String id = ids != null && ids.has(a.dim) ? ids.get(a.dim).getAsString() : ""; if (!a.sentId.isEmpty() && !id.equals(a.sentId)) { a.dirty = true; a.sentAt = 0; a.changedAt = 0; } } });
    }

    private static void send(Area a, Job jb) {
        a.dirty = false; a.sentAt = System.currentTimeMillis();
        String id = Long.toString(System.currentTimeMillis(), 36);
        a.sentId = id;
        // copie rapide ici, encodage PNG et envoi sur un autre fil : aucun à-coup pour le serveur
        BufferedImage col = snap(a.col), hgt = snap(a.hgt);
        Thread th = new Thread(() -> encodeAndSend(a, jb, id, col, hgt), "CapitolTV-carte");
        th.setDaemon(true); th.setPriority(Thread.MIN_PRIORITY); th.start();
    }
    private static BufferedImage snap(BufferedImage i) {
        BufferedImage o = new BufferedImage(i.getWidth(), i.getHeight(), BufferedImage.TYPE_INT_RGB);
        o.setRGB(0, 0, i.getWidth(), i.getHeight(), i.getRGB(0, 0, i.getWidth(), i.getHeight(), null, 0, i.getWidth()), 0, i.getWidth());
        return o;
    }
    private static void encodeAndSend(Area a, Job jb, String id, BufferedImage colImg, BufferedImage hgtImg) {
        String c = png(colImg), hg = png(hgtImg);
        if (c == null || hg == null) { if (jb != null) mainQ.add(() -> jb.say.accept("Relevé : encodage impossible.")); return; }
        int part = 400_000, n = (c.length() + part - 1) / part + (hg.length() + part - 1) / part, k = 0;
        for (String[] kv : new String[][]{{"c", c}, {"h", hg}}) {
            String d = kv[1];
            for (int o = 0; o < d.length(); o += part) {
                JsonObject m = new JsonObject();
                m.addProperty("t", "terrain"); m.addProperty("id", id); m.addProperty("dim", a.dim);
                m.addProperty("x0", a.x0); m.addProperty("z0", a.z0); m.addProperty("step", a.step); m.addProperty("w", a.w); m.addProperty("h", a.h);
                m.addProperty("kind", kv[0]); m.addProperty("part", k++); m.addProperty("parts", n);
                m.addProperty("data", d.substring(o, Math.min(d.length(), o + part)));
                if (!ServerBridge.push(m)) { mainQ.add(() -> { a.dirty = true; a.sentId = ""; }); }
            }
        }
        save(a, c, hg);
        if (jb != null) mainQ.add(() -> jb.say.accept("Relevé terminé en " + (System.currentTimeMillis() - jb.started) / 1000 + " s" + (jb.skipped > 0 ? " (" + jb.skipped + " chunks impossibles à charger)" : "") + ". La carte se met ensuite à jour toute seule."));
    }

    /* ---------- sauvegarde sur le disque du serveur ---------- */
    private static Path dir;
    static void load(MinecraftServer s) {
        areas.clear(); job = null;
        try {
            dir = s.getSavePath(WorldSavePath.ROOT).resolve("capitoltv-terrain");
            if (!Files.isDirectory(dir)) return;
            try (var st = Files.list(dir)) {
                for (Path p : (Iterable<Path>) st::iterator) {
                    if (!p.getFileName().toString().endsWith(".json")) continue;
                    JsonObject j = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
                    String base = p.getFileName().toString().replace(".json", "");
                    Area a = new Area();
                    a.dim = j.get("dim").getAsString(); a.x0 = j.get("x0").getAsInt(); a.z0 = j.get("z0").getAsInt();
                    a.step = j.get("step").getAsInt(); a.w = j.get("w").getAsInt(); a.h = j.get("h").getAsInt();
                    a.sentId = j.has("id") ? j.get("id").getAsString() : "";
                    a.col = ImageIO.read(dir.resolve(base + "-c.png").toFile()); a.hgt = ImageIO.read(dir.resolve(base + "-h.png").toFile());
                    if (a.col == null || a.hgt == null || a.col.getWidth() != a.w) continue;
                    // les PNG relus peuvent être d'un autre type : on repasse en RGB simple
                    a.col = rgb(a.col); a.hgt = rgb(a.hgt);
                    areas.put(a.dim, a);
                }
            }
        } catch (Exception e) { CapitolTV.LOG.warn("Capitol TV : carte enregistrée illisible ({})", e.toString()); }
    }
    private static BufferedImage rgb(BufferedImage i) {
        if (i.getType() == BufferedImage.TYPE_INT_RGB) return i;
        BufferedImage o = new BufferedImage(i.getWidth(), i.getHeight(), BufferedImage.TYPE_INT_RGB);
        o.getGraphics().drawImage(i, 0, 0, null); return o;
    }
    private static void save(Area a, String c64, String h64) {
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
            String base = a.dim.replaceAll("[^a-z0-9_]", "_");
            Files.write(dir.resolve(base + "-c.png"), Base64.getDecoder().decode(c64));
            Files.write(dir.resolve(base + "-h.png"), Base64.getDecoder().decode(h64));
            JsonObject j = new JsonObject();
            j.addProperty("dim", a.dim); j.addProperty("x0", a.x0); j.addProperty("z0", a.z0); j.addProperty("step", a.step); j.addProperty("w", a.w); j.addProperty("h", a.h); j.addProperty("id", a.sentId);
            Files.writeString(dir.resolve(base + ".json"), j.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) { CapitolTV.LOG.warn("Capitol TV : carte non enregistrée ({})", e.toString()); }
    }

    private static String png(BufferedImage img) {
        try (ByteArrayOutputStream o = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", o);
            return Base64.getEncoder().encodeToString(o.toByteArray());
        } catch (Exception e) { return null; }
    }
}
