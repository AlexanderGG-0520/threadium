package dev.alex.threadium.render.phase;

import dev.alex.threadium.ThreadiumClient;
import dev.alex.threadium.config.ThreadiumConfig;
import dev.alex.threadium.config.ThreadiumRuntimeConfig;
import dev.alex.threadium.mixin.accessor.TranslucentFeatureRenderPhaseAccessor;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.feature.phase.TranslucentFeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.feature.submit.TranslucentSubmit;

/** Render-thread capture/merge coordinator. Workers only see detached arrays. */
public final class ThreadiumPhasePipeline {
    private static final AtomicLong FRAME_GENERATION = new AtomicLong();
    private static final AtomicLong PIPELINE_EPOCH = new AtomicLong();
    private static final AtomicInteger CONSECUTIVE_FAILURES = new AtomicInteger();
    private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean CIRCUIT_OPEN = new AtomicBoolean();
    private static final ThreadLocal<Map<FeatureRenderPhase<?>, TranslucentPhaseResult>> REPLAY = new ThreadLocal<>();
    private static final PhasePipelineMetrics METRICS = new PhasePipelineMetrics();
    private static volatile ThreadiumConfig config;
    private static volatile ThreadiumRuntimeConfig.Snapshot runtimeConfig;
    private static volatile ThreadiumPhaseScheduler scheduler;

    private ThreadiumPhasePipeline() {}

    public static synchronized void initialize(ThreadiumConfig newConfig) {
        if (scheduler != null) return;
        config = newConfig;
        runtimeConfig = ThreadiumRuntimeConfig.effective();
        int processors = Runtime.getRuntime().availableProcessors();
        int workers = newConfig.workerCountOverride() > 0
                ? newConfig.workerCountOverride()
                : Math.max(1, Math.min(4, (processors - 2) / 2));
        scheduler = new ThreadiumPhaseScheduler(workers, newConfig.phaseQueueCapacity());
        ThreadiumClient.LOGGER.info(
                "Threadium phase pipeline initialized: enabled={}, workers={}, queueCapacity={}, deadlineMicros={}, translucentThreshold={}",
                newConfig.phasePipelineEnabled(),
                workers,
                newConfig.phaseQueueCapacity(),
                newConfig.phaseDeadlineMicros(),
                newConfig.minTranslucentSubmits());
    }

    public static void invalidate(String reason) {
        long epoch = PIPELINE_EPOCH.incrementAndGet();
        ThreadiumClient.LOGGER.info("Threadium phase pipeline invalidated: epoch={}, reason={}", epoch, reason);
    }

    public static synchronized void shutdown() {
        invalidate("client shutdown");
        ThreadiumPhaseScheduler current = scheduler;
        scheduler = null;
        if (current != null) current.shutdown();
        ThreadiumClient.LOGGER.info("Threadium phase scheduler shutdown completed");
    }

    public static void applyRuntimeConfig(ThreadiumRuntimeConfig.Snapshot snapshot) {
        runtimeConfig = snapshot;
    }

    public static synchronized void applyWorldConfiguration(ThreadiumConfig newConfig) {
        ThreadiumPhaseScheduler previous = scheduler;
        config = newConfig;
        int processors = Runtime.getRuntime().availableProcessors();
        int workers = newConfig.workerCountOverride() > 0
                ? newConfig.workerCountOverride()
                : Math.max(1, Math.min(4, (processors - 2) / 2));
        scheduler = new ThreadiumPhaseScheduler(workers, newConfig.phaseQueueCapacity());
        invalidate("world configuration applied");
        if (previous != null) previous.shutdown();
    }

    public static void drainAndPrepare(SubmitNodeStorage storage, Consumer<FeatureRenderPhase<?>> vanillaConsumer) {
        ThreadiumConfig currentConfig = config;
        ThreadiumRuntimeConfig.Snapshot currentRuntime = runtimeConfig;
        ThreadiumPhaseScheduler currentScheduler = scheduler;
        if (currentConfig == null
                || currentScheduler == null
                || currentRuntime == null
                || !currentRuntime.enabled()
                || !currentRuntime.phasePipelineEnabled()
                || CIRCUIT_OPEN.get()) {
            storage.drainPhases(vanillaConsumer);
            return;
        }

        long frameStart = System.nanoTime();
        long generation = FRAME_GENERATION.incrementAndGet();
        long epoch = PIPELINE_EPOCH.get();
        long budget = TimeUnit.MICROSECONDS.toNanos(currentConfig.phaseDeadlineMicros());
        long deadline = frameStart > Long.MAX_VALUE - budget ? Long.MAX_VALUE : frameStart + budget;
        List<PhaseEntry> entries = new ArrayList<>();

        storage.drainPhases(phase -> {
            int slot = entries.size();
            if (phase instanceof TranslucentFeatureRenderPhase translucent) {
                TranslucentFeatureRenderPhaseAccessor accessor = (TranslucentFeatureRenderPhaseAccessor) translucent;
                List<TranslucentSubmit> liveSubmits = accessor.threadium$getSubmits();
                if (shouldUseVanillaTranslucentPhase(liveSubmits.size(), currentConfig.minTranslucentSubmits())) {
                    entries.add(new PhaseEntry(phase, null, null, false));
                    return;
                }
                long captureStart = System.nanoTime();
                SubmitNode[] submits = liveSubmits.toArray(new SubmitNode[0]);
                float[] distances = accessor.threadium$getDistances().toFloatArray();
                TranslucentPhaseSnapshot snapshot =
                        new TranslucentPhaseSnapshot(generation, slot, epoch, submits, distances);
                liveSubmits.clear();
                accessor.threadium$getDistances().clear();
                METRICS.snapshot.record(System.nanoTime() - captureStart);
                METRICS.submits.addAndGet(snapshot.size());
                entries.add(createTranslucentEntry(phase, snapshot, deadline, currentScheduler));
            } else {
                entries.add(new PhaseEntry(phase, null, null, false));
            }
        });

        Map<FeatureRenderPhase<?>, TranslucentPhaseResult> replay = new IdentityHashMap<>();
        REPLAY.set(replay);
        try {
            for (PhaseEntry entry : entries) {
                if (entry.snapshot != null) replay.put(entry.phase, resolve(entry, generation, epoch, deadline));
                vanillaConsumer.accept(entry.phase);
                replay.remove(entry.phase);
            }
        } finally {
            REPLAY.remove();
        }
    }

    private static PhaseEntry createTranslucentEntry(
            FeatureRenderPhase<?> phase,
            TranslucentPhaseSnapshot snapshot,
            long deadline,
            ThreadiumPhaseScheduler currentScheduler) {
        long submitted = System.nanoTime();
        PhaseTask<TranslucentPhaseSnapshot, TranslucentPhaseResult> task = new PhaseTask<>(
                snapshot.generation(),
                snapshot.phaseSlot(),
                submitted,
                deadline,
                snapshot,
                value -> processAsync(value, submitted),
                ThreadiumPhasePipeline::observeCompletion);
        long submitStart = System.nanoTime();
        boolean accepted = currentScheduler.submit(task);
        METRICS.submission.record(System.nanoTime() - submitStart);
        if (!accepted) {
            METRICS.rejections.incrementAndGet();
            task.selectSynchronousFallback();
        }
        return new PhaseEntry(phase, snapshot, task, accepted);
    }

    static boolean shouldUseVanillaTranslucentPhase(int submitCount, int minimumSubmits) {
        return submitCount < minimumSubmits;
    }

    private static TranslucentPhaseResult processAsync(TranslucentPhaseSnapshot snapshot, long submitted) {
        METRICS.queueWait.record(Math.max(0L, System.nanoTime() - submitted));
        long start = System.nanoTime();
        TranslucentPhaseResult result = process(snapshot);
        METRICS.worker.record(System.nanoTime() - start);
        return result;
    }

    private static TranslucentPhaseResult process(TranslucentPhaseSnapshot snapshot) {
        int[] order = snapshot.data().sortedIndices();
        SubmitNode[] ordered = new SubmitNode[order.length];
        for (int index = 0; index < order.length; index++)
            ordered[index] = (SubmitNode) snapshot.data().opaqueReference(order[index]);
        METRICS.resultSize.addAndGet(ordered.length);
        return new TranslucentPhaseResult(
                snapshot.generation(), snapshot.phaseSlot(), snapshot.pipelineEpoch(), ordered);
    }

    private static TranslucentPhaseResult resolve(PhaseEntry entry, long generation, long epoch, long deadline) {
        PhaseTask<TranslucentPhaseSnapshot, TranslucentPhaseResult> task = entry.task;
        if (task != null && entry.submitted) {
            TranslucentPhaseResult async = takeCurrentResult(task, generation, epoch);
            if (async == null && task.state() != PhaseTaskState.FAILED) {
                long waitStart = System.nanoTime();
                try {
                    task.awaitUntil(deadline);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                METRICS.renderWait.record(System.nanoTime() - waitStart);
                async = takeCurrentResult(task, generation, epoch);
            }
            if (async != null) {
                METRICS.asyncCompletions.incrementAndGet();
                CONSECUTIVE_FAILURES.set(0);
                return async;
            }
        }

        long fallbackStart = System.nanoTime();
        if (task != null && !task.selectSynchronousFallback()) {
            TranslucentPhaseResult raced = takeCurrentResult(task, generation, epoch);
            if (raced != null) return raced;
            throw new IllegalStateException("phase slot could not select fallback: " + task.state());
        }
        TranslucentPhaseResult fallback = process(entry.snapshot);
        if (task != null) task.markFallbackMerged();
        METRICS.fallback.record(System.nanoTime() - fallbackStart);
        METRICS.fallbacks.incrementAndGet();
        return fallback;
    }

    private static TranslucentPhaseResult takeCurrentResult(
            PhaseTask<TranslucentPhaseSnapshot, TranslucentPhaseResult> task, long generation, long epoch) {
        return task.takeAsyncResult(
                generation, result -> result.pipelineEpoch() == epoch && PIPELINE_EPOCH.get() == epoch);
    }

    public static void sortOrReplay(FeatureRenderPhase<?> phase, FeatureRenderPhase.Output output) {
        Map<FeatureRenderPhase<?>, TranslucentPhaseResult> replay = REPLAY.get();
        TranslucentPhaseResult result = replay == null ? null : replay.get(phase);
        if (result == null) {
            phase.sortInto(output);
            return;
        }
        long mergeStart = System.nanoTime();
        for (SubmitNode submit : result.orderedSubmits()) output.accept(submit, true);
        METRICS.merge.record(System.nanoTime() - mergeStart);
    }

    private static void observeCompletion(PhaseTask<TranslucentPhaseSnapshot, TranslucentPhaseResult> task) {
        if (task.staleCompletion()) METRICS.stale.incrementAndGet();
        Throwable failure = task.failure();
        if (failure == null) return;
        METRICS.failures.incrementAndGet();
        int failures = CONSECUTIVE_FAILURES.incrementAndGet();
        if (FAILURE_LOGGED.compareAndSet(false, true))
            ThreadiumClient.LOGGER.error("Threadium phase worker failed; using synchronous fallback", failure);
        if (failures >= config.maxConsecutiveFailures() && CIRCUIT_OPEN.compareAndSet(false, true)) {
            ThreadiumClient.LOGGER.error(
                    "Threadium phase pipeline circuit breaker opened after {} consecutive failures", failures);
        }
    }

    public static String metricsSnapshot() {
        ThreadiumPhaseScheduler current = scheduler;
        return METRICS.snapshotAndReset(
                current == null ? 0 : current.queueDepth(), current == null ? 0 : current.activeWorkers());
    }

    private record PhaseEntry(
            FeatureRenderPhase<?> phase,
            TranslucentPhaseSnapshot snapshot,
            PhaseTask<TranslucentPhaseSnapshot, TranslucentPhaseResult> task,
            boolean submitted) {}
}
