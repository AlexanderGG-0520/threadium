package dev.alex.threadium.render.modelpart.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.alex.threadium.render.modelpart.pose.ImmutableModelPartBonePose;
import dev.alex.threadium.render.modelpart.pose.ImmutableRootRenderTransform;
import dev.alex.threadium.render.modelpart.pose.ModelPartInvocationSnapshot;
import dev.alex.threadium.render.modelpart.structure.ImmutableModelPartMesh;
import dev.alex.threadium.render.modelpart.structure.ModelPartStructureSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class PreparedModelPartReplayTest {
    @Test
    void composesBoneAndRootTransformsBeforeCommit() {
        PreparedModelPartReplay replay = PreparedModelPartReplay.prepare(invocation(true, 2.0F, 10.0F, false));

        assertEquals(4, replay.vertexCount());
        assertEquals(12.0F, replay.field(0, PreparedModelPartReplay.POSITION_X));
        assertEquals(13.0F, replay.field(1, PreparedModelPartReplay.POSITION_X));
        assertEquals(1.0F, replay.field(2, PreparedModelPartReplay.POSITION_Y));
        assertEquals(1.0F, replay.field(0, PreparedModelPartReplay.NORMAL_Z));
        assertEquals(0x10203040, replay.color());
        assertEquals(0x50607080, replay.overlay());
        assertEquals(0x90A0B0C0, replay.light());
    }

    @Test
    void hiddenBonesProduceNoVertices() {
        assertEquals(0, PreparedModelPartReplay.prepare(invocation(false, 0.0F, 0.0F, false)).vertexCount());
    }

    @Test
    void requestedNormalNormalizationIsApplied() {
        PreparedModelPartReplay replay = PreparedModelPartReplay.prepare(invocation(true, 0.0F, 0.0F, true));
        assertEquals(1.0F, replay.field(0, PreparedModelPartReplay.NORMAL_Z), 0.00001F);
    }

    @Test
    void nonFiniteRootFailsBeforeCommit() {
        float[] rootPosition = identity4();
        rootPosition[12] = Float.NaN;
        ModelPartInvocationSnapshot invalid = new ModelPartInvocationSnapshot(
                mesh(),
                pose(true, 0.0F, false),
                ImmutableRootRenderTransform.fromFloats(rootPosition, identity3(), false),
                1,
                2,
                3,
                4,
                5);
        assertThrows(IllegalArgumentException.class, () -> PreparedModelPartReplay.prepare(invalid));
    }

    private static ModelPartInvocationSnapshot invocation(
            boolean visible, float boneTranslationX, float rootTranslationX, boolean normalizeNormal) {
        float[] rootPosition = identity4();
        rootPosition[12] = rootTranslationX;
        return new ModelPartInvocationSnapshot(
                mesh(),
                pose(visible, boneTranslationX, normalizeNormal),
                ImmutableRootRenderTransform.fromFloats(rootPosition, identity3(), false),
                0x90A0B0C0,
                0x50607080,
                0x10203040,
                4,
                5);
    }

    private static ImmutableModelPartBonePose pose(
            boolean visible, float translationX, boolean normalizeNormal) {
        float[] position = identity4();
        position[12] = translationX;
        float[] normal = identity3();
        if (normalizeNormal) normal[8] = 2.0F;
        return ImmutableModelPartBonePose.copyOf(
                1,
                bits(position),
                bits(normal),
                new long[] {visible ? 1L : 0L},
                new long[] {visible ? 1L : 0L},
                new long[] {normalizeNormal ? 1L : 0L});
    }

    private static ImmutableModelPartMesh mesh() {
        ModelPartStructureSnapshot structure = ModelPartStructureSnapshot.of(
                List.of(new ModelPartStructureSnapshot.Node(0, -1, "root", 1)));
        int[] vertices = new int[4 * ImmutableModelPartMesh.VERTEX_STRIDE_INTS];
        setVertex(vertices, 0, 0, 0, 0, 0, 0);
        setVertex(vertices, 1, 1, 0, 0, 1, 0);
        setVertex(vertices, 2, 1, 1, 0, 1, 1);
        setVertex(vertices, 3, 0, 1, 0, 0, 1);
        return ImmutableModelPartMesh.copyOf(structure, vertices, new int[] {0, 1, 2, 2, 3, 0});
    }

    private static void setVertex(int[] data, int vertex, float x, float y, float z, float u, float v) {
        int offset = vertex * ImmutableModelPartMesh.VERTEX_STRIDE_INTS;
        data[offset + ImmutableModelPartMesh.POSITION_X] = Float.floatToRawIntBits(x);
        data[offset + ImmutableModelPartMesh.POSITION_Y] = Float.floatToRawIntBits(y);
        data[offset + ImmutableModelPartMesh.POSITION_Z] = Float.floatToRawIntBits(z);
        data[offset + ImmutableModelPartMesh.NORMAL_X] = Float.floatToRawIntBits(0.0F);
        data[offset + ImmutableModelPartMesh.NORMAL_Y] = Float.floatToRawIntBits(0.0F);
        data[offset + ImmutableModelPartMesh.NORMAL_Z] = Float.floatToRawIntBits(1.0F);
        data[offset + ImmutableModelPartMesh.TEXTURE_U] = Float.floatToRawIntBits(u);
        data[offset + ImmutableModelPartMesh.TEXTURE_V] = Float.floatToRawIntBits(v);
        data[offset + ImmutableModelPartMesh.BONE_INDEX] = 0;
    }

    private static int[] bits(float[] values) {
        int[] bits = new int[values.length];
        for (int index = 0; index < values.length; index++) bits[index] = Float.floatToRawIntBits(values[index]);
        return bits;
    }

    private static float[] identity4() {
        return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    private static float[] identity3() {
        return new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
    }
}
