package fr.capitoltv.client.mixin;

import fr.capitoltv.client.Streamer;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prend l'image du monde pour la diffusion, juste avant que le HUD et les menus soient dessinés. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "renderWorld", at = @At("TAIL"))
    private void capitoltv$afterWorld(CallbackInfo ci) {
        Streamer.afterWorld();
    }
}
