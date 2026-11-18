package fr.capitoltv.client.mixin;

import fr.capitoltv.client.Observer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientNetMixin {
    @Inject(method = "onEntityDamage", at = @At("TAIL"))
    private void capitoltv$onDamage(EntityDamageS2CPacket packet, CallbackInfo ci) {
        try { Observer.onDamage(packet.entityId(), packet.sourceType(), packet.sourceCauseId(), packet.sourceDirectId()); }
        catch (Throwable ignored) { }
    }

    @Inject(method = "onEntityStatus", at = @At("TAIL"))
    private void capitoltv$onStatus(EntityStatusS2CPacket packet, CallbackInfo ci) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.world == null) return;
            Entity e = packet.getEntity(mc.world);
            if (e != null) Observer.onStatus(e, packet.getStatus());
        } catch (Throwable ignored) { }
    }
}
