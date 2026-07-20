package dev.alex.threadium.render.modelpart.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ModelPartStructureSnapshotTest {
    private static final ModelPartStructureBuilder.Reader<SyntheticPart> READER =
            new ModelPartStructureBuilder.Reader<>() {
                @Override
                public int cuboidCount(SyntheticPart part) {
                    return part.cuboidCount;
                }

                @Override
                public void captureCuboids(SyntheticPart part, int boneIndex, ModelPartMeshCapture capture) {
                    part.captureAttempted = true;
                    capture.beginNode(boneIndex);
                    for (int cuboid = 0; cuboid < part.cuboidCount; cuboid++) capture.endCuboid();
                }

                @Override
                public Map<String, SyntheticPart> children(SyntheticPart part) {
                    return part.children;
                }
            };

    @Test
    void differentHierarchyShapesAndCuboidMembershipAreUnequal() {
        SyntheticPart rootOnly = new SyntheticPart();
        SyntheticPart rootWithChild = new SyntheticPart();
        rootWithChild.children.put("child", new SyntheticPart());
        SyntheticPart rootWithCuboid = new SyntheticPart();
        rootWithCuboid.cuboidCount = 1;

        assertNotEquals(capture(rootOnly).structure(), capture(rootWithChild).structure());
        assertNotEquals(capture(rootOnly).structure(), capture(rootWithCuboid).structure());
    }

    @Test
    void poseVisibilityAndHiddenStateDoNotChangeIdentity() {
        SyntheticPart part = new SyntheticPart();
        ModelPartMeshKey before = capture(part).key();

        part.pitch = 0.75F;
        part.pivotX = 12.0F;
        part.scale = 1.5F;
        part.visible = false;
        part.hidden = true;

        assertEquals(before, capture(part).key());
    }

    @Test
    void deterministicTraversalAssignsStablePreOrderIndices() {
        SyntheticPart root = new SyntheticPart();
        SyntheticPart firstChild = new SyntheticPart();
        firstChild.children.put("grandchild", new SyntheticPart());
        root.children.put("first", firstChild);
        root.children.put("second", new SyntheticPart());

        ModelPartStructureSnapshot first = capture(root).structure();
        ModelPartStructureSnapshot second = capture(root).structure();

        assertEquals(first, second);
        assertEquals(
                List.of("root", "first", "grandchild", "second"),
                first.nodes().stream()
                        .map(ModelPartStructureSnapshot.Node::childName)
                        .toList());
        assertEquals(
                List.of(-1, 0, 1, 0),
                first.nodes().stream()
                        .map(ModelPartStructureSnapshot.Node::parentIndex)
                        .toList());
    }

    @Test
    void callerOwnedNodeCollectionsCannotMutateSnapshot() {
        ArrayList<ModelPartStructureSnapshot.Node> nodes = new ArrayList<>();
        ModelPartStructureSnapshot.Node root = new ModelPartStructureSnapshot.Node(0, -1, "root", 0);
        nodes.add(root);
        ModelPartStructureSnapshot snapshot = ModelPartStructureSnapshot.of(nodes);
        nodes.clear();

        assertEquals(1, snapshot.partCount());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.nodes().add(root));
    }

    @Test
    void hierarchyFingerprintCollisionDoesNotReplaceExactEquality() {
        ModelPartStructureFingerprint collision = new ModelPartStructureFingerprint(1234);
        ModelPartStructureSnapshot first = new ModelPartStructureSnapshot(
                List.of(new ModelPartStructureSnapshot.Node(0, -1, "root", 0)), collision);
        ModelPartStructureSnapshot second = new ModelPartStructureSnapshot(
                List.of(new ModelPartStructureSnapshot.Node(0, -1, "root", 1)), collision);

        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, second);
    }

    @Test
    void cuboidLimitRejectsBeforeEmission() {
        SyntheticPart root = new SyntheticPart();
        root.cuboidCount = 2;
        ModelPartMeshCapture capture = new ModelPartMeshCapture(ModelPartMeshTestFixtures.DEFAULT_LIMITS);

        assertThrows(
                ModelPartMeshCapacityException.class,
                () -> ModelPartStructureBuilder.capture(root, READER, capture, 16, 16, 1));
        assertFalse(root.captureAttempted);
        assertEquals(0, capture.vertexCount());
    }

    private static ImmutableModelPartMesh capture(SyntheticPart root) {
        ModelPartMeshCapture capture = new ModelPartMeshCapture(ModelPartMeshTestFixtures.DEFAULT_LIMITS);
        ModelPartStructureSnapshot structure = ModelPartStructureBuilder.capture(root, READER, capture, 16, 16, 16);
        return capture.complete(structure);
    }

    private static final class SyntheticPart {
        private final Map<String, SyntheticPart> children = new LinkedHashMap<>();
        private int cuboidCount;
        private float pitch;
        private float pivotX;
        private float scale = 1.0F;
        private boolean visible = true;
        private boolean hidden;
        private boolean captureAttempted;
    }
}
