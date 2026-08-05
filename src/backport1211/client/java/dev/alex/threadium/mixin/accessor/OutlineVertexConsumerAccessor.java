package dev.alex.threadium.mixin.accessor;

import net.minecraft.client.render.VertexConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to Vanilla's fixed-color outline consumer wrapper. */
@Mixin(targets = "net.minecraft.client.render.OutlineVertexConsumerProvider$OutlineVertexConsumer")
public interface OutlineVertexConsumerAccessor {
    @Accessor("delegate")
    VertexConsumer threadium$getDelegate();

    @Accessor("color")
    int threadium$getColor();
}
