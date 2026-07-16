package dev.alex.threadium.mixin.accessor;

import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(StagedVertexBuffer.class)
public interface StagedVertexBufferAccessor {
    @Accessor("draws") List<StagedVertexBuffer.Draw> threadium$getDraws();
}
