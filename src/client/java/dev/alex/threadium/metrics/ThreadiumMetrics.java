package dev.alex.threadium.metrics;

import dev.alex.threadium.ThreadiumClient;
import dev.alex.threadium.config.ThreadiumConfig;
import dev.alex.threadium.config.ThreadiumRuntimeConfig;
import dev.alex.threadium.render.phase.ThreadiumPhasePipeline;
import dev.alex.threadium.render.text.RetainedTextManager;
import dev.alex.threadium.scheduler.ThreadiumScheduler;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.loader.api.FabricLoader;

/** Aggregate-only Phase 0 metrics. No hot-path strings or per-entity logging. */
public final class ThreadiumMetrics {
    private static final boolean HOT_PATH_METRICS = Boolean.getBoolean("threadium.metrics.hotPath");
    private static volatile ThreadiumMetrics current;
    private volatile ThreadiumRuntimeConfig.Snapshot runtimeConfig;
    private final boolean blockEntityTimingEnabled;
    private final AtomicLong workerFailures = new AtomicLong();
    private final AtomicLong staleDiscards = new AtomicLong();
    private final AtomicLong fallbacks = new AtomicLong();
    private final AtomicLong deadlineMisses = new AtomicLong();
    private final TimingAccumulator worldRender = new TimingAccumulator();
    private final TimingAccumulator entityExtraction = new TimingAccumulator();
    private final TimingAccumulator blockEntityExtraction = new TimingAccumulator();
    private final TimingAccumulator particleExtraction = new TimingAccumulator();
    private final AtomicLong entityVisibilityChecks = new AtomicLong();
    private final AtomicLong entityVisibilityRejected = new AtomicLong();
    private final AtomicLong entityRenderStatesExtracted = new AtomicLong();
    private final AtomicLong entityStatesSubmitted = new AtomicLong();
    private long lastReportNanos = System.nanoTime();

    public ThreadiumMetrics(ThreadiumConfig config) {
        this.runtimeConfig = ThreadiumRuntimeConfig.effective();
        this.blockEntityTimingEnabled = !FabricLoader.getInstance().isModLoaded("sodium");
        current = this;
        StagedVertexMetrics.configure(config.metricsEnabled());
    }

    public static void deactivate(ThreadiumMetrics metrics) {
        if (current == metrics) current = null;
    }

    private static boolean hotPathMetricsEnabled() {
        return HOT_PATH_METRICS || dev.alex.threadium.benchmark.ThreadiumBenchmark.active();
    }

    public static boolean isTimingEnabled() {
        ThreadiumMetrics metrics = current;
        return metrics != null && hotPathMetricsEnabled() && metrics.runtimeConfig.metricsEnabled();
    }

    public static boolean isBlockEntityTimingEnabled() {
        ThreadiumMetrics metrics = current;
        return metrics != null
                && hotPathMetricsEnabled()
                && metrics.blockEntityTimingEnabled
                && metrics.runtimeConfig.metricsEnabled();
    }

    public static void recordWorldRenderNanos(long nanos) {
        record(0, nanos);
    }

    public static void recordEntityExtractionNanos(long nanos) {
        record(1, nanos);
    }

    public static void recordBlockEntityExtractionNanos(long nanos) {
        record(2, nanos);
    }

    public static void recordParticleExtractionNanos(long nanos) {
        record(3, nanos);
    }

    private static void record(int metric, long nanos) {
        ThreadiumMetrics metrics = current;
        if (metrics == null || !hotPathMetricsEnabled() || !metrics.runtimeConfig.metricsEnabled()) return;
        switch (metric) {
            case 0 -> metrics.worldRender.record(nanos);
            case 1 -> metrics.entityExtraction.record(nanos);
            case 2 -> metrics.blockEntityExtraction.record(nanos);
            case 3 -> metrics.particleExtraction.record(nanos);
            default -> throw new AssertionError(metric);
        }
    }

    public static void recordEntityVisibilityCheck(boolean visible) {
        ThreadiumMetrics metrics = current;
        if (metrics == null || !hotPathMetricsEnabled() || !metrics.runtimeConfig.metricsEnabled()) return;
        metrics.entityVisibilityChecks.incrementAndGet();
        if (!visible) metrics.entityVisibilityRejected.incrementAndGet();
    }

    public static void recordEntityRenderStateExtracted() {
        recordCounter(0);
    }

    public static void recordEntityStateSubmitted() {
        recordCounter(1);
    }

    private static void recordCounter(int counter) {
        ThreadiumMetrics metrics = current;
        if (metrics == null || !hotPathMetricsEnabled() || !metrics.runtimeConfig.metricsEnabled()) return;
        if (counter == 0) metrics.entityRenderStatesExtracted.incrementAndGet();
        else metrics.entityStatesSubmitted.incrementAndGet();
    }

    public void recordWorkerFailure() {
        if (runtimeConfig.metricsEnabled()) workerFailures.incrementAndGet();
    }

    public void recordStaleDiscard() {
        if (runtimeConfig.metricsEnabled()) staleDiscards.incrementAndGet();
    }

    public void recordFallback() {
        if (runtimeConfig.metricsEnabled()) fallbacks.incrementAndGet();
    }

    public void recordDeadlineMiss() {
        if (runtimeConfig.metricsEnabled()) deadlineMisses.incrementAndGet();
    }

    public void resetWorldScoped() {
        staleDiscards.set(0);
        fallbacks.set(0);
        deadlineMisses.set(0);
    }

    public void applyRuntimeConfig(ThreadiumRuntimeConfig.Snapshot snapshot) {
        boolean wasEnabled = runtimeConfig.metricsEnabled();
        runtimeConfig = snapshot;
        if (wasEnabled == snapshot.metricsEnabled()) return;
        resetAll();
        lastReportNanos = System.nanoTime();
        StagedVertexMetrics.configure(snapshot.metricsEnabled());
    }

    private void resetAll() {
        workerFailures.set(0);
        staleDiscards.set(0);
        fallbacks.set(0);
        deadlineMisses.set(0);
        entityVisibilityChecks.set(0);
        entityVisibilityRejected.set(0);
        entityRenderStatesExtracted.set(0);
        entityStatesSubmitted.set(0);
        worldRender.snapshotAndReset();
        entityExtraction.snapshotAndReset();
        blockEntityExtraction.snapshotAndReset();
        particleExtraction.snapshotAndReset();
    }

    public BenchmarkTiming benchmarkTimingSnapshotAndReset() {
        return new BenchmarkTiming(
                worldRender.snapshotAndReset(),
                entityExtraction.snapshotAndReset(),
                blockEntityExtraction.snapshotAndReset(),
                particleExtraction.snapshotAndReset());
    }

    public record BenchmarkTiming(
            TimingAccumulator.Sample worldRender,
            TimingAccumulator.Sample entityExtraction,
            TimingAccumulator.Sample blockEntityExtraction,
            TimingAccumulator.Sample particleExtraction) {}

    public void reportIfDue(ThreadiumScheduler scheduler, long worldGeneration, long resourceGeneration) {
        ThreadiumRuntimeConfig.Snapshot snapshot = runtimeConfig;
        if (!snapshot.metricsEnabled() || dev.alex.threadium.benchmark.ThreadiumBenchmark.active()) return;
        long now = System.nanoTime();
        if (now - lastReportNanos < snapshot.metricsOutputIntervalSeconds() * 1_000_000_000L) return;
        lastReportNanos = now;
        TimingAccumulator.Sample world = worldRender.snapshotAndReset();
        TimingAccumulator.Sample entities = entityExtraction.snapshotAndReset();
        TimingAccumulator.Sample blockEntities = blockEntityExtraction.snapshotAndReset();
        TimingAccumulator.Sample particles = particleExtraction.snapshotAndReset();
        ThreadiumClient.LOGGER.info(
                "Phase 0 interval metrics: worldGeneration={}, resourceGeneration={}, queue={}, activeWorkers={}, workerFailures={}, staleDiscards={}, fallbacks={}, deadlineMisses={}, entityVisibilityChecks={}, entityVisibilityRejected={}, entityRenderStatesExtracted={}, entityStatesSubmitted={}, worldRender={}, entityExtraction={}, blockEntityExtraction={}, particleExtraction={}, {}",
                worldGeneration,
                resourceGeneration,
                scheduler.queueDepth(),
                scheduler.activeWorkers(),
                workerFailures.getAndSet(0),
                staleDiscards.getAndSet(0),
                fallbacks.getAndSet(0),
                deadlineMisses.getAndSet(0),
                entityVisibilityChecks.getAndSet(0),
                entityVisibilityRejected.getAndSet(0),
                entityRenderStatesExtracted.getAndSet(0),
                entityStatesSubmitted.getAndSet(0),
                world.describe(),
                entities.describe(),
                blockEntities.describe(),
                particles.describe(),
                ThreadiumPhasePipeline.metricsSnapshot() + ", " + StagedVertexMetrics.snapshotAndReset() + ", "
                        + RetainedTextManager.metricsSnapshot() + ", "
                        + (dev.alex.threadium.render.modelpart.ModelPartRenderService.get() == null
                                ? "gpuEntity={state=DISABLED}"
                                : dev.alex.threadium.render.modelpart.ModelPartRenderService.get()
                                                .metricsSnapshot() + ", "
                                        + dev.alex.threadium.render.modelpart.ModelPartRenderService.get()
                                                .visualMetricsSnapshot()));
    }
}
