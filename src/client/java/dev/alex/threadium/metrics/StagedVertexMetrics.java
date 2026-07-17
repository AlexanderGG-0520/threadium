package dev.alex.threadium.metrics;

import com.mojang.blaze3d.PrimitiveTopology;
import dev.alex.threadium.mixin.accessor.StagedDrawAccessor;
import dev.alex.threadium.mixin.accessor.StagedVertexBufferAccessor;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.renderer.StagedVertexBuffer;

/** Aggregated measurements for the 26.2 staged vertex-buffer path. */
public final class StagedVertexMetrics {
    private static volatile boolean enabled;
    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);
    private static final TimingAccumulator featurePreparation = new TimingAccumulator();
    private static final TimingAccumulator groupPreparation = new TimingAccumulator();
    private static final TimingAccumulator uploadTotal = new TimingAccumulator();
    private static final TimingAccumulator decodeSortingPoints = new TimingAccumulator();
    private static final TimingAccumulator quadOrderingIndexGeneration = new TimingAccumulator();
    private static final TimingAccumulator cpuBufferCopy = new TimingAccumulator();
    private static final TimingAccumulator gpuUploadCall = new TimingAccumulator();
    private static final TimingAccumulator availableOverlap = new TimingAccumulator();
    private static final DistributionBuckets sortedQuadBuckets =
            new DistributionBuckets(0, 1, 7, 31, 127, 511, 2047, 8191);
    private static final DistributionBuckets groupBuckets = new DistributionBuckets(0, 1, 2, 4, 8, 16);
    private static final AtomicLong frames = new AtomicLong();
    private static final AtomicLong groups = new AtomicLong();
    private static final AtomicLong draws = new AtomicLong();
    private static final AtomicLong sortedDraws = new AtomicLong();
    private static final AtomicLong unsortedDraws = new AtomicLong();
    private static final AtomicLong vertices = new AtomicLong();
    private static final AtomicLong quads = new AtomicLong();
    private static final AtomicLong sortedQuads = new AtomicLong();
    private static final AtomicLong generatedIndices = new AtomicLong();
    private static final AtomicLong stagedBytes = new AtomicLong();
    private static final AtomicLong uploadedBytes = new AtomicLong();
    private static final AtomicLong overlapCandidates = new AtomicLong();
    private static final AtomicLong nonPositiveOverlap = new AtomicLong();
    private static final AtomicLong maxGroupsPerFrame = new AtomicLong();
    private static final AtomicLong maxDrawsPerFrame = new AtomicLong();
    private static final AtomicLong maxVerticesPerFrame = new AtomicLong();
    private static final AtomicLong maxQuadsPerFrame = new AtomicLong();
    private static final AtomicLong maxSortedQuadsPerFrame = new AtomicLong();
    private static final AtomicLong maxStagedBytesPerFrame = new AtomicLong();

    private StagedVertexMetrics() {}

    public static void configure(boolean value) {
        enabled = value;
    }

    public static boolean enabled() {
        return enabled;
    }

    public static long start() {
        return enabled ? System.nanoTime() : 0L;
    }

    public static void recordFeature(long start) {
        if (!enabled) return;
        record(featurePreparation, start);
        long frameGroups = STATE.get().frameGroups;
        add(groups, frameGroups);
        groupBuckets.record(frameGroups);
        maximum(maxGroupsPerFrame, frameGroups);
    }

    public static void recordGroup(long start) {
        if (enabled) {
            record(groupPreparation, start);
            STATE.get().frameGroups++;
        }
    }

    public static void recordUpload(long start) {
        record(uploadTotal, start);
    }

    public static void recordDecode(long start) {
        record(decodeSortingPoints, start);
    }

    public static void recordIndexGeneration(long start) {
        record(quadOrderingIndexGeneration, start);
    }

    public static void recordCpuCopy(long start) {
        record(cpuBufferCopy, start);
    }

    public static void recordGpuCopy(long start) {
        record(gpuUploadCall, start);
    }

    public static void beginFrame() {
        if (!enabled) return;
        State state = STATE.get();
        state.frameGroups = 0L;
        state.cpuReadyNanos = 0L;
        add(frames, 1L);
    }

    public static void cpuDataReady(StagedVertexBuffer buffer) {
        if (!enabled) return;
        State state = STATE.get();
        state.cpuReadyNanos = System.nanoTime();
        long frameDraws = 0L, frameVertices = 0L, frameQuads = 0L, frameSortedQuads = 0L, frameBytes = 0L;
        List<StagedVertexBuffer.Draw> liveDraws = ((StagedVertexBufferAccessor) buffer).threadium$getDraws();
        for (int index = 0, size = liveDraws.size(); index < size; index++) {
            StagedVertexBuffer.Draw draw = liveDraws.get(index);
            StagedDrawAccessor accessor = (StagedDrawAccessor) draw;
            int vertexCount = accessor.threadium$getVertexCount();
            if (vertexCount == 0) continue;
            long drawQuads = StagedMetricMath.quadCount(
                    accessor.threadium$getPrimitiveTopology() == PrimitiveTopology.QUADS, vertexCount);
            boolean sorted = accessor.threadium$getQuadSorting() != null;
            long bytes = Math.max(0, accessor.threadium$getVertexBufferSize());
            frameDraws++;
            frameVertices = add(frameVertices, vertexCount);
            frameQuads = add(frameQuads, drawQuads);
            frameBytes = add(frameBytes, bytes);
            add(draws, 1L);
            add(vertices, vertexCount);
            add(quads, drawQuads);
            add(stagedBytes, bytes);
            if (sorted) {
                long indices = Math.max(0, accessor.threadium$getIndexCount());
                long indexBytes = indices * accessor.threadium$invokeIndexType().bytes;
                frameSortedQuads = add(frameSortedQuads, drawQuads);
                frameBytes = add(frameBytes, indexBytes);
                add(sortedDraws, 1L);
                add(sortedQuads, drawQuads);
                add(generatedIndices, indices);
                add(stagedBytes, indexBytes);
                sortedQuadBuckets.record(drawQuads);
            } else add(unsortedDraws, 1L);
        }
        add(uploadedBytes, frameBytes);
        maximum(maxDrawsPerFrame, frameDraws);
        maximum(maxVerticesPerFrame, frameVertices);
        maximum(maxQuadsPerFrame, frameQuads);
        maximum(maxSortedQuadsPerFrame, frameSortedQuads);
        maximum(maxStagedBytesPerFrame, frameBytes);
    }

    public static void sortingRequired() {
        if (!enabled) return;
        long overlap = StagedMetricMath.availableOverlap(STATE.get().cpuReadyNanos, System.nanoTime());
        add(overlapCandidates, 1L);
        if (overlap == 0L) add(nonPositiveOverlap, 1L);
        availableOverlap.record(overlap);
    }

    public static String snapshotAndReset() {
        if (!enabled) return "stagedVertex={disabled}";
        return "stagedVertex={frames=" + reset(frames) + ",groups=" + reset(groups) + ",draws=" + reset(draws)
                + ",sortedDraws=" + reset(sortedDraws) + ",unsortedDraws=" + reset(unsortedDraws)
                + ",vertices=" + reset(vertices) + ",quads=" + reset(quads) + ",sortedQuads=" + reset(sortedQuads)
                + ",generatedIndices=" + reset(generatedIndices) + ",stagedBytes=" + reset(stagedBytes)
                + ",uploadedBytes=" + reset(uploadedBytes) + ",maxGroupsPerFrame=" + reset(maxGroupsPerFrame)
                + ",maxDrawsPerFrame=" + reset(maxDrawsPerFrame) + ",maxVerticesPerFrame=" + reset(maxVerticesPerFrame)
                + ",maxQuadsPerFrame=" + reset(maxQuadsPerFrame) + ",maxSortedQuadsPerFrame="
                + reset(maxSortedQuadsPerFrame)
                + ",maxStagedBytesPerFrame=" + reset(maxStagedBytesPerFrame)
                + ",overlapCandidates=" + reset(overlapCandidates) + ",nonPositiveOverlap=" + reset(nonPositiveOverlap)
                + ",featurePreparation=" + featurePreparation.snapshotAndReset().describe()
                + ",groupPreparation=" + groupPreparation.snapshotAndReset().describe()
                + ",decodeSortingPoints="
                + decodeSortingPoints.snapshotAndReset().describe()
                + ",quadOrderingIndexGeneration="
                + quadOrderingIndexGeneration.snapshotAndReset().describe()
                + ",cpuBufferCopy=" + cpuBufferCopy.snapshotAndReset().describe()
                + ",uploadTotal=" + uploadTotal.snapshotAndReset().describe()
                + ",gpuUploadCall=" + gpuUploadCall.snapshotAndReset().describe()
                + ",availableOverlap=" + availableOverlap.snapshotAndReset().describe()
                + ",sortedQuadBuckets=" + java.util.Arrays.toString(sortedQuadBuckets.snapshotAndReset())
                + ",groupBuckets=" + java.util.Arrays.toString(groupBuckets.snapshotAndReset()) + '}';
    }

    public static BenchmarkTiming benchmarkTimingSnapshotAndReset() {
        return new BenchmarkTiming(featurePreparation.snapshotAndReset(), groupPreparation.snapshotAndReset());
    }

    public record BenchmarkTiming(
            TimingAccumulator.Sample featurePreparation, TimingAccumulator.Sample groupPreparation) {}

    private static void record(TimingAccumulator metric, long start) {
        if (enabled && start != 0L) metric.record(System.nanoTime() - start);
    }

    private static void add(AtomicLong target, long value) {
        target.getAndUpdate(current -> DistributionBuckets.saturatingAdd(current, value));
    }

    private static long add(long current, long value) {
        return DistributionBuckets.saturatingAdd(current, value);
    }

    private static void maximum(AtomicLong target, long value) {
        target.accumulateAndGet(value, Math::max);
    }

    private static long reset(AtomicLong value) {
        return value.getAndSet(0L);
    }

    private static final class State {
        long frameGroups;
        long cpuReadyNanos;
    }
}
