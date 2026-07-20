package dev.alex.threadium.render.modelpart.structure;

import java.util.Arrays;
import java.util.Objects;

/** Collision-safe exact mesh identity; its fingerprint is never an equality authority. */
public final class ModelPartMeshKey {
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private final ModelPartStructureSnapshot structure;
    private final int[] vertexData;
    private final int[] indices;
    private final ModelPartMeshFingerprint fingerprint;

    ModelPartMeshKey(ModelPartStructureSnapshot structure, int[] vertexData, int[] indices) {
        this(structure, vertexData, indices, fingerprint(structure, vertexData, indices));
    }

    ModelPartMeshKey(
            ModelPartStructureSnapshot structure,
            int[] vertexData,
            int[] indices,
            ModelPartMeshFingerprint fingerprint) {
        this.structure = Objects.requireNonNull(structure, "structure");
        this.vertexData = Objects.requireNonNull(vertexData, "vertexData");
        this.indices = Objects.requireNonNull(indices, "indices");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
    }

    public ModelPartStructureSnapshot structure() {
        return structure;
    }

    public ModelPartMeshFingerprint fingerprint() {
        return fingerprint;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof ModelPartMeshKey key
                        && structure.equals(key.structure)
                        && Arrays.equals(vertexData, key.vertexData)
                        && Arrays.equals(indices, key.indices));
    }

    @Override
    public int hashCode() {
        return Long.hashCode(fingerprint.value());
    }

    private static ModelPartMeshFingerprint fingerprint(
            ModelPartStructureSnapshot structure, int[] vertexData, int[] indices) {
        long hash = mix(FNV_OFFSET_BASIS, structure.fingerprint().value());
        hash = mix(hash, vertexData.length);
        for (int value : vertexData) hash = mix(hash, value);
        hash = mix(hash, indices.length);
        for (int index : indices) hash = mix(hash, index);
        return new ModelPartMeshFingerprint(hash);
    }

    private static long mix(long hash, int value) {
        return (hash ^ Integer.toUnsignedLong(value)) * FNV_PRIME;
    }

    private static long mix(long hash, long value) {
        hash = (hash ^ (value & 0xffffffffL)) * FNV_PRIME;
        return (hash ^ (value >>> 32)) * FNV_PRIME;
    }
}
