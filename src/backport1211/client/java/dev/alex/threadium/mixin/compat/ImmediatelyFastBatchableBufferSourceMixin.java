package dev.alex.threadium.mixin.compat;

import dev.alex.threadium.render.entity.PassThroughEntityRenderService;
import dev.alex.threadium.render.modelpart.material.MaterialProviderSource;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Observes ImmediatelyFast's returned consumer without wrapping it, mutating it, or changing control flow.
 *
 * <p>The target is optional. When ImmediatelyFast is absent this pseudo mixin is ignored, preserving the standalone
 * Threadium dependency boundary.
 */
@Pseudo
@Mixin(targets = "net.raphimc.immediatelyfast.feature.batching.BatchableBufferSource", remap = false)
abstract class ImmediatelyFastBatchableBufferSourceMixin {
    @Inject(method = "getBuffer", at = @At("RETURN"), require = 0, remap = false)
    private void threadium$observeReturnedConsumer(
            RenderLayer layer, CallbackInfoReturnable<VertexConsumer> callbackInfo) {
        PassThroughEntityRenderService.observeMaterialProviderRequest(
                MaterialProviderSource.IMMEDIATELY_FAST, this, layer, callbackInfo.getReturnValue());
    }
}
