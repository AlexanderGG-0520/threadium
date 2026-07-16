package dev.alex.threadium.render.modelpart;

import java.util.concurrent.atomic.LongAdder;

public final class ModelPartGpuMetrics {
    private final java.util.concurrent.atomic.AtomicLongArray pipelineAccepted=new java.util.concurrent.atomic.AtomicLongArray(ModelPartPipelineDescriptor.values().length);
    private final java.util.concurrent.atomic.AtomicLongArray pipelineFallback=new java.util.concurrent.atomic.AtomicLongArray(ModelPartPipelineDescriptor.values().length);
    private final java.util.concurrent.atomic.AtomicLongArray pipelineDrawCalls=new java.util.concurrent.atomic.AtomicLongArray(ModelPartPipelineDescriptor.values().length);
    private final java.util.concurrent.atomic.AtomicLongArray pipelineInstances=new java.util.concurrent.atomic.AtomicLongArray(ModelPartPipelineDescriptor.values().length);
    private final java.util.concurrent.atomic.AtomicIntegerArray pipelineMaximumBatch=new java.util.concurrent.atomic.AtomicIntegerArray(ModelPartPipelineDescriptor.values().length);
    private final LongAdder unknownPipelineFallbacks=new LongAdder();
    public final LongAdder eligibleInvocations=new LongAdder(), acceptedInvocations=new LongAdder(), queuedInstances=new LongAdder();
    public final LongAdder drawnInstances=new LongAdder(), batches=new LongAdder(), instancedDraws=new LongAdder(), meshHits=new LongAdder();
    public final LongAdder meshMisses=new LongAdder(), meshBakeFailures=new LongAdder(), vanillaFallbacks=new LongAdder(), capacityFallbacks=new LongAdder();
    public final LongAdder backendFailures=new LongAdder(), boneBytesUploaded=new LongAdder(), instanceBytesUploaded=new LongAdder();
    public final LongAdder initializationAttempts=new LongAdder(), initializationSuccesses=new LongAdder(), initializationFailures=new LongAdder();
    public final LongAdder backendUnavailableFallbacks=new LongAdder(), materialFallbacks=new LongAdder(), drawCalls=new LongAdder();
    public final LongAdder consolidatedBatches=new LongAdder(), singletonBatches=new LongAdder(), multiInstanceBatches=new LongAdder(), totalInstancesInMultiDraws=new LongAdder(), instanceUploadCalls=new LongAdder(), boneUploadCalls=new LongAdder();
    public final LongAdder interceptionPassThroughs=new LongAdder(), diagnosticOverlayRequests=new LongAdder(), diagnosticOverlayDraws=new LongAdder();
    public final LongAdder productionReplacementAccepts=new LongAdder(), vanillaSuppressions=new LongAdder(), forbiddenSuppressionAttempts=new LongAdder();
    public final LongAdder diagnosticOverlayFailures=new LongAdder();
    public final LongAdder blaze3dGroupsSubmitted=new LongAdder(),blaze3dPassesCreated=new LongAdder(),blaze3dDrawCommands=new LongAdder(),blaze3dInstancesSubmitted=new LongAdder(),blaze3dSubmissionFailures=new LongAdder(),blaze3dUnsupportedFallbacks=new LongAdder(),rawProductionDrawCalls=new LongAdder();
    public final LongAdder blaze3dPipelineCompileAttempts=new LongAdder(),blaze3dPipelineCompileValid=new LongAdder(),blaze3dPipelineCompileInvalid=new LongAdder(),blaze3dPipelineCompileExceptions=new LongAdder(),blaze3dPipelineValidityUnknown=new LongAdder();
    public final LongAdder blaze3dPipelineStaleFallbacks=new LongAdder(),blaze3dInvalidPipelineFallbacks=new LongAdder(),blaze3dUnsupportedBackendFallbacks=new LongAdder();
    public final LongAdder modelLayoutCacheHits=new LongAdder(),modelLayoutCacheMisses=new LongAdder(),modelTopologyTraversals=new LongAdder(),topologyPreparationNanos=new LongAdder();
    public final LongAdder posePaletteLookups=new LongAdder(),posePaletteHits=new LongAdder(),posePaletteMisses=new LongAdder(),uniqueBonePalettes=new LongAdder(),reusedBonePalettes=new LongAdder();
    public final LongAdder boneMatricesComposed=new LongAdder(),boneMatricesAvoided=new LongAdder(),boneBytesRequested=new LongAdder(),boneBytesAvoided=new LongAdder();
    public final LongAdder poseLookupNanos=new LongAdder(),boneCompositionNanos=new LongAdder(),bonePackingNanos=new LongAdder();
    public final LongAdder sortedPipelineInstances=new LongAdder(),sortedQuadsCollected=new LongAdder(),sortedQuadsSubmitted=new LongAdder(),sortedGroups=new LongAdder();
    public final LongAdder sortedIndirectCommands=new LongAdder(),sortedCpuFallbackDraws=new LongAdder(),sortedPreparationNanos=new LongAdder(),sortedKeyComputationNanos=new LongAdder(),sortedOrderingNanos=new LongAdder(),sortedSubmissionNanos=new LongAdder();
    public final java.util.concurrent.atomic.AtomicInteger maximumInstancesPerDraw=new java.util.concurrent.atomic.AtomicInteger();

    void pipelineAccepted(ModelPartPipelineDescriptor descriptor){if(descriptor!=null)pipelineAccepted.incrementAndGet(descriptor.ordinal());}
    void pipelineFallback(ModelPartPipelineDescriptor descriptor){if(descriptor!=null)pipelineFallback.incrementAndGet(descriptor.ordinal());else unknownPipelineFallbacks.increment();}
    void pipelineDraw(ModelPartPipelineDescriptor descriptor,int instances){pipelineDrawCommand(descriptor);pipelineInstances(descriptor,instances,instances);}
    void pipelineDrawCommand(ModelPartPipelineDescriptor descriptor){if(descriptor!=null)pipelineDrawCalls.incrementAndGet(descriptor.ordinal());}
    void pipelineInstances(ModelPartPipelineDescriptor descriptor,int instances){pipelineInstances(descriptor,instances,1);}
    void pipelineInstances(ModelPartPipelineDescriptor descriptor,int instances,int maximumBatch){if(descriptor==null)return;int i=descriptor.ordinal();pipelineInstances.addAndGet(i,instances);int current;do{current=pipelineMaximumBatch.get(i);if(current>=maximumBatch)return;}while(!pipelineMaximumBatch.compareAndSet(i,current,maximumBatch));}
    void resetPipelineCoverage(){for(int i=0;i<ModelPartPipelineDescriptor.values().length;i++){pipelineAccepted.set(i,0);pipelineFallback.set(i,0);pipelineDrawCalls.set(i,0);pipelineInstances.set(i,0);pipelineMaximumBatch.set(i,0);}unknownPipelineFallbacks.reset();sortedPipelineInstances.reset();sortedQuadsCollected.reset();sortedQuadsSubmitted.reset();sortedGroups.reset();sortedIndirectCommands.reset();sortedCpuFallbackDraws.reset();sortedPreparationNanos.reset();sortedKeyComputationNanos.reset();sortedOrderingNanos.reset();sortedSubmissionNanos.reset();}
    java.util.List<PipelineCoverage> pipelineCoverage(){java.util.ArrayList<PipelineCoverage> out=new java.util.ArrayList<>();for(ModelPartPipelineDescriptor descriptor:ModelPartPipelineDescriptor.values()){int i=descriptor.ordinal();out.add(new PipelineCoverage(descriptor.canonicalName(),pipelineAccepted.get(i),pipelineFallback.get(i),pipelineDrawCalls.get(i),pipelineInstances.get(i),pipelineMaximumBatch.get(i),descriptor.submissionPolicy().name().toLowerCase(java.util.Locale.ROOT)));}out.add(new PipelineCoverage("unknown_custom",0,unknownPipelineFallbacks.sum(),0,0,0,"fallback"));return java.util.List.copyOf(out);}
    public record PipelineCoverage(String pipeline,long accepted,long fallbacks,long drawCalls,long instances,int maximumBatchSize,String batchingMode){}
}
