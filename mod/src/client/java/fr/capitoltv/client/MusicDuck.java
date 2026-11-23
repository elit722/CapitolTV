package fr.capitoltv.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundCategory;

/** Baisse en douceur les musiques du jeu (musique, disques) pendant une projection, puis les remet. */
final class MusicDuck {
    private MusicDuck() { }
    private static final SoundCategory[] CATS = {SoundCategory.MUSIC, SoundCategory.RECORDS};
    private static double[] saved;
    private static double k = 1, target = 1, speed;

    static void down(double seconds) { target = 0; speed = 1.0 / Math.max(0.1, seconds * 20); }
    static void up(double seconds) { target = 1; speed = 1.0 / Math.max(0.1, seconds * 20); }

    static void tick(MinecraftClient mc) {
        if (k == target && (saved == null || target < 1)) return;
        if (saved == null) { saved = new double[CATS.length]; for (int i = 0; i < CATS.length; i++) saved[i] = mc.options.getSoundVolumeOption(CATS[i]).getValue(); }
        k = target > k ? Math.min(target, k + speed) : Math.max(target, k - speed);
        double e = k * k * (3 - 2 * k);
        for (int i = 0; i < CATS.length; i++) mc.options.getSoundVolumeOption(CATS[i]).setValue(saved[i] * e);
        if (k >= 1 && target >= 1) saved = null; // réglages du joueur rendus tels quels
    }
}
