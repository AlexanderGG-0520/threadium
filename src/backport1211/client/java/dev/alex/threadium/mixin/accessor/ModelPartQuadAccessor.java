package dev.alex.threadium.mixin.accessor;

import net.minecraft.client.model.ModelPart;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to final Quad values used directly by Cuboid.renderCuboid. */
@Mixin(ModelPart.Quad.class)
public interface ModelPartQuadAccessor {
    @Accessor("vertices")
    ModelPart.Vertex[] threadium$getVertices();

    @Accessor("direction")
    Vector3f threadium$getDirection();
}
