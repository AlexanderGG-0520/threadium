package dev.alex.threadium.mixin;

import dev.alex.threadium.render.entity.ModelPartReplacementService;
import dev.alex.threadium.render.entity.PassThroughEntityRenderService;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Owns only the explicitly validated 1.21.1 replacement subset; every other invocation remains Vanilla. */
@Mixin(ModelPart.class)
abstract class ModelPartMixin {
    @Inject(
            method =
                    "render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;III)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1)
    private void threadium$beginModelPartRender(
            MatrixStack matrices,
            VertexConsumer vertexConsumer,
            int light,
            int overlay,
            int color,
            CallbackInfo callbackInfo) {
        ModelPart root = (ModelPart) (Object) this;
        if (ModelPartReplacementService.beginModelPartRender(root, matrices, vertexConsumer, light, overlay, color)) {
            ModelPartReplacementService.endModelPartRender();
            callbackInfo.cancel();
            return;
        }
        PassThroughEntityRenderService.beginModelPartRender(root, matrices, vertexConsumer, light, overlay, color);
    }

    @Inject(
            method =
                    "render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;III)V",
            at = @At("RETURN"),
            require = 1)
    private void threadium$endModelPartRender(
            MatrixStack matrices,
            VertexConsumer vertexConsumer,
            int light,
            int overlay,
            int color,
            CallbackInfo callbackInfo) {
        PassThroughEntityRenderService.endModelPartRender();
        ModelPartReplacementService.endModelPartRender();
    }
}
