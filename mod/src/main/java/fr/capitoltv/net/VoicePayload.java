package fr.capitoltv.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Staff (responsable caméra et plus) : se rendre audible par les joueurs dans le chat vocal (true) ou non. */
public record VoicePayload(boolean on) implements CustomPayload {
    public static final CustomPayload.Id<VoicePayload> ID = new CustomPayload.Id<>(Identifier.of("capitoltv", "voice"));
    public static final PacketCodec<RegistryByteBuf, VoicePayload> CODEC = PacketCodec.tuple(PacketCodecs.BOOLEAN, VoicePayload::on, VoicePayload::new);

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
