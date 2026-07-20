package dev.alex.threadium.mixin.accessor;

import net.minecraft.client.model.ModelPart;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to final Vertex values emitted by Cuboid.renderCuboid. */
@Mixin(ModelPart.Vertex.class)
public interface ModelPartVertexAccessor {
    @Accessor("pos")
    Vector3f threadium$getPosition();

    @Accessor("u")
    float threadium$getU();

    @Accessor("v")
    float threadium$getV();
}
