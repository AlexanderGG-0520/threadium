package dev.alex.threadium.render.modelpart.pose;

import java.util.Objects;

/** Immutable copy of the real top-level render entry before the root ModelPart applies its transform. */
public final class ImmutableRootRenderTransform {
    public static final int POSITION_ELEMENTS = 16;
    public static final int NORMAL_ELEMENTS = 9;

    private final int[] positionMatrixBits;
    private final int[] normalMatrixBits;
    private final boolean normalNeedsNormalization;
    private final boolean finite;

    private ImmutableRootRenderTransform(
            int[] positionMatrixBits, int[] normalMatrixBits, boolean normalNeedsNormalization, boolean takeOwnership) {
        this.positionMatrixBits = takeOwnership ? positionMatrixBits : positionMatrixBits.clone();
        this.normalMatrixBits = takeOwnership ? normalMatrixBits : normalMatrixBits.clone();
        if (this.positionMatrixBits.length != POSITION_ELEMENTS || this.normalMatrixBits.length != NORMAL_ELEMENTS) {
            throw new IllegalArgumentException("Root transform matrix data has an invalid length");
        }
        this.normalNeedsNormalization = normalNeedsNormalization;
        this.finite = allFinite(this.positionMatrixBits) && allFinite(this.normalMatrixBits);
    }

    public static ImmutableRootRenderTransform copyOf(
            int[] positionMatrixBits, int[] normalMatrixBits, boolean normalNeedsNormalization) {
        return new ImmutableRootRenderTransform(
                Objects.requireNonNull(positionMatrixBits, "positionMatrixBits"),
                Objects.requireNonNull(normalMatrixBits, "normalMatrixBits"),
                normalNeedsNormalization,
                false);
    }

    public static ImmutableRootRenderTransform fromFloats(
            float[] positionMatrix, float[] normalMatrix, boolean normalNeedsNormalization) {
        Objects.requireNonNull(positionMatrix, "positionMatrix");
        Objects.requireNonNull(normalMatrix, "normalMatrix");
        if (positionMatrix.length != POSITION_ELEMENTS || normalMatrix.length != NORMAL_ELEMENTS) {
            throw new IllegalArgumentException("Root transform matrix data has an invalid length");
        }
        int[] positionBits = new int[POSITION_ELEMENTS];
        int[] normalBits = new int[NORMAL_ELEMENTS];
        for (int index = 0; index < POSITION_ELEMENTS; index++) {
            positionBits[index] = Float.floatToRawIntBits(positionMatrix[index]);
        }
        for (int index = 0; index < NORMAL_ELEMENTS; index++) {
            normalBits[index] = Float.floatToRawIntBits(normalMatrix[index]);
        }
        return new ImmutableRootRenderTransform(positionBits, normalBits, normalNeedsNormalization, true);
    }

    public int positionElementBits(int elementIndex) {
        return positionMatrixBits[elementIndex];
    }

    public int normalElementBits(int elementIndex) {
        return normalMatrixBits[elementIndex];
    }

    public boolean normalNeedsNormalization() {
        return normalNeedsNormalization;
    }

    public boolean finite() {
        return finite;
    }

    public int[] copyPositionMatrixBits() {
        return positionMatrixBits.clone();
    }

    public int[] copyNormalMatrixBits() {
        return normalMatrixBits.clone();
    }

    private static boolean allFinite(int[] bits) {
        for (int value : bits) {
            if (!Float.isFinite(Float.intBitsToFloat(value))) return false;
        }
        return true;
    }
}
