package dev.alex.threadium.render.entity;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.alex.threadium.ThreadiumClient;
import dev.alex.threadium.render.modelpart.material.MaterialContextResolution;
import dev.alex.threadium.render.modelpart.material.MaterialResolutionStatus;
import dev.alex.threadium.render.modelpart.material.ModelPartMaterialContextTracker;
import dev.alex.threadium.render.modelpart.pose.ImmutableModelPartBonePose;
import dev.alex.threadium.render.modelpart.pose.ImmutableRootRenderTransform;
import dev.alex.threadium.render.modelpart.pose.ModelPartInvocationSnapshot;
import dev.alex.threadium.render.modelpart.pose.ModelPartPoseCapacityException;
import dev.alex.threadium.render.modelpart.pose.ModelPartPoseInspector;
import dev.alex.threadium.render.modelpart.structure.ImmutableModelPartMesh;
import dev.alex.threadium.render.modelpart.structure.ModelPartMeshCapacityException;
import dev.alex.threadium.render.modelpart.structure.ModelPartStructureInspector;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;

/** M3A CPU and material-context observer. It never acquires rendering ownership or suppresses Vanilla rendering. */
public final class PassThroughEntityRenderService {
    private static volatile PassThroughEntityRenderService instance;

    private final AtomicLong observedModelPartRenders = new AtomicLong();
    private final AtomicLong worldGeneration = new AtomicLong();
    private final AtomicLong resourceGeneration = new AtomicLong();
    private final AtomicLong structureInspections = new AtomicLong();
    private final AtomicLong structureCacheHits = new AtomicLong();
    private final AtomicLong structureCacheMisses = new AtomicLong();
    private final AtomicLong structureInspectionFailures = new AtomicLong();
    private final AtomicLong structureCapacityRejections = new AtomicLong();
    private final AtomicLong meshCaptureAttempts = new AtomicLong();
    private final AtomicLong meshCapturesCompleted = new AtomicLong();
    private final AtomicLong meshCaptureFailures = new AtomicLong();
    private final AtomicLong meshCaptureCapacityRejections = new AtomicLong();
    private final AtomicLong meshRootCacheHits = new AtomicLong();
    private final AtomicLong meshRootCacheMisses = new AtomicLong();
    private final AtomicLong uniqueMeshes = new AtomicLong();
    private final AtomicLong lastCapturedPartCount = new AtomicLong();
    private final AtomicLong lastCapturedQuadCount = new AtomicLong();
    private final AtomicLong lastCapturedVertexCount = new AtomicLong();
    private final AtomicLong lastCapturedIndexCount = new AtomicLong();
    private final AtomicLong retainedMeshBytes = new AtomicLong();
    private final AtomicLong poseCaptureAttempts = new AtomicLong();
    private final AtomicLong poseCapturesCompleted = new AtomicLong();
    private final AtomicLong poseCaptureFailures = new AtomicLong();
    private final AtomicLong poseCapacityRejections = new AtomicLong();
    private final AtomicLong poseCacheHits = new AtomicLong();
    private final AtomicLong poseCacheMisses = new AtomicLong();
    private final AtomicLong uniquePosesThisFrame = new AtomicLong();
    private final AtomicLong lastPoseBoneCount = new AtomicLong();
    private final AtomicLong lastTreeVisibleBoneCount = new AtomicLong();
    private final AtomicLong lastDrawVisibleBoneCount = new AtomicLong();
    private final AtomicLong lastPoseBytes = new AtomicLong();
    private final AtomicLong retainedPoseBytesThisFrame = new AtomicLong();
    private final AtomicLong nonFinitePoseCaptures = new AtomicLong();
    private final AtomicLong rootTransformCaptures = new AtomicLong();
    private final ModelPartStructureInspector structureInspector = new ModelPartStructureInspector();
    private final ModelPartPoseInspector poseInspector = new ModelPartPoseInspector();
    private final ModelPartMaterialContextTracker<Object, RenderLayer, VertexConsumer> materialTracker =
            new ModelPartMaterialContextTracker<>();
    private ModelPartInvocationSnapshot lastInvocation;
    private MaterialContextResolution<Object, RenderLayer> lastMaterialResolution;
    private int renderDepth;
    private boolean meshCaptureLogged;
    private boolean meshCaptureFailureLogged;
    private boolean poseCaptureLogged;
    private boolean poseCaptureFailureLogged;
    private boolean directMaterialLogged;
    private boolean unresolvedMaterialLogged;
    private boolean materialCapacityLogged;
    private boolean materialFailureLogged;
    private volatile boolean enabled = true;

    private PassThroughEntityRenderService() {}

    public static synchronized void initialize() {
        if (instance == null) instance = new PassThroughEntityRenderService();
    }

    public static void beginFrame() {
        PassThroughEntityRenderService current = instance;
        if (current != null) current.resetFrameState();
    }

    public static void beginModelPartRender(
            ModelPart part, MatrixStack matrices, VertexConsumer vertexConsumer, int light, int overlay, int color) {
        PassThroughEntityRenderService current = instance;
        if (current == null || !current.enabled) return;
        if (current.observedModelPartRenders.getAndIncrement() == 0) {
            ThreadiumClient.LOGGER.info(
                    "Threadium detected Minecraft 1.21.1 ModelPart rendering; Vanilla pass-through remains active");
        }
        if (current.renderDepth++ != 0) return;
        current.captureInvocation(part, matrices, vertexConsumer, light, overlay, color);
    }

    /**
     * Called only from return injections; the exact Vanilla objects are observed and returned control flow is
     * untouched.
     */
    public static void observeMaterialProviderRequest(Object provider, RenderLayer layer, VertexConsumer consumer) {
        PassThroughEntityRenderService current = instance;
        if (current == null || !current.enabled) return;
        current.registerMaterialBinding(provider, layer, consumer);
    }

    public static void endModelPartRender() {
        PassThroughEntityRenderService current = instance;
        if (current == null || current.renderDepth == 0) return;
        current.renderDepth--;
    }

    public static void invalidateWorld() {
        PassThroughEntityRenderService current = instance;
        if (current == null) return;
        current.worldGeneration.incrementAndGet();
        current.resetInspectionState();
    }

    public static void invalidateResources() {
        PassThroughEntityRenderService current = instance;
        if (current == null) return;
        current.resourceGeneration.incrementAndGet();
        current.resetInspectionState();
    }

    public static void shutdown() {
        PassThroughEntityRenderService current = instance;
        if (current == null) return;
        current.enabled = false;
        current.resetInspectionState();
        instance = null;
    }

    public static Diagnostics diagnostics() {
        PassThroughEntityRenderService current = instance;
        ModelPartMaterialContextTracker.Diagnostics material = current == null
                ? new ModelPartMaterialContextTracker<>().diagnostics()
                : current.materialTracker.diagnostics();
        return current == null
                ? Diagnostics.disabled()
                : new Diagnostics(
                        current.enabled,
                        current.observedModelPartRenders.get(),
                        current.worldGeneration.get(),
                        current.resourceGeneration.get(),
                        current.structureInspections.get(),
                        current.structureCacheHits.get(),
                        current.structureCacheMisses.get(),
                        current.structureInspectionFailures.get(),
                        current.structureCapacityRejections.get(),
                        current.meshCaptureAttempts.get(),
                        current.meshCapturesCompleted.get(),
                        current.meshCaptureFailures.get(),
                        current.meshCaptureCapacityRejections.get(),
                        current.meshRootCacheHits.get(),
                        current.meshRootCacheMisses.get(),
                        current.uniqueMeshes.get(),
                        current.lastCapturedPartCount.get(),
                        current.lastCapturedQuadCount.get(),
                        current.lastCapturedVertexCount.get(),
                        current.lastCapturedIndexCount.get(),
                        current.retainedMeshBytes.get(),
                        current.poseCaptureAttempts.get(),
                        current.poseCapturesCompleted.get(),
                        current.poseCaptureFailures.get(),
                        current.poseCapacityRejections.get(),
                        current.poseCacheHits.get(),
                        current.poseCacheMisses.get(),
                        current.uniquePosesThisFrame.get(),
                        current.lastPoseBoneCount.get(),
                        current.lastTreeVisibleBoneCount.get(),
                        current.lastDrawVisibleBoneCount.get(),
                        current.lastPoseBytes.get(),
                        current.retainedPoseBytesThisFrame.get(),
                        current.nonFinitePoseCaptures.get(),
                        current.rootTransformCaptures.get(),
                        material);
    }

    private void captureInvocation(
            ModelPart root, MatrixStack matrices, VertexConsumer vertexConsumer, int light, int overlay, int color) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || !client.isOnThread() || !RenderSystem.isOnRenderThread()) {
            structureInspectionFailures.incrementAndGet();
            meshCaptureFailures.incrementAndGet();
            poseCaptureFailures.incrementAndGet();
            materialTracker.recordTrackingFailure();
            return;
        }

        resolveMaterial(vertexConsumer);
        ImmutableModelPartMesh mesh = captureStructure(root);
        if (mesh != null) capturePose(root, mesh, matrices, light, overlay, color);
    }

    private void registerMaterialBinding(Object provider, RenderLayer layer, VertexConsumer consumer) {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || !client.isOnThread() || !RenderSystem.isOnRenderThread()) {
                materialTracker.recordTrackingFailure();
                warnMaterialFailureOnce("provider observation occurred off the Render Thread", null);
                return;
            }
            ModelPartMaterialContextTracker.RegistrationResult result =
                    materialTracker.register(provider, layer, consumer);
            if (result == ModelPartMaterialContextTracker.RegistrationResult.CAPACITY_REJECTED) {
                warnMaterialCapacityOnce();
            }
        } catch (RuntimeException exception) {
            materialTracker.recordTrackingFailure();
            warnMaterialFailureOnce("provider observation failed", exception);
        }
    }

    private void resolveMaterial(VertexConsumer consumer) {
        lastMaterialResolution = null;
        try {
            MaterialContextResolution<Object, RenderLayer> resolution = materialTracker.observeResolution(consumer);
            lastMaterialResolution = resolution;
            if (resolution.status() == MaterialResolutionStatus.DIRECT_UNIQUE) {
                if (!directMaterialLogged) {
                    directMaterialLogged = true;
                    ThreadiumClient.LOGGER.info(
                            "Threadium directly associated a Minecraft 1.21.1 ModelPart VertexConsumer with a RenderLayer; Vanilla pass-through remains active");
                }
            } else if (resolution.status() == MaterialResolutionStatus.UNRESOLVED && !unresolvedMaterialLogged) {
                unresolvedMaterialLogged = true;
                ThreadiumClient.LOGGER.info(
                        "Threadium observed an unresolved Minecraft 1.21.1 ModelPart VertexConsumer ({}); Vanilla pass-through remains active",
                        resolution.unresolvedConsumerClass());
            } else if (resolution.status() == MaterialResolutionStatus.CAPACITY_REJECTED) {
                warnMaterialCapacityOnce();
            }
        } catch (RuntimeException exception) {
            materialTracker.recordTrackingFailure();
            warnMaterialFailureOnce("consumer resolution failed", exception);
        }
    }

    private void warnMaterialCapacityOnce() {
        if (materialCapacityLogged) return;
        materialCapacityLogged = true;
        ThreadiumClient.LOGGER.warn(
                "Threadium Minecraft 1.21.1 material-context capacity was reached; Vanilla pass-through remains active");
    }

    private void warnMaterialFailureOnce(String reason, RuntimeException exception) {
        if (materialFailureLogged) return;
        materialFailureLogged = true;
        String message =
                "Threadium Minecraft 1.21.1 material-context " + reason + "; Vanilla pass-through remains active";
        if (exception == null) ThreadiumClient.LOGGER.warn(message);
        else ThreadiumClient.LOGGER.warn(message, exception);
    }

    private ImmutableModelPartMesh captureStructure(ModelPart root) {

        ImmutableModelPartMesh mesh = structureInspector.cached(root);
        if (mesh != null) {
            structureCacheHits.incrementAndGet();
            meshRootCacheHits.incrementAndGet();
            recordMesh(mesh);
            return mesh;
        }

        structureCacheMisses.incrementAndGet();
        meshRootCacheMisses.incrementAndGet();
        if (!structureInspector.canCaptureNewRoot()) {
            structureCapacityRejections.incrementAndGet();
            meshCaptureCapacityRejections.incrementAndGet();
            return null;
        }

        meshCaptureAttempts.incrementAndGet();
        try {
            mesh = structureInspector.captureAndCache(root);
            structureInspections.incrementAndGet();
            meshCapturesCompleted.incrementAndGet();
            uniqueMeshes.set(structureInspector.uniqueMeshCount());
            retainedMeshBytes.set(structureInspector.retainedMeshBytes());
            recordMesh(mesh);
            return mesh;
        } catch (ModelPartMeshCapacityException exception) {
            structureCapacityRejections.incrementAndGet();
            meshCaptureCapacityRejections.incrementAndGet();
            warnCaptureFailureOnce("capacity limit reached", exception, false);
            return null;
        } catch (RuntimeException exception) {
            structureInspectionFailures.incrementAndGet();
            meshCaptureFailures.incrementAndGet();
            warnCaptureFailureOnce("capture failed", exception, true);
            return null;
        }
    }

    private void capturePose(
            ModelPart root, ImmutableModelPartMesh mesh, MatrixStack matrices, int light, int overlay, int color) {
        lastInvocation = null;
        poseCaptureAttempts.incrementAndGet();
        try {
            ImmutableRootRenderTransform rootTransform = poseInspector.captureRoot(matrices.peek());
            rootTransformCaptures.incrementAndGet();
            boolean rootIsFinite = rootTransform.finite();
            if (!rootIsFinite) nonFinitePoseCaptures.incrementAndGet();
            ModelPartPoseInspector.PoseObservation observation = poseInspector.capturePose(root, mesh);
            ImmutableModelPartBonePose pose = observation.pose();
            poseCapturesCompleted.incrementAndGet();
            if (observation.poseCacheHit()) poseCacheHits.incrementAndGet();
            else poseCacheMisses.incrementAndGet();
            uniquePosesThisFrame.set(poseInspector.uniquePoseCount());
            retainedPoseBytesThisFrame.set(poseInspector.retainedPoseBytes());
            lastPoseBoneCount.set(pose.boneCount());
            lastTreeVisibleBoneCount.set(pose.treeVisibleCount());
            lastDrawVisibleBoneCount.set(pose.drawVisibleCount());
            lastPoseBytes.set(pose.retainedBytes());
            if (!pose.finite() && rootIsFinite) nonFinitePoseCaptures.incrementAndGet();
            lastInvocation = new ModelPartInvocationSnapshot(
                    mesh, pose, rootTransform, light, overlay, color, worldGeneration.get(), resourceGeneration.get());
            recordPose(pose);
        } catch (ModelPartPoseCapacityException exception) {
            poseCapacityRejections.incrementAndGet();
            warnPoseFailureOnce("capacity limit reached", exception, false);
        } catch (RuntimeException exception) {
            poseCaptureFailures.incrementAndGet();
            warnPoseFailureOnce("capture failed", exception, true);
        }
    }

    private void recordPose(ImmutableModelPartBonePose pose) {
        if (poseCaptureLogged) return;
        poseCaptureLogged = true;
        ThreadiumClient.LOGGER.info(
                "Threadium captured a Minecraft 1.21.1 ModelPart pose ({} bones, {} draw-visible); Vanilla pass-through remains active",
                pose.boneCount(),
                pose.drawVisibleCount());
    }

    private void warnPoseFailureOnce(String reason, RuntimeException exception, boolean includeCause) {
        if (poseCaptureFailureLogged) return;
        poseCaptureFailureLogged = true;
        String message =
                "Threadium Minecraft 1.21.1 ModelPart pose " + reason + "; Vanilla pass-through remains active";
        if (includeCause) ThreadiumClient.LOGGER.warn(message, exception);
        else ThreadiumClient.LOGGER.warn("{} ({})", message, exception.getMessage());
    }

    private void warnCaptureFailureOnce(String reason, RuntimeException exception, boolean includeCause) {
        if (meshCaptureFailureLogged) return;
        meshCaptureFailureLogged = true;
        String message =
                "Threadium Minecraft 1.21.1 ModelPart CPU mesh " + reason + "; Vanilla pass-through remains active";
        if (includeCause) ThreadiumClient.LOGGER.warn(message, exception);
        else ThreadiumClient.LOGGER.warn("{} ({})", message, exception.getMessage());
    }

    private void recordMesh(ImmutableModelPartMesh mesh) {
        lastCapturedPartCount.set(mesh.partCount());
        lastCapturedQuadCount.set(mesh.quadCount());
        lastCapturedVertexCount.set(mesh.vertexCount());
        lastCapturedIndexCount.set(mesh.indexCount());
        if (!meshCaptureLogged) {
            meshCaptureLogged = true;
            ThreadiumClient.LOGGER.info(
                    "Threadium captured a Minecraft 1.21.1 ModelPart CPU mesh ({} parts, {} quads, {} vertices, {} indices); Vanilla pass-through remains active",
                    mesh.partCount(),
                    mesh.quadCount(),
                    mesh.vertexCount(),
                    mesh.indexCount());
        }
    }

    private void resetInspectionState() {
        observedModelPartRenders.set(0);
        structureInspections.set(0);
        structureCacheHits.set(0);
        structureCacheMisses.set(0);
        structureInspectionFailures.set(0);
        structureCapacityRejections.set(0);
        meshCaptureAttempts.set(0);
        meshCapturesCompleted.set(0);
        meshCaptureFailures.set(0);
        meshCaptureCapacityRejections.set(0);
        meshRootCacheHits.set(0);
        meshRootCacheMisses.set(0);
        uniqueMeshes.set(0);
        lastCapturedPartCount.set(0);
        lastCapturedQuadCount.set(0);
        lastCapturedVertexCount.set(0);
        lastCapturedIndexCount.set(0);
        retainedMeshBytes.set(0);
        poseCaptureAttempts.set(0);
        poseCapturesCompleted.set(0);
        poseCaptureFailures.set(0);
        poseCapacityRejections.set(0);
        poseCacheHits.set(0);
        poseCacheMisses.set(0);
        uniquePosesThisFrame.set(0);
        lastPoseBoneCount.set(0);
        lastTreeVisibleBoneCount.set(0);
        lastDrawVisibleBoneCount.set(0);
        lastPoseBytes.set(0);
        retainedPoseBytesThisFrame.set(0);
        nonFinitePoseCaptures.set(0);
        rootTransformCaptures.set(0);
        structureInspector.clear();
        poseInspector.clear();
        materialTracker.clearLifecycle();
        lastInvocation = null;
        lastMaterialResolution = null;
        renderDepth = 0;
        meshCaptureLogged = false;
        meshCaptureFailureLogged = false;
        poseCaptureLogged = false;
        poseCaptureFailureLogged = false;
        directMaterialLogged = false;
        unresolvedMaterialLogged = false;
        materialCapacityLogged = false;
        materialFailureLogged = false;
    }

    private void resetFrameState() {
        renderDepth = 0;
        poseInspector.beginFrame();
        materialTracker.beginFrame();
        lastInvocation = null;
        lastMaterialResolution = null;
        uniquePosesThisFrame.set(0);
        retainedPoseBytesThisFrame.set(0);
    }

    public record Diagnostics(
            boolean enabled,
            long observedModelPartRenders,
            long worldGeneration,
            long resourceGeneration,
            long structureInspections,
            long structureCacheHits,
            long structureCacheMisses,
            long structureInspectionFailures,
            long structureCapacityRejections,
            long meshCaptureAttempts,
            long meshCapturesCompleted,
            long meshCaptureFailures,
            long meshCaptureCapacityRejections,
            long meshRootCacheHits,
            long meshRootCacheMisses,
            long uniqueMeshes,
            long lastCapturedPartCount,
            long lastCapturedQuadCount,
            long lastCapturedVertexCount,
            long lastCapturedIndexCount,
            long retainedMeshBytes,
            long poseCaptureAttempts,
            long poseCapturesCompleted,
            long poseCaptureFailures,
            long poseCapacityRejections,
            long poseCacheHits,
            long poseCacheMisses,
            long uniquePosesThisFrame,
            long lastPoseBoneCount,
            long lastTreeVisibleBoneCount,
            long lastDrawVisibleBoneCount,
            long lastPoseBytes,
            long retainedPoseBytesThisFrame,
            long nonFinitePoseCaptures,
            long rootTransformCaptures,
            ModelPartMaterialContextTracker.Diagnostics material) {
        private static Diagnostics disabled() {
            ModelPartMaterialContextTracker.Diagnostics material =
                    new ModelPartMaterialContextTracker<>().diagnostics();
            return new Diagnostics(
                    false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, material);
        }
    }
}
