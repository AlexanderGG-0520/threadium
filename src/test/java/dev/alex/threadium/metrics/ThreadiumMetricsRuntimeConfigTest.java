package dev.alex.threadium.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.alex.threadium.config.ThreadiumConfig;
import dev.alex.threadium.config.ThreadiumRuntimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ThreadiumMetricsRuntimeConfigTest {
    private ThreadiumMetrics metrics;

    @AfterEach
    void deactivate() {
        ThreadiumMetrics.deactivate(metrics);
        StagedVertexMetrics.configure(false);
    }

    @Test
    void disablingDropsExistingSamplesAndStopsNewCollection() {
        ThreadiumRuntimeConfig.initialize(ThreadiumConfig.defaults());
        metrics = new ThreadiumMetrics(ThreadiumConfig.defaults());
        ThreadiumMetrics.recordWorldRenderNanos(10);

        metrics.applyRuntimeConfig(snapshot(false));
        ThreadiumMetrics.recordWorldRenderNanos(20);

        assertEquals(0, metrics.benchmarkTimingSnapshotAndReset().worldRender().count());
    }

    @Test
    void reenablingRestartsFromCleanState() {
        ThreadiumRuntimeConfig.initialize(ThreadiumConfig.defaults());
        metrics = new ThreadiumMetrics(ThreadiumConfig.defaults());
        metrics.applyRuntimeConfig(snapshot(false));
        metrics.applyRuntimeConfig(snapshot(true));
        ThreadiumMetrics.recordWorldRenderNanos(30);

        var sample = metrics.benchmarkTimingSnapshotAndReset().worldRender();
        assertEquals(1, sample.count());
        assertEquals(30, sample.totalNanos());
    }

    private static ThreadiumRuntimeConfig.Snapshot snapshot(boolean metricsEnabled) {
        return new ThreadiumRuntimeConfig.Snapshot(
                true, metricsEnabled, false, false, true, 0, true, true, 4, true, true, 30);
    }
}
