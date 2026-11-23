package fr.capitoltv;

import java.util.UUID;

/** Pont entre l'extension Simple Voice Chat (commune) et le mixage de la POV (côté client). */
public final class VoiceTap {
    private VoiceTap() { }

    public interface Sink {
        /** voix reçue d'un autre joueur (PCM 48 kHz mono) ; pos = null si sans position (groupe) */
        void voice(UUID from, short[] pcm, double[] pos);
        /** ma propre voix (micro) */
        void own(short[] pcm);
    }

    public static volatile Sink sink;
}
