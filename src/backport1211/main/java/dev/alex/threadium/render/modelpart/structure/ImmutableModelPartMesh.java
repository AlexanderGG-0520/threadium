package dev.alex.threadium.render.modelpart.structure;

import java.util.Objects;

/** Immutable, Minecraft-object-free CPU mesh captured from Vanilla cuboid emission. */
public final class ImmutableModelPartMesh {
    public static final int VERTEX_STRIDE_INTS = 9;
    public static final int POSITION_X = 0;
    public static final int POSITION_Y = 1;
    public static final int POSITION_Z = 2;
    public static final int NORMAL_X = 3;
    public static final int NORMAL_Y = 4;
    public static final int NORMAL_Z = 5;
    public static final int TEXTURE_U = 6;
    public static final int TEXTURE_V = 7;
    public static final int BONE_INDEX = 8;

    private final int[] vertexData;
    private final int[] indices;
    private final ModelPartMeshKey key;
    private final long retainedBytes;

    private ImmutableModelPartMesh(
            ModelPartStructureSnapshot structure, int[] vertexData, int[] indices, boolean takeOwnership) {
        this.vertexData = takeOwnership ? vertexData : vertexData.clone();
        this.indices = takeOwnership ? indices : indices.clone();
        validate(structure, this.vertexData, this.indices);
        this.key = new ModelPartMeshKey(structure, this.vertexData, this.indices);
        this.retainedBytes = retainedBytes(this.vertexData.length, this.indices.length);
    }

    public static ImmutableModelPartMesh copyOf(ModelPartStructureSnapshot structure, int[] vertexData, int[] indices) {
        return new ImmutableModelPartMesh(
                Objects.requireNonNull(structure),
                Objects.requireNonNull(vertexData),
                Objects.requireNonNull(indices),
                false);
    }

    static ImmutableModelPartMesh takeOwnership(ModelPartStructureSnapshot structure, int[] vertexData, int[] indices) {
        return new ImmutableModelPartMesh(structure, vertexData, indices, true);
    }

    public ModelPartStructureSnapshot structure() {
        return key.structure();
    }

    public ModelPartMeshKey key() {
        return key;
    }

    public int partCount() {
        return structure().partCount();
    }

    public int quadCount() {
        return vertexCount() / 4;
    }

    public int vertexCount() {
        return vertexData.length / VERTEX_STRIDE_INTS;
    }

    public int indexCount() {
        return indices.length;
    }

    public long retainedBytes() {
        return retainedBytes;
    }

    public int vertexFieldBits(int vertexIndex, int fieldOffset) {
        if (vertexIndex < 0 || vertexIndex >= vertexCount()) throw new IndexOutOfBoundsException(vertexIndex);
        if (fieldOffset < 0 || fieldOffset >= VERTEX_STRIDE_INTS) throw new IndexOutOfBoundsException(fieldOffset);
        return vertexData[Math.addExact(Math.multiplyExact(vertexIndex, VERTEX_STRIDE_INTS), fieldOffset)];
    }

    public int index(int indexPosition) {
        return indices[indexPosition];
    }

    public int[] copyVertexData() {
        return vertexData.clone();
    }

    public int[] copyIndices() {
        return indices.clone();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof ImmutableModelPartMesh mesh && key.equals(mesh.key));
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    private static void validate(ModelPartStructureSnapshot structure, int[] vertexData, int[] indices) {
        if (vertexData.length % VERTEX_STRIDE_INTS != 0) {
            throw new IllegalArgumentException("Vertex data does not contain complete logical vertices");
        }
        int vertexCount = vertexData.length / VERTEX_STRIDE_INTS;
        if (vertexCount % 4 != 0 || indices.length != (long) vertexCount / 4 * 6) {
            throw new IllegalArgumentException("Mesh does not contain complete indexed quads");
        }
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int bone = vertexData[vertex * VERTEX_STRIDE_INTS + BONE_INDEX];
            if (bone < 0 || bone >= structure.partCount()) {
                throw new IllegalArgumentException("Vertex bone index is outside the structure");
            }
            if (vertex % 4 != 0) {
                int quadBone = vertexData[(vertex - vertex % 4) * VERTEX_STRIDE_INTS + BONE_INDEX];
                if (bone != quadBone) throw new IllegalArgumentException("A quad cannot span multiple bones");
            }
        }
        for (int index : indices) {
            if (index < 0 || index >= vertexCount) throw new IllegalArgumentException("Mesh index is out of range");
        }
    }

    static long retainedBytes(int vertexInts, int indexInts) {
        return Math.addExact(
                Math.multiplyExact((long) vertexInts, Integer.BYTES),
                Math.multiplyExact((long) indexInts, Integer.BYTES));
    }
}
