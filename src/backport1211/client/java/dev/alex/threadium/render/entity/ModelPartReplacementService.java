package dev.alex.threadium.render.entity;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.alex.threadium.ThreadiumClient;
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

/**
 * First fail-closed Minecraft 1.21.1 replacement path.
 *
 * <p>It replaces only exact {@code entity_cutout_no_cull} draws that resolve directly from a trusted provider to the
 * exact Vanilla BufferBuilder. Geometry is completely transformed and validated before the destination consumer is
 * mutated. The experimental path remains opt-in until real-machine visual validation is complete.
 */
public final class ModelPartReplacementService {
    private static final boolean CONFIGURED = Boolean.getBoolean("threadium.backport1211.replacement");
    private static volatile ModelPartReplacementService instance;

    private final ModelPartStructureInspector structureInspector = new ModelPartStructureInspector();
    private final ModelPartPoseInspector poseInspector = new ModelPartPoseInspector();
    private final ModelPartMaterialContextTracker<Object, RenderLayer, VertexConsumer> materialTracker =
            new ModelPartMaterialContextTracker<>();
    private final AtomicLong pendingWorldInvalidations = new AtomicLong();
    private final AtomicLong pendingResourceInvalidations = new AtomicLong();
    private final AtomicBoolean pendingShutdown = new AtomicBoolean();
    private final AtomicLong replacementAttempts = new AtomicLong();
    private final AtomicLong replacementAccepts = new AtomicLong();
    private final AtomicLong replacementFallbacks = new AtomicLong();
    private final AtomicLong replacementFailures = new AtomicLong();
    private long worldGeneration;
    private long resourceGeneration;
    private int renderDepth;
    private boolean runtimeEnabled = CONFIGURED;
    private boolean replacementLogged;
    private boolean failureLogged;

    private ModelPartReplacementService() {}

    public static synchronized void initialize() {
        if (instance == null) instance = new ModelPartReplacementService();
    }

    public static boolean configured() {
        return CONFIGURED;
    }

    public static void beginFrame() {
        ModelPartReplacementService current = instance;
        if (current == null) return;
        current.requireRenderThread();
        if (current.pendingShutdown.getAndSet(false)) {
            current.destroyState();
            current.runtimeEnabled = false;
            synchronized (ModelPartReplacementService.class) {
                if (instance == current) instance = null;
            }
            return;
        }
        long worlds = current.pendingWorldInvalidations.getAndSet(0);
        long resources = current.pendingResourceInvalidations.getAndSet(0);
        if (worlds != 0 || resources != 0) {
            current.worldGeneration = Math.addExact(current.worldGeneration, worlds);
            current.resourceGeneration = Math.addExact(current.resourceGeneration, resources);
            current.destroyState();
        }
        current.renderDepth = 0;
        current.poseInspector.beginFrame();
        current.materialTracker.beginFrame();
    }

    public static void observeMaterialProviderRequest(
            MaterialProviderSource source, Object provider, RenderLayer layer, VertexConsumer consumer) {
        ModelPartReplacementService current = instance;
        if (current == null || !current.runtimeEnabled) return;
        if (!current.isRenderThread()) return;
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
        if (current != null) current.pendingShutdown.set(true);
    }

    public static Diagnostics diagnostics() {
        ModelPartReplacementService current = instance;
        return current == null
                ? new Diagnostics(CONFIGURED, false, 0, 0, 0, 0)
                : new Diagnostics(
                        CONFIGURED,
                        current.runtimeEnabled,
                        current.replacementAttempts.get(),
                        current.replacementAccepts.get(),
                        current.replacementFallbacks.get(),
                        current.replacementFailures.get());
    }

    private boolean tryReplace(
            ModelPart root, MatrixStack matrices, VertexConsumer consumer, int light, int overlay, int color) {
        replacementAttempts.incrementAndGet();
        if (!isRenderThread()) return fallback();

        MaterialContextResolution<Object, RenderLayer> material;
        ImmutableModelPartMesh mesh;
        ModelPartInvocationSnapshot invocation;
        PreparedModelPartReplay replay;
        try {
            material = materialTracker.observeResolution(consumer);
            RenderLayer1211Descriptor descriptor = RenderLayer1211Descriptor.inspect(material);
            if (!descriptor.replacementSafe() || !ModelPartVertexReplayCommitter.supports(consumer)) {
                return fallback();
            }
            mesh = structureInspector.cached(root);
            if (mesh == null) mesh = structureInspector.captureAndCache(root);
            ImmutableRootRenderTransform rootTransform = poseInspector.captureRoot(matrices.peek());
            ImmutableModelPartBonePose pose = poseInspector.capturePose(root, mesh).pose();
            invocation = new ModelPartInvocationSnapshot(
                    mesh, pose, rootTransform, light, overlay, color, worldGeneration, resourceGeneration);
            replay = PreparedModelPartReplay.prepare(invocation);
        } catch (RuntimeException exception) {
            replacementFailures.incrementAndGet();
            warnFailureOnce("preparation", exception);
            return fallback();
        }

        try {
            ModelPartVertexReplayCommitter.commit(replay, consumer);
        } catch (RuntimeException exception) {
            replacementFailures.incrementAndGet();
            disableAfterFailure("destination commit", exception);
            return true;
        }
        replacementAccepts.incrementAndGet();
        if (!replacementLogged) {
            replacementLogged = true;
            ThreadiumClient.LOGGER.info(
                    "Threadium replaced a Minecraft 1.21.1 ModelPart draw with a validated cached vertex replay");
        }
        return true;
    }

    private boolean fallback() {
        replacementFallbacks.incrementAndGet();
        return false;
    }

    private void disableAfterFailure(String stage, RuntimeException exception) {
        runtimeEnabled = false;
        replacementFailures.incrementAndGet();
        warnFailureOnce(stage, exception);
    }

    private void warnFailureOnce(String stage, RuntimeException exception) {
        if (failureLogged) return;
        failureLogged = true;
        ThreadiumClient.LOGGER.warn(
                "Threadium disabled the experimental Minecraft 1.21.1 replacement path after {} failed; unsupported draws remain Vanilla",
                stage,
                exception);
    }

    private void destroyState() {
        structureInspector.clear();
        poseInspector.clear();
        materialTracker.clearLifecycle();
        renderDepth = 0;
    }

    private boolean isRenderThread() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null && client.isOnThread() && RenderSystem.isOnRenderThread();
    }

    private void requireRenderThread() {
        if (!isRenderThread()) throw new IllegalStateException("Threadium replacement state is not on the Render Thread");
    }

    public record Diagnostics(
            boolean configured,
            boolean runtimeEnabled,
            long replacementAttempts,
            long replacementAccepts,
            long replacementFallbacks,
            long replacementFailures) {}
}
