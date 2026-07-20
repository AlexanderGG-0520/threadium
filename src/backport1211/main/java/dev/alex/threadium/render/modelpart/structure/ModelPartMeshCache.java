package dev.alex.threadium.render.modelpart.structure;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/** Render-thread-owned bounded root memoization plus exact immutable-mesh interning. */
final class ModelPartMeshCache {
    private final int maximumRoots;
    private final int maximumUniqueMeshes;
    private final long maximumRetainedBytes;
    private final IdentityHashMap<Object, ImmutableModelPartMesh> meshesByRoot = new IdentityHashMap<>();
    private final Map<ModelPartMeshKey, ImmutableModelPartMesh> uniqueMeshes = new HashMap<>();
    private long retainedBytes;

    ModelPartMeshCache(int maximumRoots, int maximumUniqueMeshes, long maximumRetainedBytes) {
        if (maximumRoots <= 0 || maximumUniqueMeshes <= 0 || maximumRetainedBytes < 0) {
            throw new IllegalArgumentException("Mesh cache limits are invalid");
        }
        this.maximumRoots = maximumRoots;
        this.maximumUniqueMeshes = maximumUniqueMeshes;
        this.maximumRetainedBytes = maximumRetainedBytes;
    }

    ImmutableModelPartMesh get(Object root) {
        return meshesByRoot.get(root);
    }

    boolean canCacheNewRoot() {
        return meshesByRoot.size() < maximumRoots;
    }

    ImmutableModelPartMesh intern(Object root, ImmutableModelPartMesh candidate) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(candidate, "candidate");
        if (!canCacheNewRoot() && !meshesByRoot.containsKey(root)) {
            throw new ModelPartMeshCapacityException("ModelPart root cache capacity exceeded");
        }

        ImmutableModelPartMesh canonical = uniqueMeshes.get(candidate.key());
        if (canonical == null) {
            long nextRetainedBytes = Math.addExact(retainedBytes, candidate.retainedBytes());
            if (uniqueMeshes.size() >= maximumUniqueMeshes || nextRetainedBytes > maximumRetainedBytes) {
                throw new ModelPartMeshCapacityException("ModelPart unique mesh cache capacity exceeded");
            }
            uniqueMeshes.put(candidate.key(), candidate);
            canonical = candidate;
            retainedBytes = nextRetainedBytes;
        }
        meshesByRoot.put(root, canonical);
        return canonical;
    }

    int rootCount() {
        return meshesByRoot.size();
    }

    int uniqueMeshCount() {
        return uniqueMeshes.size();
    }

    long retainedBytes() {
        return retainedBytes;
    }

    void clear() {
        meshesByRoot.clear();
        uniqueMeshes.clear();
        retainedBytes = 0;
    }
}
