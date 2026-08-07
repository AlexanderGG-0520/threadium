package dev.alex.threadium.mixin.accessor;

import net.minecraft.client.render.RenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the exact 1.21.1 MultiPhase parameter object. */
@Mixin(targets = "net.minecraft.client.render.RenderLayer$MultiPhase")
public interface RenderLayerMultiPhaseAccessor {
    @Accessor("phases")
    RenderLayer.MultiPhaseParameters threadium$getPhases();
}
