package dev.alex.threadium.mixin;

import dev.alex.threadium.metrics.ThreadiumMetrics;
import dev.alex.threadium.render.modelpart.ModelPartRenderService;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Exact 26.2 world-render method; HEAD/TAIL is resilient to interior feature edits. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Unique
    private long threadium$worldStart;

    @Inject(
            method =
                    "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
            at = @At("HEAD"),
            require = 1)
    private void threadium$beginWorldRender(CallbackInfo ci) {
        threadium$worldStart = ThreadiumMetrics.isTimingEnabled() ? System.nanoTime() : 0L;
        ModelPartRenderService service = ModelPartRenderService.get();
        if (service != null) service.beginDiagnosticFrame();
    }

    @Inject(
            method =
                    "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
            at = @At("TAIL"),
            require = 1)
    private void threadium$endWorldRender(CallbackInfo ci) {
        ModelPartRenderService service = ModelPartRenderService.get();
        if (service != null) service.drawDiagnosticOverlayAtWorldTail();
        dev.alex.threadium.benchmark.PipelineDifferentialRunner.renderTail();
        if (threadium$worldStart != 0L)
            ThreadiumMetrics.recordWorldRenderNanos(System.nanoTime() - threadium$worldStart);
    }

    @Inject(
            method =
                    "submitEntities(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
                            ordinal = 0),
            require = 1)
    private void threadium$recordSubmittedEntity(CallbackInfo ci) {
        ThreadiumMetrics.recordEntityStateSubmitted();
    }
}
