package dev.alex.threadium.render.modelpart.pose;

import java.util.Objects;

/**
 * Immutable, Minecraft-object-free animated bone state. Matrix arrays follow JOML's column-major get(float[]) order: 16
 * position elements and 9 normal elements per bone.
 */
public final class ImmutableModelPartBonePose {
    public static final int POSITION_ELEMENTS_PER_BONE = 16;
    public static final int NORMAL_ELEMENTS_PER_BONE = 9;

    private final int boneCount;
    private final int[] positionMatrixBits;
    private final int[] normalMatrixBits;
    private final long[] treeVisible;
    private final long[] drawVisible;
    private final long[] normalNeedsNormalization;
    private final ModelPartPoseKey key;
    private final long retainedBytes;
    private final boolean finite;

    private ImmutableModelPartBonePose(
            int boneCount,
            int[] positionMatrixBits,
            int[] normalMatrixBits,
            long[] treeVisible,
            long[] drawVisible,
            long[] normalNeedsNormalization,
            boolean takeOwnership) {
        this.boneCount = boneCount;
        this.positionMatrixBits = takeOwnership ? positionMatrixBits : positionMatrixBits.clone();
        this.normalMatrixBits = takeOwnership ? normalMatrixBits : normalMatrixBits.clone();
        this.treeVisible = takeOwnership ? treeVisible : treeVisible.clone();
        this.drawVisible = takeOwnership ? drawVisible : drawVisible.clone();
        this.normalNeedsNormalization = takeOwnership ? normalNeedsNormalization : normalNeedsNormalization.clone();
        validate();
        this.key = new ModelPartPoseKey(
                boneCount,
                this.positionMatrixBits,
                this.normalMatrixBits,
                this.treeVisible,
                this.drawVisible,
                this.normalNeedsNormalization);
        this.retainedBytes = retainedBytes(
                this.positionMatrixBits.length,
                this.normalMatrixBits.length,
                this.treeVisible.length,
                this.drawVisible.length,
                this.normalNeedsNormalization.length);
        this.finite = allFinite(this.positionMatrixBits) && allFinite(this.normalMatrixBits);
    }

    public static ImmutableModelPartBonePose copyOf(
            int boneCount,
            int[] positionMatrixBits,
            int[] normalMatrixBits,
            long[] treeVisible,
            long[] drawVisible,
            long[] normalNeedsNormalization) {
        return new ImmutableModelPartBonePose(
                boneCount,
                Objects.requireNonNull(positionMatrixBits, "positionMatrixBits"),
                Objects.requireNonNull(normalMatrixBits, "normalMatrixBits"),
                Objects.requireNonNull(treeVisible, "treeVisible"),
                Objects.requireNonNull(drawVisible, "drawVisible"),
                Objects.requireNonNull(normalNeedsNormalization, "normalNeedsNormalization"),
                false);
    }

    static ImmutableModelPartBonePose takeOwnership(
            int boneCount,
            int[] positionMatrixBits,
            int[] normalMatrixBits,
            long[] treeVisible,
            long[] drawVisible,
            long[] normalNeedsNormalization) {
        return new ImmutableModelPartBonePose(
                boneCount,
                positionMatrixBits,
                normalMatrixBits,
                treeVisible,
                drawVisible,
                normalNeedsNormalization,
                true);
    }

    public int boneCount() {
        return boneCount;
    }

    public ModelPartPoseKey key() {
        return key;
    }

    public long retainedBytes() {
        return retainedBytes;
    }

    public boolean finite() {
        return finite;
    }

    public int positionElementBits(int boneIndex, int elementIndex) {
        return positionMatrixBits[offset(boneIndex, elementIndex, POSITION_ELEMENTS_PER_BONE)];
    }

    public int normalElementBits(int boneIndex, int elementIndex) {
        return normalMatrixBits[offset(boneIndex, elementIndex, NORMAL_ELEMENTS_PER_BONE)];
    }

    public boolean treeVisible(int boneIndex) {
        return bit(treeVisible, boneIndex);
    }

    public boolean drawVisible(int boneIndex) {
        return bit(drawVisible, boneIndex);
    }

    public boolean normalNeedsNormalization(int boneIndex) {
        return bit(normalNeedsNormalization, boneIndex);
    }

    public int treeVisibleCount() {
        return bitCount(treeVisible);
    }

    public int drawVisibleCount() {
        return bitCount(drawVisible);
    }

    public int[] copyPositionMatrixBits() {
        return positionMatrixBits.clone();
    }

    public int[] copyNormalMatrixBits() {
        return normalMatrixBits.clone();
    }

    public long[] copyTreeVisibleMask() {
        return treeVisible.clone();
    }

    public long[] copyDrawVisibleMask() {
        return drawVisible.clone();
    }

    public long[] copyNormalNeedsNormalizationMask() {
        return normalNeedsNormalization.clone();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof ImmutableModelPartBonePose pose && key.equals(pose.key));
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    private void validate() {
        if (boneCount <= 0) throw new IllegalArgumentException("A bone pose requires at least one bone");
        if (positionMatrixBits.length != Math.multiplyExact(boneCount, POSITION_ELEMENTS_PER_BONE)
                || normalMatrixBits.length != Math.multiplyExact(boneCount, NORMAL_ELEMENTS_PER_BONE)) {
            throw new IllegalArgumentException("Bone matrix data has an invalid length");
        }
        int expectedWords = Math.addExact(boneCount, 63) / 64;
        if (treeVisible.length != expectedWords
                || drawVisible.length != expectedWords
                || normalNeedsNormalization.length != expectedWords) {
            throw new IllegalArgumentException("Bone mask data has an invalid length");
        }
        long unusedMask = -1L << (boneCount & 63);
        if ((boneCount & 63) != 0
                && ((treeVisible[expectedWords - 1] & unusedMask) != 0
                        || (drawVisible[expectedWords - 1] & unusedMask) != 0
                        || (normalNeedsNormalization[expectedWords - 1] & unusedMask) != 0)) {
            throw new IllegalArgumentException("Unused visibility bits must be zero");
        }
    }

    static long retainedBytes(
            int positionElements, int normalElements, int treeWords, int drawWords, int normalizationWords) {
        long matrixBytes = Math.multiplyExact(Math.addExact((long) positionElements, normalElements), Integer.BYTES);
        long maskBytes = Math.multiplyExact(
                Math.addExact(Math.addExact((long) treeWords, drawWords), normalizationWords), Long.BYTES);
        return Math.addExact(matrixBytes, maskBytes);
    }

    private static int offset(int boneIndex, int elementIndex, int stride) {
        if (boneIndex < 0) throw new IndexOutOfBoundsException(boneIndex);
        if (elementIndex < 0 || elementIndex >= stride) throw new IndexOutOfBoundsException(elementIndex);
        return Math.addExact(Math.multiplyExact(boneIndex, stride), elementIndex);
    }

    private boolean bit(long[] words, int boneIndex) {
        if (boneIndex < 0 || boneIndex >= boneCount) throw new IndexOutOfBoundsException(boneIndex);
        return (words[boneIndex >>> 6] & (1L << (boneIndex & 63))) != 0;
    }

    private static int bitCount(long[] words) {
        int count = 0;
        for (long word : words) count = Math.addExact(count, Long.bitCount(word));
        return count;
    }

    private static boolean allFinite(int[] bits) {
        for (int value : bits) {
            if (!Float.isFinite(Float.intBitsToFloat(value))) return false;
        }
        return true;
    }
}
