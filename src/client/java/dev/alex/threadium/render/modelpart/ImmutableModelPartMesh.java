package dev.alex.threadium.render.modelpart;

public record ImmutableModelPartMesh(
        float[] vertices,
        int[] indices,
        float[] quadCenters,
        int[] quadBones,
        int boneCount,
        GenericModelPartTopology.StructuralKey key) {
    public int byteSize() {
        return Math.addExact(Math.multiplyExact(vertices.length, 4), Math.multiplyExact(indices.length, 4));
    }

    public int quadCount() {
        return quadBones.length;
    }
}
