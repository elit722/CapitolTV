package fr.capitoltv.client.mixin;

import fr.capitoltv.client.CamDrone;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Roulis du drone cinéma : penche l'image (Z / C) après le calcul normal de la caméra. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    private static long capitoltv$last;

    @Inject(method = "update", at = @At("TAIL"))
    private void capitoltv$roll(CallbackInfo ci) {
        long n = System.nanoTime();
        float dt = capitoltv$last == 0 ? 0.016f : Math.min(0.1f, (n - capitoltv$last) / 1e9f);
        capitoltv$last = n;
        CamDrone.frame(dt);
        if (CamDrone.roll != 0f) ((Camera) (Object) this).getRotation().rotateZ((float) Math.toRadians(CamDrone.roll));
    }
}
