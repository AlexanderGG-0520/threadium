package dev.alex.threadium.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.alex.threadium.render.text.RetainedTextManager;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.TextDisplayEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DisplayRenderer.TextDisplayRenderer.class)
abstract class TextDisplayRendererMixin {
    /** Marks only sequences originating from Text Displays; vanilla submission remains unchanged. */
    @Inject(
            method =
                    "submitInner(Lnet/minecraft/client/renderer/entity/state/TextDisplayEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IF)V",
            at = @At("HEAD"),
            require = 1,
            expect = 1)
    private void threadium$markTextDisplay(
            TextDisplayEntityRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            int light,
            float progress,
            CallbackInfo ci) {
        if (state.cachedInfo != null) RetainedTextManager.mark(state.cachedInfo);
    }
}
