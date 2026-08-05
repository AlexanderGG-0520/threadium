package dev.alex.threadium.render.entity;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.alex.threadium.ThreadiumClient;
import dev.alex.threadium.config.ThreadiumConfig;
import dev.alex.threadium.config.ThreadiumRuntimeConfig;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;
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
    private ModelPartGpuInstanceBackend gpuBackend;
    private long worldGeneration;
    private long resourceGeneration;
    private int renderDepth;
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
        return ThreadiumRuntimeConfig.current().gpuReplacementEnabled()
                && ModelPartGpuInstanceBackend.configured();
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
        current.renderDepth = 0;
        current.poseInspector.beginFrame();
        current.materialTracker.beginFrame();
    }

    public static void observeMaterialProviderRequest(
            MaterialProviderSource source, Object provider, RenderLayer layer, VertexConsumer consumer) {
        ModelPartReplacementService current = instance;
        if (current == null || !current.runtimeEnabled || !current.isRenderThread()) return;
        try {
            current.materialTracker.register(provider, layer, consumer, source);
        } catch (RuntimeException exception) {
            current.disableAfterFailure("material provider registration", exception);
        }
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
            current.gpuBackend.flush(provider, layer, current.worldGeneration, current.resourceGeneration);
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
                        0)
                : current.gpuBackend.diagnostics();
        return current == null
                ? new Diagnostics(configured(), gpuConfigured(), false, 0, 0, 0, 0, gpu)
                : new Diagnostics(
                        configured(),
                        gpuConfigured(),
                        current.runtimeEnabled,
                        current.replacementAttempts.get(),
                        current.replacementAccepts.get(),
                        current.replacementFallbacks.get(),
                        current.replacementFailures.get(),
                        gpu);
    }

    private boolean tryReplace(
            ModelPart root, MatrixStack matrices, VertexConsumer consumer, int light, int overlay, int color) {
        replacementAttempts.incrementAndGet();
        if (!isRenderThread()) return fallback();

        MaterialContextResolution<Object, RenderLayer> material;
        RenderLayer1211Descriptor descriptor;
        ImmutableModelPartMesh mesh;
        ImmutableRootRenderTransform rootTransform;
        ImmutableModelPartBonePose pose;
        try {
            material = materialTracker.observeResolution(consumer);
            descriptor = RenderLayer1211Descriptor.inspect(material);
            if (!descriptor.replacementSafe() || !ModelPartVertexReplayCommitter.supports(consumer)) {
                return fallback();
            }
            mesh = structureInspector.cached(root);
            if (mesh == null) mesh = structureInspector.captureAndCache(root);
            rootTransform = poseInspector.captureRoot(matrices.peek());
            pose = poseInspector.capturePose(root, mesh).pose();
            if (!pose.finite() || !rootTransform.finite() || pose.boneCount() != mesh.partCount()) {
                throw new IllegalArgumentException("ModelPart instance input is not finite or structurally aligned");
            }
        } catch (RuntimeException exception) {
            replacementFailures.incrementAndGet();
            warnFailureOnce("preparation", exception);
            return fallback();
        }

        if (pose.drawVisibleCount() == 0) return accept("empty validated instance");
        if (gpuConfigured()) {
            try {
                if (!gpuBackend().queue(
                        material.provider(),
                        descriptor.layer(),
                        mesh,
                        pose,
                        rootTransform,
                        light,
                        overlay,
                        color,
                        worldGeneration,
                        resourceGeneration)) {
                    return fallback();
                }
                return accept("Threadium-owned GPU instance queue");
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
        } catch (RuntimeException exception) {
            disableAfterFailure("destination commit", exception);
            return true;
        }
        return accept("validated cached vertex replay");
    }

    private boolean accept(String path) {
        replacementAccepts.incrementAndGet();
        if (!replacementLogged) {
            replacementLogged = true;
            ThreadiumClient.LOGGER.info("Threadium replaced a Minecraft 1.21.1 ModelPart draw through {}", path);
        }
        return true;
    }

    private boolean fallback() {
        replacementFallbacks.incrementAndGet();
        return false;
    }

    private ModelPartGpuInstanceBackend gpuBackend() {
        if (gpuBackend == null) gpuBackend = new ModelPartGpuInstanceBackend();
        return gpuBackend;
    }

    private void disableAfterFailure(String stage, RuntimeException exception) {
        runtimeEnabled = false;
        replacementFailures.incrementAndGet();
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
        renderDepth = 0;
        if (gpuBackend == null) return;
        if (closeGpu) {
            gpuBackend.close();
            gpuBackend = null;
        } else {
            gpuBackend.reset();
        }
    }

    private void applyConfiguration() {
        ThreadiumConfig config = ThreadiumRuntimeConfig.current();
        appliedConfigurationRevision = ThreadiumRuntimeConfig.revision();
        runtimeEnabled = config.replacementEnabled();
        structureInspector.clear();
        poseInspector.clear();
        materialTracker.clearLifecycle();
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

    public record Diagnostics(
            boolean configured,
            boolean gpuConfigured,
            boolean runtimeEnabled,
            long replacementAttempts,
            long replacementAccepts,
            long replacementFallbacks,
            long replacementFailures,
            ModelPartGpuInstanceBackend.Diagnostics gpu) {}
}
