package dev.alex.threadium.mixin.accessor;

import net.minecraft.client.render.VertexConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to Vanilla's two-destination VertexConsumers union. */
@Mixin(targets = "net.minecraft.client.render.VertexConsumers$Dual")
public interface VertexConsumersDualAccessor {
    @Accessor("first")
    VertexConsumer threadium$getFirst();

    @Accessor("second")
    VertexConsumer threadium$getSecond();
}
