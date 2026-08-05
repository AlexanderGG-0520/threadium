package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DetailedMetrics1211WiringTest {
    private static final Path CLIENT = Path.of("src", "backport1211", "client", "java");

    @Test
    void serviceReportsIntervalMetricsAndExposesStructuredDiagnostics() throws IOException {
        String service = Files.readString(CLIENT.resolve(
                Path.of("dev", "alex", "threadium", "render", "entity", "ModelPartReplacementService.java")));

        assertTrue(service.contains("metrics.configure(config.metricsEnabled())"));
        assertTrue(service.contains("metrics.snapshotAndReset()"));
        assertTrue(service.contains("Minecraft 1.21.1 interval metrics:"));
        assertTrue(service.contains("ModelPartMaterialContextTracker.Diagnostics material"));
        assertTrue(service.contains("ModelPart1211Metrics.Snapshot metrics"));
        assertTrue(service.contains("public static ModelPart1211Metrics.Snapshot metricsSnapshotAndReset()"));
    }

    @Test
    void interceptTimingAndFallbackClassesAreRecordedBeforeExpensivePreparation() throws IOException {
        String service = Files.readString(CLIENT.resolve(
                Path.of("dev", "alex", "threadium", "render", "entity", "ModelPartReplacementService.java")));

        int adaptive = service.indexOf("FallbackReason.ADAPTIVE");
        int mesh = service.indexOf("structureInspector.cached(root)");
        assertTrue(adaptive >= 0);
        assertTrue(mesh > adaptive);
        assertTrue(service.contains("metrics.recordInterceptTiming("));
        assertTrue(service.contains("metrics.recordPoseObservation("));
        assertTrue(service.contains("metrics.recordStructureLookup("));
    }

    @Test
    void backendRecordsPipelineCoverageBatchEfficiencyUploadsAndFlushTiming() throws IOException {
        String backend = Files.readString(CLIENT.resolve(
                Path.of("dev", "alex", "threadium", "render", "modelpart", "ModelPartGpuInstanceBackend.java")));

        assertTrue(backend.contains("metrics.recordQueued(descriptor.kind(), 1)"));
        assertTrue(backend.contains("metrics.recordFlush("));
        assertTrue(backend.contains("metrics.recordUploads("));
        assertTrue(backend.contains("metrics.recordFlushTiming("));
        assertTrue(backend.contains("metrics.recordInitializationResult(true)"));
        assertTrue(backend.contains("metrics.recordBackendFailure()"));
    }
}
