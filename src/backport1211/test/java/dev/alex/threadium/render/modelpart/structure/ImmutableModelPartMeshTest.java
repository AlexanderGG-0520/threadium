package dev.alex.threadium.render.modelpart.structure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

final class ImmutableModelPartMeshTest {
    @Test
    void callerAndReturnedArraysCannotMutateMesh() {
        ImmutableModelPartMesh source = ModelPartMeshTestFixtures.oneQuadMesh(0);
        int[] vertexData = source.copyVertexData();
        int[] indices = source.copyIndices();
        ImmutableModelPartMesh copied = ImmutableModelPartMesh.copyOf(source.structure(), vertexData, indices);

        vertexData[0] = Float.floatToRawIntBits(99);
        indices[0] = 3;
        int[] returnedVertices = copied.copyVertexData();
        int[] returnedIndices = copied.copyIndices();
        returnedVertices[0] = Float.floatToRawIntBits(88);
        returnedIndices[0] = 2;

        assertEquals(Float.floatToRawIntBits(0), copied.vertexFieldBits(0, ImmutableModelPartMesh.POSITION_X));
        assertArrayEquals(new int[] {0, 1, 2, 2, 3, 0}, copied.copyIndices());
    }

    @Test
    void exactMeshesCompareEqualAndDifferentDataDoesNot() {
        ImmutableModelPartMesh first = ModelPartMeshTestFixtures.oneQuadMesh(0);
        ImmutableModelPartMesh equal = ModelPartMeshTestFixtures.oneQuadMesh(0);
        ImmutableModelPartMesh different = ModelPartMeshTestFixtures.oneQuadMesh(1);

        assertEquals(first, equal);
        assertEquals(first.hashCode(), equal.hashCode());
        assertNotEquals(first, different);
    }

    @Test
    void forcedFingerprintCollisionDoesNotBypassExactEquality() {
        ImmutableModelPartMesh first = ModelPartMeshTestFixtures.oneQuadMesh(0);
        ImmutableModelPartMesh second = ModelPartMeshTestFixtures.oneQuadMesh(1);
        ModelPartMeshFingerprint collision = new ModelPartMeshFingerprint(42);
        ModelPartMeshKey firstKey =
                new ModelPartMeshKey(first.structure(), first.copyVertexData(), first.copyIndices(), collision);
        ModelPartMeshKey secondKey =
                new ModelPartMeshKey(second.structure(), second.copyVertexData(), second.copyIndices(), collision);

        assertEquals(firstKey.hashCode(), secondKey.hashCode());
        assertNotEquals(firstKey, secondKey);
    }

    @Test
    void retainedBytesCountExactPrimitiveMeshPayload() {
        ImmutableModelPartMesh mesh = ModelPartMeshTestFixtures.oneQuadMesh(0);

        long expected = (long) (4 * ImmutableModelPartMesh.VERTEX_STRIDE_INTS + 6) * Integer.BYTES;
        assertEquals(expected, mesh.retainedBytes());
    }
}
