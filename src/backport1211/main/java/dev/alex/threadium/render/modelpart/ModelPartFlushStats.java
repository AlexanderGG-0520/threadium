package dev.alex.threadium.render.modelpart;

/** Immutable summary of one frame-local ModelPart submission group. */
public record ModelPartFlushStats(
        int instances,
        int drawCalls,
        int batchableInstances,
        int batchableDrawCalls,
        int sortedInstances,
        int sortedDrawCalls,
        int singletonBatches,
        int multiInstanceBatches,
        int maximumInstancesPerDraw,
        int totalInstancesInMultiDraws,
        int instanceUploadCalls,
        int boneUploadCalls,
        long instanceBytes,
        long boneBytes) {
    public static final ModelPartFlushStats EMPTY =
            new ModelPartFlushStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    public ModelPartFlushStats {
        if (instances < 0
                || drawCalls < 0
                || batchableInstances < 0
                || batchableDrawCalls < 0
                || sortedInstances < 0
                || sortedDrawCalls < 0
                || singletonBatches < 0
                || multiInstanceBatches < 0
                || maximumInstancesPerDraw < 0
                || totalInstancesInMultiDraws < 0
                || instanceUploadCalls < 0
                || boneUploadCalls < 0
                || instanceBytes < 0
                || boneBytes < 0) {
            throw new IllegalArgumentException("ModelPart flush statistics cannot be negative");
        }
    }
}
