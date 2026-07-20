package dev.alex.threadium.render.entity;

import dev.alex.threadium.ThreadiumClient;
import dev.alex.threadium.render.modelpart.structure.ImmutableModelPartMesh;
import dev.alex.threadium.render.modelpart.structure.ModelPartMeshCapacityException;
import dev.alex.threadium.render.modelpart.structure.ModelPartStructureInspector;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;

/** M1 CPU-mesh observer. It never acquires rendering ownership and cannot suppress Vanilla rendering. */
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
    private final ModelPartStructureInspector structureInspector = new ModelPartStructureInspector();
    private int renderDepth;
    private boolean meshCaptureLogged;
    private boolean meshCaptureFailureLogged;
    private volatile boolean enabled = true;

    private PassThroughEntityRenderService() {}

    public static synchronized void initialize() {
        if (instance == null) instance = new PassThroughEntityRenderService();
    }

    public static void beginFrame() {
        PassThroughEntityRenderService current = instance;
        if (current != null) current.renderDepth = 0;
    }

    public static void beginModelPartRender(ModelPart part) {
        PassThroughEntityRenderService current = instance;
        if (current == null || !current.enabled) return;
        if (current.observedModelPartRenders.getAndIncrement() == 0) {
            ThreadiumClient.LOGGER.info(
                    "Threadium detected Minecraft 1.21.1 ModelPart rendering; Vanilla pass-through remains active");
        }
        if (current.renderDepth++ != 0) return;
        current.captureStructure(part);
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
                        current.retainedMeshBytes.get());
    }

    private void captureStructure(ModelPart root) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || !client.isOnThread()) {
            structureInspectionFailures.incrementAndGet();
            meshCaptureFailures.incrementAndGet();
            return;
        }

        ImmutableModelPartMesh mesh = structureInspector.cached(root);
        if (mesh != null) {
            structureCacheHits.incrementAndGet();
            meshRootCacheHits.incrementAndGet();
            recordMesh(mesh);
            return;
        }

        structureCacheMisses.incrementAndGet();
        meshRootCacheMisses.incrementAndGet();
        if (!structureInspector.canCaptureNewRoot()) {
            structureCapacityRejections.incrementAndGet();
            meshCaptureCapacityRejections.incrementAndGet();
            return;
        }

        meshCaptureAttempts.incrementAndGet();
        try {
            mesh = structureInspector.captureAndCache(root);
            structureInspections.incrementAndGet();
            meshCapturesCompleted.incrementAndGet();
            uniqueMeshes.set(structureInspector.uniqueMeshCount());
            retainedMeshBytes.set(structureInspector.retainedMeshBytes());
            recordMesh(mesh);
        } catch (ModelPartMeshCapacityException exception) {
            structureCapacityRejections.incrementAndGet();
            meshCaptureCapacityRejections.incrementAndGet();
            warnCaptureFailureOnce("capacity limit reached", exception, false);
        } catch (RuntimeException exception) {
            structureInspectionFailures.incrementAndGet();
            meshCaptureFailures.incrementAndGet();
            warnCaptureFailureOnce("capture failed", exception, true);
        }
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
        structureInspector.clear();
        renderDepth = 0;
        meshCaptureLogged = false;
        meshCaptureFailureLogged = false;
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
            long retainedMeshBytes) {
        private static Diagnostics disabled() {
            return new Diagnostics(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}
