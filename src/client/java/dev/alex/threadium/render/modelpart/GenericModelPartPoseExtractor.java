package dev.alex.threadium.render.modelpart;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

public final class GenericModelPartPoseExtractor {
    public ModelPartBoneData extract(GenericModelPartTopology topology) {
        Matrix4f[] accumulated = new Matrix4f[topology.nodes().size()];
        boolean[] treeVisible = new boolean[topology.nodes().size()];
        float[] out = new float[topology.nodes().size() * 28];
        VisibilityMask visibility = new VisibilityMask(topology.nodes().size());
        for (GenericModelPartTopology.Node node : topology.nodes()) {
            Matrix4f parent = node.parent() < 0 ? new Matrix4f() : accumulated[node.parent()];
            var p = node.part();
            Matrix4f matrix = dev.alex.threadium.render.modelpart.TransformMath.compose(
                    parent, p.x, p.y, p.z, p.xRot, p.yRot, p.zRot, p.xScale, p.yScale, p.zScale);
            accumulated[node.index()] = matrix;
            boolean parentVisible = node.parent() < 0 || treeVisible[node.parent()];
            treeVisible[node.index()] = parentVisible && p.visible;
            visibility.set(node.index(), treeVisible[node.index()] && !p.skipDraw);
            int base = node.index() * 28;
            matrix.get(out, base);
            Matrix3f normal = new Matrix3f(matrix).normal();
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
        return new ModelPartBoneData(out, visibility.copyWords());
    }
}
