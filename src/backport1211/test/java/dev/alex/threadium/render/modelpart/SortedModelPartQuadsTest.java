package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.alex.threadium.render.modelpart.pose.ImmutableModelPartBonePose;
import dev.alex.threadium.render.modelpart.pose.ImmutableRootRenderTransform;
import dev.alex.threadium.render.modelpart.structure.ImmutableModelPartMesh;
import dev.alex.threadium.render.modelpart.structure.ModelPartMeshCapture;
import dev.alex.threadium.render.modelpart.structure.ModelPartStructureSnapshot;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SortedModelPartQuadsTest {
    @Test
    void perspectiveOrderIsFarToNearAndStableForTies() {
        ArrayList<SortedModelPartQuads.Reference> references = new ArrayList<>(List.of(
                new SortedModelPartQuads.Reference(0, 0, 4, 0),
                new SortedModelPartQuads.Reference(1, 0, 9, 1),
                new SortedModelPartQuads.Reference(2, 0, 9, 2),
                new SortedModelPartQuads.Reference(3, 0, 1, 3)));

        SortedModelPartQuads.sort(references);

        assertEquals(
                List.of(1, 2, 0, 3),
                references.stream()
                        .map(SortedModelPartQuads.Reference::sourceIndex)
                        .toList());
    }

    @Test
    void distanceKeyAppliesBoneThenRootTransform() {
        ImmutableModelPartMesh mesh = quad(1, 0, 0, 1, 0, 0);
        SortedModelPartQuads.Reference boneMoved =
                SortedModelPartQuads.reference(0, 0, 0, mesh, pose(2, 0, 0), root(0, 0, 0));
        SortedModelPartQuads.Reference rootMoved =
                SortedModelPartQuads.reference(0, 0, 0, mesh, pose(0, 0, 0), root(2, 0, 0));

        assertEquals(9, boneMoved.distanceSquared());
        assertEquals(9, rootMoved.distanceSquared());
    }

    @Test
    void oppositeVerticesAreTransformedBeforeTheirCenterIsComputed() {
        ImmutableModelPartMesh mesh = quad(0, 0, 0, 4, 2, 0);
        SortedModelPartQuads.Reference reference =
                SortedModelPartQuads.reference(7, 0, 11, mesh, pose(1, 2, 0), root(3, 0, 0));

        assertEquals(45, reference.distanceSquared());
        assertEquals(7, reference.sourceIndex());
        assertEquals(11, reference.sequence());
    }

    @Test
    void differentInstancesCanProduceDifferentQuadOrder() {
        ImmutableModelPartMesh mesh = quad(1, 0, 0, 1, 0, 0);
        ArrayList<SortedModelPartQuads.Reference> references = new ArrayList<>();
        references.add(SortedModelPartQuads.reference(0, 0, 0, mesh, pose(0, 0, 0), root(0, 0, 0)));
        references.add(SortedModelPartQuads.reference(1, 0, 1, mesh, pose(0, 0, 0), root(10, 0, 0)));

        SortedModelPartQuads.sort(references);

        assertEquals(1, references.getFirst().sourceIndex());
    }

    private static ImmutableModelPartMesh quad(float ax, float ay, float az, float cx, float cy, float cz) {
        ModelPartMeshCapture capture = new ModelPartMeshCapture(new ModelPartMeshCapture.Limits(1, 1, 4, 6, 4096));
        capture.beginNode(0);
        vertex(capture, ax, ay, az);
        vertex(capture, ax, cy, az);
        vertex(capture, cx, cy, cz);
        vertex(capture, cx, ay, cz);
        capture.endCuboid();
        return capture.complete(
                ModelPartStructureSnapshot.of(List.of(new ModelPartStructureSnapshot.Node(0, -1, "root", 1))));
    }

    private static void vertex(ModelPartMeshCapture capture, float x, float y, float z) {
        capture.position(x, y, z);
        capture.color(255, 255, 255, 255);
        capture.texture(0, 0);
        capture.overlay(0, 0);
        capture.light(0, 0);
        capture.normal(0, 0, 1);
    }

    private static ImmutableModelPartBonePose pose(float tx, float ty, float tz) {
        int[] position = identity4();
        position[12] = Float.floatToRawIntBits(tx);
        position[13] = Float.floatToRawIntBits(ty);
        position[14] = Float.floatToRawIntBits(tz);
        return ImmutableModelPartBonePose.copyOf(
                1, position, identity3(), new long[] {1}, new long[] {1}, new long[] {0});
    }

    private static ImmutableRootRenderTransform root(float tx, float ty, float tz) {
        int[] position = identity4();
        position[12] = Float.floatToRawIntBits(tx);
        position[13] = Float.floatToRawIntBits(ty);
        position[14] = Float.floatToRawIntBits(tz);
        return ImmutableRootRenderTransform.copyOf(position, identity3(), false);
    }

    private static int[] identity4() {
        int[] values = new int[16];
        values[0] = values[5] = values[10] = values[15] = Float.floatToRawIntBits(1);
        return values;
    }

    private static int[] identity3() {
        int[] values = new int[9];
        values[0] = values[4] = values[8] = Float.floatToRawIntBits(1);
        return values;
    }
}
