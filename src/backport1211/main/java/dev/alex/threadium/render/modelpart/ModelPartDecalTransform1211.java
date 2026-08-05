package dev.alex.threadium.render.modelpart;

import java.util.Objects;
import org.joml.Matrix3fc;
import org.joml.Matrix4fc;

/** Immutable Minecraft 1.21.1 SheetedDecalTextureGenerator projection state. */
public final class ModelPartDecalTransform1211 {
    public static final int ELEMENTS = 28;
    private static final int NORMAL_OFFSET = 16;
    private static final int TEXTURE_SCALE_OFFSET = 19;

    private final int[] elementBits;
    private final boolean finite;

    private ModelPartDecalTransform1211(int[] elementBits, boolean takeOwnership) {
        this.elementBits = takeOwnership ? elementBits : elementBits.clone();
        if (this.elementBits.length != ELEMENTS) {
            throw new IllegalArgumentException("Decal transform data has an invalid length");
        }
        boolean allFinite = true;
        for (int bits : this.elementBits) {
            if (!Float.isFinite(Float.intBitsToFloat(bits))) {
                allFinite = false;
                break;
            }
        }
        float textureScale = textureScale();
        finite = allFinite && textureScale > 0.0F;
    }

    public static ModelPartDecalTransform1211 capture(
            Matrix4fc inverseTextureMatrix, Matrix3fc inverseNormalMatrix, float textureScale) {
        Objects.requireNonNull(inverseTextureMatrix, "inverseTextureMatrix");
        Objects.requireNonNull(inverseNormalMatrix, "inverseNormalMatrix");
        float[] values = new float[ELEMENTS];
        inverseTextureMatrix.get(values, 0);
        values[NORMAL_OFFSET] = inverseNormalMatrix.m00();
        values[NORMAL_OFFSET + 1] = inverseNormalMatrix.m01();
        values[NORMAL_OFFSET + 2] = inverseNormalMatrix.m02();
        values[TEXTURE_SCALE_OFFSET] = textureScale;
        values[NORMAL_OFFSET + 4] = inverseNormalMatrix.m10();
        values[NORMAL_OFFSET + 5] = inverseNormalMatrix.m11();
        values[NORMAL_OFFSET + 6] = inverseNormalMatrix.m12();
        values[NORMAL_OFFSET + 8] = inverseNormalMatrix.m20();
        values[NORMAL_OFFSET + 9] = inverseNormalMatrix.m21();
        values[NORMAL_OFFSET + 10] = inverseNormalMatrix.m22();
        int[] bits = new int[ELEMENTS];
        for (int index = 0; index < values.length; index++) bits[index] = Float.floatToRawIntBits(values[index]);
        return new ModelPartDecalTransform1211(bits, true);
    }

    public int elementBits(int index) {
        return elementBits[index];
    }

    public float value(int index) {
        return Float.intBitsToFloat(elementBits[index]);
    }

    public float textureScale() {
        return value(TEXTURE_SCALE_OFFSET);
    }

    public boolean finite() {
        return finite;
    }

    public Uv project(float x, float y, float z, float normalX, float normalY, float normalZ) {
        float positionX = value(0) * x + value(4) * y + value(8) * z + value(12);
        float positionY = value(1) * x + value(5) * y + value(9) * z + value(13);
        float positionZ = value(2) * x + value(6) * y + value(10) * z + value(14);
        float transformedNormalX = value(16) * normalX + value(20) * normalY + value(24) * normalZ;
        float transformedNormalY = value(17) * normalX + value(21) * normalY + value(25) * normalZ;
        float transformedNormalZ = value(18) * normalX + value(22) * normalY + value(26) * normalZ;
        return projectFacing(
                positionX,
                positionY,
                positionZ,
                transformedNormalX,
                transformedNormalY,
                transformedNormalZ,
                textureScale());
    }

    static Uv projectFacing(
            float x, float y, float z, float normalX, float normalY, float normalZ, float textureScale) {
        float absoluteX = Math.abs(normalX);
        float absoluteY = Math.abs(normalY);
        float absoluteZ = Math.abs(normalZ);
        float u;
        float v;
        if (absoluteY >= absoluteX && absoluteY >= absoluteZ) {
            u = x;
            v = normalY < 0.0F ? -z : z;
        } else if (absoluteZ >= absoluteX) {
            u = normalZ < 0.0F ? -x : x;
            v = -y;
        } else {
            u = normalX < 0.0F ? -z : z;
            v = -y;
        }
        return new Uv(u * textureScale, v * textureScale);
    }

    public record Uv(float u, float v) {}
}
