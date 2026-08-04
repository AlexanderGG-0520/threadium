package dev.alex.threadium.render.modelpart;

import dev.alex.threadium.metrics.ThreadiumMetrics;
import java.util.concurrent.atomic.LongAdder;

public final class ModelPartGpuMetrics {
    private static final class HotPathLongAdder extends LongAdder {
        @Override
        public void add(long value) {
            if (detailedMetricsEnabled()) super.add(value);
        }
    }

    static boolean detailedMetricsEnabled() {
        return ThreadiumMetrics.hotPathMetricsEnabled();
    }

    private static LongAdder hotCounter() {
        return new HotPathLongAdder();
    }

    private final java.util.concurrent.atomic.AtomicLongArray pipelineAccepted =
            new java.util.concurrent.atomic.AtomicLongArray(ModelPartPipelineDescriptor.values().length);
    private final java.util.concurrent.atomic.AtomicLongArray pipelineFallback =
            new java.util.concurrent.atomic.AtomicLongArray(ModelPartPipelineDescriptor.values().length);
    private final java.util.concurrent.atomic.AtomicLongArray pipelineDrawCalls =
            new java.util.concurrent.atomic.AtomicLongArray(ModelPartPipelineDescriptor.values().length);
    private final java.util.concurrent.atomic.AtomicLongArray pipelineInstances =
            new java.util.concurrent.atomic.AtomicLongArray(ModelPartPipelineDescriptor.values().length);
    private final java.util.concurrent.atomic.AtomicIntegerArray pipelineMaximumBatch =
            new java.util.concurrent.atomic.AtomicIntegerArray(ModelPartPipelineDescriptor.values().length);
    private final LongAdder unknownPipelineFallbacks = hotCounter();
    public final LongAdder eligibleInvocations = hotCounter(),
            acceptedInvocations = hotCounter(),
            queuedInstances = hotCounter();
    public final LongAdder drawnInstances = hotCounter(),
            batches = hotCounter(),
            instancedDraws = hotCounter(),
            meshHits = hotCounter();
    public final LongAdder meshMisses = hotCounter(),
            meshBakeFailures = hotCounter(),
            vanillaFallbacks = hotCounter(),
            capacityFallbacks = hotCounter();
    public final LongAdder backendFailures = hotCounter(),
            boneBytesUploaded = hotCounter(),
            instanceBytesUploaded = hotCounter();
    public final LongAdder initializationAttempts = hotCounter(),
            initializationSuccesses = hotCounter(),
            initializationFailures = hotCounter();
    public final LongAdder backendUnavailableFallbacks = hotCounter(),
            materialFallbacks = hotCounter(),
            drawCalls = hotCounter();
    public final LongAdder batchableInstances = hotCounter(),
            batchableDrawCalls = hotCounter(),
            sortedInstances = hotCounter(),
            sortedDrawCalls = hotCounter();
    public final LongAdder consolidatedBatches = hotCounter(),
            singletonBatches = hotCounter(),
            multiInstanceBatches = hotCounter(),
            totalInstancesInMultiDraws = hotCounter(),
            instanceUploadCalls = hotCounter(),
            boneUploadCalls = hotCounter();
    public final LongAdder interceptionPassThroughs = hotCounter(),
            diagnosticOverlayRequests = hotCounter(),
            diagnosticOverlayDraws = hotCounter();
    public final LongAdder productionReplacementAccepts = hotCounter(),
            vanillaSuppressions = hotCounter(),
            forbiddenSuppressionAttempts = hotCounter();
    public final LongAdder diagnosticOverlayFailures = hotCounter();
    public final LongAdder blaze3dGroupsSubmitted = hotCounter(),
            blaze3dPassesCreated = hotCounter(),
            blaze3dDrawCommands = hotCounter(),
            blaze3dInstancesSubmitted = hotCounter(),
            blaze3dSubmissionFailures = hotCounter(),
            blaze3dUnsupportedFallbacks = hotCounter(),
            rawProductionDrawCalls = hotCounter();
    public final LongAdder blaze3dPipelineCompileAttempts = hotCounter(),
            blaze3dPipelineCompileValid = hotCounter(),
            blaze3dPipelineCompileInvalid = hotCounter(),
            blaze3dPipelineCompileExceptions = hotCounter(),
            blaze3dPipelineValidityUnknown = hotCounter();
    public final LongAdder blaze3dPipelineStaleFallbacks = hotCounter(),
            blaze3dInvalidPipelineFallbacks = hotCounter(),
            blaze3dUnsupportedBackendFallbacks = hotCounter();
    public final LongAdder modelLayoutCacheHits = hotCounter(),
            modelLayoutCacheMisses = hotCounter(),
            modelTopologyTraversals = hotCounter(),
            topologyPreparationNanos = hotCounter();
    public final LongAdder posePaletteLookups = hotCounter(),
            posePaletteHits = hotCounter(),
            posePaletteMisses = hotCounter(),
            posePaletteBypasses = hotCounter(),
            directPackedPosePalettes = hotCounter(),
            uniqueBonePalettes = hotCounter(),
            reusedBonePalettes = hotCounter();
    public final LongAdder boneMatricesComposed = hotCounter(),
            boneMatricesAvoided = hotCounter(),
            boneBytesRequested = hotCounter(),
            boneBytesAvoided = hotCounter();
    public final LongAdder interceptProfileCount = hotCounter(),
            interceptTotalNanos = hotCounter(),
            interceptPipelineValidationNanos = hotCounter(),
            interceptTopologyAndMeshLookupNanos = hotCounter(),
            interceptPosePreparationNanos = hotCounter(),
            interceptMaterialCaptureNanos = hotCounter(),
            interceptBackendQueueNanos = hotCounter();
    public final LongAdder poseLookupNanos = hotCounter(),
            boneCompositionNanos = hotCounter(),
            bonePackingNanos = hotCounter();
    public final LongAdder backendQueueProfileCount = hotCounter(),
            backendQueuePrepareCalls = hotCounter(),
            backendQueuePrepareReuseHits = hotCounter(),
            backendQueueSelectorNanos = hotCounter(),
            backendQueuePrecheckNanos = hotCounter(),
            backendQueuePrepareNanos = hotCounter(),
            backendQueuePreparedValidationNanos = hotCounter(),
            backendQueueInstanceCaptureNanos = hotCounter(),
            backendQueueInsertionAndPaletteNanos = hotCounter();
    public final LongAdder blaze3dFlushCount = hotCounter(),
            blaze3dFlushTotalNanos = hotCounter(),
            blaze3dBoneAndInstancePackingNanos = hotCounter(),
            blaze3dBoneUploadNanos = hotCounter(),
            blaze3dInstanceUploadNanos = hotCounter(),
            blaze3dDrawPlanningNanos = hotCounter(),
            blaze3dDrawSubmissionNanos = hotCounter();
    public final LongAdder sortedPipelineInstances = hotCounter(),
            sortedQuadsCollected = hotCounter(),
            sortedQuadsSubmitted = hotCounter(),
            sortedGroups = hotCounter();
    public final LongAdder sortedIndirectCommands = hotCounter(),
            sortedCpuFallbackDraws = hotCounter(),
            sortedPreparationNanos = hotCounter(),
            sortedKeyComputationNanos = hotCounter(),
            sortedOrderingNanos = hotCounter(),
            sortedSubmissionNanos = hotCounter();
    public final java.util.concurrent.atomic.AtomicInteger maximumInstancesPerDraw =
            new java.util.concurrent.atomic.AtomicInteger();

    void recordBlaze3dFlushTiming(
            long totalNanos,
            long packingNanos,
            long boneUploadNanos,
            long instanceUploadNanos,
            long drawPlanningNanos,
            long drawSubmissionNanos) {
        blaze3dFlushCount.increment();
        blaze3dFlushTotalNanos.add(totalNanos);
        blaze3dBoneAndInstancePackingNanos.add(packingNanos);
        blaze3dBoneUploadNanos.add(boneUploadNanos);
        blaze3dInstanceUploadNanos.add(instanceUploadNanos);
        blaze3dDrawPlanningNanos.add(drawPlanningNanos);
        blaze3dDrawSubmissionNanos.add(drawSubmissionNanos);
    }

    void recordInterceptTiming(
            long totalNanos,
            long pipelineValidationNanos,
            long topologyAndMeshLookupNanos,
            long posePreparationNanos,
            long materialCaptureNanos,
            long backendQueueNanos) {
        interceptProfileCount.increment();
        interceptTotalNanos.add(totalNanos);
        interceptPipelineValidationNanos.add(pipelineValidationNanos);
        interceptTopologyAndMeshLookupNanos.add(topologyAndMeshLookupNanos);
        interceptPosePreparationNanos.add(posePreparationNanos);
        interceptMaterialCaptureNanos.add(materialCaptureNanos);
        interceptBackendQueueNanos.add(backendQueueNanos);
    }

    void recordBackendQueueTiming(
            long precheckNanos,
            long prepareNanos,
            long preparedValidationNanos,
            long instanceCaptureNanos,
            long insertionAndPaletteNanos) {
        backendQueueProfileCount.increment();
        backendQueuePrecheckNanos.add(precheckNanos);
        backendQueuePrepareNanos.add(prepareNanos);
        backendQueuePreparedValidationNanos.add(preparedValidationNanos);
        backendQueueInstanceCaptureNanos.add(instanceCaptureNanos);
        backendQueueInsertionAndPaletteNanos.add(insertionAndPaletteNanos);
    }

    void pipelineAccepted(ModelPartPipelineDescriptor descriptor) {
        if (!detailedMetricsEnabled()) return;
        if (descriptor != null) pipelineAccepted.incrementAndGet(descriptor.ordinal());
    }

    void pipelineFallback(ModelPartPipelineDescriptor descriptor) {
        if (!detailedMetricsEnabled()) return;
        if (descriptor != null) pipelineFallback.incrementAndGet(descriptor.ordinal());
        else unknownPipelineFallbacks.increment();
    }

    void pipelineDraw(ModelPartPipelineDescriptor descriptor, int instances) {
        pipelineDrawCommand(descriptor);
        pipelineInstances(descriptor, instances, instances);
    }

    void pipelineDrawCommand(ModelPartPipelineDescriptor descriptor) {
        if (!detailedMetricsEnabled()) return;
        if (descriptor != null) pipelineDrawCalls.incrementAndGet(descriptor.ordinal());
    }

    void pipelineInstances(ModelPartPipelineDescriptor descriptor, int instances) {
        pipelineInstances(descriptor, instances, 1);
    }

    void pipelineInstances(ModelPartPipelineDescriptor descriptor, int instances, int maximumBatch) {
        if (!detailedMetricsEnabled()) return;
        if (descriptor == null) return;
        int i = descriptor.ordinal();
        pipelineInstances.addAndGet(i, instances);
        int current;
        do {
            current = pipelineMaximumBatch.get(i);
            if (current >= maximumBatch) return;
        } while (!pipelineMaximumBatch.compareAndSet(i, current, maximumBatch));
    }

    void resetPipelineCoverage() {
        for (int i = 0; i < ModelPartPipelineDescriptor.values().length; i++) {
            pipelineAccepted.set(i, 0);
            pipelineFallback.set(i, 0);
            pipelineDrawCalls.set(i, 0);
            pipelineInstances.set(i, 0);
            pipelineMaximumBatch.set(i, 0);
        }
        unknownPipelineFallbacks.reset();
        sortedPipelineInstances.reset();
        sortedQuadsCollected.reset();
        sortedQuadsSubmitted.reset();
        sortedGroups.reset();
        sortedIndirectCommands.reset();
        sortedCpuFallbackDraws.reset();
        sortedPreparationNanos.reset();
        sortedKeyComputationNanos.reset();
        sortedOrderingNanos.reset();
        sortedSubmissionNanos.reset();
    }

    java.util.List<PipelineCoverage> pipelineCoverage() {
        java.util.ArrayList<PipelineCoverage> out = new java.util.ArrayList<>();
        for (ModelPartPipelineDescriptor descriptor : ModelPartPipelineDescriptor.values()) {
            int i = descriptor.ordinal();
            out.add(new PipelineCoverage(
                    descriptor.canonicalName(),
                    pipelineAccepted.get(i),
                    pipelineFallback.get(i),
                    pipelineDrawCalls.get(i),
                    pipelineInstances.get(i),
                    pipelineMaximumBatch.get(i),
                    descriptor.submissionPolicy().name().toLowerCase(java.util.Locale.ROOT)));
        }
        out.add(new PipelineCoverage("unknown_custom", 0, unknownPipelineFallbacks.sum(), 0, 0, 0, "fallback"));
        return java.util.List.copyOf(out);
    }

    public record PipelineCoverage(
            String pipeline,
            long accepted,
            long fallbacks,
            long drawCalls,
            long instances,
            int maximumBatchSize,
            String batchingMode) {}
}
