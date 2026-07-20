package dev.alex.threadium.render.entity;

import dev.alex.threadium.ThreadiumClient;
import dev.alex.threadium.render.modelpart.structure.ModelPartStructureInspector;
import dev.alex.threadium.render.modelpart.structure.ModelPartStructureSnapshot;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;

/** M1 structural observer. It never acquires rendering ownership and cannot suppress Vanilla rendering. */
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
    private final AtomicLong uniqueStructures = new AtomicLong();
    private final AtomicLong lastObservedPartCount = new AtomicLong();
    private final AtomicLong lastObservedCuboidCount = new AtomicLong();
    private final ModelPartStructureInspector structureInspector = new ModelPartStructureInspector();
    private int renderDepth;
    private boolean structureLogged;
    private boolean inspectionFailureLogged;
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
        current.inspectStructure(part);
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
                ? new Diagnostics(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
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
                        current.uniqueStructures.get(),
                        current.lastObservedPartCount.get(),
                        current.lastObservedCuboidCount.get());
    }

    private void inspectStructure(ModelPart root) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || !client.isOnThread()) {
            structureInspectionFailures.incrementAndGet();
            return;
        }

        ModelPartStructureSnapshot snapshot = structureInspector.cached(root);
        if (snapshot != null) {
            structureCacheHits.incrementAndGet();
            recordSnapshot(snapshot);
            return;
        }

        structureCacheMisses.incrementAndGet();
        if (!structureInspector.canInspectNewRoot()) {
            structureCapacityRejections.incrementAndGet();
            return;
        }

        try {
            snapshot = structureInspector.inspectAndCache(root);
            structureInspections.incrementAndGet();
            uniqueStructures.set(structureInspector.uniqueStructureCount());
            recordSnapshot(snapshot);
        } catch (RuntimeException exception) {
            structureInspectionFailures.incrementAndGet();
            if (!inspectionFailureLogged) {
                inspectionFailureLogged = true;
                ThreadiumClient.LOGGER.warn(
                        "Threadium could not inspect a Minecraft 1.21.1 ModelPart structure; Vanilla pass-through remains active",
                        exception);
            }
        }
    }

    private void recordSnapshot(ModelPartStructureSnapshot snapshot) {
        lastObservedPartCount.set(snapshot.partCount());
        lastObservedCuboidCount.set(snapshot.cuboidCount());
        if (!structureLogged) {
            structureLogged = true;
            ThreadiumClient.LOGGER.info(
                    "Threadium inspected a Minecraft 1.21.1 ModelPart structure ({} parts, {} cuboids); Vanilla pass-through remains active",
                    snapshot.partCount(),
                    snapshot.cuboidCount());
        }
    }

    private void resetInspectionState() {
        observedModelPartRenders.set(0);
        structureInspections.set(0);
        structureCacheHits.set(0);
        structureCacheMisses.set(0);
        structureInspectionFailures.set(0);
        structureCapacityRejections.set(0);
        uniqueStructures.set(0);
        lastObservedPartCount.set(0);
        lastObservedCuboidCount.set(0);
        structureInspector.clear();
        renderDepth = 0;
        structureLogged = false;
        inspectionFailureLogged = false;
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
            long uniqueStructures,
            long lastObservedPartCount,
            long lastObservedCuboidCount) {}
}
