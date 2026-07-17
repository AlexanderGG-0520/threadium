package dev.alex.threadium.render.modelpart;

import org.joml.Matrix4f;

public final class TransformMath {
    private TransformMath() {}

    public static Matrix4f compose(
            Matrix4f parent,
            float x,
            float y,
            float z,
            float xRot,
            float yRot,
            float zRot,
            float xScale,
            float yScale,
            float zScale) {
        return new Matrix4f(parent)
                .translate(x / 16.0f, y / 16.0f, z / 16.0f)
                .rotateZYX(zRot, yRot, xRot)
                .scale(xScale, yScale, zScale);
    }
}
