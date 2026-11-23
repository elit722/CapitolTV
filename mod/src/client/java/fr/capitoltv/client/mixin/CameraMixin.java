package fr.capitoltv.client.mixin;

import fr.capitoltv.client.CamDrone;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Roulis du drone cinéma (Z / C) et pose du travelling, appliqués après le calcul normal de la caméra. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    private static long capitoltv$last;
    @Shadow protected abstract void setPos(Vec3d pos);
    @Shadow protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "update", at = @At("TAIL"))
    private void capitoltv$roll(CallbackInfo ci) {
        long n = System.nanoTime();
        float dt = capitoltv$last == 0 ? 0.016f : Math.min(0.1f, (n - capitoltv$last) / 1e9f);
        capitoltv$last = n;
        CamDrone.frame(dt);
        // travelling : pose calculée à chaque image (mouvement continu, indépendant des ticks)
        double[] q = fr.capitoltv.client.CamPath.frame(dt);
        if (q != null) { setPos(new Vec3d(q[0], q[1], q[2])); setRotation((float) q[3], (float) q[4]); }
        if (CamDrone.roll != 0f) ((Camera) (Object) this).getRotation().rotateZ((float) Math.toRadians(CamDrone.roll));
    }
}
