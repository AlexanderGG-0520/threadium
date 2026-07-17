package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class GenericModelPartPoseExtractorTest {
    @Test
    void composeIntoMatchesAllocatingCompositionAndReusesDestination() {
        Matrix4f parent = new Matrix4f().translate(2.0f, -3.0f, 4.0f).rotateY(0.35f);
        Matrix4f expected = new Matrix4f(parent)
                .translate(1, 2, 3)
                .rotateZYX(0.3f, -0.2f, 0.1f)
                .scale(2, 3, 4);
        Matrix4f destination = new Matrix4f().translation(99, 99, 99);

        Matrix4f actual = TransformMath.compose(parent, destination, 16, 32, 48, 0.1f, -0.2f, 0.3f, 2, 3, 4);

        assertSame(destination, actual);
        assertEquals(expected, actual);
    }

    @Test
    void optimizedExtractorExactlyMatchesLegacyCompositionAcrossMutations() {
        ModelPart root = part();
        ModelPart child = part();
        ModelPart leaf = part();
        GenericModelPartTopology topology = topology(root, child, leaf);
        GenericModelPartPoseExtractor extractor = new GenericModelPartPoseExtractor();

        root.x = 16;
        root.y = -8;
        root.z = 4;
        root.xRot = 0.15f;
        root.yRot = -0.35f;
        root.zRot = 0.45f;
        root.xScale = 2.0f;
        root.yScale = 0.75f;
        root.zScale = 1.5f;
        child.x = -6;
        child.y = 12;
        child.z = 3;
        child.xRot = -0.25f;
        child.yRot = 0.5f;
        child.zRot = -0.1f;
        child.xScale = 0.8f;
        child.yScale = 1.25f;
        child.zScale = 0.6f;
        child.skipDraw = true;
        leaf.x = 2;
        leaf.y = 5;
        leaf.z = -9;
        leaf.xRot = 0.7f;
        leaf.yRot = -0.4f;
        leaf.zRot = 0.2f;

        ModelPartBoneData firstPose = extractor.extract(topology);
        assertExact(referenceExtract(topology), firstPose);
        assertArrayEquals(new long[] {0b101}, firstPose.visibility());
        float[] firstMatrices = firstPose.matrices().clone();
        long[] firstVisibility = firstPose.visibility().clone();

        root.x = -24;
        root.yRot = 1.1f;
        root.xScale = 0.5f;
        root.yScale = 1.75f;
        root.zScale = 3.0f;
        child.skipDraw = false;
        child.visible = false;
        leaf.xRot = -1.2f;
        leaf.zScale = 2.25f;

        ModelPartBoneData hiddenChild = extractor.extract(topology);
        assertExact(referenceExtract(topology), hiddenChild);
        assertArrayEquals(new long[] {0b001}, hiddenChild.visibility());
        assertNotSame(firstPose.matrices(), hiddenChild.matrices());
        assertNotSame(firstPose.visibility(), hiddenChild.visibility());
        assertRawFloatArrayEquals(firstMatrices, firstPose.matrices());
        assertArrayEquals(firstVisibility, firstPose.visibility());

        root.visible = false;
        ModelPartBoneData hiddenTree = extractor.extract(topology);
        assertExact(referenceExtract(topology), hiddenTree);
        assertArrayEquals(new long[] {0}, hiddenTree.visibility());
    }

    @Test
    void optimizedVisibilityPackingCrossesWordBoundary() {
        java.util.ArrayList<ModelPart> parts = new java.util.ArrayList<>();
        java.util.ArrayList<GenericModelPartTopology.Node> nodes = new java.util.ArrayList<>();
        for (int index = 0; index < 70; index++) {
            ModelPart part = part();
            part.visible = true;
            part.skipDraw = true;
            parts.add(part);
            nodes.add(new GenericModelPartTopology.Node(part, index - 1, index, "root/" + index));
        }
        parts.get(0).skipDraw = false;
        parts.get(64).skipDraw = false;
        parts.get(69).skipDraw = false;
        GenericModelPartTopology topology = new GenericModelPartTopology(
                List.copyOf(nodes), new GenericModelPartTopology.StructuralKey(2, List.of(2L)));

        ModelPartBoneData actual = new GenericModelPartPoseExtractor().extract(topology);

        assertExact(referenceExtract(topology), actual);
        assertArrayEquals(new long[] {1, 0b100001}, actual.visibility());
    }

    @Test
    void scratchCapacityGrowsOnceAndRetainsReusableBoneObjects() {
        PoseExtractionScratch scratch = new PoseExtractionScratch();
        scratch.ensureCapacity(3);
        Matrix4f firstBone = scratch.matrix(0);
        int initialCapacity = scratch.capacity();

        scratch.ensureCapacity(2);
        assertEquals(initialCapacity, scratch.capacity());
        assertSame(firstBone, scratch.matrix(0));

        scratch.ensureCapacity(initialCapacity + 1);
        assertTrue(scratch.capacity() > initialCapacity);
        assertSame(firstBone, scratch.matrix(0));
    }

    private static ModelPartBoneData referenceExtract(GenericModelPartTopology topology) {
        Matrix4f[] accumulated = new Matrix4f[topology.nodes().size()];
        boolean[] treeVisible = new boolean[topology.nodes().size()];
        float[] out = new float[topology.nodes().size() * 28];
        VisibilityMask visibility = new VisibilityMask(topology.nodes().size());
        for (GenericModelPartTopology.Node node : topology.nodes()) {
            Matrix4f parent = node.parent() < 0 ? new Matrix4f() : accumulated[node.parent()];
            ModelPart part = node.part();
            Matrix4f matrix = new Matrix4f(parent)
                    .translate(part.x / 16.0f, part.y / 16.0f, part.z / 16.0f)
                    .rotateZYX(part.zRot, part.yRot, part.xRot)
                    .scale(part.xScale, part.yScale, part.zScale);
            accumulated[node.index()] = matrix;
            boolean parentVisible = node.parent() < 0 || treeVisible[node.parent()];
            treeVisible[node.index()] = parentVisible && part.visible;
            visibility.set(node.index(), treeVisible[node.index()] && !part.skipDraw);
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

    private static void assertExact(ModelPartBoneData expected, ModelPartBoneData actual) {
        assertRawFloatArrayEquals(expected.matrices(), actual.matrices());
        assertArrayEquals(expected.visibility(), actual.visibility());
    }

    private static void assertRawFloatArrayEquals(float[] expected, float[] actual) {
        assertEquals(expected.length, actual.length);
        for (int index = 0; index < expected.length; index++)
            assertEquals(
                    Float.floatToRawIntBits(expected[index]),
                    Float.floatToRawIntBits(actual[index]),
                    "matrix component " + index);
    }

    private static GenericModelPartTopology topology(ModelPart root, ModelPart child, ModelPart leaf) {
        return new GenericModelPartTopology(
                List.of(
                        new GenericModelPartTopology.Node(root, -1, 0, "root"),
                        new GenericModelPartTopology.Node(child, 0, 1, "root/child"),
                        new GenericModelPartTopology.Node(leaf, 1, 2, "root/child/leaf")),
                new GenericModelPartTopology.StructuralKey(1, List.of(1L)));
    }

    private static ModelPart part() {
        ModelPart part = new ModelPart(List.of(), Map.of());
        part.xScale = 1;
        part.yScale = 1;
        part.zScale = 1;
        part.visible = true;
        return part;
    }
}
