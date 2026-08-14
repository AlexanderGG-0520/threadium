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

/** Owns only the explicitly validated 1.20.1 replacement subset; every other invocation remains Vanilla. */
@Mixin(ModelPart.class)
abstract class ModelPartMixin {
    @Inject(
            method =
                    "render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;IIFFFF)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1)
    private void threadium$beginModelPartRender(
            MatrixStack matrices,
            VertexConsumer vertexConsumer,
            int light,
            int overlay,
            float red,
            float green,
            float blue,
            float alpha,
            CallbackInfo callbackInfo) {
        ModelPart root = (ModelPart) (Object) this;
        int color = packColor(red, green, blue, alpha);
        if (ModelPartReplacementService.beginModelPartRender(
                root, matrices, vertexConsumer, light, overlay, color)) {
            ModelPartReplacementService.endModelPartRender();
            callbackInfo.cancel();
            return;
        }
        PassThroughEntityRenderService.beginModelPartRender(root, matrices, vertexConsumer, light, overlay, color);
    }

    @Inject(
            method =
                    "render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;IIFFFF)V",
            at = @At("RETURN"),
            require = 1)
    private void threadium$endModelPartRender(
            MatrixStack matrices,
            VertexConsumer vertexConsumer,
            int light,
            int overlay,
            float red,
            float green,
            float blue,
            float alpha,
            CallbackInfo callbackInfo) {
        PassThroughEntityRenderService.endModelPartRender();
        ModelPartReplacementService.endModelPartRender();
    }

    private static int packColor(float red, float green, float blue, float alpha) {
        return channel(alpha) << 24 | channel(red) << 16 | channel(green) << 8 | channel(blue);
    }

    private static int channel(float value) {
        if (!Float.isFinite(value)) return 0;
        return Math.round(Math.max(0.0F, Math.min(1.0F, value)) * 255.0F);
    }
}
