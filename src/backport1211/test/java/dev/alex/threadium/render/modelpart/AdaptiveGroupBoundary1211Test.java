package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AdaptiveGroupBoundary1211Test {
    private static final Path CLIENT = Path.of("src", "backport1211", "client");

    @Test
    void livingRendererAndEachFeatureEstablishStableOwnerScopes() throws IOException {
        String mixin = Files.readString(
                CLIENT.resolve(Path.of("java", "dev", "alex", "threadium", "mixin", "LivingEntityRendererMixin.java")));

        assertTrue(mixin.contains("ModelPartReplacementService.beginRenderGroup(this);"));
        assertTrue(mixin.contains("ModelPartReplacementService.endRenderGroup(this);"));
        assertTrue(mixin.contains("ModelPartReplacementService.beginRenderGroup(feature);"));
        assertTrue(mixin.contains("finally"));
        assertTrue(mixin.contains("ModelPartReplacementService.endRenderGroup(feature);"));
    }

    @Test
    void profitabilityGateRunsBeforeMeshAndPosePreparation() throws IOException {
        String service = Files.readString(CLIENT.resolve(
                Path.of("java", "dev", "alex", "threadium", "render", "entity", "ModelPartReplacementService.java")));

        int gate = service.indexOf("batchProfitability.observeAndShouldReplace(");
        int mesh = service.indexOf("structureInspector.cached(root)");
        assertTrue(gate >= 0);
        assertTrue(mesh > gate);
        assertTrue(service.contains("groupOwner, root, materialPath.batchType(), minimumGroupSubmits"));
        assertTrue(service.contains("batchProfitability.recordFlush(entry.getKey(), entry.getValue())"));
    }

    @Test
    void backendKeepsDrawConsolidationAndFeedbackInsideOwnerBoundaries() throws IOException {
        String backend = Files.readString(CLIENT.resolve(Path.of(
                "java", "dev", "alex", "threadium", "render", "modelpart", "ModelPartGpuInstanceBackend.java")));

        assertTrue(backend.contains("IdentityHashMap<Object, IdentityHashMap<MeshHandle, Object>> groupMeshKeys"));
        assertTrue(backend.contains("computeIfAbsent(entry.groupOwner"));
        assertTrue(backend.contains("IdentityHashMap<Object, ModelPartFlushStats> flush("));
        assertTrue(backend.contains("MutableFlushStats"));
        assertTrue(backend.contains("stats.totalInstancesInMultiDraws += instanceCount"));
    }
}
