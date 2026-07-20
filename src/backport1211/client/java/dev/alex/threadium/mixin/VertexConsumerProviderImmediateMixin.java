package dev.alex.threadium.mixin;

import dev.alex.threadium.render.entity.PassThroughEntityRenderService;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observes the exact BufferBuilder returned by Vanilla's ordinary entity provider without replacing it. */
@Mixin(VertexConsumerProvider.Immediate.class)
abstract class VertexConsumerProviderImmediateMixin {
    @Inject(
            method = "getBuffer(Lnet/minecraft/client/render/RenderLayer;)Lnet/minecraft/client/render/VertexConsumer;",
            at = @At("RETURN"),
            require = 1)
    private void threadium$observeReturnedConsumer(
            RenderLayer layer, CallbackInfoReturnable<VertexConsumer> callbackInfo) {
        PassThroughEntityRenderService.observeMaterialProviderRequest(this, layer, callbackInfo.getReturnValue());
    }
}
