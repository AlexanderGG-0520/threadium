package dev.alex.threadium.render.modelpart;

import java.util.LinkedHashMap;

final class ModelPartMeshCache {
    private final int limit;
    private final long byteLimit;
    private long bytes;
    private final LinkedHashMap<GenericModelPartTopology.StructuralKey, ModelPartGpuBackend.MeshHandle> meshes =
            new LinkedHashMap<>(16, .75f, true);
    private final java.util.HashSet<GenericModelPartTopology.StructuralKey> failed = new java.util.HashSet<>();

    ModelPartMeshCache(int limit, long byteLimit) {
        this.limit = limit;
        this.byteLimit = byteLimit;
    }

    ModelPartGpuBackend.MeshHandle get(GenericModelPartTopology.StructuralKey key) {
        return meshes.get(key);
    }

    boolean failed(GenericModelPartTopology.StructuralKey key) {
        return failed.contains(key);
    }

    void fail(GenericModelPartTopology.StructuralKey key) {
        failed.add(key);
    }

    boolean put(GenericModelPartTopology.StructuralKey key, ModelPartGpuBackend.MeshHandle handle) {
        if (handle.bytes() > byteLimit || meshes.size() >= limit || bytes + handle.bytes() > byteLimit) return false;
        meshes.put(key, handle);
        bytes += handle.bytes();
        return true;
    }

    Iterable<ModelPartGpuBackend.MeshHandle> handles() {
        return meshes.values();
    }

    void clear() {
        meshes.clear();
        failed.clear();
        bytes = 0;
    }

    int size() {
        return meshes.size();
    }

    long bytes() {
        return bytes;
    }
}
