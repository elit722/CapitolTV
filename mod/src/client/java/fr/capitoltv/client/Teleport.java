package fr.capitoltv.client;

import fr.capitoltv.net.TpPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.network.packet.c2s.play.SpectatorTeleportC2SPacket;

import java.util.Locale;

/**
 * Téléportation en un clic :
 *  1. en spectateur : téléportation vanilla vers le joueur (aucun droit requis) ;
 *  2. sinon, si le mod est aussi installé sur le serveur : demande au serveur ;
 *  3. sinon : commande /tp (nécessite les droits sur le serveur).
 */
public final class Teleport {
    private Teleport() { }

    public static boolean serverSide() {
        try { return ClientPlayNetworking.canSend(TpPayload.ID); } catch (Exception e) { return false; }
    }

    public static String toPlayer(String name) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayNetworkHandler nh = mc.getNetworkHandler();
        if (nh == null || mc.player == null) return "Pas connecté à un serveur";
        if (name == null || name.isEmpty()) return "Pseudo Minecraft inconnu";
        if (mc.player.isSpectator()) {
            for (PlayerListEntry e : nh.getPlayerList()) {
                if (e.getProfile().getName().equalsIgnoreCase(name)) {
                    nh.sendPacket(new SpectatorTeleportC2SPacket(e.getProfile().getId()));
                    return "Téléporté vers " + e.getProfile().getName();
                }
            }
        }
        if (serverSide()) {
            ClientPlayNetworking.send(new TpPayload(name, "", 0, 0, 0));
            return "Téléportation demandée vers " + name;
        }
        nh.sendChatCommand("tp @s " + name);
        return "/tp @s " + name;
    }

    public static String toCoords(double x, double y, double z, String dim) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayNetworkHandler nh = mc.getNetworkHandler();
        if (nh == null || mc.player == null) return "Pas connecté à un serveur";
        if (serverSide()) {
            ClientPlayNetworking.send(new TpPayload("", dim == null ? "" : dim, x, y, z));
            return "Téléportation demandée";
        }
        String cmd = dim == null || dim.isEmpty()
                ? String.format(Locale.ROOT, "tp @s %.1f %.1f %.1f", x, y, z)
                : String.format(Locale.ROOT, "execute in %s run tp @s %.1f %.1f %.1f", dim, x, y, z);
        nh.sendChatCommand(cmd);
        return "/" + cmd;
    }

    /** Téléportation vers un tribut (par son pseudo Minecraft, sinon par sa dernière position connue). */
    public static String toTribute(Data.Trib t) {
        if (t == null) return "Aucun tribut sélectionné";
        if (!t.mc.isEmpty()) {
            MinecraftClient mc = MinecraftClient.getInstance();
            boolean listed = false;
            if (mc.getNetworkHandler() != null) for (PlayerListEntry e : mc.getNetworkHandler().getPlayerList()) if (e.getProfile().getName().equalsIgnoreCase(t.mc)) { listed = true; break; }
            if (listed) return toPlayer(t.mc);
        }
        Data.Pos p = Data.pos.get(t.id);
        if (p != null) return toCoords(p.x, p.y, p.z, p.d);
        return t.mc.isEmpty() ? "Pseudo Minecraft du tribut non renseigné (régie)" : t.mc + " n'est pas connecté";
    }
}
