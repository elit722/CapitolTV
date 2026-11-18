package fr.capitoltv.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Demande d'une caméra : passer en mode spectateur (true) ou revenir (false). */
public record SpecPayload(boolean on) implements CustomPayload {
    public static final CustomPayload.Id<SpecPayload> ID = new CustomPayload.Id<>(Identifier.of("capitoltv", "spec"));
    public static final PacketCodec<RegistryByteBuf, SpecPayload> CODEC = PacketCodec.tuple(PacketCodecs.BOOLEAN, SpecPayload::on, SpecPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
