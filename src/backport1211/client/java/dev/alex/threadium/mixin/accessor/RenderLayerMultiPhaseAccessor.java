package dev.alex.threadium.mixin.accessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the private 1.21.1 MultiPhase parameter object without naming its protected type. */
@Mixin(targets = "net.minecraft.client.render.RenderLayer$MultiPhase")
public interface RenderLayerMultiPhaseAccessor {
    @Accessor("phases")
    Object threadium$getPhases();
}
