package dev.alex.threadium.render.entity;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.alex.threadium.ThreadiumClient;
import dev.alex.threadium.config.ThreadiumConfig;
import dev.alex.threadium.config.ThreadiumRuntimeConfig;
import dev.alex.threadium.mixin.accessor.OutlineVertexConsumerAccessor;
import dev.alex.threadium.mixin.accessor.OverlayVertexConsumerAccessor;
import dev.alex.threadium.mixin.accessor.VertexConsumersDualAccessor;
import dev.alex.threadium.render.modelpart.AdaptiveModelPartBatchGate;
import dev.alex.threadium.render.modelpart.ModelPart1211Metrics;
import dev.alex.threadium.render.modelpart.ModelPart1211Metrics.FallbackReason;
import dev.alex.threadium.render.modelpart.ModelPartDecalTransform1211;
import dev.alex.threadium.render.modelpart.ModelPartGpuInstanceBackend;
import dev.alex.threadium.render.modelpart.material.MaterialContextResolution;
import dev.alex.threadium.render.modelpart.material.MaterialProviderSource;
import dev.alex.threadium.render.modelpart.material.ModelPartMaterialContextTracker;
import dev.alex.threadium.render.modelpart.material.RenderLayer1211Descriptor;
import dev.alex.threadium.render.modelpart.pose.ImmutableModelPartBonePose;
import dev.alex.threadium.render.modelpart.pose.ImmutableRootRenderTransform;
import dev.alex.threadium.render.modelpart.pose.ModelPartInvocationSnapshot;
import dev.alex.threadium.render.modelpart.pose.ModelPartPoseInspector;
import dev.alex.threadium.render.modelpart.replay.ModelPartVertexReplayCommitter;
import dev.alex.threadium.render.modelpart.replay.PreparedModelPartReplay;
import dev.alex.threadium.render.modelpart.structure.ImmutableModelPartMesh;
import dev.alex.threadium.render.modelpart.structure.ModelPartStructureInspector;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.OverlayVertexConsumer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;

/** Render-thread-owned fail-closed Minecraft 1.21.1 ModelPart replacement service. */
public final class ModelPartReplacementService {
    private static volatile ModelPartReplacementService instance;

    private final ModelPartStructureInspector structureInspector = new ModelPartStructureInspector();
    private final ModelPartPoseInspector poseInspector = new ModelPartPoseInspector();
    private final ModelPartMaterialContextTracker<Object, RenderLayer, VertexConsumer> materialTracker =
            new ModelPartMaterialContextTracker<>();
    private final AtomicLong pendingWorldInvalidations = new AtomicLong();
    private final AtomicLong pendingResourceInvalidations = new AtomicLong();
    private final AtomicBoolean pendingShutdown = new AtomicBoolean();
    private final AtomicBoolean pendingConfigurationReload = new AtomicBoolean();
    private final AtomicLong replacementAttempts = new AtomicLong();
    private final AtomicLong replacementAccepts = new AtomicLong();
    private final AtomicLong replacementFallbacks = new AtomicLong();
    private final AtomicLong replacementFailures = new AtomicLong();
    private final ModelPart1211Metrics metrics = new ModelPart1211Metrics();
    private final AdaptiveModelPartBatchGate batchProfitability = new AdaptiveModelPartBatchGate();
    private final ArrayDeque<Object> renderGroupOwners = new ArrayDeque<>();
    private ModelPartGpuInstanceBackend gpuBackend;
    private long worldGeneration;
    private long resourceGeneration;
    private int renderDepth;
    private int minimumGroupSubmits = 1;
    private long metricsOutputIntervalNanos = 30_000_000_000L;
    private long lastMetricsReportNanos = System.nanoTime();
    private long appliedConfigurationRevision = -1;
    private boolean runtimeEnabled;
    private boolean replacementLogged;
    private boolean failureLogged;

    private ModelPartReplacementService() {}

    public static synchronized void initialize() {
        if (instance == null) {
            instance = new ModelPartReplacementService();
            instance.applyConfiguration();
        }
    }

    public static boolean configured() {
        return ThreadiumRuntimeConfig.current().replacementEnabled();
    }

    public static boolean gpuConfigured() {
        return ThreadiumRuntimeConfig.current().gpuReplacementEnabled() && ModelPartGpuInstanceBackend.configured();
    }

    public static void beginFrame() {
        ModelPartReplacementService current = instance;
        if (current == null) return;
        current.requireRenderThread();
        if (current.pendingConfigurationReload.getAndSet(false)
                || current.appliedConfigurationRevision != ThreadiumRuntimeConfig.revision()) {
            current.applyConfiguration();
        }
        if (current.pendingShutdown.getAndSet(false)) {
            current.shutdownOnRenderThread();
            return;
        }
        long worlds = current.pendingWorldInvalidations.getAndSet(0);
        long resources = current.pendingResourceInvalidations.getAndSet(0);
        if (worlds != 0 || resources != 0) {
            current.worldGeneration = Math.addExact(current.worldGeneration, worlds);
            current.resourceGeneration = Math.addExact(current.resourceGeneration, resources);
            current.destroyState(false);
        } else if (current.gpuBackend != null) {
            try {
                current.gpuBackend.verifyFrameDrained();
            } catch (RuntimeException exception) {
                current.disableAfterFailure("GPU flush boundary validation", exception);
            }
        }
        current.reportMetricsIfDue();
        current.renderDepth = 0;
        current.renderGroupOwners.clear();
        current.batchProfitability.beginFrame();
        current.poseInspector.beginFrame();
        current.materialTracker.beginFrame();
    }

    public static void observeMaterialProviderRequest(
            MaterialProviderSource source, Object provider, RenderLayer layer, VertexConsumer consumer) {
        ModelPartReplacementService current = instance;
        if (current == null || !current.runtimeEnabled || !current.isRenderThread()) return;
        current.metrics.recordMaterialProviderRequest();
        try {
            current.materialTracker.register(provider, layer, consumer, source);
        } catch (RuntimeException exception) {
            current.disableAfterFailure("material provider registration", exception);
        }
    }

    public static void beginRenderGroup(Object groupOwner) {
        ModelPartReplacementService current = instance;
        if (current == null || !current.runtimeEnabled || groupOwner == null || !current.isRenderThread()) return;
        current.renderGroupOwners.addLast(groupOwner);
        current.metrics.recordGroupBegin();
    }

    public static void endRenderGroup(Object groupOwner) {
        ModelPartReplacementService current = instance;
        if (current == null || !current.runtimeEnabled || groupOwner == null || !current.isRenderThread()) return;
        if (current.renderGroupOwners.isEmpty() || current.renderGroupOwners.getLast() != groupOwner) {
            current.renderGroupOwners.clear();
            current.metrics.recordGroupScopeFailure();
            current.disableAfterFailure(
                    "feature renderer group scope",
                    new IllegalStateException("Threadium observed an unbalanced Minecraft 1.21.1 render group"));
            return;
        }
        current.renderGroupOwners.removeLast();
        current.metrics.recordGroupEnd();
    }

    public static boolean beginModelPartRender(
            ModelPart root, MatrixStack matrices, VertexConsumer consumer, int light, int overlay, int color) {
        ModelPartReplacementService current = instance;
        if (current == null || !current.runtimeEnabled) return false;
        if (current.renderDepth++ != 0) return false;
        return current.tryReplace(root, matrices, consumer, light, overlay, color);
    }

    public static void endModelPartRender() {
        ModelPartReplacementService current = instance;
        if (current == null || current.renderDepth == 0) return;
        current.renderDepth--;
    }

    public static void flushProviderLayer(Object provider, RenderLayer layer) {
        ModelPartReplacementService current = instance;
        if (current == null || !current.runtimeEnabled || current.gpuBackend == null) return;
        try {
            var feedback =
                    current.gpuBackend.flush(provider, layer, current.worldGeneration, current.resourceGeneration);
            for (var entry : feedback.entrySet()) {
                current.batchProfitability.recordFlush(entry.getKey(), entry.getValue());
            }
        } catch (RuntimeException exception) {
            current.disableAfterFailure("GPU upload or draw", exception);
        }
    }

    public static void requestConfigurationReload() {
        ModelPartReplacementService current = instance;
        if (current != null) current.pendingConfigurationReload.set(true);
    }

    public static void invalidateWorld() {
        ModelPartReplacementService current = instance;
        if (current != null) current.pendingWorldInvalidations.incrementAndGet();
    }

    public static void invalidateResources() {
        ModelPartReplacementService current = instance;
        if (current != null) current.pendingResourceInvalidations.incrementAndGet();
    }

    public static void shutdown() {
        ModelPartReplacementService current = instance;
        if (current == null) return;
        if (current.isRenderThread()) current.shutdownOnRenderThread();
        else current.pendingShutdown.set(true);
    }

    public static Diagnostics diagnostics() {
        ModelPartReplacementService current = instance;
        ModelPartGpuInstanceBackend.Diagnostics gpu = current == null || current.gpuBackend == null
                ? new ModelPartGpuInstanceBackend.Diagnostics(
                        dev.alex.threadium.render.modelpart.ModelPartBackendState.UNINITIALIZED,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0)
                : current.gpuBackend.diagnostics();
        if (current == null) {
            ModelPartMaterialContextTracker<Object, RenderLayer, VertexConsumer> tracker =
                    new ModelPartMaterialContextTracker<>();
            return new Diagnostics(
                    configured(),
                    gpuConfigured(),
                    false,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    tracker.diagnostics(),
                    new ModelPart1211Metrics().snapshot(),
                    gpu);
        }
        return new Diagnostics(
                configured(),
                gpuConfigured(),
                current.runtimeEnabled,
                current.replacementAttempts.get(),
                current.replacementAccepts.get(),
                current.replacementFallbacks.get(),
                current.replacementFailures.get(),
                current.renderGroupOwners.size(),
                current.structureInspector.cachedRootCount(),
                current.structureInspector.uniqueMeshCount(),
                current.structureInspector.retainedMeshBytes(),
                current.poseInspector.uniquePoseCount(),
                current.poseInspector.retainedPoseBytes(),
                current.materialTracker.diagnostics(),
                current.metrics.snapshot(),
                gpu);
    }

    public static ModelPart1211Metrics.Snapshot metricsSnapshot() {
        ModelPartReplacementService current = instance;
        return current == null ? new ModelPart1211Metrics().snapshot() : current.metrics.snapshot();
    }

    public static ModelPart1211Metrics.Snapshot metricsSnapshotAndReset() {
        ModelPartReplacementService current = instance;
        return current == null ? new ModelPart1211Metrics().snapshot() : current.metrics.snapshotAndReset();
    }

    private boolean tryReplace(
            ModelPart root, MatrixStack matrices, VertexConsumer consumer, int light, int overlay, int color) {
        replacementAttempts.incrementAndGet();
        metrics.recordReplacementAttempt();
        long interceptStart = metrics.now();
        long materialNanos = 0L;
        long meshNanos = 0L;
        long poseNanos = 0L;
        long queueNanos = 0L;
        try {
            if (!isRenderThread()) return fallback(FallbackReason.UNSCOPED, null);

            boolean gpuReplacement = gpuConfigured();
            Object groupOwner = currentRenderGroupOwner();
            if (gpuReplacement && groupOwner == null) return fallback(FallbackReason.UNSCOPED, null);

            MaterialPath materialPath;
            ImmutableModelPartMesh mesh;
            ImmutableRootRenderTransform rootTransform;
            ImmutableModelPartBonePose pose;
            try {
                long materialStart = metrics.now();
                materialPath = resolveMaterialPath(consumer);
                materialNanos = metrics.delta(materialStart);
                if (materialPath == null) return fallback(FallbackReason.MATERIAL, null);
                if (gpuReplacement
                        && !batchProfitability.observeAndShouldReplace(
                                groupOwner, root, materialPath.batchType(), minimumGroupSubmits)) {
                    return fallback(FallbackReason.ADAPTIVE, materialPath);
                }

                long meshStart = metrics.now();
                mesh = structureInspector.cached(root);
                metrics.recordStructureLookup(mesh != null);
                if (mesh == null) mesh = structureInspector.captureAndCache(root);
                meshNanos = metrics.delta(meshStart);

                long poseStart = metrics.now();
                rootTransform = poseInspector.captureRoot(matrices.peek());
                ModelPartPoseInspector.PoseObservation observation = poseInspector.capturePose(root, mesh);
                pose = observation.pose();
                metrics.recordPoseObservation(
                        observation.lookupPerformed(), observation.poseCacheHit(), observation.directPacked());
                poseNanos = metrics.delta(poseStart);
                if (!pose.finite() || !rootTransform.finite() || pose.boneCount() != mesh.partCount()) {
                    throw new IllegalArgumentException(
                            "ModelPart instance input is not finite or structurally aligned");
                }
            } catch (RuntimeException exception) {
                replacementFailures.incrementAndGet();
                metrics.recordFailure();
                warnFailureOnce("preparation", exception);
                return fallback(FallbackReason.PREPARATION, null);
            }

            if (pose.drawVisibleCount() == 0) {
                return accept("empty validated instance", true, false, false);
            }
            long queueStart = metrics.now();
            if (materialPath.crumbling != null) {
                if (!gpuReplacement) return fallback(FallbackReason.BACKEND_UNAVAILABLE, materialPath);
                try {
                    ModelPartGpuInstanceBackend backend = gpuBackend();
                    boolean queued = materialPath.base == null
                            ? backend.queue(
                                    groupOwner,
                                    materialPath.crumbling.provider,
                                    materialPath.crumbling.descriptor,
                                    mesh,
                                    pose,
                                    rootTransform,
                                    light,
                                    overlay,
                                    color,
                                    materialPath.decal,
                                    worldGeneration,
                                    resourceGeneration)
                            : backend.queuePair(
                                    groupOwner,
                                    materialPath.base.provider,
                                    materialPath.base.descriptor,
                                    materialPath.crumbling.provider,
                                    materialPath.crumbling.descriptor,
                                    mesh,
                                    pose,
                                    rootTransform,
                                    light,
                                    overlay,
                                    color,
                                    materialPath.decal,
                                    worldGeneration,
                                    resourceGeneration);
                    queueNanos = metrics.delta(queueStart);
                    if (!queued) return fallback(FallbackReason.CAPACITY, materialPath);
                    return accept(
                            materialPath.base == null
                                    ? "Threadium-owned crumbling decal queue"
                                    : "Threadium-owned atomic base and crumbling queues",
                            false,
                            true,
                            false);
                } catch (RuntimeException exception) {
                    disableAfterFailure("crumbling GPU queue commitment", exception);
                    return false;
                }
            }

            if (materialPath.outline != null) {
                if (!gpuReplacement) return fallback(FallbackReason.BACKEND_UNAVAILABLE, materialPath);
                try {
                    ModelPartGpuInstanceBackend backend = gpuBackend();
                    MaterialBinding outline = materialPath.outline.material;
                    boolean queued = materialPath.base == null
                            ? backend.queue(
                                    groupOwner,
                                    outline.provider,
                                    outline.descriptor,
                                    mesh,
                                    pose,
                                    rootTransform,
                                    light,
                                    overlay,
                                    materialPath.outline.color,
                                    worldGeneration,
                                    resourceGeneration)
                            : backend.queueOutlinePair(
                                    groupOwner,
                                    materialPath.base.provider,
                                    materialPath.base.descriptor,
                                    outline.provider,
                                    outline.descriptor,
                                    mesh,
                                    pose,
                                    rootTransform,
                                    light,
                                    overlay,
                                    color,
                                    materialPath.outline.color,
                                    worldGeneration,
                                    resourceGeneration);
                    queueNanos = metrics.delta(queueStart);
                    if (!queued) return fallback(FallbackReason.CAPACITY, materialPath);
                    return accept(
                            materialPath.base == null
                                    ? "Threadium-owned outline queue"
                                    : "Threadium-owned atomic base and outline queues",
                            false,
                            true,
                            false);
                } catch (RuntimeException exception) {
                    disableAfterFailure("outline GPU queue commitment", exception);
                    return false;
                }
            }

            MaterialBinding material = materialPath.base;
            if (gpuReplacement) {
                try {
                    if (!gpuBackend()
                            .queue(
                                    groupOwner,
                                    material.provider,
                                    material.descriptor,
                                    mesh,
                                    pose,
                                    rootTransform,
                                    light,
                                    overlay,
                                    color,
                                    worldGeneration,
                                    resourceGeneration)) {
                        queueNanos = metrics.delta(queueStart);
                        return fallback(FallbackReason.CAPACITY, materialPath);
                    }
                    queueNanos = metrics.delta(queueStart);
                    return accept("Threadium-owned GPU instance queue", false, true, false);
                } catch (RuntimeException exception) {
                    disableAfterFailure("GPU instance queue commitment", exception);
                    return false;
                }
            }

            try {
                ModelPartInvocationSnapshot invocation = new ModelPartInvocationSnapshot(
                        mesh, pose, rootTransform, light, overlay, color, worldGeneration, resourceGeneration);
                PreparedModelPartReplay replay = PreparedModelPartReplay.prepare(invocation);
                ModelPartVertexReplayCommitter.commit(replay, consumer);
                queueNanos = metrics.delta(queueStart);
            } catch (RuntimeException exception) {
                disableAfterFailure("destination commit", exception);
                return true;
            }
            return accept("validated cached vertex replay", false, false, true);
        } finally {
            metrics.recordInterceptTiming(
                    metrics.delta(interceptStart), materialNanos, meshNanos, poseNanos, queueNanos);
        }
    }

    private MaterialPath resolveMaterialPath(VertexConsumer consumer) {
        if (consumer instanceof OverlayVertexConsumer overlay) {
            CrumblingBinding crumbling = resolveCrumbling(overlay);
            return crumbling == null ? null : new MaterialPath(null, crumbling.material, null, crumbling.decal);
        }
        if (consumer instanceof OutlineVertexConsumerAccessor) {
            OutlineBinding outline = resolveOutline(consumer);
            return outline == null ? null : new MaterialPath(null, null, outline, null);
        }
        if (consumer instanceof VertexConsumersDualAccessor dual) {
            VertexConsumer first = dual.threadium$getFirst();
            VertexConsumer second = dual.threadium$getSecond();
            boolean firstCrumbling = first instanceof OverlayVertexConsumer;
            boolean secondCrumbling = second instanceof OverlayVertexConsumer;
            boolean firstOutline = first instanceof OutlineVertexConsumerAccessor;
            boolean secondOutline = second instanceof OutlineVertexConsumerAccessor;
            if (firstCrumbling != secondCrumbling && !firstOutline && !secondOutline) {
                OverlayVertexConsumer overlay = (OverlayVertexConsumer) (firstCrumbling ? first : second);
                VertexConsumer baseConsumer = firstCrumbling ? second : first;
                CrumblingBinding crumbling = resolveCrumbling(overlay);
                MaterialBinding base = resolveDirect(baseConsumer);
                if (crumbling == null
                        || base == null
                        || base.descriptor.kind() == RenderLayer1211Descriptor.Kind.CRUMBLING) {
                    return null;
                }
                return new MaterialPath(base, crumbling.material, null, crumbling.decal);
            }
            if (firstOutline != secondOutline && !firstCrumbling && !secondCrumbling) {
                VertexConsumer outlineConsumer = firstOutline ? first : second;
                VertexConsumer baseConsumer = firstOutline ? second : first;
                OutlineBinding outline = resolveOutline(outlineConsumer);
                MaterialBinding base = resolveDirect(baseConsumer);
                if (outline == null || base == null || isOutline(base.descriptor)) return null;
                return new MaterialPath(base, null, outline, null);
            }
            return null;
        }
        MaterialBinding direct = resolveDirect(consumer);
        if (direct == null
                || direct.descriptor.kind() == RenderLayer1211Descriptor.Kind.CRUMBLING
                || isOutline(direct.descriptor)
                || !ModelPartVertexReplayCommitter.supports(consumer)) {
            return null;
        }
        return new MaterialPath(direct, null, null, null);
    }

    private MaterialBinding resolveDirect(VertexConsumer consumer) {
        MaterialContextResolution<Object, RenderLayer> resolution = materialTracker.observeResolution(consumer);
        RenderLayer1211Descriptor descriptor = RenderLayer1211Descriptor.inspect(resolution);
        return descriptor.replacementSafe() ? new MaterialBinding(resolution.provider(), descriptor) : null;
    }

    private CrumblingBinding resolveCrumbling(OverlayVertexConsumer overlay) {
        OverlayVertexConsumerAccessor accessor = (OverlayVertexConsumerAccessor) overlay;
        MaterialBinding material = resolveDirect(accessor.threadium$getDelegate());
        if (material == null || material.descriptor.kind() != RenderLayer1211Descriptor.Kind.CRUMBLING) return null;
        ModelPartDecalTransform1211 decal = ModelPartDecalTransform1211.capture(
                accessor.threadium$getInverseTextureMatrix(),
                accessor.threadium$getInverseNormalMatrix(),
                accessor.threadium$getTextureScale());
        return decal.finite() ? new CrumblingBinding(material, decal) : null;
    }

    private OutlineBinding resolveOutline(VertexConsumer consumer) {
        if (!(consumer instanceof OutlineVertexConsumerAccessor accessor)) return null;
        MaterialContextResolution<Object, RenderLayer> resolution =
                materialTracker.observeResolution(accessor.threadium$getDelegate());
        RenderLayer1211Descriptor descriptor = RenderLayer1211Descriptor.inspectOutline(resolution);
        if (!descriptor.replacementSafe() || !isOutline(descriptor)) return null;
        return new OutlineBinding(
                new MaterialBinding(resolution.provider(), descriptor), accessor.threadium$getColor());
    }

    private static boolean isOutline(RenderLayer1211Descriptor descriptor) {
        return descriptor.kind() == RenderLayer1211Descriptor.Kind.OUTLINE_CULL
                || descriptor.kind() == RenderLayer1211Descriptor.Kind.OUTLINE_NO_CULL;
    }

    private Object currentRenderGroupOwner() {
        return renderGroupOwners.isEmpty() ? null : renderGroupOwners.getLast();
    }

    private boolean accept(String path, boolean empty, boolean gpu, boolean cpuReplay) {
        replacementAccepts.incrementAndGet();
        metrics.recordAccepted(empty, gpu, cpuReplay);
        if (!replacementLogged) {
            replacementLogged = true;
            ThreadiumClient.LOGGER.info("Threadium replaced a Minecraft 1.21.1 ModelPart draw through {}", path);
        }
        return true;
    }

    private boolean fallback(FallbackReason reason, MaterialPath materialPath) {
        replacementFallbacks.incrementAndGet();
        metrics.recordFallback(reason);
        if (materialPath != null) materialPath.recordPipelineFallback(metrics);
        else if (reason == FallbackReason.MATERIAL) {
            metrics.recordPipelineFallback(RenderLayer1211Descriptor.Kind.UNSUPPORTED);
        }
        return false;
    }

    private ModelPartGpuInstanceBackend gpuBackend() {
        if (gpuBackend == null) gpuBackend = new ModelPartGpuInstanceBackend(metrics);
        return gpuBackend;
    }

    private void disableAfterFailure(String stage, RuntimeException exception) {
        runtimeEnabled = false;
        replacementFailures.incrementAndGet();
        metrics.recordFailure();
        warnFailureOnce(stage, exception);
        if (gpuBackend != null && isRenderThread()) gpuBackend.reset();
    }

    private void warnFailureOnce(String stage, RuntimeException exception) {
        if (failureLogged) return;
        failureLogged = true;
        ThreadiumClient.LOGGER.warn(
                "Threadium disabled the experimental Minecraft 1.21.1 replacement path after {} failed; unsupported draws remain Vanilla",
                stage,
                exception);
    }

    private void shutdownOnRenderThread() {
        requireRenderThread();
        destroyState(true);
        runtimeEnabled = false;
        synchronized (ModelPartReplacementService.class) {
            if (instance == this) instance = null;
        }
    }

    private void destroyState(boolean closeGpu) {
        structureInspector.clear();
        poseInspector.clear();
        materialTracker.clearLifecycle();
        batchProfitability.clear();
        renderGroupOwners.clear();
        renderDepth = 0;
        if (gpuBackend == null) return;
        if (closeGpu) {
            gpuBackend.close();
            gpuBackend = null;
        } else {
            gpuBackend.reset();
        }
    }

    private void reportMetricsIfDue() {
        if (!metrics.enabled()) return;
        long now = System.nanoTime();
        if (now - lastMetricsReportNanos < metricsOutputIntervalNanos) return;
        lastMetricsReportNanos = now;
        ModelPart1211Metrics.Snapshot snapshot = metrics.snapshotAndReset();
        ModelPartMaterialContextTracker.Diagnostics material = materialTracker.diagnostics();
        ModelPartGpuInstanceBackend.Diagnostics gpu = gpuBackend == null
                ? new ModelPartGpuInstanceBackend.Diagnostics(
                        dev.alex.threadium.render.modelpart.ModelPartBackendState.UNINITIALIZED,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0)
                : gpuBackend.diagnostics();
        ThreadiumClient.LOGGER.info(
                "Minecraft 1.21.1 interval metrics: worldGeneration={}, resourceGeneration={}, renderGroupDepth={}, cachedRoots={}, uniqueMeshes={}, retainedMeshBytes={}, uniquePoses={}, retainedPoseBytes={}, materialRequests={}, materialResolutions={}, materialUnresolved={}, materialCapacityRejections={}, materialTrackingFailures={}, gpuState={}, queuedInstances={}, uploadedMeshes={}, {}",
                worldGeneration,
                resourceGeneration,
                renderGroupOwners.size(),
                structureInspector.cachedRootCount(),
                structureInspector.uniqueMeshCount(),
                structureInspector.retainedMeshBytes(),
                poseInspector.uniquePoseCount(),
                poseInspector.retainedPoseBytes(),
                material.materialProviderRequests(),
                material.materialResolutionAttempts(),
                material.materialUnresolvedResolutions(),
                material.materialCapacityRejections(),
                material.materialTrackingFailures(),
                gpu.state(),
                gpu.queuedInstances(),
                gpu.uploadedMeshes(),
                snapshot.describe());
    }

    private void applyConfiguration() {
        ThreadiumConfig config = ThreadiumRuntimeConfig.current();
        appliedConfigurationRevision = ThreadiumRuntimeConfig.revision();
        runtimeEnabled = config.replacementEnabled();
        minimumGroupSubmits = config.gpuMinimumGroupSubmits();
        metrics.configure(config.metricsEnabled());
        metricsOutputIntervalNanos = Math.multiplyExact(config.metricsOutputIntervalSeconds(), 1_000_000_000L);
        lastMetricsReportNanos = System.nanoTime();
        structureInspector.clear();
        poseInspector.clear();
        materialTracker.clearLifecycle();
        batchProfitability.clear();
        renderGroupOwners.clear();
        renderDepth = 0;
        if (gpuBackend != null) {
            if (isRenderThread()) gpuBackend.close();
            gpuBackend = null;
        }
        if (config.debugLogging()) {
            ThreadiumClient.LOGGER.info(
                    "Applied Threadium 1.21.1 config revision {}: replacement={}, gpu={}, backend={}, consolidation={}",
                    appliedConfigurationRevision,
                    runtimeEnabled,
                    config.gpuReplacementEnabled(),
                    config.gpuBackend(),
                    config.gpuBatchConsolidation());
        }
    }

    private boolean isRenderThread() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null && client.isOnThread() && RenderSystem.isOnRenderThread();
    }

    private void requireRenderThread() {
        if (!isRenderThread()) {
            throw new IllegalStateException("Threadium replacement state is not on the Render Thread");
        }
    }

    private record MaterialBinding(Object provider, RenderLayer1211Descriptor descriptor) {}

    private record CrumblingBinding(MaterialBinding material, ModelPartDecalTransform1211 decal) {}

    private record OutlineBinding(MaterialBinding material, int color) {}

    private record MaterialPath(
            MaterialBinding base,
            MaterialBinding crumbling,
            OutlineBinding outline,
            ModelPartDecalTransform1211 decal) {
        private RenderLayer batchType() {
            if (base != null) return base.descriptor.layer();
            if (crumbling != null) return crumbling.descriptor.layer();
            return outline == null ? null : outline.material.descriptor.layer();
        }

        private void recordPipelineFallback(ModelPart1211Metrics metrics) {
            if (base != null) metrics.recordPipelineFallback(base.descriptor.kind());
            if (crumbling != null) metrics.recordPipelineFallback(crumbling.descriptor.kind());
            if (outline != null) metrics.recordPipelineFallback(outline.material.descriptor.kind());
        }
    }

    public record Diagnostics(
            boolean configured,
            boolean gpuConfigured,
            boolean runtimeEnabled,
            long replacementAttempts,
            long replacementAccepts,
            long replacementFallbacks,
            long replacementFailures,
            int renderGroupDepth,
            int cachedRoots,
            int uniqueMeshes,
            long retainedMeshBytes,
            int uniquePoses,
            long retainedPoseBytes,
            ModelPartMaterialContextTracker.Diagnostics material,
            ModelPart1211Metrics.Snapshot metrics,
            ModelPartGpuInstanceBackend.Diagnostics gpu) {}
}
