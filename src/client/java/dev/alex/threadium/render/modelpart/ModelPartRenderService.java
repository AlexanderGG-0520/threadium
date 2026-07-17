package dev.alex.threadium.render.modelpart;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.alex.threadium.ThreadiumClient;
import dev.alex.threadium.benchmark.BoundedFallbackDiagnostics;
import dev.alex.threadium.config.ThreadiumConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

public final class ModelPartRenderService {
    private static final boolean VERIFY_PALETTE_REUSE = Boolean.getBoolean("threadium.verifyPosePaletteReuse");
    private static ModelPartRenderService INSTANCE;
    private final ThreadiumConfig config;
    private final ModelPartGpuMetrics metrics = new ModelPartGpuMetrics();
    private final BoundedFallbackDiagnostics benchmarkFallbacks = new BoundedFallbackDiagnostics(32);
    private final ModelPartDiagnosticOverlay diagnosticOverlay = new ModelPartDiagnosticOverlay(metrics);
    private final DebugVisualMode debugMode;
    private boolean loggedOverlayInterception, loggedForbiddenSuppression;
    private SelectingModelPartGpuBackend backend;
    private final ModelPartMeshCache cache;
    private final GenericModelPartMeshBaker baker = new GenericModelPartMeshBaker();
    private final GenericModelPartPoseExtractor poses = new GenericModelPartPoseExtractor();
    /** Model roots are renderer-owned and shared; pose fields remain live on the cached nodes. */
    private final ModelPartTopologyCache topologies = new ModelPartTopologyCache();

    private final FrameBonePaletteCache posePalettes = new FrameBonePaletteCache();
    private long generation;
    private static long nextConfigPollNanos;

    private ModelPartRenderService(ThreadiumConfig c) {
        config = c;
        debugMode = DebugVisualMode.parse(c.gpuDebugVisualMode());
        backend = new SelectingModelPartGpuBackend(
                c.gpuBackend(),
                c.gpuMaxInstances(),
                c.gpuMaxBonesPerFrame(),
                c.gpuBatchConsolidation(),
                debugMode,
                c.gpuEntityEnabled()
                        && compatibilityReason(c) == null
                        && !c.gpuBackend().equals("disabled")
                        && !debugMode.overlayOnly(),
                metrics);
        cache = new ModelPartMeshCache(c.gpuMaxCachedMeshes(), c.gpuMaxMeshBytes());
        if (debugMode.overlayOnly())
            ThreadiumClient.LOGGER.info(
                    "GPU ModelPart diagnostic mode: {}, overlayOnly=true, debugSuppressVanilla={}, productionReplacementEnabled=false, vanillaSuppressionAllowed=false",
                    debugMode.configName(),
                    c.gpuDebugSuppressVanilla());
    }

    public static void initialize(ThreadiumConfig c) {
        INSTANCE = new ModelPartRenderService(c);
        String reason = INSTANCE.compatibilityReason();
        ThreadiumClient.LOGGER.info(
                "GPU ModelPart backend {}{}", INSTANCE.backend.state(), reason == null ? "" : " (" + reason + ")");
    }

    public static ModelPartRenderService get() {
        return INSTANCE;
    }

    private String compatibilityReason() {
        return compatibilityReason(config);
    }

    private static String compatibilityReason(ThreadiumConfig config) {
        if (!config.gpuEntityEnabled()) return "configuration";
        if (FabricLoader.getInstance().isModLoaded("iris")) return "Iris is installed";
        if (FabricLoader.getInstance().isModLoaded("immediatelyfast")) return "ImmediatelyFast overlap is unverified";
        return null;
    }

    public static void pollRuntimeConfig() {
        long now = System.nanoTime();
        if (now < nextConfigPollNanos) return;
        nextConfigPollNanos = now + 1_000_000_000L;
        ThreadiumConfig fresh = ThreadiumConfig.load();
        ModelPartRenderService current = INSTANCE;
        if (current != null
                && (fresh.gpuEntityEnabled() != current.config.gpuEntityEnabled()
                        || fresh.gpuBatchConsolidation() != current.config.gpuBatchConsolidation()
                        || !fresh.gpuDebugVisualMode().equals(current.config.gpuDebugVisualMode())
                        || fresh.gpuDebugSuppressVanilla() != current.config.gpuDebugSuppressVanilla()
                        || !fresh.gpuBackend().equals(current.config.gpuBackend()))) {
            current.close();
            INSTANCE = new ModelPartRenderService(fresh);
            ThreadiumClient.LOGGER.info(
                    "GPU ModelPart runtime configuration: enabled={}, backend={}, consolidation={}, debugVisualMode={}, debugSuppressVanilla={}; previous resources released",
                    fresh.gpuEntityEnabled(),
                    fresh.gpuBackend(),
                    fresh.gpuBatchConsolidation(),
                    fresh.gpuDebugVisualMode(),
                    fresh.gpuDebugSuppressVanilla());
        }
    }

    public ModelPartInterceptionResult intercept(
            Model<?> model,
            PoseStack stack,
            VertexConsumer consumer,
            int light,
            int overlay,
            int tint,
            RenderType type,
            TextureAtlasSprite sprite,
            PoseStack.Pose sheetedDecalPose) {
        ModelPartPipelineDescriptor differentialDescriptor = ModelPartPipelineDescriptor.from(type.pipeline());
        String differentialCanonical =
                differentialDescriptor == null ? "unknown_custom" : differentialDescriptor.canonicalName();
        if (DifferentialExecutionScope.referenceBypass(differentialCanonical))
            return ModelPartInterceptionResult.PASS_THROUGH;
        metrics.eligibleInvocations.increment();
        if (debugMode.overlayOnly()) {
            if (!loggedOverlayInterception) {
                loggedOverlayInterception = true;
                ThreadiumClient.LOGGER.info(
                        "{} interception result: DEBUG_OVERLAY_ONLY, vanillaCancelled=false", debugMode.configName());
            }
            return ModelPartInterceptionResult.DEBUG_OVERLAY_ONLY;
        }
        // Optional world-space diagnostics stay out of the replacement queue when vanilla
        // suppression is disabled. This preserves accepts == actual suppressions exactly.
        if (debugMode.worldSpaceDiagnostic() && !config.gpuDebugSuppressVanilla()) {
            metrics.interceptionPassThroughs.increment();
            return ModelPartInterceptionResult.PASS_THROUGH;
        }
        if (compatibilityReason() != null) {
            recordFallback("backend compatibility", model, type, null);
            metrics.backendUnavailableFallbacks.increment();
            metrics.vanillaFallbacks.increment();
            metrics.interceptionPassThroughs.increment();
            return ModelPartInterceptionResult.PASS_THROUGH;
        }
        if (!backend.ensureReady()) {
            recordFallback(
                    "backend unavailable: state=" + backend.state() + ", selected=" + backend.selected(),
                    model,
                    type,
                    null);
            metrics.backendUnavailableFallbacks.increment();
            metrics.vanillaFallbacks.increment();
            metrics.interceptionPassThroughs.increment();
            return ModelPartInterceptionResult.PASS_THROUGH;
        }
        if (!(consumer instanceof BufferBuilder) && sprite == null && sheetedDecalPose == null) {
            recordFallback("unsupported wrapped vertex consumer", model, type, null);
            metrics.materialFallbacks.increment();
            metrics.vanillaFallbacks.increment();
            metrics.interceptionPassThroughs.increment();
            return ModelPartInterceptionResult.PASS_THROUGH;
        }
        PipelineValidity pipelineValidity = backend.pipelineValidity(type, generation);
        if (!pipelineValidity.permitsReplacement(generation)) {
            recordFallback("pipeline validity", model, type, pipelineValidity);
            metrics.vanillaFallbacks.increment();
            metrics.interceptionPassThroughs.increment();
            return ModelPartInterceptionResult.PASS_THROUGH;
        }
        try {
            ModelPart root = model.root();
            GenericModelPartTopology topology = topologies.get(root);
            if (topology == null) {
                long topologyStart = System.nanoTime();
                topology = GenericModelPartTopology.inspect(root, config.gpuMaxBonesPerModel(), 64, generation);
                metrics.topologyPreparationNanos.add(System.nanoTime() - topologyStart);
                metrics.modelLayoutCacheMisses.increment();
                metrics.modelTopologyTraversals.increment();
                topologies.put(root, topology);
            } else metrics.modelLayoutCacheHits.increment();
            var handle = cache.get(topology.key());
            if (handle == null) {
                metrics.meshMisses.increment();
                if (cache.failed(topology.key())) {
                    metrics.interceptionPassThroughs.increment();
                    return ModelPartInterceptionResult.PASS_THROUGH;
                }
                var mesh = baker.bake(topology, config.gpuMaxVerticesPerMesh(), config.gpuMaxIndicesPerMesh());
                handle = backend.upload(mesh);
                if (handle == null || !cache.put(topology.key(), handle)) {
                    cache.fail(topology.key());
                    metrics.meshBakeFailures.increment();
                    metrics.interceptionPassThroughs.increment();
                    return ModelPartInterceptionResult.PASS_THROUGH;
                }
            } else metrics.meshHits.increment();
            long poseLookupStart = System.nanoTime();
            ModelPartBoneData boneData = posePalettes.find(topology);
            metrics.poseLookupNanos.add(System.nanoTime() - poseLookupStart);
            metrics.posePaletteLookups.increment();
            boolean reused = boneData != null;
            if (reused) {
                metrics.posePaletteHits.increment();
                metrics.reusedBonePalettes.increment();
                metrics.boneMatricesAvoided.add(topology.nodes().size());
                if (VERIFY_PALETTE_REUSE) {
                    ModelPartBoneData independent = poses.extract(topology);
                    if (!java.util.Arrays.equals(independent.matrices(), boneData.matrices())
                            || !java.util.Arrays.equals(independent.visibility(), boneData.visibility()))
                        throw new IllegalStateException(
                                "Reused ModelPart bone palette differs from independent composition");
                }
            } else {
                long compositionStart = System.nanoTime();
                boneData = poses.extract(topology);
                metrics.boneCompositionNanos.add(System.nanoTime() - compositionStart);
                posePalettes.store(boneData);
                metrics.posePaletteMisses.increment();
                metrics.uniqueBonePalettes.increment();
                metrics.boneMatricesComposed.add(topology.nodes().size());
            }
            ModelPartDecalTransform decal =
                    sheetedDecalPose == null ? null : ModelPartDecalTransform.capture(sheetedDecalPose);
            if (!backend.queue(
                    handle,
                    type,
                    stack.last().pose(),
                    boneData,
                    light,
                    overlay,
                    tint,
                    ModelPartUvTransform.from(sprite),
                    decal)) {
                recordFallback("queue capacity", model, type, pipelineValidity);
                metrics.capacityFallbacks.increment();
                metrics.vanillaFallbacks.increment();
                metrics.interceptionPassThroughs.increment();
                return ModelPartInterceptionResult.PASS_THROUGH;
            }
            long requestedBoneBytes = (long) topology.nodes().size() * ModelPartLayouts.BONE_STRIDE;
            metrics.boneBytesRequested.add(requestedBoneBytes);
            if (reused) metrics.boneBytesAvoided.add(requestedBoneBytes);
            metrics.pipelineAccepted(ModelPartPipelineDescriptor.from(type.pipeline()));
            metrics.acceptedInvocations.increment();
            metrics.queuedInstances.increment();
            metrics.productionReplacementAccepts.increment();
            return ModelPartInterceptionResult.GPU_REPLACED;
        } catch (Throwable failure) {
            recordFallback(
                    "mesh or pose failure: " + failure.getClass().getSimpleName(), model, type, pipelineValidity);
            metrics.meshBakeFailures.increment();
            metrics.vanillaFallbacks.increment();
            metrics.interceptionPassThroughs.increment();
            return ModelPartInterceptionResult.PASS_THROUGH;
        }
    }

    private void recordFallback(String reason, Model<?> model, RenderType type, PipelineValidity validity) {
        DifferentialExecutionScope.fallbackReason(reason);
        try {
            metrics.pipelineFallback(ModelPartPipelineDescriptor.from(type.pipeline()));
        } catch (Throwable ignored) {
        }
        if (dev.alex.threadium.benchmark.ThreadiumBenchmark.trialActive())
            benchmarkFallbacks.record(
                    reason,
                    type.toString(),
                    type.toString(),
                    model.getClass().getName(),
                    validity == null ? "unknown" : validity.state().name());
    }

    public java.util.List<ModelPartGpuMetrics.PipelineCoverage> pipelineCoverage() {
        return metrics.pipelineCoverage();
    }

    public DifferentialMetrics differentialMetrics() {
        return new DifferentialMetrics(
                metrics.backendFailures.sum(),
                metrics.blaze3dSubmissionFailures.sum(),
                metrics.blaze3dDrawCommands.sum(),
                metrics.blaze3dInstancesSubmitted.sum(),
                metrics.sortedPipelineInstances.sum(),
                metrics.sortedQuadsCollected.sum(),
                metrics.sortedQuadsSubmitted.sum(),
                metrics.sortedGroups.sum());
    }

    public record DifferentialMetrics(
            long backendFailures,
            long submissionFailures,
            long drawCommands,
            long instancesSubmitted,
            long sortedInstances,
            long sortedQuadsCollected,
            long sortedQuadsSubmitted,
            long sortedGroups) {}

    public void recordDifferentialCompletion(RenderType type, ModelPartInterceptionResult result) {
        ModelPartPipelineDescriptor descriptor = ModelPartPipelineDescriptor.from(type.pipeline());
        DifferentialExecutionScope.completed(
                descriptor == null ? "unknown_custom" : descriptor.canonicalName(), result);
    }

    public void resetPipelineCoverage() {
        metrics.resetPipelineCoverage();
    }

    public String sortedPipelineMetrics() {
        return "sortedPipelineInstances=" + metrics.sortedPipelineInstances.sum() + ", sortedQuadsCollected="
                + metrics.sortedQuadsCollected.sum() + ", sortedQuadsSubmitted=" + metrics.sortedQuadsSubmitted.sum()
                + ", sortedGroups=" + metrics.sortedGroups.sum() + ", sortedIndirectCommands="
                + metrics.sortedIndirectCommands.sum() + ", sortedCpuFallbackDraws="
                + metrics.sortedCpuFallbackDraws.sum() + ", sortedPreparationNanos="
                + metrics.sortedPreparationNanos.sum() + ", sortedKeyComputationNanos="
                + metrics.sortedKeyComputationNanos.sum() + ", sortedOrderingNanos=" + metrics.sortedOrderingNanos.sum()
                + ", sortedSubmissionNanos=" + metrics.sortedSubmissionNanos.sum();
    }

    public BoundedFallbackDiagnostics.Snapshot benchmarkFallbackDiagnosticsAndReset() {
        return benchmarkFallbacks.snapshotAndReset();
    }

    public boolean maySuppressVanilla(ModelPartInterceptionResult result) {
        boolean suppress = ModelPartSuppressionPolicy.maySuppress(debugMode, config.gpuDebugSuppressVanilla(), result);
        if (result == ModelPartInterceptionResult.GPU_REPLACED && !suppress && debugMode.overlayOnly()) {
            metrics.forbiddenSuppressionAttempts.increment();
            if (!loggedForbiddenSuppression) {
                loggedForbiddenSuppression = true;
                ThreadiumClient.LOGGER.error(
                        "Forbidden GPU ModelPart suppression attempt blocked: mode={}, result={}, overlayOnly=true",
                        debugMode.configName(),
                        result,
                        new IllegalStateException("suppression gate context"));
            }
        }
        return suppress;
    }

    public void recordVanillaSuppression() {
        DifferentialExecutionScope.suppression();
        metrics.vanillaSuppressions.increment();
    }

    public void beginDiagnosticFrame() {
        if (DifferentialExecutionScope.active()) DifferentialExecutionScope.reset();
        posePalettes.beginFrame();
        backend.beginFrame();
        diagnosticOverlay.beginFrame(debugMode);
    }

    public void drawDiagnosticOverlayAtWorldTail() {
        diagnosticOverlay.drawWorldTail(debugMode);
    }

    public void drawDiagnosticOverlayAtMainTargetTail() {
        diagnosticOverlay.drawMainTargetTail(debugMode);
    }

    public void beginGroup(boolean strictlyOrdered) {
        backend.beginGroup(strictlyOrdered);
    }

    public void endGroup() {
        backend.endGroup();
    }

    public void flush() {
        backend.flushGroup();
    }

    public void invalidate() {
        DifferentialExecutionScope.reset();
        generation++;
        backend.invalidatePipelines(generation);
        for (var h : cache.handles()) backend.destroy(h);
        cache.clear();
        topologies.clear();
        posePalettes.beginFrame();
        backend.clear();
        if (backend.state() == ModelPartBackendState.FAILED) {
            backend.close();
            backend = new SelectingModelPartGpuBackend(
                    config.gpuBackend(),
                    config.gpuMaxInstances(),
                    config.gpuMaxBonesPerFrame(),
                    config.gpuBatchConsolidation(),
                    DebugVisualMode.parse(config.gpuDebugVisualMode()),
                    config.gpuEntityEnabled()
                            && compatibilityReason() == null
                            && !config.gpuBackend().equals("disabled"),
                    metrics);
            backend.invalidatePipelines(generation);
            ThreadiumClient.LOGGER.info("GPU ModelPart backend reset to UNINITIALIZED after generation invalidation");
        }
    }

    public void close() {
        diagnosticOverlay.close();
        invalidate();
        backend.close();
    }

    public BenchmarkMetrics benchmarkSnapshotAndReset() {
        long drawn = metrics.drawnInstances.sumThenReset(),
                calls = metrics.drawCalls.sumThenReset(),
                multiInstances = metrics.totalInstancesInMultiDraws.sumThenReset();
        int maximum = metrics.maximumInstancesPerDraw.getAndSet(0);
        return new BenchmarkMetrics(
                metrics.acceptedInvocations.sumThenReset(),
                metrics.productionReplacementAccepts.sumThenReset(),
                metrics.vanillaSuppressions.sumThenReset(),
                metrics.queuedInstances.sumThenReset(),
                drawn,
                calls,
                metrics.multiInstanceBatches.sumThenReset(),
                maximum,
                multiInstances,
                metrics.boneBytesUploaded.sumThenReset(),
                metrics.instanceBytesUploaded.sumThenReset(),
                metrics.boneUploadCalls.sumThenReset(),
                metrics.instanceUploadCalls.sumThenReset(),
                metrics.backendFailures.sumThenReset(),
                metrics.blaze3dSubmissionFailures.sumThenReset(),
                metrics.rawProductionDrawCalls.sumThenReset(),
                metrics.vanillaFallbacks.sumThenReset(),
                metrics.blaze3dPipelineCompileInvalid.sumThenReset(),
                metrics.blaze3dPipelineStaleFallbacks.sumThenReset(),
                metrics.blaze3dPipelineValidityUnknown.sumThenReset(),
                metrics.modelLayoutCacheHits.sumThenReset(),
                metrics.modelLayoutCacheMisses.sumThenReset(),
                metrics.modelTopologyTraversals.sumThenReset(),
                metrics.topologyPreparationNanos.sumThenReset(),
                metrics.posePaletteLookups.sumThenReset(),
                metrics.posePaletteHits.sumThenReset(),
                metrics.posePaletteMisses.sumThenReset(),
                metrics.uniqueBonePalettes.sumThenReset(),
                metrics.reusedBonePalettes.sumThenReset(),
                metrics.boneMatricesComposed.sumThenReset(),
                metrics.boneMatricesAvoided.sumThenReset(),
                metrics.boneBytesRequested.sumThenReset(),
                metrics.boneBytesAvoided.sumThenReset(),
                metrics.poseLookupNanos.sumThenReset(),
                metrics.boneCompositionNanos.sumThenReset(),
                metrics.bonePackingNanos.sumThenReset());
    }

    public record BenchmarkMetrics(
            long acceptedInvocations,
            long productionReplacementAccepts,
            long vanillaSuppressions,
            long queuedInstances,
            long drawnInstances,
            long drawCalls,
            long multiInstanceBatches,
            long maximumInstancesPerDraw,
            long totalInstancesInMultiDraws,
            long boneBytesUploaded,
            long instanceBytesUploaded,
            long boneUploadCalls,
            long instanceUploadCalls,
            long backendFailures,
            long blaze3dSubmissionFailures,
            long rawProductionDrawCalls,
            long vanillaFallbacks,
            long pipelineInvalid,
            long pipelineStale,
            long pipelineUnknown,
            long modelLayoutCacheHits,
            long modelLayoutCacheMisses,
            long modelTopologyTraversals,
            long topologyPreparationNanos,
            long posePaletteLookups,
            long posePaletteHits,
            long posePaletteMisses,
            long uniqueBonePalettes,
            long reusedBonePalettes,
            long boneMatricesComposed,
            long boneMatricesAvoided,
            long boneBytesRequested,
            long boneBytesAvoided,
            long poseLookupNanos,
            long boneCompositionNanos,
            long bonePackingNanos) {
        public double instancesPerDraw() {
            return BatchMetricMath.instancesPerDraw(drawnInstances, drawCalls);
        }

        public double drawReductionRatio() {
            return BatchMetricMath.drawReduction(drawnInstances, drawCalls);
        }

        public double multiInstanceCoverage() {
            return BatchMetricMath.multiCoverage(totalInstancesInMultiDraws, drawnInstances);
        }

        public static BenchmarkMetrics zero() {
            return new BenchmarkMetrics(
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0);
        }
    }

    public String metricsSnapshot() {
        long drawn = metrics.drawnInstances.sumThenReset(),
                calls = metrics.drawCalls.sumThenReset(),
                multiInstances = metrics.totalInstancesInMultiDraws.sumThenReset();
        int maximum = metrics.maximumInstancesPerDraw.getAndSet(0);
        return "gpuEntity={state=" + backend.state() + ",selectedBackend=" + backend.selected()
                + ",initializationAttempts=" + metrics.initializationAttempts.sum() + ",initializationSuccesses="
                + metrics.initializationSuccesses.sum() + ",initializationFailures="
                + metrics.initializationFailures.sum() + ",eligibleInvocations="
                + metrics.eligibleInvocations.sumThenReset() + ",acceptedInvocations="
                + metrics.acceptedInvocations.sumThenReset() + ",interceptionPassThroughs="
                + metrics.interceptionPassThroughs.sumThenReset() + ",diagnosticOverlayRequests="
                + metrics.diagnosticOverlayRequests.sumThenReset() + ",diagnosticOverlayDraws="
                + metrics.diagnosticOverlayDraws.sumThenReset() + ",diagnosticOverlayFailures="
                + metrics.diagnosticOverlayFailures.sumThenReset() + ",productionReplacementAccepts="
                + metrics.productionReplacementAccepts.sumThenReset() + ",vanillaSuppressions="
                + metrics.vanillaSuppressions.sumThenReset() + ",forbiddenSuppressionAttempts="
                + metrics.forbiddenSuppressionAttempts.sumThenReset() + ",queuedInstances="
                + metrics.queuedInstances.sumThenReset() + ",drawnInstances=" + drawn + ",drawCalls=" + calls
                + ",consolidatedBatches=" + metrics.consolidatedBatches.sumThenReset() + ",singletonBatches="
                + metrics.singletonBatches.sumThenReset() + ",multiInstanceBatches="
                + metrics.multiInstanceBatches.sumThenReset() + ",maximumInstancesPerDraw=" + maximum
                + ",totalInstancesInMultiDraws=" + multiInstances + ",instancesPerDraw="
                + BatchMetricMath.instancesPerDraw(drawn, calls) + ",drawReductionRatio="
                + BatchMetricMath.drawReduction(drawn, calls) + ",multiInstanceCoverage="
                + BatchMetricMath.multiCoverage(multiInstances, drawn) + ",instanceUploadCalls="
                + metrics.instanceUploadCalls.sumThenReset() + ",boneUploadCalls="
                + metrics.boneUploadCalls.sumThenReset() + ",meshHits=" + metrics.meshHits.sumThenReset()
                + ",meshMisses=" + metrics.meshMisses.sumThenReset() + ",meshBakeFailures="
                + metrics.meshBakeFailures.sumThenReset() + ",vanillaFallbacks="
                + metrics.vanillaFallbacks.sumThenReset() + ",backendUnavailableFallbacks="
                + metrics.backendUnavailableFallbacks.sumThenReset() + ",materialFallbacks="
                + metrics.materialFallbacks.sumThenReset() + ",capacityFallbacks="
                + metrics.capacityFallbacks.sumThenReset() + ",backendFailures="
                + metrics.backendFailures.sumThenReset() + ",cachedMeshes=" + cache.size() + ",meshGpuBytes="
                + cache.bytes() + ",boneBytesUploaded=" + metrics.boneBytesUploaded.sumThenReset()
                + ",instanceBytesUploaded=" + metrics.instanceBytesUploaded.sumThenReset() + "}";
    }

    public String visualMetricsSnapshot() {
        return VisualDiagnosticMetrics.snapshot(DebugVisualMode.parse(config.gpuDebugVisualMode()))
                + ",blaze3dPipelineCompileAttempts=" + metrics.blaze3dPipelineCompileAttempts.sum()
                + ",blaze3dPipelineCompileValid=" + metrics.blaze3dPipelineCompileValid.sum()
                + ",blaze3dPipelineCompileInvalid=" + metrics.blaze3dPipelineCompileInvalid.sum()
                + ",blaze3dPipelineCompileExceptions=" + metrics.blaze3dPipelineCompileExceptions.sum()
                + ",blaze3dPipelineValidityUnknown=" + metrics.blaze3dPipelineValidityUnknown.sum()
                + ",blaze3dPipelineStaleFallbacks=" + metrics.blaze3dPipelineStaleFallbacks.sum()
                + ",blaze3dInvalidPipelineFallbacks=" + metrics.blaze3dInvalidPipelineFallbacks.sum()
                + ",blaze3dUnsupportedBackendFallbacks=" + metrics.blaze3dUnsupportedBackendFallbacks.sum()
                + ",blaze3dGroupsSubmitted=" + metrics.blaze3dGroupsSubmitted.sum() + ",blaze3dPassesCreated="
                + metrics.blaze3dPassesCreated.sum() + ",blaze3dDrawCommands=" + metrics.blaze3dDrawCommands.sum()
                + ",blaze3dInstancesSubmitted=" + metrics.blaze3dInstancesSubmitted.sum()
                + ",blaze3dSubmissionFailures=" + metrics.blaze3dSubmissionFailures.sum()
                + ",blaze3dUnsupportedFallbacks=" + metrics.blaze3dUnsupportedFallbacks.sum()
                + ",rawProductionDrawCalls=" + metrics.rawProductionDrawCalls.sum();
    }
}
