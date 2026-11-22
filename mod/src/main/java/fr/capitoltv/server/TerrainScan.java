package fr.capitoltv.server;

import com.google.gson.JsonObject;
import fr.capitoltv.CapitolTV;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.function.Consumer;

/**
 * Relevé du terrain de l'arène pour la carte du site : pour chaque colonne, la couleur du bloc
 * du dessus (couleur des cartes Minecraft), sa hauteur et la profondeur de l'eau.
 * Le relevé avance un peu à chaque tick (pas de lag), puis part au site en deux images PNG.
 */
final class TerrainScan {
    private static TerrainScan cur;

    final String dim; final int x0, z0, step, w, h;
    final BufferedImage col, hgt;
    int row = 0;
    final Consumer<String> say;
    final long started = System.currentTimeMillis();

    private TerrainScan(String dim, int cx, int cz, int radius, Consumer<String> say) {
        this.dim = dim; this.say = say;
        int span = Math.max(32, Math.min(4096, radius * 2));
        this.step = Math.max(1, (int) Math.ceil(span / 1024.0));
        this.w = this.h = span / step;
        this.x0 = cx - span / 2; this.z0 = cz - span / 2;
        this.col = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        this.hgt = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    }

    static boolean busy() { return cur != null; }

    static String start(MinecraftServer s, String dim, int cx, int cz, int radius, Consumer<String> say) {
        if (cur != null) return "Un relevé est déjà en cours (" + (cur.row * 100 / cur.h) + " %).";
        ServerWorld w = world(s, dim);
        if (w == null) return "Dimension inconnue : " + dim;
        cur = new TerrainScan(dim, cx, cz, Math.max(16, Math.min(2048, radius)), say);
        return "Relevé du terrain lancé : " + cur.w * cur.step + " blocs de côté autour de " + cx + ", " + cz + " (1 point tous les " + cur.step + " bloc" + (cur.step > 1 ? "s" : "") + ").";
    }

    private static ServerWorld world(MinecraftServer s, String dim) {
        try { return s.getWorld(RegistryKey.of(RegistryKeys.WORLD, Identifier.of(dim))); } catch (Exception e) { return null; }
    }

    /** appelé à chaque tick du serveur : quelques lignes, au plus ~15 ms */
    static void tick(MinecraftServer s) {
        TerrainScan t = cur; if (t == null) return;
        ServerWorld w = world(s, t.dim);
        if (w == null) { cur = null; return; }
        long until = System.nanoTime() + 15_000_000L;
        BlockPos.Mutable p = new BlockPos.Mutable();
        try {
            while (t.row < t.h && System.nanoTime() < until) {
                int z = t.z0 + t.row * t.step;
                for (int i = 0; i < t.w; i++) {
                    int x = t.x0 + i * t.step;
                    WorldChunk ch = w.getChunk(x >> 4, z >> 4);
                    int y = ch.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x & 15, z & 15) - 1;
                    int bottom = w.getBottomY();
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
                    int hh = Math.max(0, Math.min(65535, y + 64));
                    t.col.setRGB(i, t.row, rgb);
                    t.hgt.setRGB(i, t.row, ((hh >> 8) & 255) << 16 | (hh & 255) << 8 | Math.min(255, depth));
                }
                t.row++;
            }
        } catch (Exception e) {
            CapitolTV.LOG.warn("Capitol TV : relevé du terrain interrompu", e);
            t.say.accept("Relevé interrompu : " + e.getMessage());
            cur = null; return;
        }
        if (t.row < t.h) return;
        cur = null;
        String c = png(t.col), hg = png(t.hgt);
        if (c == null || hg == null) { t.say.accept("Relevé : encodage impossible."); return; }
        String id = Long.toString(System.currentTimeMillis(), 36);
        int part = 400_000, n = (c.length() + part - 1) / part + (hg.length() + part - 1) / part, k = 0;
        for (String[] kv : new String[][]{{"c", c}, {"h", hg}}) {
            String d = kv[1];
            for (int o = 0; o < d.length(); o += part) {
                JsonObject m = new JsonObject();
                m.addProperty("t", "terrain"); m.addProperty("id", id); m.addProperty("dim", t.dim);
                m.addProperty("x0", t.x0); m.addProperty("z0", t.z0); m.addProperty("step", t.step); m.addProperty("w", t.w); m.addProperty("h", t.h);
                m.addProperty("kind", kv[0]); m.addProperty("part", k++); m.addProperty("parts", n);
                m.addProperty("data", d.substring(o, Math.min(d.length(), o + part)));
                ServerBridge.push(m);
            }
        }
        t.say.accept("Relevé terminé en " + (System.currentTimeMillis() - t.started) / 1000 + " s, envoyé au site (" + (c.length() + hg.length()) / 1366 + " Ko).");
    }

    private static String png(BufferedImage img) {
        try (ByteArrayOutputStream o = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", o);
            return Base64.getEncoder().encodeToString(o.toByteArray());
        } catch (Exception e) { return null; }
    }
}
