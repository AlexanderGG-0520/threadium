package dev.alex.threadium.mixin;

import dev.alex.threadium.metrics.ThreadiumMetrics;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measures state extraction, not GPU submission. */
@Mixin(ParticleEngine.class)
abstract class ParticleEngineMixin {
    @Unique private long threadium$particleStart;

    @Inject(method = "extract(Lnet/minecraft/client/renderer/state/level/ParticlesRenderState;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/Camera;F)V", at = @At("HEAD"), require = 1)
    private void threadium$beginParticleExtraction(CallbackInfo ci) { threadium$particleStart = ThreadiumMetrics.isTimingEnabled() ? System.nanoTime() : 0L; }

    @Inject(method = "extract(Lnet/minecraft/client/renderer/state/level/ParticlesRenderState;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/Camera;F)V", at = @At("TAIL"), require = 1)
    private void threadium$endParticleExtraction(CallbackInfo ci) { if (threadium$particleStart != 0L) ThreadiumMetrics.recordParticleExtractionNanos(System.nanoTime() - threadium$particleStart); }
}
