package dev.alex.threadium.mixin;

import dev.alex.threadium.benchmark.PipelineDifferentialRunner1211;
import dev.alex.threadium.lifecycle.ThreadiumFrameBoundary;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V", at = @At("HEAD"), require = 1)
    private void threadium$applyFrameBoundary(RenderTickCounter tickCounter, boolean tick, CallbackInfo callbackInfo) {
        ThreadiumFrameBoundary.applyPendingConfiguration();
    }

    @Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V", at = @At("RETURN"), require = 1)
    private void threadium$runDifferentialTail(RenderTickCounter tickCounter, boolean tick, CallbackInfo callbackInfo) {
        PipelineDifferentialRunner1211.renderTail();
    }
}
