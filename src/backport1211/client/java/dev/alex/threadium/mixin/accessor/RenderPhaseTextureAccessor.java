package dev.alex.threadium.mixin.accessor;

import java.util.Optional;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to a single-texture RenderPhase. Multi-texture phases remain unsupported. */
@Mixin(targets = "net.minecraft.client.render.RenderPhase$Texture")
public interface RenderPhaseTextureAccessor {
    @Accessor("id")
    Optional<Identifier> threadium$getTextureId();

    @Accessor("blur")
    boolean threadium$isBlurred();

    @Accessor("mipmap")
    boolean threadium$isMipmapped();
}
