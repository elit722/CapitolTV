package fr.capitoltv.client.mixin;

import fr.capitoltv.client.CamMode;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Caméra en spectateur : les blocs l'arrêtent (sauf NoClip activé sur la barre caméra). */
@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerMixin {
    @Inject(method = "tickMovement", at = @At("HEAD"))
    private void capitoltv$collide(CallbackInfo ci) {
        ClientPlayerEntity p = (ClientPlayerEntity) (Object) this;
        if (p.isSpectator() && CamMode.blockNoclip()) ((Entity) p).noClip = false;
    }
}
