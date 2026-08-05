package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.alex.threadium.render.modelpart.ModelPart1211Metrics.FallbackReason;
import dev.alex.threadium.render.modelpart.material.RenderLayer1211Descriptor;
import org.junit.jupiter.api.Test;

class ModelPart1211MetricsTest {
    @Test
    void disabledMetricsDoNotMutateHotPathCounters() {
        ModelPart1211Metrics metrics = new ModelPart1211Metrics();

        metrics.recordReplacementAttempt();
        metrics.recordFallback(FallbackReason.ADAPTIVE);
        metrics.recordQueued(RenderLayer1211Descriptor.Kind.ENTITY_SOLID, 4);

        ModelPart1211Metrics.Snapshot snapshot = metrics.snapshot();
        assertFalse(snapshot.enabled());
        assertEquals(0, snapshot.replacement().attempts());
        assertEquals(0, snapshot.replacement().adaptiveFallbacks());
        assertEquals(0, snapshot.backend().queuedInstances());
    }

    @Test
    void snapshotReportsReplacementBatchingUploadsTimingsAndPipelineCoverage() {
        ModelPart1211Metrics metrics = new ModelPart1211Metrics();
        metrics.configure(true);

        metrics.recordReplacementAttempt();
        metrics.recordAccepted(false, true, false);
        metrics.recordFallback(FallbackReason.ADAPTIVE);
        metrics.recordGroupBegin();
        metrics.recordGroupEnd();
        metrics.recordStructureLookup(true);
        metrics.recordGpuMeshLookup(false);
        metrics.recordMeshUpload(true);
        metrics.recordPoseObservation(true, true, false);
        metrics.recordInitializationAttempt();
        metrics.recordInitializationResult(true);
        metrics.recordQueued(RenderLayer1211Descriptor.Kind.ENTITY_SOLID, 4);
        metrics.recordPipelineFallback(RenderLayer1211Descriptor.Kind.ENTITY_TRANSLUCENT);
        metrics.recordUploads(1, 1, 384, 448);
        metrics.recordFlush(RenderLayer1211Descriptor.Kind.ENTITY_SOLID, 4, 1, 4, 1, 0, 0, 0, 0, 1, 4, 4);
        metrics.recordInterceptTiming(100, 10, 20, 30, 40);
        metrics.recordFlushTiming(200, 50, 40, 60, 50);

        ModelPart1211Metrics.Snapshot snapshot = metrics.snapshot();
        assertTrue(snapshot.enabled());
        assertEquals(1, snapshot.replacement().attempts());
        assertEquals(1, snapshot.replacement().accepts());
        assertEquals(1, snapshot.replacement().adaptiveFallbacks());
        assertEquals(4, snapshot.backend().queuedInstances());
        assertEquals(4, snapshot.backend().submittedInstances());
        assertEquals(1, snapshot.backend().drawCalls());
        assertEquals(4, snapshot.backend().maximumInstancesPerDraw());
        assertEquals(384, snapshot.backend().instanceBytes());
        assertEquals(448, snapshot.backend().boneBytes());
        assertEquals(100, snapshot.timings().interceptTotalNanos());
        assertEquals(200, snapshot.timings().flushTotalNanos());

        ModelPart1211Metrics.PipelineCoverage solid = snapshot.pipelines().stream()
                .filter(coverage -> coverage.pipeline().equals("entity_solid"))
                .findFirst()
                .orElseThrow();
        assertEquals(4, solid.accepted());
        assertEquals(1, solid.drawCalls());
        assertEquals(4, solid.instances());
        assertEquals(4, solid.maximumBatchSize());

        ModelPart1211Metrics.PipelineCoverage translucent = snapshot.pipelines().stream()
                .filter(coverage -> coverage.pipeline().equals("entity_translucent"))
                .findFirst()
                .orElseThrow();
        assertEquals(1, translucent.fallbacks());
    }

    @Test
    void snapshotAndResetIsIntervalScoped() {
        ModelPart1211Metrics metrics = new ModelPart1211Metrics();
        metrics.configure(true);
        metrics.recordReplacementAttempt();
        metrics.recordQueued(RenderLayer1211Descriptor.Kind.GLINT, 2);

        ModelPart1211Metrics.Snapshot interval = metrics.snapshotAndReset();
        assertEquals(1, interval.replacement().attempts());
        assertEquals(2, interval.backend().queuedInstances());

        ModelPart1211Metrics.Snapshot after = metrics.snapshot();
        assertEquals(0, after.replacement().attempts());
        assertEquals(0, after.backend().queuedInstances());
        assertTrue(after.pipelines().stream().allMatch(coverage -> coverage.accepted() == 0));
    }
}
