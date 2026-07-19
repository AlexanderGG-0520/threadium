package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.junit.jupiter.api.Test;

class InstanceSubmissionOrderPlannerTest {
    private final InstanceSubmissionOrderPlanner planner = new InstanceSubmissionOrderPlanner();

    @Test
    void emptyInputProducesNoInstancesOrBatches() {
        var plan = plan(new int[0], new String[0], 0, true, true);

        assertEquals(0, plan.instanceCount());
        assertEquals(0, plan.batchCount());
    }

    @Test
    void oneInstanceProducesOneBatch() {
        var plan = plan(indices(1), new String[] {"A"}, 0, true, true);

        assertArrayEquals(new int[] {0}, packed(plan));
        assertArrayEquals(new int[] {0}, representatives(plan));
        assertArrayEquals(new int[] {0}, firstPacked(plan));
        assertArrayEquals(new int[] {1}, counts(plan));
    }

    @Test
    void reorderablePlanCompactsEqualKeysInFirstSeenOrder() {
        var plan = plan(indices(5), new String[] {"A", "B", "A", "C", "A"}, 0, true, true);

        assertArrayEquals(new int[] {0, 2, 4, 1, 3}, packed(plan));
        assertArrayEquals(new int[] {0, 1, 3}, representatives(plan));
        assertArrayEquals(new int[] {0, 3, 4}, firstPacked(plan));
        assertArrayEquals(new int[] {3, 1, 1}, counts(plan));
    }

    @Test
    void firstSeenKeyGroupOrderUsesTheSuppliedSourceOrder() {
        var plan = plan(new int[] {2, 0, 3, 1}, new String[] {"A", "B", "C", "A"}, 0, true, true);

        assertArrayEquals(new int[] {2, 0, 3, 1}, packed(plan));
        assertArrayEquals(new int[] {2, 0, 1}, representatives(plan));
    }

    @Test
    void compactionPreservesRelativeOrderInsideEachKey() {
        var plan = plan(new int[] {3, 1, 2, 0}, new String[] {"A", "B", "A", "A"}, 0, true, true);

        assertArrayEquals(new int[] {3, 2, 0, 1}, packed(plan));
    }

    @Test
    void strictlyOrderedPlanDoesNotMergeSeparatedKeys() {
        var plan = plan(indices(4), new String[] {"A", "A", "B", "A"}, 0, false, true);

        assertArrayEquals(new int[] {0, 1, 2, 3}, packed(plan));
        assertArrayEquals(new int[] {0, 2, 3}, representatives(plan));
        assertArrayEquals(new int[] {2, 1, 1}, counts(plan));
    }

    @Test
    void strictlyOrderedPlanMergesAdjacentEqualKeys() {
        var plan = plan(indices(3), new String[] {"A", "A", "B"}, 0, false, true);

        assertArrayEquals(new int[] {0, 2}, representatives(plan));
        assertArrayEquals(new int[] {0, 2}, firstPacked(plan));
        assertArrayEquals(new int[] {2, 1}, counts(plan));
    }

    @Test
    void disabledConsolidationPreservesOrderAndProducesSingletons() {
        var plan = plan(indices(3), new String[] {"A", "B", "A"}, 5, true, false);

        assertArrayEquals(new int[] {0, 1, 2}, packed(plan));
        assertArrayEquals(new int[] {0, 1, 2}, representatives(plan));
        assertArrayEquals(new int[] {5, 6, 7}, firstPacked(plan));
        assertArrayEquals(new int[] {1, 1, 1}, counts(plan));
    }

    @Test
    void equalDistinctKeyObjectsShareOneBatch() {
        var plan = plan(indices(2), new String[] {new String("A"), new String("A")}, 0, true, true);

        assertEquals(1, plan.batchCount());
        assertArrayEquals(new int[] {2}, counts(plan));
    }

    @Test
    void sharedAndDistinctEqualModelPartKeysProduceEquivalentPlans() {
        RenderType type = RenderType.create(
                "planner-equivalence",
                RenderSetup.builder(RenderPipelines.ENTITY_CUTOUT).createRenderSetup());
        var mesh = new ModelPartGpuBackend.MeshHandle(1, 2, 3, 6, 1, 1);
        ModelPartBatchKey shared = new ModelPartBatchKey(mesh, type, 2, 3);
        ModelPartBatchKey[] sharedKeys = {shared, shared, shared, shared};
        ModelPartBatchKey[] distinctKeys = {
            new ModelPartBatchKey(mesh, type, 2, 3),
            new ModelPartBatchKey(mesh, type, 2, 3),
            new ModelPartBatchKey(mesh, type, 2, 3),
            new ModelPartBatchKey(mesh, type, 2, 3)
        };
        var distinctPlan = new InstanceSubmissionOrderPlanner().plan(indices(4), 4, distinctKeys, 4, 7, true, true);
        var sharedPlan = new InstanceSubmissionOrderPlanner().plan(indices(4), 4, sharedKeys, 4, 7, true, true);

        assertArrayEquals(packed(distinctPlan), packed(sharedPlan));
        assertArrayEquals(representatives(distinctPlan), representatives(sharedPlan));
        assertArrayEquals(firstPacked(distinctPlan), firstPacked(sharedPlan));
        assertArrayEquals(counts(distinctPlan), counts(sharedPlan));
        assertEquals(distinctPlan.instanceCount(), sharedPlan.instanceCount());
        assertEquals(distinctPlan.batchCount(), sharedPlan.batchCount());
    }

    @Test
    void keysDifferingInSignificantFieldsRemainSeparate() {
        var plan = plan(
                indices(3),
                new TestKey[] {new TestKey("A", 1), new TestKey("A", 2), new TestKey("A", 1)},
                0,
                true,
                true);

        assertArrayEquals(new int[] {0, 2, 1}, packed(plan));
        assertArrayEquals(new int[] {2, 1}, counts(plan));
    }

    @Test
    void packedIndicesAreAnExactPermutationOfSources() {
        var plan = plan(indices(6), new String[] {"C", "A", "B", "A", "C", "B"}, 0, true, true);
        int[] sorted = packed(plan);
        Arrays.sort(sorted);

        assertArrayEquals(indices(6), sorted);
        assertEquals(6, plan.instanceCount());
    }

    @Test
    void sourceToPackedMappingMatchesPackedOrder() {
        var plan = plan(indices(5), new String[] {"A", "B", "A", "C", "A"}, 0, true, true);
        int[] sourceToPacked = new int[plan.instanceCount()];
        for (int packed = 0; packed < plan.instanceCount(); packed++) {
            sourceToPacked[plan.packedSourceIndices()[packed]] = packed;
        }

        assertArrayEquals(new int[] {0, 3, 1, 4, 2}, sourceToPacked);
    }

    @Test
    void packedBaseIsAddedToEveryBatchOffset() {
        var plan = plan(indices(3), new String[] {"A", "B", "A"}, 7, true, true);

        assertArrayEquals(new int[] {7, 9}, firstPacked(plan));
        assertArrayEquals(new int[] {2, 1}, counts(plan));
    }

    @Test
    void emptyInputAfterPopulatedInputDoesNotExposeStaleData() {
        plan(indices(4), new String[] {"A", "B", "A", "C"}, 0, true, true);

        var empty = plan(new int[0], new String[0], 0, true, true);

        assertEquals(0, empty.instanceCount());
        assertEquals(0, empty.batchCount());
        assertArrayEquals(new int[0], packed(empty));
        assertArrayEquals(new int[0], counts(empty));
    }

    @Test
    void smallLargeSmallSequenceDoesNotLeakState() {
        plan(indices(1), repeatedKeys(1), 0, true, true);
        plan(indices(65), uniqueKeys(65), 0, true, true);

        var small = plan(indices(2), new String[] {"X", "X"}, 4, false, true);

        assertArrayEquals(new int[] {0, 1}, packed(small));
        assertArrayEquals(new int[] {4}, firstPacked(small));
        assertArrayEquals(new int[] {2}, counts(small));
    }

    @Test
    void planningAtExactCapacityReusesBackingArray() {
        plan(indices(4), uniqueKeys(4), 0, true, true);
        int capacity = planner.packedCapacity();
        int[] backing = planner.packedBackingArray();

        plan(indices(capacity), uniqueKeys(capacity), 0, true, true);

        assertSame(backing, planner.packedBackingArray());
    }

    @Test
    void crossingCapacityBoundaryGrowsBackingArray() {
        plan(indices(4), uniqueKeys(4), 0, true, true);
        int capacity = planner.packedCapacity();
        int[] backing = planner.packedBackingArray();

        plan(indices(capacity + 1), uniqueKeys(capacity + 1), 0, true, true);

        assertNotSame(backing, planner.packedBackingArray());
    }

    @Test
    void repeatedPlanningWithinCapacityReusesEntryAndHashBackingArrays() {
        plan(indices(32), uniqueKeys(32), 0, true, true);
        int[] packed = planner.packedBackingArray();
        Object[] table = planner.tableBackingArray();

        plan(indices(8), repeatedKeys(8), 0, true, true);

        assertSame(packed, planner.packedBackingArray());
        assertSame(table, planner.tableBackingArray());
    }

    @Test
    void planningModesDoNotLeakStateAcrossCalls() {
        plan(indices(4), new String[] {"A", "B", "A", "B"}, 0, true, true);
        plan(indices(4), new String[] {"A", "B", "A", "B"}, 0, false, true);
        plan(indices(4), new String[] {"A", "A", "A", "A"}, 0, true, false);

        var finalPlan = plan(indices(5), new String[] {"C", "A", "C", "B", "A"}, 2, true, true);

        assertArrayEquals(new int[] {0, 2, 1, 4, 3}, packed(finalPlan));
        assertArrayEquals(new int[] {2, 4, 6}, firstPacked(finalPlan));
        assertArrayEquals(new int[] {2, 2, 1}, counts(finalPlan));
    }

    @Test
    void invalidSourceIndexIsRejectedInsteadOfProducingAnInvalidPlan() {
        assertThrows(
                IllegalArgumentException.class, () -> plan(new int[] {0, 2}, new String[] {"A", "B"}, 0, true, true));
    }

    @Test
    void missingExactKeyIsRejectedInsteadOfProducingAnInvalidPlan() {
        assertThrows(IllegalArgumentException.class, () -> plan(indices(2), new String[] {"A", null}, 0, true, true));
    }

    @Test
    void emptyPlanClearsTouchedSlotsFromThePreviousCompaction() {
        plan(indices(3), new String[] {"A", "B", "A"}, 0, true, true);

        plan(new int[0], new String[0], 0, true, true);

        assertTrue(tableIsClear());
    }

    @Test
    void clearReleasesTouchedSlotsAfterAPlannerFailure() {
        var previous = plan(indices(2), new String[] {"A", "B"}, 0, true, true);

        assertThrows(IllegalArgumentException.class, () -> plan(indices(2), new String[] {"A", null}, 0, true, true));

        assertTrue(tableIsClear());
        assertEquals(0, previous.instanceCount());
    }

    private InstanceSubmissionOrderPlanner.Plan plan(
            int[] sourceIndices, Object[] keys, int packedBase, boolean reorderable, boolean consolidate) {
        return planner.plan(
                sourceIndices, sourceIndices.length, keys, keys.length, packedBase, reorderable, consolidate);
    }

    private static int[] packed(InstanceSubmissionOrderPlanner.Plan plan) {
        return Arrays.copyOf(plan.packedSourceIndices(), plan.instanceCount());
    }

    private static int[] representatives(InstanceSubmissionOrderPlanner.Plan plan) {
        return Arrays.copyOf(plan.representativeSourceIndices(), plan.batchCount());
    }

    private static int[] firstPacked(InstanceSubmissionOrderPlanner.Plan plan) {
        return Arrays.copyOf(plan.firstPackedInstances(), plan.batchCount());
    }

    private static int[] counts(InstanceSubmissionOrderPlanner.Plan plan) {
        return Arrays.copyOf(plan.instanceCounts(), plan.batchCount());
    }

    private static int[] indices(int count) {
        int[] result = new int[count];
        for (int i = 0; i < count; i++) result[i] = i;
        return result;
    }

    private static String[] uniqueKeys(int count) {
        String[] result = new String[count];
        for (int i = 0; i < count; i++) result[i] = "key-" + i;
        return result;
    }

    private static String[] repeatedKeys(int count) {
        String[] result = new String[count];
        Arrays.fill(result, "A");
        return result;
    }

    private boolean tableIsClear() {
        for (Object key : planner.tableBackingArray()) {
            if (key != null) return false;
        }
        return true;
    }

    private record TestKey(String name, int variant) {}
}
