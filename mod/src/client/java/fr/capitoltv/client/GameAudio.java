package fr.capitoltv.client;

import fr.capitoltv.CapitolTV;
import org.lwjgl.openal.SOFTLoopback;
import org.lwjgl.system.MemoryStack;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.function.BiConsumer;

/**
 * Capture du son du jeu sans logiciel externe : OpenAL Soft mixe dans un « loopback »,
 * on lit ce mélange par tranches de 10 ms, on le joue sur la sortie son du PC (Java Sound)
 * et on le transmet à la diffusion. Si quoi que ce soit échoue, Minecraft garde son son normal.
 */
public final class GameAudio {
    private GameAudio() { }

    public static final int RATE = 48000, FRAMES = 480; // 10 ms stéréo 16 bits
    private static final AudioFormat FMT = new AudioFormat(RATE, 16, 2, true, false);
    private static volatile long loop;
    private static volatile boolean running, failed;
    private static Thread thread;
    /** reçoit chaque tranche de 10 ms (octets PCM little-endian, nombre d'images) pendant la diffusion */
    public static volatile BiConsumer<byte[], Integer> sink;

    /** le son du jeu doit-il passer par Capitol TV ? (accord du joueur + option + sortie son disponible) */
    public static boolean wanted() {
        ClientConfig c = ClientConfig.get();
        if (failed || !c.gameAudio || !c.streamOn) return false;
        try { return AudioSystem.isLineSupported(new DataLine.Info(SourceDataLine.class, FMT)); }
        catch (Throwable t) { return false; }
    }

    public static boolean active() { return running && loop != 0L; }

    public static long openLoopback() {
        if (!wanted()) return 0L;
        try {
            long d = SOFTLoopback.alcLoopbackOpenDeviceSOFT((CharSequence) null);
            if (d == 0L) { failed = true; return 0L; }
            if (!SOFTLoopback.alcIsRenderFormatSupportedSOFT(d, RATE, SOFTLoopback.ALC_STEREO_SOFT, SOFTLoopback.ALC_SHORT_SOFT)) {
                org.lwjgl.openal.ALC10.alcCloseDevice(d); failed = true; return 0L;
            }
            loop = d;
            CapitolTV.LOG.info("Capitol TV : son du jeu capturé pour la POV (OpenAL loopback)");
            return d;
        } catch (Throwable t) {
            failed = true;
            CapitolTV.LOG.warn("Capitol TV : capture du son du jeu indisponible ({})", t.toString());
            return 0L;
        }
    }

    public static boolean isLoopback(long device) { return device != 0L && device == loop; }

    /** ajoute le format de rendu (stéréo, 16 bits, 48 kHz) aux attributs du contexte OpenAL */
    public static IntBuffer withFormat(MemoryStack stack, IntBuffer in) {
        IntBuffer out = stack.callocInt(in.capacity() + 8);
        for (int i = 0; i + 1 < in.limit(); i += 2) { int k = in.get(i); if (k == 0) break; out.put(k).put(in.get(i + 1)); }
        out.put(SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT).put(SOFTLoopback.ALC_STEREO_SOFT);
        out.put(SOFTLoopback.ALC_FORMAT_TYPE_SOFT).put(SOFTLoopback.ALC_SHORT_SOFT);
        out.put(org.lwjgl.openal.ALC10.ALC_FREQUENCY).put(RATE);
        return out.put(0).flip();
    }

    public static void started(long device) {
        if (!isLoopback(device)) return;
        running = true;
        thread = new Thread(() -> render(device), "CapitolTV-son");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY - 1);
        thread.start();
    }

    public static void stopping(long device) {
        if (!isLoopback(device)) return;
        running = false;
        Thread t = thread;
        if (t != null) try { t.join(500); } catch (InterruptedException ignored) { }
        thread = null; loop = 0L;
    }

    private static byte[] withVoice = new byte[0];
    private static float duck = 1f;
    private static byte[] spk = new byte[0];
    private static void render(long device) {
        SourceDataLine line = null;
        ShortBuffer pcm = ByteBuffer.allocateDirect(FRAMES * 4).order(ByteOrder.nativeOrder()).asShortBuffer();
        byte[] bytes = new byte[FRAMES * 4];
        try {
            line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, FMT));
            line.open(FMT, FRAMES * 4 * 6); // ~60 ms de tampon : peu de latence
            line.start();
        } catch (Throwable t) {
            CapitolTV.LOG.warn("Capitol TV : sortie son indisponible ({}), son du jeu muet dans la POV", t.toString());
            line = null;
        }
        long next = System.nanoTime();
        while (running) {
            try {
                pcm.clear();
                SOFTLoopback.alcRenderSamplesSOFT(device, pcm, FRAMES);
                for (int i = 0; i < FRAMES * 2; i++) { short v = pcm.get(i); bytes[i * 2] = (byte) v; bytes[i * 2 + 1] = (byte) (v >> 8); }
                BiConsumer<byte[], Integer> s = sink;
                if (s != null) {
                    // voix du chat vocal : seulement dans la POV envoyée (le joueur les entend déjà)
                    if (withVoice.length != bytes.length) withVoice = new byte[bytes.length];
                    s.accept(VoiceMix.mixInto(bytes, FRAMES, withVoice) ? withVoice : bytes, FRAMES);
                }
                if (line != null) {
                    // interphone : le son du jeu baisse dans TES oreilles quand la régie parle (pas dans la POV diffusée)
                    float want = Talk.regieSpeaks && Talk.open ? 0.3f : 1f;
                    if (duck != 1f || want != 1f) {
                        if (spk.length != bytes.length) spk = new byte[bytes.length];
                        for (int i = 0; i < FRAMES * 2; i++) {
                            if ((i & 1) == 0) duck += (want - duck) * 0.0005f;
                            short v = (short) ((bytes[i * 2] & 0xFF) | (bytes[i * 2 + 1] << 8));
                            int o = Math.round(v * duck);
                            spk[i * 2] = (byte) o; spk[i * 2 + 1] = (byte) (o >> 8);
                        }
                        if (Math.abs(duck - 1f) < 0.002f && want == 1f) duck = 1f;
                        line.write(spk, 0, spk.length);
                    } else line.write(bytes, 0, bytes.length); // bloque : donne le rythme
                }
                else { next += 10_000_000L; long w = next - System.nanoTime(); if (w > 0) java.util.concurrent.locks.LockSupport.parkNanos(w); }
            } catch (Throwable t) {
                CapitolTV.LOG.warn("Capitol TV : erreur du son ({})", t.toString());
                try { Thread.sleep(10); } catch (InterruptedException ignored) { }
            }
        }
        if (line != null) { try { line.drain(); line.close(); } catch (Throwable ignored) { } }
    }

    /** relance le moteur de son de Minecraft (après un changement d'option ou d'accord) */
    public static void reload() {
        failed = false;
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        mc.execute(() -> { try { mc.getSoundManager().reloadSounds(); } catch (Throwable t) { CapitolTV.LOG.warn("Capitol TV : rechargement du son raté ({})", t.toString()); } });
    }
}
