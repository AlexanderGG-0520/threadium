package dev.alex.threadium.render.modelpart.structure;

import static dev.alex.threadium.render.modelpart.structure.ModelPartMeshTestFixtures.DEFAULT_LIMITS;
import static dev.alex.threadium.render.modelpart.structure.ModelPartMeshTestFixtures.emitQuad;
import static dev.alex.threadium.render.modelpart.structure.ModelPartMeshTestFixtures.emitVertex;
import static dev.alex.threadium.render.modelpart.structure.ModelPartMeshTestFixtures.structure;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class ModelPartMeshCaptureTest {
    @Test
    void oneCompleteQuadProducesVanillaTriangleIndices() {
        ImmutableModelPartMesh mesh = ModelPartMeshTestFixtures.oneQuadMesh(0);

        assertEquals(1, mesh.quadCount());
        assertEquals(4, mesh.vertexCount());
        assertEquals(6, mesh.indexCount());
        assertArrayEquals(new int[] {0, 1, 2, 2, 3, 0}, mesh.copyIndices());
    }

    @Test
    void multipleQuadsPreserveEmissionOrder() {
        ModelPartMeshCapture capture = new ModelPartMeshCapture(DEFAULT_LIMITS);
        capture.beginNode(0);
        emitQuad(capture, 2, 1, 2, 3, 0, 0, 1);
        emitQuad(capture, 7, 1, 2, 3, 0, 1, 0);
        ImmutableModelPartMesh mesh = capture.complete(structure(1));

        assertEquals(Float.floatToRawIntBits(2), mesh.vertexFieldBits(0, ImmutableModelPartMesh.POSITION_X));
        assertEquals(Float.floatToRawIntBits(7), mesh.vertexFieldBits(4, ImmutableModelPartMesh.POSITION_X));
        assertArrayEquals(new int[] {0, 1, 2, 2, 3, 0, 4, 5, 6, 6, 7, 4}, mesh.copyIndices());
    }

    @Test
    void multipleNodesReceiveDeterministicBoneIndices() {
        ModelPartMeshCapture capture = new ModelPartMeshCapture(DEFAULT_LIMITS);
        capture.beginNode(0);
        emitQuad(capture, 0, 1, 2, 3, 1, 0, 0);
        capture.beginNode(1);
        emitQuad(capture, 4, 1, 2, 3, 0, 1, 0);
        ImmutableModelPartMesh mesh = capture.complete(structure(1, 1));

        assertEquals(java.util.List.of(0, 0, 0, 0, 1, 1, 1, 1), ModelPartMeshTestFixtures.boneIndices(mesh));
    }

    @Test
    void positionNormalAndUvUseExactRawFloatBits() {
        float rawPosition = Float.intBitsToFloat(0x7fc00001);
        float rawNormal = -0.0F;
        float rawUv = Float.intBitsToFloat(0x7fc01234);
        ModelPartMeshCapture capture = new ModelPartMeshCapture(DEFAULT_LIMITS);
        capture.beginNode(0);
        for (int vertex = 0; vertex < 4; vertex++) {
            emitVertex(capture, rawPosition, 2, 3, rawUv, -0.0F, 1, 2, 3, rawNormal, 1, 0);
        }
        ImmutableModelPartMesh mesh = capture.complete(structure(1));

        assertEquals(Float.floatToRawIntBits(rawPosition), mesh.vertexFieldBits(0, ImmutableModelPartMesh.POSITION_X));
        assertEquals(Float.floatToRawIntBits(rawNormal), mesh.vertexFieldBits(0, ImmutableModelPartMesh.NORMAL_X));
        assertEquals(Float.floatToRawIntBits(rawUv), mesh.vertexFieldBits(0, ImmutableModelPartMesh.TEXTURE_U));
        assertEquals(Float.floatToRawIntBits(-0.0F), mesh.vertexFieldBits(0, ImmutableModelPartMesh.TEXTURE_V));
    }

    @Test
    void colorLightAndOverlayDoNotAffectMeshIdentity() {
        ModelPartMeshCapture first = new ModelPartMeshCapture(DEFAULT_LIMITS);
        first.beginNode(0);
        emitQuad(first, 0, 1, 2, 3, 0, 0, 1);
        ModelPartMeshCapture second = new ModelPartMeshCapture(DEFAULT_LIMITS);
        second.beginNode(0);
        emitQuad(second, 0, 91, 92, 93, 0, 0, 1);

        assertEquals(first.complete(structure(1)), second.complete(structure(1)));
    }

    @Test
    void omittedFacesAndMirroringChangeCapturedMesh() {
        ImmutableModelPartMesh oneFace = ModelPartMeshTestFixtures.oneQuadMesh(0);

        ModelPartMeshCapture twoFacesCapture = new ModelPartMeshCapture(DEFAULT_LIMITS);
        twoFacesCapture.beginNode(0);
        emitQuad(twoFacesCapture, 0, 1, 2, 3, 0, 0, 1);
        emitQuad(twoFacesCapture, 0, 1, 2, 3, 0, 0, -1);
        ImmutableModelPartMesh twoFaces = twoFacesCapture.complete(structure(1));

        ModelPartMeshCapture mirroredCapture = new ModelPartMeshCapture(DEFAULT_LIMITS);
        mirroredCapture.beginNode(0);
        emitVertex(mirroredCapture, 0, 1, 0, 0, 1, 1, 2, 3, -1, 0, 1);
        emitVertex(mirroredCapture, 1, 1, 0, 1, 1, 1, 2, 3, -1, 0, 1);
        emitVertex(mirroredCapture, 1, 0, 0, 1, 0, 1, 2, 3, -1, 0, 1);
        emitVertex(mirroredCapture, 0, 0, 0, 0, 0, 1, 2, 3, -1, 0, 1);
        ImmutableModelPartMesh mirrored = mirroredCapture.complete(structure(1));

        assertNotEquals(oneFace, twoFaces);
        assertNotEquals(oneFace, mirrored);
        assertEquals(2, twoFaces.quadCount());
    }

    @Test
    void incompleteVertexAndIncompleteQuadAreRejected() {
        ModelPartMeshCapture incompleteVertex = new ModelPartMeshCapture(DEFAULT_LIMITS);
        incompleteVertex.beginNode(0);
        incompleteVertex.position(1, 2, 3);
        assertThrows(IllegalStateException.class, incompleteVertex::endCuboid);

        ModelPartMeshCapture incompleteQuad = new ModelPartMeshCapture(DEFAULT_LIMITS);
        incompleteQuad.beginNode(0);
        emitVertex(incompleteQuad, 0, 0, 0, 0, 0, 1, 2, 3, 0, 0, 1);
        assertThrows(IllegalStateException.class, () -> incompleteQuad.complete(structure(1)));
    }

    @Test
    void malformedConsumerOrderIsRejected() {
        ModelPartMeshCapture capture = new ModelPartMeshCapture(DEFAULT_LIMITS);
        capture.beginNode(0);

        assertThrows(IllegalStateException.class, () -> capture.texture(0, 0));
        capture.position(0, 0, 0);
        assertThrows(IllegalStateException.class, () -> capture.normal(0, 0, 1));
    }

    @Test
    void vertexIndexAndByteLimitsRejectBeforeGrowth() {
        ModelPartMeshCapture vertexLimited =
                new ModelPartMeshCapture(new ModelPartMeshCapture.Limits(1, 1, 3, 6, 4_096));
        vertexLimited.beginNode(0);
        for (int vertex = 0; vertex < 3; vertex++) {
            emitVertex(vertexLimited, vertex, 0, 0, 0, 0, 1, 2, 3, 0, 0, 1);
        }
        assertThrows(
                ModelPartMeshCapacityException.class, () -> emitVertex(vertexLimited, 3, 0, 0, 0, 0, 1, 2, 3, 0, 0, 1));

        ModelPartMeshCapture indexLimited =
                new ModelPartMeshCapture(new ModelPartMeshCapture.Limits(1, 1, 4, 5, 4_096));
        indexLimited.beginNode(0);
        for (int vertex = 0; vertex < 3; vertex++) {
            emitVertex(indexLimited, vertex, 0, 0, 0, 0, 1, 2, 3, 0, 0, 1);
        }
        assertThrows(
                ModelPartMeshCapacityException.class, () -> emitVertex(indexLimited, 3, 0, 0, 0, 0, 1, 2, 3, 0, 0, 1));
        assertEquals(0, indexLimited.indexCount());

        ModelPartMeshCapture byteLimited = new ModelPartMeshCapture(new ModelPartMeshCapture.Limits(1, 1, 4, 6, 35));
        byteLimited.beginNode(0);
        assertThrows(
                ModelPartMeshCapacityException.class, () -> emitVertex(byteLimited, 0, 0, 0, 0, 0, 1, 2, 3, 0, 0, 1));
        assertEquals(0, byteLimited.vertexCount());
    }

    @Test
    void repeatedDeterministicCaptureProducesIdenticalResults() {
        ImmutableModelPartMesh first = ModelPartMeshTestFixtures.oneQuadMesh(3.5F);
        ImmutableModelPartMesh second = ModelPartMeshTestFixtures.oneQuadMesh(3.5F);

        assertEquals(first, second);
        assertEquals(first.key().fingerprint(), second.key().fingerprint());
    }
}
