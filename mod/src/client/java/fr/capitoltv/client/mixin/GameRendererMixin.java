package fr.capitoltv.client.mixin;

import fr.capitoltv.client.Streamer;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prend l'image du monde pour la diffusion, juste avant que le HUD et les menus soient dessinés. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "renderWorld", at = @At("TAIL"))
    private void capitoltv$afterWorld(CallbackInfo ci) {
        Streamer.afterWorld();
    }

    /** zoom fluide du drone cinéma (Ctrl + molette) */
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void capitoltv$droneFov(CallbackInfoReturnable<Float> cir) {
        float k = fr.capitoltv.client.CamDrone.fov;
        if (k != 1f) cir.setReturnValue(cir.getReturnValue() * k);
    }
}
