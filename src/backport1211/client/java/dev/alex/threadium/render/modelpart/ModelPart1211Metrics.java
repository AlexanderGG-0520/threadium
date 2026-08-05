package dev.alex.threadium.render.modelpart;

import dev.alex.threadium.render.modelpart.material.RenderLayer1211Descriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/** Aggregate-only Minecraft 1.21.1 ModelPart metrics. No hot-path strings or per-entity logging. */
public final class ModelPart1211Metrics {
    private final AtomicLongArray pipelineAccepted =
            new AtomicLongArray(RenderLayer1211Descriptor.Kind.values().length);
    private final AtomicLongArray pipelineFallbacks =
            new AtomicLongArray(RenderLayer1211Descriptor.Kind.values().length);
    private final AtomicLongArray pipelineDrawCalls =
            new AtomicLongArray(RenderLayer1211Descriptor.Kind.values().length);
    private final AtomicLongArray pipelineInstances =
            new AtomicLongArray(RenderLayer1211Descriptor.Kind.values().length);
    private final AtomicIntegerArray pipelineMaximumBatch =
            new AtomicIntegerArray(RenderLayer1211Descriptor.Kind.values().length);

    private final LongAdder replacementAttempts = new LongAdder();
    private final LongAdder replacementAccepts = new LongAdder();
    private final LongAdder replacementFallbacks = new LongAdder();
    private final LongAdder replacementFailures = new LongAdder();
    private final LongAdder unscopedFallbacks = new LongAdder();
    private final LongAdder adaptiveFallbacks = new LongAdder();
    private final LongAdder materialFallbacks = new LongAdder();
    private final LongAdder preparationFallbacks = new LongAdder();
    private final LongAdder capacityFallbacks = new LongAdder();
    private final LongAdder backendUnavailableFallbacks = new LongAdder();
    private final LongAdder emptyAccepts = new LongAdder();
    private final LongAdder gpuAccepts = new LongAdder();
    private final LongAdder cpuReplayAccepts = new LongAdder();
    private final LongAdder materialProviderRequests = new LongAdder();

    private final LongAdder groupBegins = new LongAdder();
    private final LongAdder groupEnds = new LongAdder();
    private final LongAdder groupScopeFailures = new LongAdder();

    private final LongAdder structureHits = new LongAdder();
    private final LongAdder structureMisses = new LongAdder();
    private final LongAdder gpuMeshHits = new LongAdder();
    private final LongAdder gpuMeshMisses = new LongAdder();
    private final LongAdder meshUploads = new LongAdder();
    private final LongAdder meshUploadFailures = new LongAdder();
    private final LongAdder poseLookups = new LongAdder();
    private final LongAdder poseHits = new LongAdder();
    private final LongAdder poseMisses = new LongAdder();
    private final LongAdder poseBypasses = new LongAdder();
    private final LongAdder directPackedPoses = new LongAdder();

    private final LongAdder initializationAttempts = new LongAdder();
    private final LongAdder initializationSuccesses = new LongAdder();
    private final LongAdder initializationFailures = new LongAdder();
    private final LongAdder queuedInstances = new LongAdder();
    private final LongAdder flushes = new LongAdder();
    private final LongAdder submittedInstances = new LongAdder();
    private final LongAdder drawCalls = new LongAdder();
    private final LongAdder batchableInstances = new LongAdder();
    private final LongAdder batchableDrawCalls = new LongAdder();
    private final LongAdder sortedInstances = new LongAdder();
    private final LongAdder sortedDrawCalls = new LongAdder();
    private final LongAdder sortedQuads = new LongAdder();
    private final LongAdder singletonBatches = new LongAdder();
    private final LongAdder multiInstanceBatches = new LongAdder();
    private final LongAdder totalInstancesInMultiDraws = new LongAdder();
    private final AtomicInteger maximumInstancesPerDraw = new AtomicInteger();
    private final LongAdder instanceUploadCalls = new LongAdder();
    private final LongAdder boneUploadCalls = new LongAdder();
    private final LongAdder instanceBytes = new LongAdder();
    private final LongAdder boneBytes = new LongAdder();
    private final LongAdder backendFailures = new LongAdder();
    private final LongAdder staleSubmissions = new LongAdder();
    private final LongAdder undrainedFrames = new LongAdder();

    private final LongAdder interceptCount = new LongAdder();
    private final LongAdder interceptTotalNanos = new LongAdder();
    private final LongAdder materialResolutionNanos = new LongAdder();
    private final LongAdder meshLookupNanos = new LongAdder();
    private final LongAdder poseCaptureNanos = new LongAdder();
    private final LongAdder queueCommitNanos = new LongAdder();
    private final LongAdder flushCount = new LongAdder();
    private final LongAdder flushTotalNanos = new LongAdder();
    private final LongAdder flushPackingNanos = new LongAdder();
    private final LongAdder flushPlanningNanos = new LongAdder();
    private final LongAdder flushUploadNanos = new LongAdder();
    private final LongAdder flushSubmissionNanos = new LongAdder();

    private volatile boolean enabled;

    public void configure(boolean enabled) {
        boolean changed = this.enabled != enabled;
        this.enabled = enabled;
        if (changed) reset();
    }

    public boolean enabled() {
        return enabled;
    }

    public long now() {
        return enabled ? System.nanoTime() : 0L;
    }

    public long delta(long start) {
        return enabled && start != 0L ? System.nanoTime() - start : 0L;
    }

    public void recordReplacementAttempt() {
        if (enabled) replacementAttempts.increment();
    }

    public void recordAccepted(boolean empty, boolean gpu, boolean cpuReplay) {
        if (!enabled) return;
        replacementAccepts.increment();
        if (empty) emptyAccepts.increment();
        if (gpu) gpuAccepts.increment();
        if (cpuReplay) cpuReplayAccepts.increment();
    }

    public void recordFallback(FallbackReason reason) {
        if (!enabled) return;
        replacementFallbacks.increment();
        switch (reason) {
            case UNSCOPED -> unscopedFallbacks.increment();
            case ADAPTIVE -> adaptiveFallbacks.increment();
            case MATERIAL -> materialFallbacks.increment();
            case PREPARATION -> preparationFallbacks.increment();
            case CAPACITY -> capacityFallbacks.increment();
            case BACKEND_UNAVAILABLE -> backendUnavailableFallbacks.increment();
            case OTHER -> {}
        }
    }

    public void recordFailure() {
        if (enabled) replacementFailures.increment();
    }

    public void recordMaterialProviderRequest() {
        if (enabled) materialProviderRequests.increment();
    }

    public void recordGroupBegin() {
        if (enabled) groupBegins.increment();
    }

    public void recordGroupEnd() {
        if (enabled) groupEnds.increment();
    }

    public void recordGroupScopeFailure() {
        if (enabled) groupScopeFailures.increment();
    }

    public void recordStructureLookup(boolean hit) {
        if (!enabled) return;
        if (hit) structureHits.increment();
        else structureMisses.increment();
    }

    public void recordGpuMeshLookup(boolean hit) {
        if (!enabled) return;
        if (hit) gpuMeshHits.increment();
        else gpuMeshMisses.increment();
    }

    public void recordMeshUpload(boolean success) {
        if (!enabled) return;
        if (success) meshUploads.increment();
        else meshUploadFailures.increment();
    }

    public void recordPoseObservation(boolean lookupPerformed, boolean hit, boolean directPacked) {
        if (!enabled) return;
        if (lookupPerformed) {
            poseLookups.increment();
            if (hit) poseHits.increment();
            else poseMisses.increment();
        } else {
            poseBypasses.increment();
        }
        if (directPacked) directPackedPoses.increment();
    }

    public void recordInitializationAttempt() {
        if (enabled) initializationAttempts.increment();
    }

    public void recordInitializationResult(boolean success) {
        if (!enabled) return;
        if (success) initializationSuccesses.increment();
        else initializationFailures.increment();
    }

    public void recordQueued(RenderLayer1211Descriptor.Kind kind, int count) {
        if (!enabled || count <= 0) return;
        queuedInstances.add(count);
        pipelineAccepted.addAndGet(kind.ordinal(), count);
    }

    public void recordPipelineFallback(RenderLayer1211Descriptor.Kind kind) {
        if (!enabled) return;
        RenderLayer1211Descriptor.Kind actual = kind == null ? RenderLayer1211Descriptor.Kind.UNSUPPORTED : kind;
        pipelineFallbacks.incrementAndGet(actual.ordinal());
    }

    public void recordFlush(
            RenderLayer1211Descriptor.Kind kind,
            int instances,
            int draws,
            int batchableInstanceCount,
            int batchableDrawCount,
            int sortedInstanceCount,
            int sortedDrawCount,
            int quadCount,
            int singletonCount,
            int multiCount,
            int instancesInMultiDraws,
            int maximumBatch) {
        if (!enabled) return;
        flushes.increment();
        submittedInstances.add(instances);
        drawCalls.add(draws);
        batchableInstances.add(batchableInstanceCount);
        batchableDrawCalls.add(batchableDrawCount);
        sortedInstances.add(sortedInstanceCount);
        sortedDrawCalls.add(sortedDrawCount);
        sortedQuads.add(quadCount);
        singletonBatches.add(singletonCount);
        multiInstanceBatches.add(multiCount);
        totalInstancesInMultiDraws.add(instancesInMultiDraws);
        maximumInstancesPerDraw.accumulateAndGet(maximumBatch, Math::max);
        int ordinal = kind.ordinal();
        pipelineDrawCalls.addAndGet(ordinal, draws);
        pipelineInstances.addAndGet(ordinal, instances);
        updateMaximum(pipelineMaximumBatch, ordinal, maximumBatch);
    }

    public void recordUploads(int instanceCalls, int boneCalls, long uploadedInstanceBytes, long uploadedBoneBytes) {
        if (!enabled) return;
        instanceUploadCalls.add(instanceCalls);
        boneUploadCalls.add(boneCalls);
        instanceBytes.add(uploadedInstanceBytes);
        boneBytes.add(uploadedBoneBytes);
    }

    public void recordBackendFailure() {
        if (enabled) backendFailures.increment();
    }

    public void recordStaleSubmission() {
        if (enabled) staleSubmissions.increment();
    }

    public void recordUndrainedFrame() {
        if (enabled) undrainedFrames.increment();
    }

    public void recordInterceptTiming(long total, long material, long mesh, long pose, long queue) {
        if (!enabled) return;
        interceptCount.increment();
        interceptTotalNanos.add(total);
        materialResolutionNanos.add(material);
        meshLookupNanos.add(mesh);
        poseCaptureNanos.add(pose);
        queueCommitNanos.add(queue);
    }

    public void recordFlushTiming(long total, long packing, long planning, long upload, long submission) {
        if (!enabled) return;
        flushCount.increment();
        flushTotalNanos.add(total);
        flushPackingNanos.add(packing);
        flushPlanningNanos.add(planning);
        flushUploadNanos.add(upload);
        flushSubmissionNanos.add(submission);
    }

    public Snapshot snapshot() {
        return snapshot(false);
    }

    public Snapshot snapshotAndReset() {
        return snapshot(true);
    }

    public void reset() {
        snapshot(true);
    }

    private Snapshot snapshot(boolean reset) {
        ReplacementCounters replacement = new ReplacementCounters(
                value(replacementAttempts, reset),
                value(replacementAccepts, reset),
                value(replacementFallbacks, reset),
                value(replacementFailures, reset),
                value(unscopedFallbacks, reset),
                value(adaptiveFallbacks, reset),
                value(materialFallbacks, reset),
                value(preparationFallbacks, reset),
                value(capacityFallbacks, reset),
                value(backendUnavailableFallbacks, reset),
                value(emptyAccepts, reset),
                value(gpuAccepts, reset),
                value(cpuReplayAccepts, reset),
                value(materialProviderRequests, reset));
        GroupCounters groups =
                new GroupCounters(value(groupBegins, reset), value(groupEnds, reset), value(groupScopeFailures, reset));
        CacheCounters cache = new CacheCounters(
                value(structureHits, reset),
                value(structureMisses, reset),
                value(gpuMeshHits, reset),
                value(gpuMeshMisses, reset),
                value(meshUploads, reset),
                value(meshUploadFailures, reset),
                value(poseLookups, reset),
                value(poseHits, reset),
                value(poseMisses, reset),
                value(poseBypasses, reset),
                value(directPackedPoses, reset));
        BackendCounters backend = new BackendCounters(
                value(initializationAttempts, reset),
                value(initializationSuccesses, reset),
                value(initializationFailures, reset),
                value(queuedInstances, reset),
                value(flushes, reset),
                value(submittedInstances, reset),
                value(drawCalls, reset),
                value(batchableInstances, reset),
                value(batchableDrawCalls, reset),
                value(sortedInstances, reset),
                value(sortedDrawCalls, reset),
                value(sortedQuads, reset),
                value(singletonBatches, reset),
                value(multiInstanceBatches, reset),
                value(totalInstancesInMultiDraws, reset),
                reset ? maximumInstancesPerDraw.getAndSet(0) : maximumInstancesPerDraw.get(),
                value(instanceUploadCalls, reset),
                value(boneUploadCalls, reset),
                value(instanceBytes, reset),
                value(boneBytes, reset),
                value(backendFailures, reset),
                value(staleSubmissions, reset),
                value(undrainedFrames, reset));
        TimingCounters timings = new TimingCounters(
                value(interceptCount, reset),
                value(interceptTotalNanos, reset),
                value(materialResolutionNanos, reset),
                value(meshLookupNanos, reset),
                value(poseCaptureNanos, reset),
                value(queueCommitNanos, reset),
                value(flushCount, reset),
                value(flushTotalNanos, reset),
                value(flushPackingNanos, reset),
                value(flushPlanningNanos, reset),
                value(flushUploadNanos, reset),
                value(flushSubmissionNanos, reset));
        return new Snapshot(enabled, replacement, groups, cache, backend, timings, pipelineCoverage(reset));
    }

    private List<PipelineCoverage> pipelineCoverage(boolean reset) {
        ArrayList<PipelineCoverage> coverage = new ArrayList<>();
        for (RenderLayer1211Descriptor.Kind kind : RenderLayer1211Descriptor.Kind.values()) {
            int ordinal = kind.ordinal();
            long accepted = reset ? pipelineAccepted.getAndSet(ordinal, 0) : pipelineAccepted.get(ordinal);
            long fallbacks = reset ? pipelineFallbacks.getAndSet(ordinal, 0) : pipelineFallbacks.get(ordinal);
            long draws = reset ? pipelineDrawCalls.getAndSet(ordinal, 0) : pipelineDrawCalls.get(ordinal);
            long instances = reset ? pipelineInstances.getAndSet(ordinal, 0) : pipelineInstances.get(ordinal);
            int maximum = reset ? pipelineMaximumBatch.getAndSet(ordinal, 0) : pipelineMaximumBatch.get(ordinal);
            coverage.add(new PipelineCoverage(
                    kind.name().toLowerCase(Locale.ROOT),
                    accepted,
                    fallbacks,
                    draws,
                    instances,
                    maximum,
                    kind.submissionPolicy().name().toLowerCase(Locale.ROOT)));
        }
        return List.copyOf(coverage);
    }

    private static long value(LongAdder adder, boolean reset) {
        return reset ? adder.sumThenReset() : adder.sum();
    }

    private static void updateMaximum(AtomicIntegerArray values, int index, int candidate) {
        int current;
        do {
            current = values.get(index);
            if (current >= candidate) return;
        } while (!values.compareAndSet(index, current, candidate));
    }

    public enum FallbackReason {
        UNSCOPED,
        ADAPTIVE,
        MATERIAL,
        PREPARATION,
        CAPACITY,
        BACKEND_UNAVAILABLE,
        OTHER
    }

    public record Snapshot(
            boolean enabled,
            ReplacementCounters replacement,
            GroupCounters groups,
            CacheCounters cache,
            BackendCounters backend,
            TimingCounters timings,
            List<PipelineCoverage> pipelines) {
        public Snapshot {
            pipelines = List.copyOf(pipelines);
        }

        public String describe() {
            return "gpuEntity1211={replacement=" + replacement + ", groups=" + groups + ", cache=" + cache
                    + ", backend=" + backend + ", timings=" + timings + ", pipelines=" + pipelines + "}";
        }
    }

    public record ReplacementCounters(
            long attempts,
            long accepts,
            long fallbacks,
            long failures,
            long unscopedFallbacks,
            long adaptiveFallbacks,
            long materialFallbacks,
            long preparationFallbacks,
            long capacityFallbacks,
            long backendUnavailableFallbacks,
            long emptyAccepts,
            long gpuAccepts,
            long cpuReplayAccepts,
            long materialProviderRequests) {}

    public record GroupCounters(long begins, long ends, long scopeFailures) {}

    public record CacheCounters(
            long structureHits,
            long structureMisses,
            long gpuMeshHits,
            long gpuMeshMisses,
            long meshUploads,
            long meshUploadFailures,
            long poseLookups,
            long poseHits,
            long poseMisses,
            long poseBypasses,
            long directPackedPoses) {}

    public record BackendCounters(
            long initializationAttempts,
            long initializationSuccesses,
            long initializationFailures,
            long queuedInstances,
            long flushes,
            long submittedInstances,
            long drawCalls,
            long batchableInstances,
            long batchableDrawCalls,
            long sortedInstances,
            long sortedDrawCalls,
            long sortedQuads,
            long singletonBatches,
            long multiInstanceBatches,
            long totalInstancesInMultiDraws,
            int maximumInstancesPerDraw,
            long instanceUploadCalls,
            long boneUploadCalls,
            long instanceBytes,
            long boneBytes,
            long backendFailures,
            long staleSubmissions,
            long undrainedFrames) {}

    public record TimingCounters(
            long interceptCount,
            long interceptTotalNanos,
            long materialResolutionNanos,
            long meshLookupNanos,
            long poseCaptureNanos,
            long queueCommitNanos,
            long flushCount,
            long flushTotalNanos,
            long flushPackingNanos,
            long flushPlanningNanos,
            long flushUploadNanos,
            long flushSubmissionNanos) {}

    public record PipelineCoverage(
            String pipeline,
            long accepted,
            long fallbacks,
            long drawCalls,
            long instances,
            int maximumBatchSize,
            String batchingMode) {}
}
