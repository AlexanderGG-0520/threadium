package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PoseExtractionAllocationModelTest {
    private static final int COW_INSTANCES = 256;
    private static final int FLOATS_PER_BONE = 28;

    @Test
    void warmScratchRemovesBoneScaledAllocationsFromAnimatedExtraction() {
        for (int bones : new int[] {7, 12, 64}) {
            AllocationEstimate legacy = legacyExtractor(bones, 1).times(COW_INSTANCES);
            AllocationEstimate optimized = warmScratchExtractor(bones).times(COW_INSTANCES);

            assertEquals(0, optimized.temporaryObjects());
            assertEquals(3L * COW_INSTANCES, optimized.heapObjects());
            assertTrue(optimized.heapObjects() * 5 < legacy.heapObjects());
            assertTrue(reduction(legacy.heapObjects(), optimized.heapObjects()) > 0.85);
            assertEquals(0, optimized.referenceElements());
            assertEquals(0, optimized.booleanElements());
            assertEquals(0, optimized.copiedLongElements());
        }
    }

    @Test
    void staticDedupAndAnimatedUniquePoseScenariosRemainDistinct() {
        int bones = 12;
        AllocationEstimate legacyStatic = legacyExtractor(bones, 1);
        AllocationEstimate optimizedStatic = warmScratchExtractor(bones);
        AllocationEstimate legacyAnimated = legacyStatic.times(COW_INSTANCES);
        AllocationEstimate optimizedAnimated = optimizedStatic.times(COW_INSTANCES);

        assertEquals(32, legacyStatic.heapObjects());
        assertEquals(3, optimizedStatic.heapObjects());
        assertEquals(8_192, legacyAnimated.heapObjects());
        assertEquals(768, optimizedAnimated.heapObjects());
        assertEquals(3_072, legacyAnimated.referenceElements());
        assertEquals(3_072, legacyAnimated.booleanElements());
        assertEquals(256, legacyAnimated.copiedLongElements());
    }

    @Test
    void retainedMatrixOutputRemainsWhileVisibilityAndTemporaryCopiesShrink() {
        int bones = 12;
        AllocationEstimate legacy = legacyExtractor(bones, 1).times(COW_INSTANCES);
        AllocationEstimate optimized = warmScratchExtractor(bones).times(COW_INSTANCES);

        assertEquals(86_016, legacy.floatElements());
        assertEquals(legacy.floatElements(), optimized.floatElements());
        assertEquals(512, legacy.longElements());
        assertEquals(256, optimized.longElements());
        assertEquals(256, legacy.copiedLongElements());
        assertEquals(0, optimized.copiedLongElements());
    }

    @Test
    void scratchGrowthCostIsOneTimeRatherThanPerPose() {
        int bones = 12;
        AllocationEstimate growth = scratchGrowth(bones);
        AllocationEstimate animatedFrame = warmScratchExtractor(bones).times(COW_INSTANCES);

        assertEquals(14, growth.heapObjects());
        assertEquals(12, growth.referenceElements());
        assertEquals(12, growth.booleanElements());
        assertTrue(growth.heapObjects() < animatedFrame.heapObjects());
    }

    private static AllocationEstimate legacyExtractor(int bones, int roots) {
        int visibilityWords = (bones + 63) >>> 6;
        // Arrays: accumulated, tree visibility, matrix output, temporary visibility words, copied visibility words.
        long arrays = 5;
        // Objects: ModelPartBoneData, VisibilityMask, one Matrix4f per root, and Matrix4f + Matrix3f per bone.
        long ordinaryObjects = 2L + roots + 2L * bones;
        return new AllocationEstimate(
                arrays + ordinaryObjects,
                arrays + ordinaryObjects - 3,
                3,
                (long) bones * FLOATS_PER_BONE,
                2L * visibilityWords,
                bones,
                bones,
                visibilityWords);
    }

    private static AllocationEstimate warmScratchExtractor(int bones) {
        int visibilityWords = (bones + 63) >>> 6;
        // Only the output matrix array, output visibility array, and ModelPartBoneData survive each extraction.
        return new AllocationEstimate(3, 0, 3, (long) bones * FLOATS_PER_BONE, visibilityWords, 0, 0, 0);
    }

    private static AllocationEstimate scratchGrowth(int bones) {
        // A Matrix4f reference array, a boolean array, and one reusable Matrix4f per capacity slot.
        return new AllocationEstimate(2L + bones, 2L + bones, 0, 0, 0, bones, bones, 0);
    }

    private static double reduction(long before, long after) {
        return 1.0 - (double) after / before;
    }

    private record AllocationEstimate(
            long heapObjects,
            long temporaryObjects,
            long retainedObjects,
            long floatElements,
            long longElements,
            long referenceElements,
            long booleanElements,
            long copiedLongElements) {
        AllocationEstimate times(long multiplier) {
            return new AllocationEstimate(
                    heapObjects * multiplier,
                    temporaryObjects * multiplier,
                    retainedObjects * multiplier,
                    floatElements * multiplier,
                    longElements * multiplier,
                    referenceElements * multiplier,
                    booleanElements * multiplier,
                    copiedLongElements * multiplier);
        }
    }
}
