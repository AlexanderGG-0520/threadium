package dev.alex.threadium.render.modelpart.pose;

import java.util.Objects;

/** Bounded single-use builder for exact per-bone matrices and visibility state. */
public final class ModelPartPoseCapture {
    private final int boneCount;
    private final int[] positionMatrixBits;
    private final int[] normalMatrixBits;
    private final long[] treeVisible;
    private final long[] drawVisible;
    private final long[] normalNeedsNormalization;
    private int capturedBones;
    private boolean completed;

    public ModelPartPoseCapture(int boneCount, Limits limits) {
        Objects.requireNonNull(limits, "limits");
        if (boneCount <= 0 || boneCount > limits.maximumBones()) {
            throw new ModelPartPoseCapacityException("ModelPart pose bone limit exceeded");
        }
        long positionElements =
                Math.multiplyExact((long) boneCount, ImmutableModelPartBonePose.POSITION_ELEMENTS_PER_BONE);
        long normalElements = Math.multiplyExact((long) boneCount, ImmutableModelPartBonePose.NORMAL_ELEMENTS_PER_BONE);
        long matrixElements = Math.addExact(positionElements, normalElements);
        long visibilityWords = Math.addExact((long) boneCount, 63) / 64;
        if (matrixElements > limits.maximumMatrixElements() || visibilityWords > limits.maximumVisibilityWords()) {
            throw new ModelPartPoseCapacityException("ModelPart pose element limit exceeded");
        }
        long retainedBytes = ImmutableModelPartBonePose.retainedBytes(
                Math.toIntExact(positionElements),
                Math.toIntExact(normalElements),
                Math.toIntExact(visibilityWords),
                Math.toIntExact(visibilityWords),
                Math.toIntExact(visibilityWords));
        if (retainedBytes > limits.maximumPoseBytes()) {
            throw new ModelPartPoseCapacityException("ModelPart pose retained-byte limit exceeded");
        }
        this.boneCount = boneCount;
        this.positionMatrixBits = new int[Math.toIntExact(positionElements)];
        this.normalMatrixBits = new int[Math.toIntExact(normalElements)];
        this.treeVisible = new long[Math.toIntExact(visibilityWords)];
        this.drawVisible = new long[Math.toIntExact(visibilityWords)];
        this.normalNeedsNormalization = new long[Math.toIntExact(visibilityWords)];
    }

    public void captureBone(
            int boneIndex,
            float[] positionMatrix,
            float[] normalMatrix,
            boolean treeIsVisible,
            boolean drawIsVisible,
            boolean normalRequiresNormalization) {
        ensureOpen();
        Objects.requireNonNull(positionMatrix, "positionMatrix");
        Objects.requireNonNull(normalMatrix, "normalMatrix");
        if (boneIndex != capturedBones || boneIndex >= boneCount) {
            throw new IllegalArgumentException("Bone poses must be captured once in deterministic index order");
        }
        if (positionMatrix.length < ImmutableModelPartBonePose.POSITION_ELEMENTS_PER_BONE
                || normalMatrix.length < ImmutableModelPartBonePose.NORMAL_ELEMENTS_PER_BONE) {
            throw new IllegalArgumentException("Scratch matrix data is incomplete");
        }
        int positionOffset = boneIndex * ImmutableModelPartBonePose.POSITION_ELEMENTS_PER_BONE;
        int normalOffset = boneIndex * ImmutableModelPartBonePose.NORMAL_ELEMENTS_PER_BONE;
        for (int index = 0; index < ImmutableModelPartBonePose.POSITION_ELEMENTS_PER_BONE; index++) {
            positionMatrixBits[positionOffset + index] = Float.floatToRawIntBits(positionMatrix[index]);
        }
        for (int index = 0; index < ImmutableModelPartBonePose.NORMAL_ELEMENTS_PER_BONE; index++) {
            normalMatrixBits[normalOffset + index] = Float.floatToRawIntBits(normalMatrix[index]);
        }
        set(treeVisible, boneIndex, treeIsVisible);
        set(drawVisible, boneIndex, drawIsVisible);
        set(normalNeedsNormalization, boneIndex, normalRequiresNormalization);
        capturedBones++;
    }

    void resetForFrameReuse() {
        capturedBones = 0;
        completed = false;
        java.util.Arrays.fill(treeVisible, 0L);
        java.util.Arrays.fill(drawVisible, 0L);
        java.util.Arrays.fill(normalNeedsNormalization, 0L);
    }

    public ImmutableModelPartBonePose complete() {
        ensureOpen();
        if (capturedBones != boneCount) throw new IllegalStateException("Pose capture is incomplete");
        completed = true;
        return ImmutableModelPartBonePose.takeOwnership(
                boneCount, positionMatrixBits, normalMatrixBits, treeVisible, drawVisible, normalNeedsNormalization);
    }

    private void ensureOpen() {
        if (completed) throw new IllegalStateException("Pose capture is already complete");
    }

    private static void set(long[] words, int bitIndex, boolean value) {
        if (value) words[bitIndex >>> 6] |= 1L << (bitIndex & 63);
    }

    public record Limits(
            int maximumBones, long maximumMatrixElements, int maximumVisibilityWords, long maximumPoseBytes) {
        public Limits {
            if (maximumBones <= 0
                    || maximumMatrixElements <= 0
                    || maximumVisibilityWords <= 0
                    || maximumPoseBytes < 0) {
                throw new IllegalArgumentException("Pose capture limits are invalid");
            }
        }
    }
}
