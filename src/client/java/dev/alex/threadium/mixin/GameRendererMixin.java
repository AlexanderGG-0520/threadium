package dev.alex.threadium.mixin;

import dev.alex.threadium.render.modelpart.ModelPartRenderService;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Final main-target diagnostic hook: after world, post effects and GUI, before Minecraft blits to the window surface.
 */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"), require = 1)
    private void threadium$benchmarkFrameStart(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        dev.alex.threadium.lifecycle.ThreadiumLifecycle.beginRenderFrame();
        dev.alex.threadium.benchmark.ThreadiumBenchmark.frameStart(Minecraft.getInstance());
    }

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("TAIL"), require = 1)
    private void threadium$drawMainTargetControl(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        ModelPartRenderService service = ModelPartRenderService.get();
        if (service != null) service.drawDiagnosticOverlayAtMainTargetTail();
        dev.alex.threadium.benchmark.ThreadiumBenchmark.frameEnd(Minecraft.getInstance());
    }
}
