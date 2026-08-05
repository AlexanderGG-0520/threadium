package dev.alex.threadium.mixin.accessor;

import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to Vanilla's current directional-light vectors. */
@Mixin(RenderSystem.class)
public interface RenderSystemAccessor {
    @Accessor("shaderLightDirections")
    static Vector3f[] threadium$getShaderLightDirections() {
        throw new AssertionError();
    }
}
