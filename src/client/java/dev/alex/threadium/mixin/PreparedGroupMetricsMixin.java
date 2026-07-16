package dev.alex.threadium.mixin;

import dev.alex.threadium.metrics.StagedVertexMetrics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.renderer.feature.FeatureRenderDispatcher$PreparedGroup")
abstract class PreparedGroupMetricsMixin {
    @Unique
    private long threadium$prepareStart;

    /** 26.2 PreparedGroup.prepare delegates exactly once to FeatureRenderer.prepareGroup. */
    @Inject(
            method =
                    "prepare(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;Lnet/minecraft/client/renderer/feature/FeatureRendererMap;Ljava/util/List;)V",
            at = @At("HEAD"),
            require = 1,
            expect = 1)
    private void threadium$startGroup(CallbackInfo ci) {
        threadium$prepareStart = StagedVertexMetrics.start();
    }

    @Inject(
            method =
                    "prepare(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;Lnet/minecraft/client/renderer/feature/FeatureRendererMap;Ljava/util/List;)V",
            at = @At("RETURN"),
            require = 1,
            expect = 1)
    private void threadium$endGroup(CallbackInfo ci) {
        StagedVertexMetrics.recordGroup(threadium$prepareStart);
    }
}
