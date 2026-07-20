package dev.alex.threadium.render.modelpart.pose;

import java.util.Arrays;
import java.util.Objects;

/** Collision-safe exact identity for an immutable ModelPart bone pose. */
public final class ModelPartPoseKey {
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private final int boneCount;
    private final int[] positionMatrixBits;
    private final int[] normalMatrixBits;
    private final long[] treeVisible;
    private final long[] drawVisible;
    private final long[] normalNeedsNormalization;
    private final ModelPartPoseFingerprint fingerprint;

    ModelPartPoseKey(
            int boneCount,
            int[] positionMatrixBits,
            int[] normalMatrixBits,
            long[] treeVisible,
            long[] drawVisible,
            long[] normalNeedsNormalization) {
        this(
                boneCount,
                positionMatrixBits,
                normalMatrixBits,
                treeVisible,
                drawVisible,
                normalNeedsNormalization,
                fingerprint(
                        boneCount,
                        positionMatrixBits,
                        normalMatrixBits,
                        treeVisible,
                        drawVisible,
                        normalNeedsNormalization));
    }

    ModelPartPoseKey(
            int boneCount,
            int[] positionMatrixBits,
            int[] normalMatrixBits,
            long[] treeVisible,
            long[] drawVisible,
            long[] normalNeedsNormalization,
            ModelPartPoseFingerprint fingerprint) {
        this.boneCount = boneCount;
        this.positionMatrixBits = Objects.requireNonNull(positionMatrixBits, "positionMatrixBits");
        this.normalMatrixBits = Objects.requireNonNull(normalMatrixBits, "normalMatrixBits");
        this.treeVisible = Objects.requireNonNull(treeVisible, "treeVisible");
        this.drawVisible = Objects.requireNonNull(drawVisible, "drawVisible");
        this.normalNeedsNormalization = Objects.requireNonNull(normalNeedsNormalization, "normalNeedsNormalization");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
    }

    public ModelPartPoseFingerprint fingerprint() {
        return fingerprint;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof ModelPartPoseKey key
                        && boneCount == key.boneCount
                        && Arrays.equals(positionMatrixBits, key.positionMatrixBits)
                        && Arrays.equals(normalMatrixBits, key.normalMatrixBits)
                        && Arrays.equals(treeVisible, key.treeVisible)
                        && Arrays.equals(drawVisible, key.drawVisible)
                        && Arrays.equals(normalNeedsNormalization, key.normalNeedsNormalization));
    }

    @Override
    public int hashCode() {
        return Long.hashCode(fingerprint.value());
    }

    private static ModelPartPoseFingerprint fingerprint(
            int boneCount,
            int[] positionMatrixBits,
            int[] normalMatrixBits,
            long[] treeVisible,
            long[] drawVisible,
            long[] normalNeedsNormalization) {
        long hash = mix(FNV_OFFSET_BASIS, boneCount);
        hash = mix(hash, positionMatrixBits.length);
        for (int value : positionMatrixBits) hash = mix(hash, value);
        hash = mix(hash, normalMatrixBits.length);
        for (int value : normalMatrixBits) hash = mix(hash, value);
        hash = mixLongs(hash, treeVisible);
        hash = mixLongs(hash, drawVisible);
        hash = mixLongs(hash, normalNeedsNormalization);
        return new ModelPartPoseFingerprint(hash);
    }

    private static long mixLongs(long hash, long[] values) {
        hash = mix(hash, values.length);
        for (long value : values) {
            hash = mix(hash, (int) value);
            hash = mix(hash, (int) (value >>> 32));
        }
        return hash;
    }

    private static long mix(long hash, int value) {
        return (hash ^ Integer.toUnsignedLong(value)) * FNV_PRIME;
    }
}
