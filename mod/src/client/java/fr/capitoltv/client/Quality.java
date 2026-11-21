package fr.capitoltv.client;

/**
 * Accord du joueur pour sa POV. La qualité n'est plus un choix : c'est toujours le maximum
 * autorisé par la régie, et l'anti-lag du mod la baisse tout seul si le PC du joueur souffre.
 */
public final class Quality {
    private Quality() { }

    /** derniers retours du serveur : niveau appliqué et plafond de la régie */
    public static volatile String applied = "", cap = "";
    public static volatile boolean blocked, capOff;

    /** "on" (accord), "off" (refus) ou "" (pas encore demandé) */
    public static String chosen() {
        ClientConfig c = ClientConfig.get();
        if (!c.streamAsked) return "";
        return c.streamOn ? "on" : "off";
    }

    /** applique l'accord (ou le refus) : coupe ou relance le flux, prévient le serveur, adapte le son du jeu */
    public static void choose(boolean on) {
        ClientConfig c = ClientConfig.get();
        boolean audioBefore = GameAudio.wanted();
        c.streamAsked = true;
        c.streamOn = on;
        c.streamLevel = on ? "on" : "off";
        ClientConfig.save();
        Streamer.stop(on ? null : "POV refusée par le joueur");
        Streamer.announce();
        if (audioBefore != GameAudio.wanted()) GameAudio.reload();
    }

    public static String label(String level) {
        return switch (level == null ? "" : level) {
            case "eco" -> "480p"; case "std" -> "720p"; case "hd" -> "1080p"; case "max" -> "1080p 60 i/s"; case "off" -> "coupée";
            default -> "";
        };
    }
}
