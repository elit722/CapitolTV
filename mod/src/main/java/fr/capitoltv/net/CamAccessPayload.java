package fr.capitoltv.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Le serveur indique au joueur s'il fait partie des caméras (mode caméra autorisé). */
public record CamAccessPayload(boolean on) implements CustomPayload {
    public static final CustomPayload.Id<CamAccessPayload> ID = new CustomPayload.Id<>(Identifier.of("capitoltv", "cam_access"));
    public static final PacketCodec<RegistryByteBuf, CamAccessPayload> CODEC = PacketCodec.tuple(PacketCodecs.BOOLEAN, CamAccessPayload::on, CamAccessPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
