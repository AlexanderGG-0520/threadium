package dev.alex.threadium.mixin;

import dev.alex.threadium.render.entity.PassThroughEntityRenderService;
import net.minecraft.client.render.OutlineVertexConsumerProvider;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observes Vanilla outline-provider return identities, including wrappers, without inspecting their delegates. */
@Mixin(OutlineVertexConsumerProvider.class)
abstract class OutlineVertexConsumerProviderMixin {
    @Inject(
            method = "getBuffer(Lnet/minecraft/client/render/RenderLayer;)Lnet/minecraft/client/render/VertexConsumer;",
            at = @At("RETURN"),
            require = 1)
    private void threadium$observeReturnedConsumer(
            RenderLayer layer, CallbackInfoReturnable<VertexConsumer> callbackInfo) {
        PassThroughEntityRenderService.observeMaterialProviderRequest(this, layer, callbackInfo.getReturnValue());
    }
}
