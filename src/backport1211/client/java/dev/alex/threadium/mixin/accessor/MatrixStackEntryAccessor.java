package dev.alex.threadium.mixin.accessor;

import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to Vanilla's normal-vector normalization decision for an exact captured matrix entry. */
@Mixin(MatrixStack.Entry.class)
public interface MatrixStackEntryAccessor {
    @Accessor("canSkipNormalization")
    boolean threadium$canSkipNormalization();
}
