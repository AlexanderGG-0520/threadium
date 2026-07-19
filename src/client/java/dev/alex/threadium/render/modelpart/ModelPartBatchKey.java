package dev.alex.threadium.render.modelpart;

import net.minecraft.client.renderer.rendertype.RenderType;

/** Exact identity key; RenderType owns the immutable pipeline, textures and output target for this generation. */
public final class ModelPartBatchKey {
    private final ModelPartGpuBackend.MeshHandle mesh;
    private final RenderType type;
    private final Object pipeline, outputTarget, format, topology;
    private final long epoch, group;
    private final int hash;

    public ModelPartBatchKey(ModelPartGpuBackend.MeshHandle mesh, RenderType type, long epoch, long group) {
        this.mesh = mesh;
        this.type = type;
        pipeline = type.pipeline();
        outputTarget = type.outputTarget();
        format = type.format();
        topology = type.primitiveTopology();
        this.epoch = epoch;
        this.group = group;
        int computedHash = mesh.hashCode();
        computedHash = 31 * computedHash + System.identityHashCode(type);
        computedHash = 31 * computedHash + System.identityHashCode(pipeline);
        computedHash = 31 * computedHash + System.identityHashCode(outputTarget);
        computedHash = 31 * computedHash + System.identityHashCode(format);
        computedHash = 31 * computedHash + System.identityHashCode(topology);
        computedHash = 31 * computedHash + Long.hashCode(epoch);
        hash = 31 * computedHash + Long.hashCode(group);
    }

    /**
     * Tests whether constructing a key from these inputs would produce an equal key. An exact RenderType identity owns
     * the immutable pipeline, output target, format, and topology captured by this key, so those identities need not be
     * fetched again for a consecutive candidate of that same type.
     */
    boolean matches(
            ModelPartGpuBackend.MeshHandle candidateMesh,
            RenderType candidateType,
            long candidateEpoch,
            long candidateGroup) {
        return mesh.equals(candidateMesh)
                && type == candidateType
                && epoch == candidateEpoch
                && group == candidateGroup;
    }

    boolean referencesMesh(ModelPartGpuBackend.MeshHandle candidateMesh) {
        return mesh.equals(candidateMesh);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof ModelPartBatchKey key
                        && mesh.equals(key.mesh)
                        && type == key.type
                        && pipeline == key.pipeline
                        && outputTarget == key.outputTarget
                        && format == key.format
                        && topology == key.topology
                        && epoch == key.epoch
                        && group == key.group;
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
