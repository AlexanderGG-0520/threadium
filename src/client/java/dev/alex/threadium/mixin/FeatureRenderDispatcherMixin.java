package dev.alex.threadium.mixin;

import dev.alex.threadium.metrics.StagedVertexMetrics;
import dev.alex.threadium.render.phase.ThreadiumPhasePipeline;
import dev.alex.threadium.render.text.RetainedTextManager;
import java.util.function.Consumer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FeatureRenderDispatcher.class)
abstract class FeatureRenderDispatcherMixin {
    @Unique
    private long threadium$featurePreparationStart;

    @Inject(
            method =
                    "prepareFrameWithContext(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;",
            at = @At("HEAD"),
            require = 1,
            expect = 1)
    private void threadium$beginStagedFrame(
            FeatureFrameContext context, SubmitNodeStorage storage, CallbackInfoReturnable<?> cir) {
        StagedVertexMetrics.beginFrame();
        RetainedTextManager.beginFrame();
    }

    /** Offset 47, before the first FeatureRendererMap.values call: beginPrepare starts here. */
    @Inject(
            method =
                    "prepareFrameWithContext(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/feature/FeatureRendererMap;values()Ljava/lang/Iterable;",
                            ordinal = 0),
            require = 1,
            expect = 1)
    private void threadium$startFeaturePreparation(
            FeatureFrameContext context, SubmitNodeStorage storage, CallbackInfoReturnable<?> cir) {
        threadium$featurePreparationStart = StagedVertexMetrics.start();
    }

    /** Offset 293, immediately before upload: beginPrepare, all groups, and finishPrepare are complete. */
    @Inject(
            method =
                    "prepareFrameWithContext(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/client/renderer/StagedVertexBuffer;upload()V",
                            ordinal = 0),
            require = 1,
            expect = 1)
    private void threadium$endFeaturePreparation(
            FeatureFrameContext context, SubmitNodeStorage storage, CallbackInfoReturnable<?> cir) {
        StagedVertexMetrics.recordFeature(threadium$featurePreparationStart);
    }
    /**
     * 26.2 bytecode offset 24: the sole drainPhases invocation, immediately after profiler "sort"
     * and before every renderer.beginPrepare call. Critical: failure must abort Mixin application.
     */
    @Redirect(
            method =
                    "prepareFrameWithContext(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/SubmitNodeStorage;drainPhases(Ljava/util/function/Consumer;)V",
                            ordinal = 0),
            require = 1,
            expect = 1)
    private void threadium$preparePhases(SubmitNodeStorage storage, Consumer<FeatureRenderPhase<?>> consumer) {
        ThreadiumPhasePipeline.drainAndPrepare(storage, consumer);
    }

    /**
     * 26.2 synthetic consumer bytecode offset 10: the sole FeatureRenderPhase.sortInto invocation.
     * The original phase still constructs vanilla PhaseSubmitGrouper; Threadium only replays ordering.
     */
    @Redirect(
            method =
                    "lambda$prepareFrameWithContext$0(Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lnet/minecraft/client/renderer/feature/phase/FeatureRenderPhase;)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/feature/phase/FeatureRenderPhase;sortInto(Lnet/minecraft/client/renderer/feature/phase/FeatureRenderPhase$Output;)V",
                            ordinal = 0),
            require = 1,
            expect = 1)
    private static void threadium$sortOrReplay(FeatureRenderPhase<?> phase, FeatureRenderPhase.Output output) {
        ThreadiumPhasePipeline.sortOrReplay(phase, output);
    }
}
