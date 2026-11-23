package fr.capitoltv.client;

import fr.capitoltv.VoiceTap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.PlayerEntity;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Chat vocal (Simple Voice Chat) dans la POV diffusée :
 *  - les voix entendues partent dans le flux, plus faibles avec la distance ;
 *  - sauf celles des caméras et du staff (à moins qu'ils ne se soient rendus audibles) ;
 *  - la voix du tribut lui-même part aussi ; jamais celle d'une caméra ou du staff.
 * Rien ne change dans le casque du joueur : seul le flux envoyé à la régie est concerné.
 */
public final class VoiceMix implements VoiceTap.Sink {
    private VoiceMix() { }
    private static final VoiceMix I = new VoiceMix();
    static void install() { VoiceTap.sink = I; }

    private static final Set<String> silent = new HashSet<>(), loud = new HashSet<>();
    static synchronized void roster(com.google.gson.JsonObject m) {
        silent.clear(); loud.clear();
        for (var e : Data.arr(m, "silent")) silent.add(e.getAsString().toLowerCase(Locale.ROOT));
        for (var e : Data.arr(m, "loud")) loud.add(e.getAsString().toLowerCase(Locale.ROOT));
    }
    private static synchronized boolean blocked(String name) { String k = name.toLowerCase(Locale.ROOT); return silent.contains(k) && !loud.contains(k); }

    private static final class Q { final ArrayDeque<float[]> q = new ArrayDeque<>(); float[] cur; int pos; long last; }
    private static final Map<UUID, Q> qs = new HashMap<>();
    private static final UUID OWN = new UUID(0, 0);

    private static boolean wanted() { return GameAudio.sink != null; }

    @Override
    public void voice(UUID from, short[] pcm, double[] pos) {
        if (!wanted() || pcm.length == 0) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        var nh = mc.getNetworkHandler(); if (nh == null || mc.player == null) return;
        PlayerListEntry pe = nh.getPlayerListEntry(from);
        if (pe != null && blocked(pe.getProfile().getName())) return;
        float g = 1f;
        double[] p = pos;
        if (p == null && mc.world != null) { PlayerEntity e = mc.world.getPlayerByUuid(from); if (e != null) p = new double[]{e.getX(), e.getEyeY(), e.getZ()}; }
        if (p != null && p.length == 3) {
            double d = Math.sqrt(mc.player.squaredDistanceTo(p[0], p[1], p[2]));
            g = (float) Math.max(0, Math.min(1, 1 - d / 48.0));
            g *= g;
            if (g <= 0.01f) return;
        }
        push(from, pcm, g);
    }

    @Override
    public void own(short[] pcm) {
        if (!wanted() || CamMode.allowed() || Data.meSup) return; // caméra ou staff : sa voix ne part jamais dans le flux
        push(OWN, pcm, 1f);
    }

    private static synchronized void push(UUID id, short[] pcm, float g) {
        Q q = qs.computeIfAbsent(id, k -> new Q());
        float[] f = new float[pcm.length];
        for (int i = 0; i < pcm.length; i++) f[i] = pcm[i] * g;
        q.q.add(f); q.last = System.currentTimeMillis();
        while (q.q.size() > 12) q.q.poll(); // plus de 240 ms de retard : on rattrape
    }

    /** ajoute les voix (mono) aux deux voies du son de la POV ; renvoie false s'il n'y avait rien */
    static synchronized boolean mixInto(byte[] stereo, int frames, byte[] out) {
        if (qs.isEmpty()) return false;
        boolean any = false;
        float[] mix = null;
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, Q>> it = qs.entrySet().iterator(); it.hasNext(); ) {
            Q q = it.next().getValue();
            if (q.cur == null && q.q.isEmpty()) { if (now - q.last > 5000) it.remove(); continue; }
            if (mix == null) mix = new float[frames];
            for (int i = 0; i < frames; i++) {
                if (q.cur == null || q.pos >= q.cur.length) { q.cur = q.q.poll(); q.pos = 0; if (q.cur == null) break; }
                mix[i] += q.cur[q.pos++];
            }
            any = true;
        }
        if (!any) return false;
        for (int i = 0; i < frames; i++) {
            for (int c = 0; c < 2; c++) {
                int k = (i * 2 + c) * 2;
                int v = (short) ((stereo[k] & 0xFF) | (stereo[k + 1] << 8)) + Math.round(mix[i]);
                v = Math.max(-32768, Math.min(32767, v));
                out[k] = (byte) v; out[k + 1] = (byte) (v >> 8);
            }
        }
        return true;
    }
}
