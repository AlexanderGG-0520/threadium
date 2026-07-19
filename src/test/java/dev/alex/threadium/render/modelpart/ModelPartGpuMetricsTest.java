package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ModelPartGpuMetricsTest {
    @Test
    void interceptTimingScopesAccumulateTogether() {
        ModelPartGpuMetrics metrics = new ModelPartGpuMetrics();

        metrics.recordInterceptTiming(100, 10, 20, 30, 15, 5);
        metrics.recordInterceptTiming(200, 20, 40, 60, 30, 10);

        assertEquals(2, metrics.interceptProfileCount.sum());
        assertEquals(300, metrics.interceptTotalNanos.sum());
        assertEquals(30, metrics.interceptPipelineValidationNanos.sum());
        assertEquals(60, metrics.interceptTopologyAndMeshLookupNanos.sum());
        assertEquals(90, metrics.interceptPosePreparationNanos.sum());
        assertEquals(45, metrics.interceptMaterialCaptureNanos.sum());
        assertEquals(15, metrics.interceptBackendQueueNanos.sum());
    }

    @Test
    void backendQueueTimingsAccumulateAndSnapshotResetTogether() {
        ModelPartGpuMetrics metrics = new ModelPartGpuMetrics();
        metrics.recordBackendQueueTiming(10, 20, 30, 40, 50);
        metrics.backendQueuePrepareCalls.add(1);
        metrics.backendQueuePrepareReuseHits.add(7);
        metrics.backendQueueSelectorNanos.add(5);
        metrics.recordBackendQueueTiming(1, 2, 3, 4, 5);
        metrics.backendQueuePrepareCalls.add(2);
        metrics.backendQueuePrepareReuseHits.add(8);
        metrics.backendQueueSelectorNanos.add(6);

        ModelPartRenderService.BackendQueueTimings snapshot =
                ModelPartRenderService.snapshotBackendQueueTimings(metrics);
        assertEquals(2, snapshot.count());
        assertEquals(3, snapshot.prepareCalls());
        assertEquals(15, snapshot.prepareReuseHits());
        assertEquals(11, snapshot.selectorNanos());
        assertEquals(11, snapshot.precheckNanos());
        assertEquals(22, snapshot.prepareNanos());
        assertEquals(33, snapshot.preparedValidationNanos());
        assertEquals(44, snapshot.instanceCaptureNanos());
        assertEquals(55, snapshot.insertionAndPaletteNanos());
        assertEquals(
                ModelPartRenderService.BackendQueueTimings.zero(),
                ModelPartRenderService.snapshotBackendQueueTimings(metrics));
    }
}
