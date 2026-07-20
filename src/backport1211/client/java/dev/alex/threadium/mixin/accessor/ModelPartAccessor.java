package dev.alex.threadium.mixin.accessor;

import java.util.List;
import java.util.Map;
import net.minecraft.client.model.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Narrow read-only access to the final collections traversed by ModelPart.render in Minecraft 1.21.1. */
@Mixin(ModelPart.class)
public interface ModelPartAccessor {
    @Accessor("cuboids")
    List<ModelPart.Cuboid> threadium$getCuboids();

    @Accessor("children")
    Map<String, ModelPart> threadium$getChildren();
}
