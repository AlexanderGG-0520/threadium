package dev.alex.threadium.render.phase;

import com.google.common.primitives.Floats;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class TranslucentSorterTest {
    @Test void handlesEmptyAndSingleton() {
        assertArrayEquals(new int[0], TranslucentSorter.sortIndices(new float[0]));
        assertArrayEquals(new int[]{0}, TranslucentSorter.sortIndices(new float[]{4.0f}));
    }

    @Test void sortsBackToFront() {
        assertDistancesDescending(new float[]{1, 5, 2, -3});
        assertDistancesDescending(new float[]{5, 4, 3, 2, 1});
        assertDistancesDescending(new float[]{1, 2, 3, 4, 5});
        assertDistancesDescending(new float[]{2, 2, 1, 2});
    }

    @Test void matchesFloatsCompareForSpecialValues() {
        float[] values = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, +0.0f, -0.0f, 1.0f, -1.0f};
        assertDistancesDescending(values);
        int[] order = TranslucentSorter.sortIndices(values);
        assertTrue(Float.isNaN(values[order[0]]));
        assertEquals(Float.POSITIVE_INFINITY, values[order[1]]);
        assertEquals(+0.0f, values[order[3]]);
        assertEquals(-0.0f, values[order[4]]);
    }

    @Test void largeArrayIsAPermutationAndSorted() {
        float[] values = new float[100_000];
        Random random = new Random(42);
        for (int index = 0; index < values.length; index++) values[index] = random.nextFloat() * 20_000 - 10_000;
        int[] order = TranslucentSorter.sortIndices(values);
        boolean[] seen = new boolean[values.length];
        for (int index : order) { assertFalse(seen[index]); seen[index] = true; }
        assertTrue(Arrays.stream(order).allMatch(index -> seen[index]));
        assertDescending(values, order);
    }

    private static void assertDistancesDescending(float[] values) {
        assertDescending(values, TranslucentSorter.sortIndices(values));
    }

    private static void assertDescending(float[] values, int[] order) {
        for (int index = 1; index < order.length; index++)
            assertTrue(Floats.compare(values[order[index - 1]], values[order[index]]) >= 0);
    }
}
