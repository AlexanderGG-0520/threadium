package dev.alex.threadium.render.modelpart;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.alex.threadium.ThreadiumClient;
import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.joml.Matrix4fc;

/** One-shot active-context capability selection; explicit reset is performed by replacing this object. */
public final class SelectingModelPartGpuBackend implements ModelPartGpuBackend {
    private static final boolean PROFILE_QUEUE = Boolean.getBoolean("threadium.modelpart.profileIntercept");
    private final BackendStateMachine states;
    private final String requested;
    private final int maxInstances, maxBones;
    private final boolean consolidate;
    private final DebugVisualMode debugMode;
    private final ModelPartGpuMetrics metrics;
    private final BooleanSupplier renderThreadCheck;
    private final Supplier<ModelPartGpuBackend> backendFactory;
    private final InitializationAttemptGuard attemptGuard = new InitializationAttemptGuard();
    private final ArrayDeque<Integer> groupCounts = new ArrayDeque<>();
    private volatile ModelPartGpuBackend delegate;
    private String selected = "none";
    private int queuedCount, groupStart;
    private long generation;
    private boolean groupOpen, groupStrictlyOrdered;

    public SelectingModelPartGpuBackend(
            String requested,
            int maxInstances,
            int maxBones,
            boolean consolidate,
            DebugVisualMode debugMode,
            boolean enabled,
            ModelPartGpuMetrics metrics) {
        this(
                requested,
                maxInstances,
                maxBones,
                consolidate,
                debugMode,
                enabled,
                metrics,
                RenderSystem::isOnRenderThread,
                () -> new Blaze3dModelPartBackend(maxInstances, maxBones, consolidate, metrics));
    }

    SelectingModelPartGpuBackend(
            String requested,
            int maxInstances,
            int maxBones,
            boolean consolidate,
            DebugVisualMode debugMode,
            boolean enabled,
            ModelPartGpuMetrics metrics,
            BooleanSupplier renderThreadCheck,
            Supplier<ModelPartGpuBackend> backendFactory) {
        this.requested = requested;
        this.maxInstances = maxInstances;
        this.maxBones = maxBones;
        this.consolidate = consolidate;
        this.debugMode = debugMode;
        this.metrics = metrics;
        this.renderThreadCheck = renderThreadCheck;
        this.backendFactory = backendFactory;
        states =
                new BackendStateMachine(enabled ? ModelPartBackendState.UNINITIALIZED : ModelPartBackendState.DISABLED);
    }

    @Override
    public ModelPartBackendState state() {
        return states.state();
    }

    public String selected() {
        return selected;
    }

    @Override
    public boolean ensureReady() {
        ModelPartBackendState currentState = state();
        if (currentState == ModelPartBackendState.READY || currentState == ModelPartBackendState.ACTIVE) {
            ModelPartGpuBackend currentDelegate = delegate;
            return currentDelegate != null && currentDelegate.ensureReady();
        }
        return ensureReadySlow();
    }

    private synchronized boolean ensureReadySlow() {
        if (state().accepts()) {
            ModelPartGpuBackend currentDelegate = delegate;
            return currentDelegate != null && currentDelegate.ensureReady();
        }
        if (state() != ModelPartBackendState.UNINITIALIZED
                || attemptGuard.attempted()
                || !renderThreadCheck.getAsBoolean()) return false;
        if (!attemptGuard.beginAttempt()) return false;
        metrics.initializationAttempts.increment();
        states.transition(ModelPartBackendState.UNINITIALIZED, ModelPartBackendState.INITIALIZING);
        ThreadiumClient.LOGGER.info("GPU ModelPart backend INITIALIZING");
        selected = "Blaze3D";
        ThreadiumClient.LOGGER.info("GPU ModelPart backend selected: {}", selected);
        delegate = backendFactory.get();
        delegate.invalidatePipelines(generation);
        if (!delegate.ensureReady()) {
            fail("backend resource or shader initialization failed");
            return false;
        }
        if (groupOpen) delegate.beginGroup(groupStrictlyOrdered);
        states.transition(ModelPartBackendState.INITIALIZING, ModelPartBackendState.READY);
        metrics.initializationSuccesses.increment();
        ThreadiumClient.LOGGER.info("GPU ModelPart backend READY");
        return true;
    }

    private void fail(String reason) {
        metrics.initializationFailures.increment();
        metrics.backendFailures.increment();
        states.transition(ModelPartBackendState.INITIALIZING, ModelPartBackendState.FAILED);
        if (delegate != null) delegate.close();
        ThreadiumClient.LOGGER.error("GPU ModelPart backend FAILED: {}", reason);
    }

    @Override
    public PipelineValidity pipelineValidity(Object renderType, long generation) {
        return delegate == null
                ? PipelineValidity.uninitialized(generation)
                : delegate.pipelineValidity(renderType, generation);
    }

    @Override
    public void invalidatePipelines(long generation) {
        this.generation = generation;
        if (delegate != null) delegate.invalidatePipelines(generation);
    }

    @Override
    public MeshHandle upload(ImmutableModelPartMesh mesh) {
        return state().accepts() ? delegate.upload(mesh) : null;
    }

    @Override
    public boolean queue(
            MeshHandle mesh,
            Object renderType,
            Matrix4fc rootPose,
            ModelPartBoneData bones,
            int light,
            int overlay,
            int tint,
            ModelPartUvTransform uvTransform,
            ModelPartDecalTransform decalTransform) {
        long selectorStart = PROFILE_QUEUE ? System.nanoTime() : 0L;
        boolean ready = state().accepts();
        long selectorNanos = PROFILE_QUEUE ? System.nanoTime() - selectorStart : 0L;
        boolean accepted = ready
                && delegate.queue(mesh, renderType, rootPose, bones, light, overlay, tint, uvTransform, decalTransform);
        if (accepted) {
            queuedCount++;
            if (PROFILE_QUEUE) metrics.backendQueueSelectorNanos.add(selectorNanos);
        }
        return accepted;
    }

    @Override
    public void beginFrame() {
        if (delegate != null) delegate.beginFrame();
    }

    @Override
    public void beginGroup(boolean strictlyOrdered) {
        groupStart = queuedCount;
        groupOpen = true;
        groupStrictlyOrdered = strictlyOrdered;
        if (delegate != null) delegate.beginGroup(strictlyOrdered);
    }

    @Override
    public void endGroup() {
        groupCounts.addLast(queuedCount - groupStart);
        if (delegate != null && groupOpen) delegate.endGroup();
        groupOpen = false;
    }

    @Override
    public FlushStats flushGroup() {
        int expected = groupCounts.isEmpty() ? queuedCount : groupCounts.removeFirst();
        if (!state().accepts()) return FlushStats.EMPTY;
        FlushStats stats = delegate.flushGroup();
        if (delegate.state() == ModelPartBackendState.FAILED) {
            queuedCount = 0;
            groupCounts.clear();
            states.transition(state(), ModelPartBackendState.FAILED);
            metrics.backendFailures.increment();
            metrics.blaze3dSubmissionFailures.increment();
            ThreadiumClient.LOGGER.error("GPU ModelPart backend FAILED: draw submission failed");
            return FlushStats.EMPTY;
        }
        queuedCount -= expected;
        if (stats.instances() > 0) metrics.blaze3dGroupsSubmitted.increment();
        metrics.drawnInstances.add(stats.instances());
        metrics.drawCalls.add(stats.drawCalls());
        metrics.batchableInstances.add(stats.batchableInstances());
        metrics.batchableDrawCalls.add(stats.batchableDrawCalls());
        metrics.sortedInstances.add(stats.sortedInstances());
        metrics.sortedDrawCalls.add(stats.sortedDrawCalls());
        metrics.instancedDraws.add(stats.batchableDrawCalls());
        metrics.consolidatedBatches.add(stats.batchableDrawCalls());
        metrics.singletonBatches.add(stats.singletonBatches());
        metrics.multiInstanceBatches.add(stats.multiInstanceBatches());
        metrics.totalInstancesInMultiDraws.add(stats.totalInstancesInMultiDraws());
        metrics.instanceUploadCalls.add(stats.instanceUploadCalls());
        metrics.boneUploadCalls.add(stats.boneUploadCalls());
        metrics.instanceBytesUploaded.add(stats.instanceBytes());
        metrics.boneBytesUploaded.add(stats.boneBytes());
        if (ModelPartGpuMetrics.detailedMetricsEnabled())
            metrics.maximumInstancesPerDraw.accumulateAndGet(stats.maximumInstancesPerDraw(), Math::max);
        if (state() == ModelPartBackendState.READY && delegate.state() == ModelPartBackendState.ACTIVE) {
            states.transition(ModelPartBackendState.READY, ModelPartBackendState.ACTIVE);
            ThreadiumClient.LOGGER.info("GPU ModelPart backend ACTIVE");
        }
        return stats;
    }

    @Override
    public void destroy(MeshHandle mesh) {
        if (delegate != null) delegate.destroy(mesh);
    }

    @Override
    public void clear() {
        queuedCount = 0;
        groupCounts.clear();
        groupOpen = false;
        if (delegate != null) delegate.clear();
    }

    @Override
    public void close() {
        if (delegate != null) delegate.close();
    }
}
