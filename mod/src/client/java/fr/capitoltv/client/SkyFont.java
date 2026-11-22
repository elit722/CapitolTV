package fr.capitoltv.client;

/** Métriques de la police de l'hommage dans le ciel (générées avec la texture sky/font.png). */
final class SkyFont {
    private SkyFont() { }
    static final int W = 4096, H = 128, PAD = 10;
    static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-\'. ";
    static final int[] X = {0, 105, 210, 315, 420, 519, 612, 723, 834, 896, 989, 1100, 1193, 1316, 1427, 1538, 1637, 1748, 1853, 1952, 2051, 2162, 2267, 2397, 2502, 2607, 2706, 2799, 2892, 2985, 3078, 3171, 3264, 3357, 3450, 3543, 3636, 3692, 3742, 3798};
    static final int[] ADV = {85, 85, 85, 85, 79, 73, 91, 91, 42, 73, 91, 73, 103, 91, 91, 79, 91, 85, 79, 79, 91, 85, 110, 85, 85, 79, 73, 73, 73, 73, 73, 73, 73, 73, 73, 73, 36, 30, 36, 36};
}
