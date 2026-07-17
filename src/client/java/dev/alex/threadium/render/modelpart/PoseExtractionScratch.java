package dev.alex.threadium.render.modelpart;

import java.util.Arrays;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Render-thread-owned reusable state for ModelPart pose extraction. */
final class PoseExtractionScratch {
    private final Matrix4f identity = new Matrix4f();
    private final Matrix3f normal = new Matrix3f();
    private Matrix4f[] accumulated = new Matrix4f[0];
    private boolean[] treeVisible = new boolean[0];

    void ensureCapacity(int bones) {
        if (bones <= accumulated.length) return;
        int oldCapacity = accumulated.length;
        int newCapacity = grow(oldCapacity, bones);
        accumulated = Arrays.copyOf(accumulated, newCapacity);
        treeVisible = Arrays.copyOf(treeVisible, newCapacity);
        for (int index = oldCapacity; index < newCapacity; index++) accumulated[index] = new Matrix4f();
    }

    Matrix4fc parentMatrix(int parent) {
        return parent < 0 ? identity : accumulated[parent];
    }

    Matrix4f matrix(int bone) {
        return accumulated[bone];
    }

    boolean treeVisible(int bone) {
        return treeVisible[bone];
    }

    void treeVisible(int bone, boolean visible) {
        treeVisible[bone] = visible;
    }

    Matrix3f normal(Matrix4fc matrix) {
        return normal.set(matrix).normal();
    }

    int capacity() {
        return accumulated.length;
    }

    private static int grow(int current, int required) {
        int grown = current == 0 ? 8 : current + (current >>> 1);
        return Math.max(grown, required);
    }
}
