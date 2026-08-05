package dev.alex.threadium.mixin.accessor;

import net.minecraft.client.render.OverlayVertexConsumer;
import net.minecraft.client.render.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to Vanilla's sheeted decal projection inputs. */
@Mixin(OverlayVertexConsumer.class)
public interface OverlayVertexConsumerAccessor {
    @Accessor("delegate")
    VertexConsumer threadium$getDelegate();

    @Accessor("inverseTextureMatrix")
    Matrix4f threadium$getInverseTextureMatrix();

    @Accessor("inverseNormalMatrix")
    Matrix3f threadium$getInverseNormalMatrix();

    @Accessor("textureScale")
    float threadium$getTextureScale();
}
