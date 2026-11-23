package fr.capitoltv.client;

import net.minecraft.client.option.KeyBinding;

/** Nom des touches telles que le joueur les a réglées (Options › Commandes) : textes et wiki suivent ses choix. */
public final class Keys {
    private Keys() { }

    public static String of(KeyBinding k) {
        if (k == null) return "?";
        if (k.isUnbound()) return "(aucune touche)";
        String s = k.getBoundKeyLocalizedText().getString();
        return s.length() == 1 ? s.toUpperCase() : s;
    }

    /** remplace {menu}, {cam}, {signal}… par les touches choisies */
    public static String fill(String s) {
        if (s.indexOf('{') < 0) return s;
        return s.replace("{menu}", of(CapitolClient.menuKey)).replace("{signal}", of(CapitolClient.signalKey))
                .replace("{cam}", of(CapitolClient.cameraKey)).replace("{peek}", of(CapitolClient.peekKey))
                .replace("{moves}", of(CapitolClient.movesKey)).replace("{talk}", of(CapitolClient.talkKey))
                .replace("{drone}", of(CapitolClient.droneKey)).replace("{pathAdd}", of(CapitolClient.pathAddKey))
                .replace("{pathPlay}", of(CapitolClient.pathPlayKey)).replace("{radar}", of(CapitolClient.radarKey))
                .replace("{noclip}", slotKey(CapitolClient.noclipKey, 9)).replace("{voice}", of(CapitolClient.voiceKey))
                .replace("{state}", of(CapitolClient.stateKey)).replace("{follow}", of(CapitolClient.followKey)).replace("{reply}", of(CapitolClient.replyKey));
    }

    /** touches de la barre rapide (1 à 9), selon les réglages du joueur */
    public static String hotbar(int i) {
        var k = net.minecraft.client.MinecraftClient.getInstance().options.hotbarKeys;
        return i < k.length ? slotKey(k[i], i) : "?";
    }

    /** touche d'un emplacement : chiffre de la rangée du haut quand c'est la touche par défaut
     *  (sur AZERTY elle s'affiche « & é " ' … », peu lisible), sinon la touche choisie par le joueur */
    public static String slotKey(KeyBinding k, int i) {
        if (k == null) return "?";
        if (k.isDefault()) return String.valueOf((i + 1) % 10);
        return of(k);
    }
}
