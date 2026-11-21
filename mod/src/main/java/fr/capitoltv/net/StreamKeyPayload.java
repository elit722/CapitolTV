package fr.capitoltv.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Le serveur transmet au joueur sa clé de diffusion Capitol TV (obtenue auprès du site) :
 * le joueur n'a rien à copier ni à coller.
 */
public record StreamKeyPayload(String key, String url, String who) implements CustomPayload {
    public static final CustomPayload.Id<StreamKeyPayload> ID = new CustomPayload.Id<>(Identifier.of("capitoltv", "stream_key"));
    public static final PacketCodec<RegistryByteBuf, StreamKeyPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, StreamKeyPayload::key,
            PacketCodecs.STRING, StreamKeyPayload::url,
            PacketCodecs.STRING, StreamKeyPayload::who,
            StreamKeyPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
