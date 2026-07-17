package dev.alex.threadium.render.modelpart;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

/** Render-thread-owned, non-thread-safe ModelPart pose extractor. */
public final class GenericModelPartPoseExtractor {
    private static final int FLOATS_PER_BONE = 28;

    private final PoseExtractionScratch scratch = new PoseExtractionScratch();

    public ModelPartBoneData extract(GenericModelPartTopology topology) {
        int boneCount = topology.nodes().size();
        scratch.ensureCapacity(boneCount);
        float[] out = new float[Math.multiplyExact(boneCount, FLOATS_PER_BONE)];
        long[] visibility = new long[(boneCount + 63) >>> 6];
        for (GenericModelPartTopology.Node node : topology.nodes()) {
            var part = node.part();
            Matrix4f matrix = TransformMath.compose(
                    scratch.parentMatrix(node.parent()),
                    scratch.matrix(node.index()),
                    part.x,
                    part.y,
                    part.z,
                    part.xRot,
                    part.yRot,
                    part.zRot,
                    part.xScale,
                    part.yScale,
                    part.zScale);
            boolean parentVisible = node.parent() < 0 || scratch.treeVisible(node.parent());
            boolean treeVisible = parentVisible && part.visible;
            scratch.treeVisible(node.index(), treeVisible);
            if (treeVisible && !part.skipDraw) visibility[node.index() >>> 6] |= 1L << (node.index() & 63);
            int base = node.index() * FLOATS_PER_BONE;
            matrix.get(out, base);
            Matrix3f normal = scratch.normal(matrix);
            out[base + 16] = normal.m00();
            out[base + 17] = normal.m01();
            out[base + 18] = normal.m02();
            out[base + 19] = 0;
            out[base + 20] = normal.m10();
            out[base + 21] = normal.m11();
            out[base + 22] = normal.m12();
            out[base + 23] = 0;
            out[base + 24] = normal.m20();
            out[base + 25] = normal.m21();
            out[base + 26] = normal.m22();
            out[base + 27] = 0;
        }
        return new ModelPartBoneData(out, visibility);
    }
}
