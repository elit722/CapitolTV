package fr.capitoltv.client.mixin;

import fr.capitoltv.client.GameAudio;
import net.minecraft.client.sound.SoundEngine;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.IntBuffer;

/**
 * Son du jeu dans la POV : Minecraft mixe dans un périphérique « loopback » d'OpenAL Soft,
 * Capitol TV récupère le mélange, le joue sur les haut-parleurs et l'envoie avec l'image.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
    @Shadow private long devicePointer;

    @Inject(method = "openDeviceOrFallback", at = @At("HEAD"), cancellable = true)
    private static void capitoltv$loopback(String spec, CallbackInfoReturnable<Long> cir) {
        long d = GameAudio.openLoopback();
        if (d != 0L) cir.setReturnValue(d);
    }

    @Inject(method = "createAttributes", at = @At("RETURN"), cancellable = true)
    private void capitoltv$format(MemoryStack stack, boolean directional, CallbackInfoReturnable<IntBuffer> cir) {
        if (!GameAudio.isLoopback(this.devicePointer)) return;
        cir.setReturnValue(GameAudio.withFormat(stack, cir.getReturnValue()));
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void capitoltv$start(String spec, boolean directional, CallbackInfo ci) {
        GameAudio.started(this.devicePointer);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void capitoltv$stop(CallbackInfo ci) {
        GameAudio.stopping(this.devicePointer);
    }
}
