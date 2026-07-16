package dev.alex.threadium.render.phase;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DetachedTranslucentSnapshotTest {
    @Test
    void originalArraysCannotChangeSnapshot() {
        Object first = new Object();
        Object second = new Object();
        Object[] references = {first, second};
        float[] distances = {1.0f, 2.0f};
        DetachedTranslucentSnapshot snapshot = new DetachedTranslucentSnapshot(references, distances);

        references[0] = new Object();
        distances[0] = 99.0f;

        assertSame(first, snapshot.opaqueReference(0));
        assertEquals(1.0f, snapshot.distance(0));
        assertArrayEquals(new int[] {1, 0}, snapshot.sortedIndices());
    }
}
