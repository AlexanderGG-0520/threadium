package dev.alex.threadium.render.modelpart;

import java.util.ArrayList;
import java.util.List;

public final class InstanceBatchSlicePlanner {
    private InstanceBatchSlicePlanner() { }

    public static List<Slice> plan(long uploadedBaseOffset, int stride, List<Integer> batchCounts, int uploadedInstanceCount) {
        if (uploadedBaseOffset < 0 || stride <= 0 || uploadedInstanceCount < 0) throw new IllegalArgumentException("invalid instance upload layout");
        ArrayList<Slice> slices = new ArrayList<>(batchCounts.size());
        int firstInstance = 0;
        for (int count : batchCounts) {
            if (count <= 0) throw new IllegalArgumentException("batch instance count must be positive");
            slices.add(slice(uploadedBaseOffset, stride, firstInstance, count));
            firstInstance = Math.addExact(firstInstance, count);
        }
        requireCoverage(firstInstance, uploadedInstanceCount);
        return List.copyOf(slices);
    }

    public static Slice slice(long uploadedBaseOffset, int stride, int firstInstance, int instanceCount) {
        if (uploadedBaseOffset < 0 || stride <= 0 || firstInstance < 0 || instanceCount <= 0) throw new IllegalArgumentException("invalid instance batch layout");
        long relativeOffset = Math.multiplyExact((long) firstInstance, stride);
        long byteLength = Math.multiplyExact((long) instanceCount, stride);
        return new Slice(firstInstance, instanceCount, Math.addExact(uploadedBaseOffset, relativeOffset), byteLength);
    }

    public static void requireCoverage(int plannedInstanceCount, int uploadedInstanceCount) {
        if (plannedInstanceCount != uploadedInstanceCount) {
            throw new IllegalStateException("ModelPart batch instance coverage mismatch: planned=" + plannedInstanceCount + ", uploaded=" + uploadedInstanceCount);
        }
    }

    public record Slice(int firstInstance, int instanceCount, long byteOffset, long byteLength) { }
}
