package dev.alex.threadium.mixin;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.vertex.CompactVectorArray;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.alex.threadium.metrics.StagedVertexMetrics;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;

@Mixin(StagedVertexBuffer.class)
abstract class StagedVertexBufferMetricsMixin {
    @Unique private long threadium$uploadStart;
    @Unique private static final ThreadLocal<DecodeTimer> threadium$decodeTimer = ThreadLocal.withInitial(DecodeTimer::new);

    @Inject(method = "upload()V", at = @At("HEAD"), require = 1, expect = 1)
    private void threadium$startUpload(CallbackInfo ci) { threadium$uploadStart = StagedVertexMetrics.start(); }

    /** Offset 31 is the sole final BufferBuilder flush; after it, upload performs no more vertex writes. */
    @Inject(method = "upload()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/StagedVertexBuffer;finishLastVertexBuilder()V", ordinal = 0, shift = At.Shift.AFTER), require = 1, expect = 1)
    private void threadium$cpuDataReady(CallbackInfo ci) { StagedVertexMetrics.cpuDataReady((StagedVertexBuffer) (Object) this); }

    @Inject(method = "upload()V", at = @At("RETURN"), require = 1, expect = 3)
    private void threadium$endUpload(CallbackInfo ci) { StagedVertexMetrics.recordUpload(threadium$uploadStart); }

    /** Exact 26.2 centroid decoder, called once for every sorted non-empty draw at upload offset 209. */
    @Inject(method = "decodeSortingPoints(Lnet/minecraft/client/renderer/StagedVertexBuffer$Draw;)Lcom/mojang/blaze3d/vertex/CompactVectorArray;", at = @At("HEAD"), require = 1, expect = 1)
    private static void threadium$startDecode(StagedVertexBuffer.Draw draw, CallbackInfoReturnable<CompactVectorArray> cir) {
        if (!StagedVertexMetrics.enabled()) return;
        StagedVertexMetrics.sortingRequired();
        threadium$decodeTimer.get().start = StagedVertexMetrics.start();
    }

    @Inject(method = "decodeSortingPoints(Lnet/minecraft/client/renderer/StagedVertexBuffer$Draw;)Lcom/mojang/blaze3d/vertex/CompactVectorArray;", at = @At("RETURN"), require = 1, expect = 1)
    private static void threadium$endDecode(StagedVertexBuffer.Draw draw, CallbackInfoReturnable<CompactVectorArray> cir) {
        if (!StagedVertexMetrics.enabled()) return;
        StagedVertexMetrics.recordDecode(threadium$decodeTimer.get().start);
    }

    /** Offset 141 is the sole CPU vertex-slice copy into the mapped staging buffer. */
    @Redirect(method = "uploadDrawsToBuffers(Lcom/mojang/blaze3d/systems/GpuDevice;Ljava/util/List;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;II)V", at = @At(value = "INVOKE", target = "Ljava/nio/ByteBuffer;put(Ljava/nio/ByteBuffer;)Ljava/nio/ByteBuffer;", ordinal = 0), require = 1, expect = 1)
    private ByteBuffer threadium$measureCpuCopy(ByteBuffer destination, ByteBuffer source) {
        long start = StagedVertexMetrics.start();
        try { return destination.put(source); }
        finally { StagedVertexMetrics.recordCpuCopy(start); }
    }

    /** Offset 247: vanilla combines quad ordering and CPU index generation in this call. */
    @Redirect(method = "uploadDrawsToBuffers(Lcom/mojang/blaze3d/systems/GpuDevice;Ljava/util/List;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;II)V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/MeshData$SortState;writeSortedIndexBuffer(Ljava/nio/ByteBuffer;Lcom/mojang/blaze3d/vertex/VertexSorting;)V", ordinal = 0), require = 1, expect = 1)
    private void threadium$measureIndexGeneration(MeshData.SortState state, ByteBuffer buffer, VertexSorting sorting) {
        long start = StagedVertexMetrics.start();
        try { state.writeSortedIndexBuffer(buffer, sorting); }
        finally { StagedVertexMetrics.recordIndexGeneration(start); }
    }

    /** Offsets 317 and 347 encode vertex and optional index GPU copies; no GL call is moved or replaced. */
    @Redirect(method = "uploadDrawsToBuffers(Lcom/mojang/blaze3d/systems/GpuDevice;Ljava/util/List;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;II)V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/CommandEncoder;copyToBuffer(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"), require = 1, expect = 2)
    private void threadium$measureGpuCopy(CommandEncoder encoder, GpuBufferSlice source, GpuBufferSlice destination) {
        long start = StagedVertexMetrics.start();
        try { encoder.copyToBuffer(source, destination); }
        finally { StagedVertexMetrics.recordGpuCopy(start); }
    }

    @Unique private static final class DecodeTimer { long start; }
}
