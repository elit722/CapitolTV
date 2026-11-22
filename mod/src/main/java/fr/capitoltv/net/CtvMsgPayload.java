package fr.capitoltv.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Message libre (JSON) du serveur de l'event vers le joueur : récap de mort, hommage dans le ciel… */
public record CtvMsgPayload(String json) implements CustomPayload {
    public static final CustomPayload.Id<CtvMsgPayload> ID = new CustomPayload.Id<>(Identifier.of("capitoltv", "msg"));
    public static final PacketCodec<RegistryByteBuf, CtvMsgPayload> CODEC = PacketCodec.tuple(PacketCodecs.STRING, CtvMsgPayload::json, CtvMsgPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
