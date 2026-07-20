package dev.alex.threadium.render.modelpart.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ModelPartMeshCacheTest {
    @Test
    void rootIdentityHitReturnsCompletedMeshWithoutRecapture() {
        ModelPartMeshCache cache = new ModelPartMeshCache(2, 2, 10_000);
        Object root = new Object();
        AtomicInteger captures = new AtomicInteger();

        ImmutableModelPartMesh first = getOrCapture(cache, root, captures);
        ImmutableModelPartMesh second = getOrCapture(cache, root, captures);

        assertSame(first, second);
        assertEquals(1, captures.get());
        assertEquals(1, cache.rootCount());
    }

    @Test
    void exactEqualUniqueMeshesAreInternedAcrossDifferentRoots() {
        ModelPartMeshCache cache = new ModelPartMeshCache(2, 2, 10_000);
        ImmutableModelPartMesh first = cache.intern(new Object(), ModelPartMeshTestFixtures.oneQuadMesh(0));
        ImmutableModelPartMesh second = cache.intern(new Object(), ModelPartMeshTestFixtures.oneQuadMesh(0));

        assertSame(first, second);
        assertEquals(2, cache.rootCount());
        assertEquals(1, cache.uniqueMeshCount());
        assertEquals(first.retainedBytes(), cache.retainedBytes());
    }

    @Test
    void failedOrCapacityRejectedCapturesAreNotCached() {
        ModelPartMeshCache cache = new ModelPartMeshCache(1, 1, 1);
        Object failedRoot = new Object();
        ModelPartMeshCapture capture = new ModelPartMeshCapture(ModelPartMeshTestFixtures.DEFAULT_LIMITS);
        capture.beginNode(0);
        capture.position(0, 0, 0);

        assertThrows(IllegalStateException.class, () -> capture.complete(ModelPartMeshTestFixtures.structure(1)));
        assertNull(cache.get(failedRoot));

        assertThrows(
                ModelPartMeshCapacityException.class,
                () -> cache.intern(failedRoot, ModelPartMeshTestFixtures.oneQuadMesh(0)));
        assertNull(cache.get(failedRoot));
        assertEquals(0, cache.uniqueMeshCount());
        assertEquals(0, cache.retainedBytes());
    }

    @Test
    void lifecycleClearReleasesRootsUniqueMeshesAndRetainedBytes() {
        ModelPartMeshCache cache = new ModelPartMeshCache(1, 1, 10_000);
        Object root = new Object();
        cache.intern(root, ModelPartMeshTestFixtures.oneQuadMesh(0));
        assertFalse(cache.canCacheNewRoot());

        cache.clear();

        assertNull(cache.get(root));
        assertEquals(0, cache.rootCount());
        assertEquals(0, cache.uniqueMeshCount());
        assertEquals(0, cache.retainedBytes());
        assertTrue(cache.canCacheNewRoot());
    }

    private static ImmutableModelPartMesh getOrCapture(ModelPartMeshCache cache, Object root, AtomicInteger captures) {
        ImmutableModelPartMesh cached = cache.get(root);
        if (cached != null) return cached;
        captures.incrementAndGet();
        return cache.intern(root, ModelPartMeshTestFixtures.oneQuadMesh(0));
    }
}
