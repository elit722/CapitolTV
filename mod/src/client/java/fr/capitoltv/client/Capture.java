package fr.capitoltv.client;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.RenderPipelines;

import java.nio.ByteBuffer;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * Capture légère de l'image du jeu, sur le modèle du capteur Tracy de Minecraft :
 * la carte graphique réduit l'image à la taille d'envoi, puis la recopie en arrière-plan.
 * Le jeu ne s'arrête jamais pour attendre la copie, et rien n'est converti pixel par pixel en Java.
 */
final class Capture {
    private Capture() { }

    /** un emplacement de copie GPU → mémoire (plusieurs en vol pour ne jamais bloquer) */
    private static final class Slot {
        GpuBuffer buf;
        int gen;
        volatile boolean busy;
    }

    private static final int SLOTS = 3;
    private static final Slot[] slots = new Slot[SLOTS];
    private static GpuTexture tex;
    private static GpuTextureView view;
    private static int w, h, gen;
    private static boolean broken;

    /** taille d'envoi : l'image du jeu réduite pour tenir dans maxH (jamais agrandie), multiple de 4 */
    private static int[] target(int fbw, int fbh, int maxH) {
        int th = Math.min(fbh, Math.max(144, maxH));
        int tw = Math.round(fbw * (th / (float) fbh));
        return new int[]{Math.max(16, tw & ~3), Math.max(16, th & ~3)};
    }

    private static void ensure(int tw, int th) {
        if (tex != null && tw == w && th == h) return;
        GpuDevice dev = RenderSystem.getDevice();
        if (view != null) view.close();
        if (tex != null) tex.close();
        gen++;
        for (int i = 0; i < SLOTS; i++) {
            Slot s = slots[i];
            if (s != null && !s.busy && s.buf != null) { s.buf.close(); s.buf = null; }
        }
        w = tw; h = th;
        // 10 = copie source + cible de rendu ; 9 = lecture + destination de copie
        tex = dev.createTexture("Capitol TV capture", GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_RENDER_ATTACHMENT, TextureFormat.RGBA8, w, h, 1, 1);
        view = dev.createTextureView(tex);
    }

    /**
     * Lance une capture (appelé sur le fil de rendu, juste après le monde).
     * @param sink reçoit l'image RGBA, de haut en bas, dans un tampon direct (à rendre avec {@link #recycle})
     * @return false si aucune copie n'a pu être lancée (toutes en cours) — l'image est simplement sautée
     */
    static boolean grab(Framebuffer fb, int maxH, FrameSink sink) {
        if (broken || fb == null || fb.getColorAttachment() == null) return false;
        try {
            int[] t = target(fb.textureWidth, fb.textureHeight, maxH);
            ensure(t[0], t[1]);
            Slot slot = null;
            for (int i = 0; i < SLOTS; i++) {
                if (slots[i] == null) slots[i] = new Slot();
                if (!slots[i].busy) { slot = slots[i]; break; }
            }
            if (slot == null) return false;
            GpuDevice dev = RenderSystem.getDevice();
            if (slot.buf == null || slot.gen != gen) {
                if (slot.buf != null) slot.buf.close();
                slot.buf = dev.createBuffer(() -> "Capitol TV capture buffer", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, w * h * 4);
                slot.gen = gen;
            }
            // réduction sur la carte graphique (même méthode que le capteur Tracy de Minecraft)
            RenderSystem.ShapeIndexBuffer quads = RenderSystem.getSequentialBuffer(VertexFormat.DrawMode.QUADS);
            GpuBuffer idx = quads.getIndexBuffer(6);
            try (RenderPass pass = dev.createCommandEncoder().createRenderPass(() -> "Capitol TV blit", view, OptionalInt.empty())) {
                pass.setPipeline(RenderPipelines.TRACY_BLIT);
                pass.setVertexBuffer(0, RenderSystem.getQuadVertexBuffer());
                pass.setIndexBuffer(idx, quads.getIndexType());
                pass.bindSampler("InSampler", fb.getColorAttachmentView());
                pass.drawIndexed(0, 0, 6, 1);
            }
            final Slot s = slot;
            final int cw = w, ch = h, g = gen;
            s.busy = true;
            CommandEncoder enc = dev.createCommandEncoder();
            enc.copyTextureToBuffer(tex, s.buf, 0, () -> {
                // la copie est terminée : on lit sans attendre le GPU, une recopie mémoire par ligne (retournée)
                try {
                    if (g != gen) { s.buf.close(); s.buf = null; return; }
                    ByteBuffer out = take(cw * ch * 4);
                    try (GpuBuffer.MappedView mv = RenderSystem.getDevice().createCommandEncoder().mapBuffer(s.buf, true, false)) {
                        ByteBuffer src = mv.data();
                        int row = cw * 4;
                        for (int y = 0; y < ch; y++) out.put((ch - 1 - y) * row, src, y * row, row);
                    }
                    sink.accept(out, cw, ch);
                } catch (Throwable e) {
                    fr.capitoltv.CapitolTV.LOG.warn("Capitol TV : lecture de l'image ratée ({})", e.toString());
                } finally {
                    s.busy = false;
                }
            }, 0);
            return true;
        } catch (Throwable e) {
            broken = true;
            fr.capitoltv.CapitolTV.LOG.warn("Capitol TV : capture rapide indisponible, retour à la capture classique ({})", e.toString());
            return false;
        }
    }

    static boolean broken() { return broken; }

    /* ---------- tampons réutilisés (pas d'allocation à chaque image) ---------- */
    private static final java.util.concurrent.ConcurrentLinkedQueue<ByteBuffer> POOL = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private static ByteBuffer take(int size) {
        ByteBuffer b;
        while ((b = POOL.poll()) != null) if (b.capacity() == size) { b.clear(); return b; }
        return ByteBuffer.allocateDirect(size);
    }

    static void recycle(ByteBuffer b) { if (b != null && POOL.size() < 4) POOL.add(b); }

    @FunctionalInterface
    interface FrameSink { void accept(ByteBuffer rgba, int w, int h); }

    /** pour le test automatique : image en NativeImage via la capture classique */
    static void legacy(Framebuffer fb, Consumer<net.minecraft.client.texture.NativeImage> then) {
        net.minecraft.client.util.ScreenshotRecorder.takeScreenshot(fb, then);
    }
}
