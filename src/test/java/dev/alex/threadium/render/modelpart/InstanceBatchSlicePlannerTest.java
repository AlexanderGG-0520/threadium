package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class InstanceBatchSlicePlannerTest {
    @Test
    void singletonBatchesAdvanceByOneInstance() {
        var slices = InstanceBatchSlicePlanner.plan(0, 96, List.of(1, 1, 1), 3);
        assertEquals(
                List.of(0L, 96L, 192L),
                slices.stream().map(InstanceBatchSlicePlanner.Slice::byteOffset).toList());
        assertEquals(
                List.of(0, 1, 2),
                slices.stream()
                        .map(InstanceBatchSlicePlanner.Slice::firstInstance)
                        .toList());
    }

    @Test
    void consolidatedBatchOffsetsFollowPackedCounts() {
        var slices = InstanceBatchSlicePlanner.plan(0, 96, List.of(3, 2, 1), 6);
        assertEquals(
                List.of(0L, 288L, 480L),
                slices.stream().map(InstanceBatchSlicePlanner.Slice::byteOffset).toList());
        assertEquals(
                List.of(3, 2, 1),
                slices.stream()
                        .map(InstanceBatchSlicePlanner.Slice::instanceCount)
                        .toList());
    }

    @Test
    void uploadedSliceBaseOffsetIsPreserved() {
        var slices = InstanceBatchSlicePlanner.plan(4096, 96, List.of(1, 2), 3);
        assertEquals(
                List.of(4096L, 4192L),
                slices.stream().map(InstanceBatchSlicePlanner.Slice::byteOffset).toList());
    }

    @Test
    void exactCoverageIsAccepted() {
        assertDoesNotThrow(() -> InstanceBatchSlicePlanner.plan(0, 96, List.of(2, 3), 5));
    }

    @Test
    void underCoverageIsRejected() {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> InstanceBatchSlicePlanner.plan(0, 96, List.of(2, 2), 5));
        assertEquals("ModelPart batch instance coverage mismatch: planned=4, uploaded=5", failure.getMessage());
    }

    @Test
    void overCoverageIsRejected() {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> InstanceBatchSlicePlanner.plan(0, 96, List.of(3, 3), 5));
        assertEquals("ModelPart batch instance coverage mismatch: planned=6, uploaded=5", failure.getMessage());
    }

    @Test
    void planningPreservesQueueOrderAndDoesNotRewriteBoneBases() {
        var slices = InstanceBatchSlicePlanner.plan(0, 96, List.of(1, 1, 1), 3);
        assertEquals(
                List.of(0, 1, 2),
                slices.stream()
                        .map(InstanceBatchSlicePlanner.Slice::firstInstance)
                        .toList());
        assertArrayEquals(new int[] {0, 4, 11}, BoneBaseOffsets.compute(new int[] {4, 7, 2}, 13));
    }

    @Test
    void queueBeforeSuppressionPolicyIsUnchanged() {
        PipelineValidity valid = new PipelineValidity(PipelineValidityState.VALID, 4, "valid");
        assertEquals(
                ModelPartInterceptionResult.PASS_THROUGH, Blaze3dSubmissionPolicy.replacementResult(valid, 4, false));
        assertEquals(
                ModelPartInterceptionResult.GPU_REPLACED, Blaze3dSubmissionPolicy.replacementResult(valid, 4, true));
    }
}
