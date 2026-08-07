package dev.alex.threadium.mixin.compat;

import dev.alex.threadium.compat.ImmediatelyFastCompatibility;
import dev.alex.threadium.render.entity.ModelPartReplacementService;
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
 * Threadium dependency boundary. When an unverified ImmediatelyFast version is present, the injected callback is a
 * no-op and Threadium keeps its normal fail-closed fallback behavior.
 */
@Pseudo
@Mixin(targets = "net.raphimc.immediatelyfast.feature.core.BatchableBufferSource", remap = false)
abstract class ImmediatelyFastBatchableBufferSourceMixin {
    @Inject(method = "getBuffer", at = @At("RETURN"), require = 0, remap = false)
    private void threadium$observeReturnedConsumer(
            RenderLayer layer, CallbackInfoReturnable<VertexConsumer> callbackInfo) {
        if (!ImmediatelyFastCompatibility.observationSupported()) return;
        VertexConsumer consumer = callbackInfo.getReturnValue();
        PassThroughEntityRenderService.observeMaterialProviderRequest(
                MaterialProviderSource.IMMEDIATELY_FAST, this, layer, consumer);
        ModelPartReplacementService.observeMaterialProviderRequest(
                MaterialProviderSource.IMMEDIATELY_FAST, this, layer, consumer);
    }
}
