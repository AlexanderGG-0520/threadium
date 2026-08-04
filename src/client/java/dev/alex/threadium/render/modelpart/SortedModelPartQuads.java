package dev.alex.threadium.render.modelpart;

import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4fc;

/** Minecraft 26.2-compatible perspective ordering for transformed ModelPart quads. */
final class SortedModelPartQuads {
    private SortedModelPartQuads() {}

    record Reference(int instanceIndex, int quadIndex, float distanceSquared, long sequence) {}

    static Reference reference(
            int instanceIndex,
            int quadIndex,
            long sequence,
            float x,
            float y,
            float z,
            int boneIndex,
            ModelPartBoneData bones,
            Matrix4fc root) {
        return referenceFromOppositeVertices(
                instanceIndex, quadIndex, sequence, x, y, z, x, y, z, boneIndex, bones, root);
    }

    static Reference referenceFromOppositeVertices(
            int instanceIndex,
            int quadIndex,
            long sequence,
            float ax,
            float ay,
            float az,
            float cx,
            float cy,
            float cz,
            int boneIndex,
            ModelPartBoneData bones,
            Matrix4fc root) {
        float[] matrix = {
            root.m00(),
            root.m01(),
            root.m02(),
            root.m03(),
            root.m10(),
            root.m11(),
            root.m12(),
            root.m13(),
            root.m20(),
            root.m21(),
            root.m22(),
            root.m23(),
            root.m30(),
            root.m31(),
            root.m32(),
            root.m33()
        };
        return referenceFromOppositeVertices(
                instanceIndex, quadIndex, sequence, ax, ay, az, cx, cy, cz, boneIndex, bones, matrix, 0);
    }

    static Reference referenceFromOppositeVertices(
            int instanceIndex,
            int quadIndex,
            long sequence,
            float ax,
            float ay,
            float az,
            float cx,
            float cy,
            float cz,
            int boneIndex,
            ModelPartBoneData bones,
            float[] roots,
            int rootOffset) {
        float ad = distanceComponents(ax, ay, az, boneIndex, bones, roots, rootOffset, 0);
        float ae = distanceComponents(ax, ay, az, boneIndex, bones, roots, rootOffset, 1);
        float af = distanceComponents(ax, ay, az, boneIndex, bones, roots, rootOffset, 2);
        float cd = distanceComponents(cx, cy, cz, boneIndex, bones, roots, rootOffset, 0);
        float ce = distanceComponents(cx, cy, cz, boneIndex, bones, roots, rootOffset, 1);
        float cf = distanceComponents(cx, cy, cz, boneIndex, bones, roots, rootOffset, 2);
        float px = (ad + cd) * 0.5f;
        float py = (ae + ce) * 0.5f;
        float pz = (af + cf) * 0.5f;
        return new Reference(instanceIndex, quadIndex, px * px + py * py + pz * pz, sequence);
    }

    private static float distanceComponents(
            float x,
            float y,
            float z,
            int boneIndex,
            ModelPartBoneData bones,
            float[] roots,
            int rootOffset,
            int component) {
        int base = boneIndex * 28;
        float[] m = bones.matrices();
        float bx = m[base] * x + m[base + 4] * y + m[base + 8] * z + m[base + 12];
        float by = m[base + 1] * x + m[base + 5] * y + m[base + 9] * z + m[base + 13];
        float bz = m[base + 2] * x + m[base + 6] * y + m[base + 10] * z + m[base + 14];
        return roots[rootOffset + component] * bx
                + roots[rootOffset + 4 + component] * by
                + roots[rootOffset + 8 + component] * bz
                + roots[rootOffset + 12 + component];
    }

    static void sort(List<Reference> references) {
        // List.sort is stable. This matches VertexSorting's descending float key
        // and IntArrays.mergeSort retention of original order for equal keys.
        references.sort((left, right) -> Float.compare(right.distanceSquared, left.distanceSquared));
    }

    static List<Reference> copyAndSort(List<Reference> references) {
        ArrayList<Reference> result = new ArrayList<>(references);
        sort(result);
        return result;
    }
}
