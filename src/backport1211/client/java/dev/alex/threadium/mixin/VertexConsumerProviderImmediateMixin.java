package dev.alex.threadium.mixin;

import dev.alex.threadium.render.entity.ModelPartReplacementService;
import dev.alex.threadium.render.entity.PassThroughEntityRenderService;
import dev.alex.threadium.render.modelpart.material.MaterialProviderSource;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observes exact provider bindings and flushes Threadium-owned work at Vanilla's matching draw boundary. */
@Mixin(VertexConsumerProvider.Immediate.class)
abstract class VertexConsumerProviderImmediateMixin {
    @Inject(
            method = "getBuffer(Lnet/minecraft/client/render/RenderLayer;)Lnet/minecraft/client/render/VertexConsumer;",
            at = @At("RETURN"),
            require = 1)
    private void threadium$observeReturnedConsumer(
            RenderLayer layer, CallbackInfoReturnable<VertexConsumer> callbackInfo) {
        VertexConsumer consumer = callbackInfo.getReturnValue();
        PassThroughEntityRenderService.observeMaterialProviderRequest(
                MaterialProviderSource.IMMEDIATE, this, layer, consumer);
        ModelPartReplacementService.observeMaterialProviderRequest(
                MaterialProviderSource.IMMEDIATE, this, layer, consumer);
    }

    @Inject(
            method =
                    "draw(Lnet/minecraft/client/render/RenderLayer;Lnet/minecraft/client/render/BufferBuilder;)V",
            at = @At("HEAD"),
            require = 1)
    private void threadium$flushOwnedGpuReplay(
            RenderLayer layer, BufferBuilder vanillaBuilder, CallbackInfo callbackInfo) {
        ModelPartReplacementService.flushProviderLayer(this, layer);
    }
}
