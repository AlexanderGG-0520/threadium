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
}
