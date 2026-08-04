package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class FrameBoneDataArenaTest {
    @Test
    void entriesRemainDistinctWithinFrameAndAreReusedNextFrame() {
        FrameBoneDataArena arena = new FrameBoneDataArena();
        ModelPartBoneData first = arena.acquire(4);
        ModelPartBoneData second = arena.acquire(4);
        assertNotSame(first, second);
        assertEquals(2, arena.pooledEntries());

        arena.beginFrame();
        assertSame(first, arena.acquire(4));
        assertSame(second, arena.acquire(4));
        assertEquals(2, arena.pooledEntries());
    }

    @Test
    void visibilityWordsAreClearedBeforeReuse() {
        FrameBoneDataArena arena = new FrameBoneDataArena();
        ModelPartBoneData data = arena.acquire(65);
        data.visibility()[0] = -1L;
        data.visibility()[1] = -1L;

        arena.beginFrame();
        ModelPartBoneData reused = arena.acquire(65);
        assertSame(data, reused);
        assertEquals(0L, reused.visibility()[0]);
        assertEquals(0L, reused.visibility()[1]);
    }

    @Test
    void exactBoneCountsUseSeparateBuckets() {
        FrameBoneDataArena arena = new FrameBoneDataArena();
        ModelPartBoneData fourBones = arena.acquire(4);
        ModelPartBoneData fiveBones = arena.acquire(5);
        assertEquals(4 * 28, fourBones.matrices().length);
        assertEquals(5 * 28, fiveBones.matrices().length);
        assertEquals(2, arena.pooledEntries());
    }
}
