package fr.capitoltv.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Demande de téléportation d'un caméraman (traitée par la partie serveur du mod, si installée). */
public record TpPayload(String target, String dim, double x, double y, double z) implements CustomPayload {
    public static final CustomPayload.Id<TpPayload> ID = new CustomPayload.Id<>(Identifier.of("capitoltv", "tp"));
    public static final PacketCodec<RegistryByteBuf, TpPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, TpPayload::target,
            PacketCodecs.STRING, TpPayload::dim,
            PacketCodecs.DOUBLE, TpPayload::x,
            PacketCodecs.DOUBLE, TpPayload::y,
            PacketCodecs.DOUBLE, TpPayload::z,
            TpPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
