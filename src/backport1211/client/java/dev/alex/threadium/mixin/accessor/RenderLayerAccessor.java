package dev.alex.threadium.mixin.accessor;

import net.minecraft.client.render.RenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the immutable 1.20.1 ordering flag used by the fail-closed replacement descriptor. */
@Mixin(RenderLayer.class)
public interface RenderLayerAccessor {
    @Accessor("translucent")
    boolean threadium$isTranslucent();
}
