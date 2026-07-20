package dev.alex.threadium.render.modelpart.structure;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

/** Render-thread-owned, lifecycle-bounded memoization by root object identity. */
final class ModelPartStructureCache {
    private final int maximumRoots;
    private final IdentityHashMap<Object, ModelPartStructureSnapshot> snapshotsByRoot = new IdentityHashMap<>();
    private final Set<ModelPartStructureSnapshot> uniqueSnapshots = new HashSet<>();

    ModelPartStructureCache(int maximumRoots) {
        if (maximumRoots <= 0) throw new IllegalArgumentException("maximumRoots must be positive");
        this.maximumRoots = maximumRoots;
    }

    ModelPartStructureSnapshot get(Object root) {
        return snapshotsByRoot.get(root);
    }

    boolean hasCapacity() {
        return snapshotsByRoot.size() < maximumRoots;
    }

    void put(Object root, ModelPartStructureSnapshot snapshot) {
        if (!hasCapacity() && !snapshotsByRoot.containsKey(root)) {
            throw new IllegalStateException("ModelPart structure cache capacity exceeded");
        }
        snapshotsByRoot.put(root, snapshot);
        uniqueSnapshots.add(snapshot);
    }

    int size() {
        return snapshotsByRoot.size();
    }

    int uniqueStructureCount() {
        return uniqueSnapshots.size();
    }

    void clear() {
        snapshotsByRoot.clear();
        uniqueSnapshots.clear();
    }
}
