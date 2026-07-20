package dev.alex.threadium.render.modelpart.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                public List<ModelPartStructureSnapshot.Cuboid> cuboids(SyntheticPart part) {
                    return part.cuboids;
                }

                @Override
                public Map<String, SyntheticPart> children(SyntheticPart part) {
                    return part.children;
                }
            };

    @Test
    void equalStructuresHaveExactEqualityAndStableFingerprints() {
        ModelPartStructureSnapshot first = inspect(singleCuboidPart(cuboid(1.0F, 2.0F, false)));
        ModelPartStructureSnapshot second = inspect(singleCuboidPart(cuboid(1.0F, 2.0F, false)));

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(first.fingerprint(), second.fingerprint());
    }

    @Test
    void differentHierarchyShapesAreUnequal() {
        SyntheticPart rootOnly = new SyntheticPart();
        SyntheticPart rootWithChild = new SyntheticPart();
        rootWithChild.children.put("child", new SyntheticPart());

        assertNotEquals(inspect(rootOnly), inspect(rootWithChild));
    }

    @Test
    void differentCuboidDimensionsAreUnequal() {
        assertNotEquals(
                inspect(singleCuboidPart(cuboid(1.0F, 2.0F, false))),
                inspect(singleCuboidPart(cuboid(1.0F, 3.0F, false))));
    }

    @Test
    void textureCoordinatesAndMirroringEffectsAreStructural() {
        ModelPartStructureSnapshot ordinary = inspect(singleCuboidPart(cuboid(1.0F, 2.0F, false)));
        ModelPartStructureSnapshot changedTexture = inspect(singleCuboidPart(cuboid(4.0F, 2.0F, false)));
        ModelPartStructureSnapshot mirrored = inspect(singleCuboidPart(cuboid(1.0F, 2.0F, true)));

        assertNotEquals(ordinary, changedTexture);
        assertNotEquals(ordinary, mirrored);
    }

    @Test
    void mutablePoseAndVisibilityAreExcluded() {
        SyntheticPart part = singleCuboidPart(cuboid(1.0F, 2.0F, false));
        ModelPartStructureSnapshot before = inspect(part);

        part.pitch = 0.75F;
        part.pivotX = 12.0F;
        part.scale = 1.5F;
        part.visible = false;
        part.hidden = true;

        assertEquals(before, inspect(part));
    }

    @Test
    void deterministicTraversalUsesSuppliedChildMapOrder() {
        SyntheticPart root = new SyntheticPart();
        SyntheticPart firstChild = new SyntheticPart();
        firstChild.children.put("grandchild", new SyntheticPart());
        root.children.put("first", firstChild);
        root.children.put("second", new SyntheticPart());

        ModelPartStructureSnapshot first = inspect(root);
        ModelPartStructureSnapshot second = inspect(root);

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
    void fingerprintCollisionCannotCreateStructuralEquality() {
        ModelPartStructureFingerprint collision = new ModelPartStructureFingerprint(1234L);
        ModelPartStructureSnapshot first = new ModelPartStructureSnapshot(
                List.of(new ModelPartStructureSnapshot.Node(0, -1, "root", List.of(cuboid(1.0F, 2.0F, false)))),
                collision);
        ModelPartStructureSnapshot second = new ModelPartStructureSnapshot(
                List.of(new ModelPartStructureSnapshot.Node(0, -1, "root", List.of(cuboid(1.0F, 3.0F, false)))),
                collision);

        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, second);
    }

    @Test
    void callerOwnedCollectionsCannotMutateSnapshot() {
        ArrayList<ModelPartStructureSnapshot.Cuboid> cuboids = new ArrayList<>();
        cuboids.add(cuboid(1.0F, 2.0F, false));
        ModelPartStructureSnapshot.Node root = new ModelPartStructureSnapshot.Node(0, -1, "root", cuboids);
        ArrayList<ModelPartStructureSnapshot.Node> nodes = new ArrayList<>();
        nodes.add(root);
        ModelPartStructureSnapshot snapshot = ModelPartStructureSnapshot.of(nodes);

        cuboids.clear();
        nodes.clear();

        assertEquals(1, snapshot.partCount());
        assertEquals(1, snapshot.cuboidCount());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.nodes().add(root));
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.nodes().getFirst().cuboids().clear());
    }

    private static ModelPartStructureSnapshot inspect(SyntheticPart root) {
        return ModelPartStructureBuilder.inspect(root, READER, 16, 64, 256);
    }

    private static SyntheticPart singleCuboidPart(ModelPartStructureSnapshot.Cuboid cuboid) {
        SyntheticPart part = new SyntheticPart();
        part.cuboids.add(cuboid);
        return part;
    }

    private static ModelPartStructureSnapshot.Cuboid cuboid(float textureU, float maxX, boolean mirrored) {
        ModelPartStructureSnapshot.Vertex a = ModelPartStructureSnapshot.Vertex.from(0, 0, 0, textureU, 0);
        ModelPartStructureSnapshot.Vertex b = ModelPartStructureSnapshot.Vertex.from(maxX, 0, 0, textureU + 1, 0);
        ModelPartStructureSnapshot.Vertex c = ModelPartStructureSnapshot.Vertex.from(maxX, 1, 0, textureU + 1, 1);
        ModelPartStructureSnapshot.Vertex d = ModelPartStructureSnapshot.Vertex.from(0, 1, 0, textureU, 1);
        List<ModelPartStructureSnapshot.Vertex> vertices = mirrored ? List.of(d, c, b, a) : List.of(a, b, c, d);
        ModelPartStructureSnapshot.Polygon polygon =
                ModelPartStructureSnapshot.Polygon.fromNormal(mirrored ? -1 : 1, 0, 0, vertices);
        return ModelPartStructureSnapshot.Cuboid.fromBounds(0, 0, 0, maxX, 1, 1, List.of(polygon));
    }

    private static final class SyntheticPart {
        private final List<ModelPartStructureSnapshot.Cuboid> cuboids = new ArrayList<>();
        private final Map<String, SyntheticPart> children = new LinkedHashMap<>();
        private float pitch;
        private float pivotX;
        private float scale = 1.0F;
        private boolean visible = true;
        private boolean hidden;
    }
}
