package dev.alex.threadium.render.modelpart.pose;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.alex.threadium.render.modelpart.structure.ImmutableModelPartMesh;
import dev.alex.threadium.render.modelpart.structure.ModelPartStructureSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ImmutableModelPartBonePoseTest {
    @Test
    void exactMatricesMasksAndRawBitsDefineIdentity() {
        ImmutableModelPartBonePose first = ModelPartPoseTestFixtures.pose(2, -0.0F, true, true);
        ImmutableModelPartBonePose equal = ModelPartPoseTestFixtures.pose(2, -0.0F, true, true);
        ImmutableModelPartBonePose translated = ModelPartPoseTestFixtures.pose(2, 1.0F, true, true);
        ImmutableModelPartBonePose hidden = ModelPartPoseTestFixtures.pose(2, -0.0F, true, false);

        assertEquals(first, equal);
        assertEquals(first.hashCode(), equal.hashCode());
        assertNotEquals(first, translated);
        assertNotEquals(first, hidden);
        assertEquals(Float.floatToRawIntBits(-0.0F), first.positionElementBits(0, 12));
    }

    @Test
    void callerAndReturnedArraysCannotMutatePose() {
        ImmutableModelPartBonePose source = ModelPartPoseTestFixtures.pose(1, 2.0F, true, true);
        int[] positions = source.copyPositionMatrixBits();
        int[] normals = source.copyNormalMatrixBits();
        long[] tree = source.copyTreeVisibleMask();
        long[] draw = source.copyDrawVisibleMask();
        long[] normalize = source.copyNormalNeedsNormalizationMask();
        ImmutableModelPartBonePose copy =
                ImmutableModelPartBonePose.copyOf(1, positions, normals, tree, draw, normalize);

        positions[12] = 0;
        normals[0] = 0;
        tree[0] = 0;
        draw[0] = 0;
        normalize[0] = -1;
        copy.copyPositionMatrixBits()[12] = 0;
        copy.copyDrawVisibleMask()[0] = 0;

        assertEquals(Float.floatToRawIntBits(2.0F), copy.positionElementBits(0, 12));
        assertEquals(Float.floatToRawIntBits(1.0F), copy.normalElementBits(0, 0));
        assertTrue(copy.treeVisible(0));
        assertTrue(copy.drawVisible(0));
        assertFalse(copy.normalNeedsNormalization(0));
    }

    @Test
    void unusedMaskBitsMustBeZero() {
        ImmutableModelPartBonePose source = ModelPartPoseTestFixtures.pose(1, 0, true, true);
        long[] invalid = {2L};

        assertThrows(
                IllegalArgumentException.class,
                () -> ImmutableModelPartBonePose.copyOf(
                        1,
                        source.copyPositionMatrixBits(),
                        source.copyNormalMatrixBits(),
                        invalid,
                        source.copyDrawVisibleMask(),
                        source.copyNormalNeedsNormalizationMask()));
    }

    @Test
    void forcedFingerprintCollisionCannotReplaceExactEquality() {
        ImmutableModelPartBonePose first = ModelPartPoseTestFixtures.pose(1, 0, true, true);
        ImmutableModelPartBonePose second = ModelPartPoseTestFixtures.pose(1, 3, true, true);
        ModelPartPoseFingerprint collision = new ModelPartPoseFingerprint(17);
        ModelPartPoseKey firstKey = key(first, collision);
        ModelPartPoseKey secondKey = key(second, collision);

        assertEquals(firstKey.hashCode(), secondKey.hashCode());
        assertNotEquals(firstKey, secondKey);
    }

    @Test
    void nonFiniteMatrixBitsArePreservedAndReported() {
        ModelPartPoseCapture capture = new ModelPartPoseCapture(1, ModelPartPoseTestFixtures.LIMITS);
        float[] position = ModelPartPoseTestFixtures.identity4();
        position[4] = Float.intBitsToFloat(0x7fc01234);
        capture.captureBone(0, position, ModelPartPoseTestFixtures.identity3(), true, true, true);
        ImmutableModelPartBonePose pose = capture.complete();

        assertFalse(pose.finite());
        assertEquals(0x7fc01234, pose.positionElementBits(0, 4));
        assertTrue(pose.normalNeedsNormalization(0));
    }

    @Test
    void rootTransformIsDefensivelyCopiedAndExcludedFromPoseIdentity() {
        float[] position = ModelPartPoseTestFixtures.identity4();
        float[] normal = ModelPartPoseTestFixtures.identity3();
        ImmutableRootRenderTransform root = ImmutableRootRenderTransform.fromFloats(position, normal, true);
        ImmutableModelPartBonePose pose = ModelPartPoseTestFixtures.pose(1, 0, true, true);

        position[12] = 9;
        normal[0] = 7;

        assertEquals(Float.floatToRawIntBits(0), root.positionElementBits(12));
        assertEquals(Float.floatToRawIntBits(1), root.normalElementBits(0));
        assertTrue(root.normalNeedsNormalization());
        assertEquals(ModelPartPoseTestFixtures.pose(1, 0, true, true), pose);
        assertArrayEquals(ModelPartPoseTestFixtures.identity4(), toFloats(root.copyPositionMatrixBits()));
    }

    @Test
    void invocationStateDoesNotEnterReusablePoseIdentity() {
        ImmutableModelPartBonePose pose = ModelPartPoseTestFixtures.pose(1, 0, true, true);
        ImmutableModelPartMesh mesh = ImmutableModelPartMesh.copyOf(
                ModelPartStructureSnapshot.of(List.of(new ModelPartStructureSnapshot.Node(0, -1, "root", 0))),
                new int[0],
                new int[0]);
        ImmutableRootRenderTransform firstRoot = ImmutableRootRenderTransform.fromFloats(
                ModelPartPoseTestFixtures.identity4(), ModelPartPoseTestFixtures.identity3(), false);
        float[] translated = ModelPartPoseTestFixtures.identity4();
        translated[12] = 9;
        ImmutableRootRenderTransform secondRoot =
                ImmutableRootRenderTransform.fromFloats(translated, ModelPartPoseTestFixtures.identity3(), false);

        ModelPartInvocationSnapshot first = new ModelPartInvocationSnapshot(mesh, pose, firstRoot, 1, 2, 3, 4, 5);
        ModelPartInvocationSnapshot second = new ModelPartInvocationSnapshot(mesh, pose, secondRoot, 6, 7, 8, 4, 5);

        assertTrue(first.pose() == second.pose());
        assertEquals(first.pose().key(), second.pose().key());
        assertNotEquals(
                first.rootTransform().positionElementBits(12),
                second.rootTransform().positionElementBits(12));
    }

    private static ModelPartPoseKey key(ImmutableModelPartBonePose pose, ModelPartPoseFingerprint fingerprint) {
        return new ModelPartPoseKey(
                pose.boneCount(),
                pose.copyPositionMatrixBits(),
                pose.copyNormalMatrixBits(),
                pose.copyTreeVisibleMask(),
                pose.copyDrawVisibleMask(),
                pose.copyNormalNeedsNormalizationMask(),
                fingerprint);
    }

    private static float[] toFloats(int[] bits) {
        float[] values = new float[bits.length];
        for (int index = 0; index < bits.length; index++) values[index] = Float.intBitsToFloat(bits[index]);
        return values;
    }
}
