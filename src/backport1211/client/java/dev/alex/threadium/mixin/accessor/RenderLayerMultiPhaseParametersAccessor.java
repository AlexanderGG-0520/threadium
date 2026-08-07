package dev.alex.threadium.mixin.accessor;

import com.google.common.collect.ImmutableList;
import net.minecraft.client.render.RenderPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to the exact phase sequence and texture phase used by a 1.21.1 RenderLayer. */
@Mixin(targets = "net.minecraft.client.render.RenderLayer$MultiPhaseParameters")
public interface RenderLayerMultiPhaseParametersAccessor {
    @Accessor("phases")
    ImmutableList<?> threadium$getPhaseList();

    @Accessor("texture")
    RenderPhase.TextureBase threadium$getTexturePhase();
}
