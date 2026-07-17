package dev.alex.threadium.mixin.accessor;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(StagedVertexBuffer.Draw.class)
public interface StagedDrawAccessor {
    @Accessor("primitiveTopology")
    PrimitiveTopology threadium$getPrimitiveTopology();

    @Accessor("quadSorting")
    VertexSorting threadium$getQuadSorting();

    @Accessor("vertexBufferSize")
    int threadium$getVertexBufferSize();

    @Accessor("vertexCount")
    int threadium$getVertexCount();

    @Accessor("indexCount")
    int threadium$getIndexCount();

    @Invoker("indexType")
    IndexType threadium$invokeIndexType();
}
