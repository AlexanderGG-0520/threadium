package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QueuedModelPartArena1211Test {
    @Test
    void growsAndPreservesExactQueuedValues() {
        QueuedModelPartArena1211<Object> arena = new QueuedModelPartArena1211<>(1, 3);
        Object firstGroup = new Object();
        Object firstMesh = new Object();
        Object secondMesh = new Object();

        arena.add(firstGroup, firstMesh, null, null, null, 10, 20, 30, null, 40, 50);
        arena.add(new Object(), secondMesh, null, null, null, 11, 21, 31, null, 41, 51);

        assertEquals(2, arena.size());
        assertTrue(arena.capacity() >= 2);
        assertSame(firstGroup, arena.groupOwner(0));
        assertSame(firstMesh, arena.mesh(0));
        assertSame(secondMesh, arena.mesh(1));
        assertEquals(10, arena.light(0));
        assertEquals(21, arena.overlay(1));
        assertEquals(31, arena.color(1));
        assertEquals(40, arena.worldGeneration(0));
        assertEquals(51, arena.resourceGeneration(1));
    }

    @Test
    void truncateAndClearReuseAllocatedStorage() {
        QueuedModelPartArena1211<Object> arena = new QueuedModelPartArena1211<>(2, 4);
        Object first = new Object();
        Object replacement = new Object();
        arena.add(new Object(), first, null, null, null, 1, 2, 3, null, 4, 5);
        arena.add(new Object(), new Object(), null, null, null, 6, 7, 8, null, 9, 10);
        int capacity = arena.capacity();

        arena.truncate(1);
        arena.add(new Object(), replacement, null, null, null, 11, 12, 13, null, 14, 15);

        assertSame(first, arena.mesh(0));
        assertSame(replacement, arena.mesh(1));
        assertEquals(capacity, arena.capacity());
        arena.clear();
        assertTrue(arena.isEmpty());
        assertFalse(arena.capacity() == 0);
    }

    @Test
    void rejectsGrowthPastConfiguredMaximum() {
        QueuedModelPartArena1211<Object> arena = new QueuedModelPartArena1211<>(1, 1);
        arena.add(new Object(), new Object(), null, null, null, 0, 0, 0, null, 0, 0);
        assertThrows(
                IllegalStateException.class,
                () -> arena.add(new Object(), new Object(), null, null, null, 0, 0, 0, null, 0, 0));
    }
}
