package dev.alex.threadium.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.alex.threadium.render.modelpart.ModelPartInterceptionResult;
import dev.alex.threadium.render.modelpart.ModelPartRenderService;
import java.util.List;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelFeatureRenderer.class)
abstract class ModelFeatureRendererMixin {
    @Invoker("prepareModel")
    protected abstract void threadium$prepareCoverageModel(ModelFeatureRenderer.Submit<?> submit);

    @Inject(
            method = "buildGroup(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;Ljava/util/List;)V",
            at = @At("RETURN"),
            require = 1)
    private void threadium$appendCoverage(
            FeatureFrameContext context, List<ModelFeatureRenderer.Submit<?>> submits, CallbackInfo ci) {
        if (!submits.isEmpty())
            for (var fixture : dev.alex.threadium.benchmark.PipelineCoverageHarness.directSubmits(submits.getFirst()))
                threadium$prepareCoverageModel(fixture);
    }
    /** MC 26.2 prepareModel bytecode offset 94, after setupAnim at offset 73. */
    @Redirect(
            method = "prepareModel(Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$Submit;)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
                            ordinal = 0),
            require = 1,
            expect = 1)
    private void threadium$queueOrVanilla(
            Model<?> model,
            PoseStack poseStack,
            VertexConsumer consumer,
            int light,
            int overlay,
            int tint,
            ModelFeatureRenderer.Submit<?> submit) {
        ModelPartRenderService service = ModelPartRenderService.get();
        ModelPartInterceptionResult result = service == null
                ? ModelPartInterceptionResult.PASS_THROUGH
                : service.intercept(
                        model,
                        poseStack,
                        consumer,
                        light,
                        overlay,
                        tint,
                        submit.renderType(),
                        submit.sprite(),
                        submit.sheetedDecalPose());
        if (service != null) service.recordDifferentialCompletion(submit.renderType(), result);
        // Critical invariant: this is the sole vanilla-suppression decision in Threadium.
        if (service != null && service.maySuppressVanilla(result)) {
            service.recordVanillaSuppression();
            return;
        }
        model.renderToBuffer(poseStack, consumer, light, overlay, tint);
    }
}
