package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class InstanceSubmissionOrderPlannerTest {
    private final InstanceSubmissionOrderPlanner planner = new InstanceSubmissionOrderPlanner();

    @Test
    void reorderableEntriesCompactByExactKeyInFirstSeenOrder() {
        var plan = planner.plan(new int[] {0, 1, 2, 3, 4}, 5, new Object[] {"A", "B", "A", "C", "A"}, 5, 0, true, true);

        assertArrayEquals(new int[] {0, 2, 4, 1, 3}, copy(plan.packedSourceIndices(), plan.instanceCount()));
        assertArrayEquals(new int[] {0, 1, 3}, copy(plan.representativeSourceIndices(), plan.batchCount()));
        assertArrayEquals(new int[] {0, 3, 4}, copy(plan.firstPackedInstances(), plan.batchCount()));
        assertArrayEquals(new int[] {3, 1, 1}, copy(plan.instanceCounts(), plan.batchCount()));
    }

    @Test
    void orderedEntriesOnlyMergeAdjacentEqualKeys() {
        var plan = planner.plan(new int[] {0, 1, 2, 3}, 4, new Object[] {"A", "A", "B", "A"}, 4, 7, false, true);

        assertArrayEquals(new int[] {0, 1, 2, 3}, copy(plan.packedSourceIndices(), plan.instanceCount()));
        assertArrayEquals(new int[] {0, 2, 3}, copy(plan.representativeSourceIndices(), plan.batchCount()));
        assertArrayEquals(new int[] {7, 9, 10}, copy(plan.firstPackedInstances(), plan.batchCount()));
        assertArrayEquals(new int[] {2, 1, 1}, copy(plan.instanceCounts(), plan.batchCount()));
    }

    @Test
    void disabledConsolidationProducesSingletonsWithoutReordering() {
        var plan = planner.plan(new int[] {2, 0, 1}, 3, new Object[] {"A", "B", "A"}, 3, 4, true, false);

        assertArrayEquals(new int[] {2, 0, 1}, copy(plan.packedSourceIndices(), plan.instanceCount()));
        assertArrayEquals(new int[] {4, 5, 6}, copy(plan.firstPackedInstances(), plan.batchCount()));
        assertArrayEquals(new int[] {1, 1, 1}, copy(plan.instanceCounts(), plan.batchCount()));
    }

    @Test
    void packedEntriesRemainAnExactSourcePermutation() {
        var plan = planner.plan(
                new int[] {0, 1, 2, 3, 4, 5}, 6, new Object[] {"C", "A", "B", "A", "C", "B"}, 6, 0, true, true);
        int[] packed = copy(plan.packedSourceIndices(), plan.instanceCount());
        Arrays.sort(packed);

        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5}, packed);
        assertEquals(6, plan.instanceCount());
    }

    @Test
    void invalidOrMissingKeysFailClosedAndClearTransientState() {
        assertThrows(
                IllegalArgumentException.class,
                () -> planner.plan(new int[] {0, 2}, 2, new Object[] {"A", "B"}, 2, 0, true, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> planner.plan(new int[] {0, 1}, 2, new Object[] {"A", null}, 2, 0, true, true));

        for (Object key : planner.tableBackingArray()) assertTrue(key == null);
    }

    private static int[] copy(int[] values, int count) {
        return Arrays.copyOf(values, count);
    }
}
