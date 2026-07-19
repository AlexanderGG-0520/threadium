package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.junit.jupiter.api.Test;

class ModelPartBatchKeyTest {
    private static final ModelPartGpuBackend.MeshHandle MESH = mesh(1);

    @Test
    void immutableHashIsStableAndEqualKeysHaveEqualHashes() {
        RenderType type = type("a");
        ModelPartBatchKey first = new ModelPartBatchKey(MESH, type, 2, 3);
        ModelPartBatchKey second = new ModelPartBatchKey(MESH, type, 2, 3);

        assertEquals(first.hashCode(), first.hashCode());
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void matchingRequiresMeshEqualityExactTypeEpochAndGroup() {
        RenderType type = type("a");
        RenderType equivalent = type("a");
        ModelPartBatchKey key = new ModelPartBatchKey(MESH, type, 2, 3);

        assertTrue(key.matches(mesh(1), type, 2, 3));
        assertFalse(key.matches(mesh(2), type, 2, 3));
        assertFalse(key.matches(MESH, equivalent, 2, 3));
        assertFalse(key.matches(MESH, type, 4, 3));
        assertFalse(key.matches(MESH, type, 2, 4));
    }

    @Test
    void hashCollisionDoesNotMakeDifferentEpochsEqual() {
        RenderType type = type("a");
        ModelPartBatchKey zeroEpoch = new ModelPartBatchKey(MESH, type, 0, 3);
        ModelPartBatchKey collidingEpoch = new ModelPartBatchKey(MESH, type, 0x1_0000_0001L, 3);

        assertEquals(zeroEpoch.hashCode(), collidingEpoch.hashCode());
        assertNotEquals(zeroEpoch, collidingEpoch);
    }

    @Test
    void homogeneousPopulationReusesOneExactKey() {
        var cache = new RecentModelPartBatchKeyCache();
        RenderType type = type("a");
        ModelPartBatchKey first = cache.getOrCreate(MESH, type, 2, 3);

        for (int i = 1; i < 256; i++) assertSame(first, cache.getOrCreate(MESH, type, 2, 3));
        assertEquals(1, cache.size());
    }

    @Test
    void everyStructuralInputChangeReplacesTheRecentKey() {
        var cache = new RecentModelPartBatchKeyCache();
        RenderType firstType = type("a");
        RenderType secondType = type("a");
        ModelPartBatchKey first = cache.getOrCreate(MESH, firstType, 2, 3);
        ModelPartBatchKey differentMesh = cache.getOrCreate(mesh(2), firstType, 2, 3);
        ModelPartBatchKey differentType = cache.getOrCreate(mesh(2), secondType, 2, 3);
        ModelPartBatchKey differentEpoch = cache.getOrCreate(mesh(2), secondType, 4, 3);
        ModelPartBatchKey differentGroup = cache.getOrCreate(mesh(2), secondType, 4, 5);

        assertNotSame(first, differentMesh);
        assertNotSame(differentMesh, differentType);
        assertNotSame(differentType, differentEpoch);
        assertNotSame(differentEpoch, differentGroup);
        assertEquals(1, cache.size());
    }

    @Test
    void nonConsecutiveAbaCreatesThreeKeys() {
        var cache = new RecentModelPartBatchKeyCache();
        RenderType firstType = type("a");
        RenderType secondType = type("b");

        ModelPartBatchKey first = cache.getOrCreate(MESH, firstType, 2, 3);
        ModelPartBatchKey second = cache.getOrCreate(MESH, secondType, 2, 3);
        ModelPartBatchKey finalFirst = cache.getOrCreate(MESH, firstType, 2, 3);

        assertNotSame(first, second);
        assertNotSame(first, finalFirst);
        assertNotSame(second, finalFirst);
    }

    @Test
    void lifecycleAndMeshDestructionClearTheRecentReference() {
        var cache = new RecentModelPartBatchKeyCache();
        RenderType type = type("a");
        ModelPartBatchKey beforeBoundary = cache.getOrCreate(MESH, type, 2, 3);
        cache.clear();
        assertEquals(0, cache.size());
        assertNotSame(beforeBoundary, cache.getOrCreate(MESH, type, 2, 3));

        cache.clearIfReferences(mesh(2));
        assertEquals(1, cache.size());
        cache.clearIfReferences(mesh(1));
        assertEquals(0, cache.size());
    }

    @Test
    void backendLifecycleBoundariesReleaseTheRecentKey() {
        var backend = new Blaze3dModelPartBackend(256, 256, true, new ModelPartGpuMetrics());
        RecentModelPartBatchKeyCache cache = backend.recentBatchKeyForTesting();
        RenderType type = type("a");

        cache.getOrCreate(MESH, type, 1, 1);
        backend.beginFrame();
        assertEquals(0, cache.size());

        cache.getOrCreate(MESH, type, 1, 1);
        backend.beginGroup(false);
        assertEquals(0, cache.size());
        cache.getOrCreate(MESH, type, 1, 2);
        backend.endGroup();
        assertEquals(0, cache.size());

        cache.getOrCreate(MESH, type, 1, 2);
        backend.invalidatePipelines(2);
        assertEquals(0, cache.size());

        cache.getOrCreate(MESH, type, 1, 2);
        backend.destroy(mesh(1));
        assertEquals(0, cache.size());

        cache.getOrCreate(MESH, type, 1, 2);
        backend.clear();
        assertEquals(0, cache.size());

        cache.getOrCreate(MESH, type, 1, 2);
        backend.close();
        assertEquals(0, cache.size());
    }

    private static ModelPartGpuBackend.MeshHandle mesh(int token) {
        return new ModelPartGpuBackend.MeshHandle(token, 2, 3, 6, 1, 1);
    }

    private static RenderType type(String name) {
        return RenderType.create(
                name, RenderSetup.builder(RenderPipelines.ENTITY_CUTOUT).createRenderSetup());
    }
}
