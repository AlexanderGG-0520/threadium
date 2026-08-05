package dev.alex.threadium.render.modelpart;

import dev.alex.threadium.render.modelpart.pose.ImmutableModelPartBonePose;
import dev.alex.threadium.render.modelpart.pose.ImmutableRootRenderTransform;
import dev.alex.threadium.render.modelpart.structure.ImmutableModelPartMesh;
import java.util.ArrayList;
import java.util.List;

/** Minecraft 1.21.1-compatible perspective ordering for transformed ModelPart quads. */
public final class SortedModelPartQuads {
    private SortedModelPartQuads() {}

    public record Reference(int sourceIndex, int quadIndex, float distanceSquared, long sequence) {}

    public static Reference reference(
            int sourceIndex,
            int quadIndex,
            long sequence,
            ImmutableModelPartMesh mesh,
            ImmutableModelPartBonePose pose,
            ImmutableRootRenderTransform root) {
        if (quadIndex < 0 || quadIndex >= mesh.quadCount()) throw new IndexOutOfBoundsException(quadIndex);
        int firstVertex = Math.multiplyExact(quadIndex, 4);
        int oppositeVertex = firstVertex + 2;
        int bone = mesh.vertexFieldBits(firstVertex, ImmutableModelPartMesh.BONE_INDEX);
        return referenceFromOppositeVertices(
                sourceIndex,
                quadIndex,
                sequence,
                field(mesh, firstVertex, ImmutableModelPartMesh.POSITION_X),
                field(mesh, firstVertex, ImmutableModelPartMesh.POSITION_Y),
                field(mesh, firstVertex, ImmutableModelPartMesh.POSITION_Z),
                field(mesh, oppositeVertex, ImmutableModelPartMesh.POSITION_X),
                field(mesh, oppositeVertex, ImmutableModelPartMesh.POSITION_Y),
                field(mesh, oppositeVertex, ImmutableModelPartMesh.POSITION_Z),
                bone,
                pose,
                root);
    }

    public static Reference referenceFromOppositeVertices(
            int sourceIndex,
            int quadIndex,
            long sequence,
            float ax,
            float ay,
            float az,
            float cx,
            float cy,
            float cz,
            int boneIndex,
            ImmutableModelPartBonePose pose,
            ImmutableRootRenderTransform root) {
        float ad = distanceComponent(ax, ay, az, boneIndex, pose, root, 0);
        float ae = distanceComponent(ax, ay, az, boneIndex, pose, root, 1);
        float af = distanceComponent(ax, ay, az, boneIndex, pose, root, 2);
        float cd = distanceComponent(cx, cy, cz, boneIndex, pose, root, 0);
        float ce = distanceComponent(cx, cy, cz, boneIndex, pose, root, 1);
        float cf = distanceComponent(cx, cy, cz, boneIndex, pose, root, 2);
        float px = (ad + cd) * 0.5F;
        float py = (ae + ce) * 0.5F;
        float pz = (af + cf) * 0.5F;
        return new Reference(sourceIndex, quadIndex, px * px + py * py + pz * pz, sequence);
    }

    public static void sort(List<Reference> references) {
        // List.sort is stable. This matches Vanilla's descending float key while preserving source order for ties.
        references.sort((left, right) -> Float.compare(right.distanceSquared, left.distanceSquared));
    }

    public static List<Reference> copyAndSort(List<Reference> references) {
        ArrayList<Reference> result = new ArrayList<>(references);
        sort(result);
        return result;
    }

    private static float distanceComponent(
            float x,
            float y,
            float z,
            int boneIndex,
            ImmutableModelPartBonePose pose,
            ImmutableRootRenderTransform root,
            int component) {
        float bx = poseElement(pose, boneIndex, 0) * x
                + poseElement(pose, boneIndex, 4) * y
                + poseElement(pose, boneIndex, 8) * z
                + poseElement(pose, boneIndex, 12);
        float by = poseElement(pose, boneIndex, 1) * x
                + poseElement(pose, boneIndex, 5) * y
                + poseElement(pose, boneIndex, 9) * z
                + poseElement(pose, boneIndex, 13);
        float bz = poseElement(pose, boneIndex, 2) * x
                + poseElement(pose, boneIndex, 6) * y
                + poseElement(pose, boneIndex, 10) * z
                + poseElement(pose, boneIndex, 14);
        return rootElement(root, component) * bx
                + rootElement(root, 4 + component) * by
                + rootElement(root, 8 + component) * bz
                + rootElement(root, 12 + component);
    }

    private static float field(ImmutableModelPartMesh mesh, int vertex, int field) {
        return Float.intBitsToFloat(mesh.vertexFieldBits(vertex, field));
    }

    private static float poseElement(ImmutableModelPartBonePose pose, int bone, int element) {
        return Float.intBitsToFloat(pose.positionElementBits(bone, element));
    }

    private static float rootElement(ImmutableRootRenderTransform root, int element) {
        return Float.intBitsToFloat(root.positionElementBits(element));
    }
}
