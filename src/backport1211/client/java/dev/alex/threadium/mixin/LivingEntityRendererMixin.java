package dev.alex.threadium.mixin;

import dev.alex.threadium.render.entity.ModelPartReplacementService;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Establishes stable renderer-owner scopes around the real Minecraft 1.21.1 living-model and feature calls. */
@Mixin(LivingEntityRenderer.class)
abstract class LivingEntityRendererMixin {
    @Inject(
            method =
                    "render(Lnet/minecraft/entity/LivingEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At("HEAD"),
            require = 1)
    private void threadium$beginBaseModelGroup(
            LivingEntity entity,
            float yaw,
            float tickDelta,
            MatrixStack matrices,
            VertexConsumerProvider providers,
            int light,
            CallbackInfo callbackInfo) {
        ModelPartReplacementService.beginRenderGroup(this);
    }

    @Redirect(
            method =
                    "render(Lnet/minecraft/entity/LivingEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/render/entity/feature/FeatureRenderer;render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;ILnet/minecraft/entity/Entity;FFFFFF)V"),
            require = 1)
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void threadium$renderFeatureGroup(
            FeatureRenderer<?, ?> feature,
            MatrixStack matrices,
            VertexConsumerProvider providers,
            int light,
            Entity entity,
            float limbAngle,
            float limbDistance,
            float tickDelta,
            float animationProgress,
            float headYaw,
            float headPitch) {
        ModelPartReplacementService.beginRenderGroup(feature);
        try {
            ((FeatureRenderer) feature)
                    .render(
                            matrices,
                            providers,
                            light,
                            entity,
                            limbAngle,
                            limbDistance,
                            tickDelta,
                            animationProgress,
                            headYaw,
                            headPitch);
        } finally {
            ModelPartReplacementService.endRenderGroup(feature);
        }
    }

    @Inject(
            method =
                    "render(Lnet/minecraft/entity/LivingEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At("RETURN"),
            require = 1)
    private void threadium$endBaseModelGroup(
            LivingEntity entity,
            float yaw,
            float tickDelta,
            MatrixStack matrices,
            VertexConsumerProvider providers,
            int light,
            CallbackInfo callbackInfo) {
        ModelPartReplacementService.endRenderGroup(this);
    }
}
