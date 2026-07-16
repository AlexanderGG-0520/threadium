package dev.alex.threadium.metrics;

import dev.alex.threadium.ThreadiumClient;
import dev.alex.threadium.config.ThreadiumConfig;
import dev.alex.threadium.scheduler.ThreadiumScheduler;
import net.fabricmc.loader.api.FabricLoader;
import dev.alex.threadium.render.phase.ThreadiumPhasePipeline;
import dev.alex.threadium.render.text.RetainedTextManager;

import java.util.concurrent.atomic.AtomicLong;

/** Aggregate-only Phase 0 metrics. No hot-path strings or per-entity logging. */
public final class ThreadiumMetrics {
    private static volatile ThreadiumMetrics current;
    private final ThreadiumConfig config;
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
        this.config = config;
        this.blockEntityTimingEnabled = !FabricLoader.getInstance().isModLoaded("sodium");
        current = this;
        StagedVertexMetrics.configure(config.metricsEnabled());
    }
    public static void deactivate(ThreadiumMetrics metrics) { if (current == metrics) current = null; }
    public static boolean isTimingEnabled() {
        ThreadiumMetrics metrics = current;
        return metrics != null && metrics.config.metricsEnabled();
    }
    public static boolean isBlockEntityTimingEnabled() {
        ThreadiumMetrics metrics = current;
        return metrics != null && metrics.blockEntityTimingEnabled && metrics.config.metricsEnabled();
    }
    public static void recordWorldRenderNanos(long nanos) { record(0, nanos); }
    public static void recordEntityExtractionNanos(long nanos) { record(1, nanos); }
    public static void recordBlockEntityExtractionNanos(long nanos) { record(2, nanos); }
    public static void recordParticleExtractionNanos(long nanos) { record(3, nanos); }
    private static void record(int metric, long nanos) {
        ThreadiumMetrics metrics = current;
        if (metrics == null || !metrics.config.metricsEnabled()) return;
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
        if (metrics == null || !metrics.config.metricsEnabled()) return;
        metrics.entityVisibilityChecks.incrementAndGet();
        if (!visible) metrics.entityVisibilityRejected.incrementAndGet();
    }
    public static void recordEntityRenderStateExtracted() { recordCounter(0); }
    public static void recordEntityStateSubmitted() { recordCounter(1); }
    private static void recordCounter(int counter) {
        ThreadiumMetrics metrics = current;
        if (metrics == null || !metrics.config.metricsEnabled()) return;
        if (counter == 0) metrics.entityRenderStatesExtracted.incrementAndGet();
        else metrics.entityStatesSubmitted.incrementAndGet();
    }
    public void recordWorkerFailure() { if (config.metricsEnabled()) workerFailures.incrementAndGet(); }
    public void recordStaleDiscard() { if (config.metricsEnabled()) staleDiscards.incrementAndGet(); }
    public void recordFallback() { if (config.metricsEnabled()) fallbacks.incrementAndGet(); }
    public void recordDeadlineMiss() { if (config.metricsEnabled()) deadlineMisses.incrementAndGet(); }
    public void resetWorldScoped() { staleDiscards.set(0); fallbacks.set(0); deadlineMisses.set(0); }

    public BenchmarkTiming benchmarkTimingSnapshotAndReset() {
        return new BenchmarkTiming(worldRender.snapshotAndReset(),entityExtraction.snapshotAndReset(),blockEntityExtraction.snapshotAndReset(),particleExtraction.snapshotAndReset());
    }

    public record BenchmarkTiming(TimingAccumulator.Sample worldRender,TimingAccumulator.Sample entityExtraction,TimingAccumulator.Sample blockEntityExtraction,TimingAccumulator.Sample particleExtraction) { }

    public void reportIfDue(ThreadiumScheduler scheduler, long worldGeneration, long resourceGeneration) {
        if (!config.metricsEnabled() || dev.alex.threadium.benchmark.ThreadiumBenchmark.active()) return;
        long now = System.nanoTime();
        if (now - lastReportNanos < config.metricsOutputIntervalSeconds() * 1_000_000_000L) return;
        lastReportNanos = now;
        TimingAccumulator.Sample world = worldRender.snapshotAndReset();
        TimingAccumulator.Sample entities = entityExtraction.snapshotAndReset();
        TimingAccumulator.Sample blockEntities = blockEntityExtraction.snapshotAndReset();
        TimingAccumulator.Sample particles = particleExtraction.snapshotAndReset();
        ThreadiumClient.LOGGER.info("Phase 0 interval metrics: worldGeneration={}, resourceGeneration={}, queue={}, activeWorkers={}, workerFailures={}, staleDiscards={}, fallbacks={}, deadlineMisses={}, entityVisibilityChecks={}, entityVisibilityRejected={}, entityRenderStatesExtracted={}, entityStatesSubmitted={}, worldRender={}, entityExtraction={}, blockEntityExtraction={}, particleExtraction={}, {}",
                worldGeneration, resourceGeneration, scheduler.queueDepth(), scheduler.activeWorkers(), workerFailures.getAndSet(0), staleDiscards.getAndSet(0), fallbacks.getAndSet(0), deadlineMisses.getAndSet(0), entityVisibilityChecks.getAndSet(0), entityVisibilityRejected.getAndSet(0), entityRenderStatesExtracted.getAndSet(0), entityStatesSubmitted.getAndSet(0), world.describe(), entities.describe(), blockEntities.describe(), particles.describe(), ThreadiumPhasePipeline.metricsSnapshot() + ", " + StagedVertexMetrics.snapshotAndReset() + ", " + RetainedTextManager.metricsSnapshot() + ", " + (dev.alex.threadium.render.modelpart.ModelPartRenderService.get()==null?"gpuEntity={state=DISABLED}":dev.alex.threadium.render.modelpart.ModelPartRenderService.get().metricsSnapshot() + ", " + dev.alex.threadium.render.modelpart.ModelPartRenderService.get().visualMetricsSnapshot()));
    }
}
