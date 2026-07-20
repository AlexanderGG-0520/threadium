package dev.alex.threadium.render.modelpart.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ModelPartStructureCacheTest {
    @Test
    void memoizationUsesRootIdentityWhileUniqueStructuresUseExactEquality() {
        ModelPartStructureCache cache = new ModelPartStructureCache(2);
        Object firstRoot = new String("same contents");
        Object secondRoot = new String("same contents");
        ModelPartStructureSnapshot first = emptySnapshot();
        ModelPartStructureSnapshot equal = emptySnapshot();

        cache.put(firstRoot, first);
        cache.put(secondRoot, equal);

        assertSame(first, cache.get(firstRoot));
        assertSame(equal, cache.get(secondRoot));
        assertEquals(2, cache.size());
        assertEquals(1, cache.uniqueStructureCount());
        assertFalse(cache.hasCapacity());
    }

    @Test
    void capacityIsBoundedAndClearIsALifecycleReset() {
        ModelPartStructureCache cache = new ModelPartStructureCache(1);
        Object firstRoot = new Object();
        Object rejectedRoot = new Object();
        cache.put(firstRoot, emptySnapshot());

        assertThrows(IllegalStateException.class, () -> cache.put(rejectedRoot, emptySnapshot()));

        cache.clear();

        assertNull(cache.get(firstRoot));
        assertEquals(0, cache.size());
        assertEquals(0, cache.uniqueStructureCount());
        assertTrue(cache.hasCapacity());
        ModelPartStructureSnapshot replacement = emptySnapshot();
        cache.put(rejectedRoot, replacement);
        assertSame(replacement, cache.get(rejectedRoot));
    }

    private static ModelPartStructureSnapshot emptySnapshot() {
        return ModelPartStructureSnapshot.of(List.of(new ModelPartStructureSnapshot.Node(0, -1, "root", List.of())));
    }
}
