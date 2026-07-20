package dev.alex.threadium.mixin.accessor;

import net.minecraft.client.model.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to the final faces that encode dilation, UV, mirroring, and selected-face structure. */
@Mixin(ModelPart.Cuboid.class)
public interface ModelPartCuboidAccessor {
    @Accessor("sides")
    ModelPart.Quad[] threadium$getSides();
}
