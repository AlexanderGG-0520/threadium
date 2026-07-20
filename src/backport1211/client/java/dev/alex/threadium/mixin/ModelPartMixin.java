package dev.alex.threadium.mixin;

import dev.alex.threadium.render.entity.PassThroughEntityRenderService;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes the verified 1.21.1 ModelPart boundary without taking rendering ownership. */
@Mixin(ModelPart.class)
abstract class ModelPartMixin {
    @Inject(
            method =
                    "render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;III)V",
            at = @At("HEAD"),
            require = 1)
    private void threadium$beginModelPartRender(
            MatrixStack matrices,
            VertexConsumer vertexConsumer,
            int light,
            int overlay,
            int color,
            CallbackInfo callbackInfo) {
        PassThroughEntityRenderService.beginModelPartRender((ModelPart) (Object) this);
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
    }
}
