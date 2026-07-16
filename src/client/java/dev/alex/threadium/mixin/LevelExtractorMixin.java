package dev.alex.threadium.mixin;

import dev.alex.threadium.metrics.ThreadiumMetrics;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Timings only. The three exact private methods are stable Phase 0 boundaries:
 * they own enumeration/extraction and do not change rendering control flow.
 */
@Mixin(LevelExtractor.class)
abstract class LevelExtractorMixin {
    @Unique
    private long threadium$entityStart;

    @Unique
    private long threadium$blockEntityStart;

    @Inject(
            method =
                    "extractVisibleEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V",
            at = @At("HEAD"),
            require = 1)
    private void threadium$beginEntities(CallbackInfo ci) {
        threadium$entityStart = ThreadiumMetrics.isTimingEnabled() ? System.nanoTime() : 0L;
    }

    @Inject(
            method =
                    "extractVisibleEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V",
            at = @At("TAIL"),
            require = 1)
    private void threadium$endEntities(CallbackInfo ci) {
        if (threadium$entityStart != 0L)
            ThreadiumMetrics.recordEntityExtractionNanos(System.nanoTime() - threadium$entityStart);
    }

    @Inject(
            method =
                    "isEntityVisible(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z",
            at = @At("RETURN"),
            require = 1)
    private void threadium$recordVisibilityResult(CallbackInfoReturnable<Boolean> cir) {
        ThreadiumMetrics.recordEntityVisibilityCheck(cir.getReturnValue());
    }

    @Inject(
            method =
                    "extractEntity(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;",
            at = @At("RETURN"),
            require = 1)
    private void threadium$recordExtractedState(CallbackInfoReturnable<?> cir) {
        ThreadiumMetrics.recordEntityRenderStateExtracted();
    }

    @Inject(
            method =
                    "extractVisibleBlockEntities(Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/state/level/LevelRenderState;)V",
            at = @At("HEAD"),
            require = 1)
    private void threadium$beginBlockEntities(CallbackInfo ci) {
        threadium$blockEntityStart = ThreadiumMetrics.isBlockEntityTimingEnabled() ? System.nanoTime() : 0L;
    }

    @Inject(
            method =
                    "extractVisibleBlockEntities(Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/state/level/LevelRenderState;)V",
            at = @At("TAIL"),
            require = 1)
    private void threadium$endBlockEntities(CallbackInfo ci) {
        if (threadium$blockEntityStart != 0L)
            ThreadiumMetrics.recordBlockEntityExtractionNanos(System.nanoTime() - threadium$blockEntityStart);
    }
}
