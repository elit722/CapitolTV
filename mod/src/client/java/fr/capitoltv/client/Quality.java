package fr.capitoltv.client;

import java.util.List;

/** Niveaux de qualité du flux envoyé à Capitol TV (choisis par le joueur, plafonnés par la régie). */
public final class Quality {
    private Quality() { }

    public record Level(String id, String name, String spec, String hint, int h, int kbps, int fps) { }

    public static final List<Level> ALL = List.of(
            new Level("off", "Sans POV", "aucune image envoyée", "La régie ne verra pas ton écran.", 0, 0, 0),
            new Level("eco", "Éco", "480p · 30 i/s · 2 Mb/s", "Petit PC ou connexion modeste.", 480, 2000, 30),
            new Level("std", "Standard", "720p · 30 i/s · 4,5 Mb/s", "Conseillé pour la plupart des joueurs.", 720, 4500, 30),
            new Level("hd", "HD", "1080p · 30 i/s · 7 Mb/s", "Bonne connexion (fibre).", 1080, 7000, 30),
            new Level("max", "Max", "1080p · 60 i/s · 10 Mb/s", "PC puissant et fibre.", 1080, 10000, 60));

    public static Level of(String id) {
        for (Level l : ALL) if (l.id().equals(id)) return l;
        return ALL.get(2);
    }

    public static int rank(String id) {
        for (int i = 0; i < ALL.size(); i++) if (ALL.get(i).id().equals(id)) return i;
        return 2;
    }

    /** derniers retours du serveur : niveau appliqué et plafond de la régie */
    public static volatile String applied = "", cap = "";

    /** niveau choisi (vide = pas encore choisi) */
    public static String chosen() { return ClientConfig.get().streamLevel == null ? "" : ClientConfig.get().streamLevel; }

    /** applique un choix : coupe ou relance le flux et prévient le serveur */
    public static void choose(String id) {
        ClientConfig c = ClientConfig.get();
        c.streamLevel = of(id).id();
        c.streamAsked = true;
        c.streamOn = !"off".equals(c.streamLevel);
        ClientConfig.save();
        Streamer.stop(c.streamOn ? null : "sans POV (choix du joueur)");
        Streamer.announce();
    }
}
