package dev.alex.threadium.render.modelpart;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.alex.threadium.ThreadiumClient;
import dev.alex.threadium.benchmark.BoundedFallbackDiagnostics;
import dev.alex.threadium.compat.IrisCompatibility;
import dev.alex.threadium.config.ThreadiumConfig;
import dev.alex.threadium.config.ThreadiumRuntimeConfig;
import java.util.ArrayDeque;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

public final class ModelPartRenderService {
    private static final boolean VERIFY_PALETTE_REUSE = Boolean.getBoolean("threadium.verifyPosePaletteReuse");
    private static final boolean PROFILE_INTERCEPT = Boolean.getBoolean("threadium.modelpart.profileIntercept");
    private static ModelPartRenderService INSTANCE;
    private final ThreadiumConfig config;
    private final ModelPartGpuMetrics metrics = new ModelPartGpuMetrics();
    private final BoundedFallbackDiagnostics benchmarkFallbacks = new BoundedFallbackDiagnostics(32);
    private final ModelPartDiagnosticOverlay diagnosticOverlay = new ModelPartDiagnosticOverlay(metrics);
    private final DebugVisualMode debugMode;
    private boolean loggedOverlayInterception, loggedForbiddenSuppression;
    private boolean irisShadersActive;
    private SelectingModelPartGpuBackend backend;
    private final ModelPartMeshCache cache;
    private final GenericModelPartMeshBaker baker = new GenericModelPartMeshBaker();
    private final GenericModelPartPoseExtractor poses = new GenericModelPartPoseExtractor();
    /** Model roots are renderer-owned and shared; pose fields remain live on the cached nodes. */
    private final ModelPartTopologyCache topologies = new ModelPartTopologyCache();

    private final FrameBonePaletteCache posePalettes = new FrameBonePaletteCache();
    private final AdaptiveModelPartBatchGate batchProfitability = new AdaptiveModelPartBatchGate();
    private final ArrayDeque<GroupScope> groupReplacementScopes = new ArrayDeque<>();
    private long generation;

    private ModelPartRenderService(ThreadiumConfig c) {
        config = c;
        debugMode = DebugVisualMode.parse(c.gpuDebugVisualMode());
        irisShadersActive = IrisCompatibility.isShaderPackInUse();
        backend = new SelectingModelPartGpuBackend(
                c.gpuBackend(),
                c.gpuMaxInstances(),
                c.gpuMaxBonesPerFrame(),
                c.gpuBatchConsolidation(),
                debugMode,
                c.enabled()
                        && c.gpuEntityEnabled()
                        && compatibilityReason(c, irisShadersActive) == null
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
        return compatibilityReason(config, irisShadersActive);
    }

    private static String compatibilityReason(ThreadiumConfig config, boolean irisShadersActive) {
        if (!config.enabled()) return "Threadium is disabled";
        if (!config.gpuEntityEnabled()) return "configuration";
        if (irisShadersActive) return "Iris shader pack is active";
        return null;
    }

    public static void applyRuntimeConfig() {
        ThreadiumConfig fresh = ThreadiumRuntimeConfig.persisted();
        ModelPartRenderService current = INSTANCE;
        if (current != null
                && (fresh.enabled() != current.config.enabled()
                        || fresh.gpuEntityEnabled() != current.config.gpuEntityEnabled()
                        || fresh.gpuMinimumGroupSubmits() != current.config.gpuMinimumGroupSubmits()
                        || fresh.gpuAllowVanillaFallback() != current.config.gpuAllowVanillaFallback()
                        || fresh.gpuBatchConsolidation() != current.config.gpuBatchConsolidation()
                        || !fresh.gpuDebugVisualMode().equals(current.config.gpuDebugVisualMode())
                        || fresh.gpuDebugSuppressVanilla() != current.config.gpuDebugSuppressVanilla()
                        || !fresh.gpuBackend().equals(current.config.gpuBackend()))) {
            current.close();
            INSTANCE = new ModelPartRenderService(fresh);
            ThreadiumClient.LOGGER.info(
                    "GPU ModelPart runtime configuration: enabled={}, backend={}, consolidation={}, minimumGroupSubmits={}, debugVisualMode={}, debugSuppressVanilla={}; previous resources released",
                    fresh.gpuEntityEnabled(),
                    fresh.gpuBackend(),
                    fresh.gpuBatchConsolidation(),
                    fresh.gpuMinimumGroupSubmits(),
                    fresh.gpuDebugVisualMode(),
                    fresh.gpuDebugSuppressVanilla());
        }
    }

    public boolean replacementEnabled() {
        return config.enabled()
                && config.gpuEntityEnabled()
                && compatibilityReason() == null
                && !config.gpuBackend().equals("disabled")
                && !debugMode.overlayOnly();
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
        long interceptStart = interceptProfileNow();
        ModelPartPipelineDescriptor differentialDescriptor = ModelPartPipelineDescriptor.from(type.pipeline());
        String differentialCanonical =
                differentialDescriptor == null ? "unknown_custom" : differentialDescriptor.canonicalName();
        if (DifferentialExecutionScope.referenceBypass(differentialCanonical))
            return ModelPartInterceptionResult.PASS_THROUGH;
        metrics.eligibleInvocations.increment();
        long pipelineValidationNanos = 0L;
        long topologyAndMeshLookupNanos = 0L;
        long posePreparationNanos = 0L;
        long materialCaptureNanos = 0L;
        long backendQueueNanos = 0L;
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
        GroupScope group = currentGroup();
        if (group == null || !group.allowed()) {
            metrics.vanillaFallbacks.increment();
            metrics.interceptionPassThroughs.increment();
            return ModelPartInterceptionResult.PASS_THROUGH;
        }
        if (requiresVanillaForSortedPipeline(type.sortOnUpload())) {
            recordFallback("sorted pipeline delegated to vanilla", model, type, null);
            metrics.vanillaFallbacks.increment();
            metrics.interceptionPassThroughs.increment();
            return ModelPartInterceptionResult.PASS_THROUGH;
        }
        ModelPart root;
        try {
            root = model.root();
        } catch (Throwable failure) {
            recordFallback("model root failure: " + failure.getClass().getSimpleName(), model, type, null);
            metrics.vanillaFallbacks.increment();
            metrics.interceptionPassThroughs.increment();
            return ModelPartInterceptionResult.PASS_THROUGH;
        }
        if (!batchProfitability.observeAndShouldReplace(group.ordinal(), root, type, config.gpuMinimumGroupSubmits())) {
            metrics.vanillaFallbacks.increment();
            metrics.interceptionPassThroughs.increment();
            return ModelPartInterceptionResult.PASS_THROUGH;
        }
        long pipelineValidationStart = interceptProfileNow();
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
        long topologyAndMeshLookupStart = interceptProfileNow();
        pipelineValidationNanos = interceptProfileDelta(pipelineValidationStart, topologyAndMeshLookupStart);
        try {
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
            long posePreparationStart = interceptProfileNow();
            topologyAndMeshLookupNanos = interceptProfileDelta(topologyAndMeshLookupStart, posePreparationStart);
            long poseLookupStart = System.nanoTime();
            ModelPartBoneData boneData = posePalettes.find(topology);
            metrics.poseLookupNanos.add(System.nanoTime() - poseLookupStart);
            boolean lookupPerformed = posePalettes.lastLookupPerformed();
            if (lookupPerformed) metrics.posePaletteLookups.increment();
            else metrics.posePaletteBypasses.increment();
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
                boolean stored = posePalettes.store(boneData);
                if (lookupPerformed) metrics.posePaletteMisses.increment();
                if (stored) metrics.uniqueBonePalettes.increment();
                else metrics.directPackedPosePalettes.increment();
                metrics.boneMatricesComposed.add(topology.nodes().size());
            }
            long materialCaptureStart = interceptProfileNow();
            posePreparationNanos = interceptProfileDelta(posePreparationStart, materialCaptureStart);
            ModelPartDecalTransform decal =
                    sheetedDecalPose == null ? null : ModelPartDecalTransform.capture(sheetedDecalPose);
            ModelPartUvTransform uvTransform = ModelPartUvTransform.from(sprite);
            long backendQueueStart = interceptProfileNow();
            materialCaptureNanos = interceptProfileDelta(materialCaptureStart, backendQueueStart);
            if (!backend.queue(handle, type, stack.last().pose(), boneData, light, overlay, tint, uvTransform, decal)) {
                recordFallback("queue capacity", model, type, pipelineValidity);
                metrics.capacityFallbacks.increment();
                metrics.vanillaFallbacks.increment();
                metrics.interceptionPassThroughs.increment();
                return ModelPartInterceptionResult.PASS_THROUGH;
            }
            long backendQueueEnd = interceptProfileNow();
            backendQueueNanos = interceptProfileDelta(backendQueueStart, backendQueueEnd);
            long requestedBoneBytes = (long) topology.nodes().size() * ModelPartLayouts.BONE_STRIDE;
            metrics.boneBytesRequested.add(requestedBoneBytes);
            if (reused) metrics.boneBytesAvoided.add(requestedBoneBytes);
            metrics.pipelineAccepted(ModelPartPipelineDescriptor.from(type.pipeline()));
            metrics.acceptedInvocations.increment();
            metrics.queuedInstances.increment();
            metrics.productionReplacementAccepts.increment();
            if (PROFILE_INTERCEPT)
                metrics.recordInterceptTiming(
                        interceptProfileDelta(interceptStart, interceptProfileNow()),
                        pipelineValidationNanos,
                        topologyAndMeshLookupNanos,
                        posePreparationNanos,
                        materialCaptureNanos,
                        backendQueueNanos);
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

    private static long interceptProfileNow() {
        return PROFILE_INTERCEPT ? System.nanoTime() : 0L;
    }

    private static long interceptProfileDelta(long start, long end) {
        return PROFILE_INTERCEPT ? end - start : 0L;
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
        refreshIrisShaderState();
        if (DifferentialExecutionScope.active()) DifferentialExecutionScope.reset();
        groupReplacementScopes.clear();
        batchProfitability.beginFrame();
        posePalettes.beginFrame();
        backend.beginFrame();
        diagnosticOverlay.beginFrame(debugMode);
    }

    private void refreshIrisShaderState() {
        boolean active = IrisCompatibility.isShaderPackInUse();
        if (active == irisShadersActive) return;
        irisShadersActive = active;
        rebuildBackendForCompatibilityChange();
        ThreadiumClient.LOGGER.info(
                "GPU ModelPart Iris compatibility changed: shadersActive={}, replacementEnabled={}",
                active,
                compatibilityReason() == null);
    }

    private void rebuildBackendForCompatibilityChange() {
        DifferentialExecutionScope.reset();
        generation++;
        for (var h : cache.handles()) backend.destroy(h);
        cache.clear();
        topologies.clear();
        posePalettes.clear();
        backend.clear();
        backend.close();
        backend = new SelectingModelPartGpuBackend(
                config.gpuBackend(),
                config.gpuMaxInstances(),
                config.gpuMaxBonesPerFrame(),
                config.gpuBatchConsolidation(),
                debugMode,
                config.gpuEntityEnabled()
                        && compatibilityReason() == null
                        && !config.gpuBackend().equals("disabled")
                        && !debugMode.overlayOnly(),
                metrics);
        backend.invalidatePipelines(generation);
    }

    public void drawDiagnosticOverlayAtWorldTail() {
        diagnosticOverlay.drawWorldTail(debugMode);
    }

    public void drawDiagnosticOverlayAtMainTargetTail() {
        diagnosticOverlay.drawMainTargetTail(debugMode);
    }

    public void beginGroup(boolean strictlyOrdered, int submitCount) {
        backend.beginGroup(strictlyOrdered);
        boolean parentAllows = groupReplacementScopes.isEmpty()
                || groupReplacementScopes.getLast().allowed();
        int ordinal = batchProfitability.nextGroupOrdinal();
        groupReplacementScopes.addLast(new GroupScope(
                parentAllows && groupMeetsMinimum(submitCount, config.gpuMinimumGroupSubmits()), ordinal));
    }

    public void endGroup() {
        backend.endGroup();
        if (!groupReplacementScopes.isEmpty()) groupReplacementScopes.removeLast();
    }

    private GroupScope currentGroup() {
        return groupReplacementScopes.isEmpty() ? null : groupReplacementScopes.getLast();
    }

    static boolean groupMeetsMinimum(int submitCount, int minimumSubmits) {
        return submitCount >= minimumSubmits;
    }

    private record GroupScope(boolean allowed, int ordinal) {}

    static boolean requiresVanillaForSortedPipeline(boolean sortOnUpload) {
        return sortOnUpload;
    }

    public void flush() {
        backend.flushGroup();
    }

    public void invalidate() {
        DifferentialExecutionScope.reset();
        groupReplacementScopes.clear();
        batchProfitability.clear();
        generation++;
        backend.invalidatePipelines(generation);
        for (var h : cache.handles()) backend.destroy(h);
        cache.clear();
        topologies.clear();
        posePalettes.clear();
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

    public Blaze3dFlushTimings benchmarkFlushTimingsSnapshotAndReset() {
        return new Blaze3dFlushTimings(
                metrics.blaze3dFlushCount.sumThenReset(),
                metrics.blaze3dFlushTotalNanos.sumThenReset(),
                metrics.blaze3dBoneAndInstancePackingNanos.sumThenReset(),
                metrics.blaze3dBoneUploadNanos.sumThenReset(),
                metrics.blaze3dInstanceUploadNanos.sumThenReset(),
                metrics.blaze3dDrawPlanningNanos.sumThenReset(),
                metrics.blaze3dDrawSubmissionNanos.sumThenReset());
    }

    public record Blaze3dFlushTimings(
            long count,
            long flushTotalNanos,
            long boneAndInstancePackingNanos,
            long boneUploadNanos,
            long instanceUploadNanos,
            long drawPlanningNanos,
            long drawSubmissionNanos) {
        public static Blaze3dFlushTimings zero() {
            return new Blaze3dFlushTimings(0, 0, 0, 0, 0, 0, 0);
        }
    }

    public InterceptTimings benchmarkInterceptTimingsSnapshotAndReset() {
        return new InterceptTimings(
                metrics.interceptProfileCount.sumThenReset(),
                metrics.interceptTotalNanos.sumThenReset(),
                metrics.interceptPipelineValidationNanos.sumThenReset(),
                metrics.interceptTopologyAndMeshLookupNanos.sumThenReset(),
                metrics.interceptPosePreparationNanos.sumThenReset(),
                metrics.interceptMaterialCaptureNanos.sumThenReset(),
                metrics.interceptBackendQueueNanos.sumThenReset());
    }

    public BackendQueueTimings benchmarkBackendQueueTimingsSnapshotAndReset() {
        return snapshotBackendQueueTimings(metrics);
    }

    static BackendQueueTimings snapshotBackendQueueTimings(ModelPartGpuMetrics metrics) {
        return new BackendQueueTimings(
                metrics.backendQueueProfileCount.sumThenReset(),
                metrics.backendQueuePrepareCalls.sumThenReset(),
                metrics.backendQueuePrepareReuseHits.sumThenReset(),
                metrics.backendQueueSelectorNanos.sumThenReset(),
                metrics.backendQueuePrecheckNanos.sumThenReset(),
                metrics.backendQueuePrepareNanos.sumThenReset(),
                metrics.backendQueuePreparedValidationNanos.sumThenReset(),
                metrics.backendQueueInstanceCaptureNanos.sumThenReset(),
                metrics.backendQueueInsertionAndPaletteNanos.sumThenReset());
    }

    public record BackendQueueTimings(
            long count,
            long prepareCalls,
            long prepareReuseHits,
            long selectorNanos,
            long precheckNanos,
            long prepareNanos,
            long preparedValidationNanos,
            long instanceCaptureNanos,
            long insertionAndPaletteNanos) {
        public static BackendQueueTimings zero() {
            return new BackendQueueTimings(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    public record InterceptTimings(
            long count,
            long interceptTotalNanos,
            long pipelineValidationNanos,
            long topologyAndMeshLookupNanos,
            long posePreparationNanos,
            long materialCaptureNanos,
            long backendQueueNanos) {
        public static InterceptTimings zero() {
            return new InterceptTimings(0, 0, 0, 0, 0, 0, 0);
        }
    }

    public BenchmarkMetrics benchmarkSnapshotAndReset() {
        long drawn = metrics.drawnInstances.sumThenReset(),
                calls = metrics.drawCalls.sumThenReset(),
                batchableInstances = metrics.batchableInstances.sumThenReset(),
                batchableCalls = metrics.batchableDrawCalls.sumThenReset(),
                sortedInstances = metrics.sortedInstances.sumThenReset(),
                sortedCalls = metrics.sortedDrawCalls.sumThenReset(),
                multiInstances = metrics.totalInstancesInMultiDraws.sumThenReset();
        int maximum = metrics.maximumInstancesPerDraw.getAndSet(0);
        return new BenchmarkMetrics(
                metrics.acceptedInvocations.sumThenReset(),
                metrics.productionReplacementAccepts.sumThenReset(),
                metrics.vanillaSuppressions.sumThenReset(),
                metrics.queuedInstances.sumThenReset(),
                drawn,
                calls,
                batchableInstances,
                batchableCalls,
                sortedInstances,
                sortedCalls,
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
                metrics.posePaletteBypasses.sumThenReset(),
                metrics.directPackedPosePalettes.sumThenReset(),
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
            long batchableInstances,
            long batchableDrawCalls,
            long sortedInstances,
            long sortedDrawCalls,
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
            long posePaletteBypasses,
            long directPackedPosePalettes,
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
            return BatchMetricMath.instancesPerDraw(batchableInstances, batchableDrawCalls);
        }

        public double drawReductionRatio() {
            return BatchMetricMath.drawReduction(batchableInstances, batchableDrawCalls);
        }

        public double multiInstanceCoverage() {
            return BatchMetricMath.multiCoverage(totalInstancesInMultiDraws, batchableInstances);
        }

        public static BenchmarkMetrics zero() {
            return new BenchmarkMetrics(
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    public String metricsSnapshot() {
        long drawn = metrics.drawnInstances.sumThenReset(),
                calls = metrics.drawCalls.sumThenReset(),
                batchableInstances = metrics.batchableInstances.sumThenReset(),
                batchableCalls = metrics.batchableDrawCalls.sumThenReset(),
                sortedInstances = metrics.sortedInstances.sumThenReset(),
                sortedCalls = metrics.sortedDrawCalls.sumThenReset(),
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
                + ",batchableInstances=" + batchableInstances + ",batchableDrawCalls=" + batchableCalls
                + ",sortedInstances=" + sortedInstances + ",sortedDrawCalls=" + sortedCalls
                + ",consolidatedBatches=" + metrics.consolidatedBatches.sumThenReset() + ",singletonBatches="
                + metrics.singletonBatches.sumThenReset() + ",multiInstanceBatches="
                + metrics.multiInstanceBatches.sumThenReset() + ",maximumInstancesPerDraw=" + maximum
                + ",totalInstancesInMultiDraws=" + multiInstances + ",instancesPerDraw="
                + BatchMetricMath.instancesPerDraw(batchableInstances, batchableCalls) + ",drawReductionRatio="
                + BatchMetricMath.drawReduction(batchableInstances, batchableCalls) + ",multiInstanceCoverage="
                + BatchMetricMath.multiCoverage(multiInstances, batchableInstances) + ",instanceUploadCalls="
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
