package dev.alex.threadium.mixin;

import dev.alex.threadium.render.modelpart.ModelPartRenderService;
import java.util.List;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderTypeFeatureRenderer.class)
abstract class RenderTypeFeatureRendererMixin {
    @Inject(
            method = "prepareGroup(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;Ljava/util/List;Z)V",
            at = @At("HEAD"),
            require = 1)
    private void threadium$beginModels(
            FeatureFrameContext context, List<? extends SubmitNode> submits, boolean ordered, CallbackInfo ci) {
        if ((Object) this instanceof ModelFeatureRenderer) {
            ModelPartRenderService service = ModelPartRenderService.get();
            if (service != null && service.replacementEnabled()) service.beginGroup(this, ordered, submits.size());
        }
    }

    @Inject(
            method = "prepareGroup(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;Ljava/util/List;Z)V",
            at = @At("RETURN"),
            require = 1)
    private void threadium$endModels(
            FeatureFrameContext context, List<? extends SubmitNode> submits, boolean ordered, CallbackInfo ci) {
        if ((Object) this instanceof ModelFeatureRenderer) {
            ModelPartRenderService service = ModelPartRenderService.get();
            if (service != null && service.replacementEnabled()) service.endGroup();
        }
    }

    /** Flushes accepted opaque/cutout model batches at the matching feature execution boundary. */
    @Inject(
            method = "executeGroup(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;ILjava/util/List;Z)V",
            at = @At("HEAD"),
            require = 1)
    private void threadium$flushModels(
            FeatureFrameContext context,
            int group,
            List<? extends SubmitNode> submits,
            boolean ordered,
            CallbackInfo ci) {
        if ((Object) this instanceof ModelFeatureRenderer) {
            ModelPartRenderService service = ModelPartRenderService.get();
            if (service != null && service.replacementEnabled()) service.flush();
        }
    }
}
