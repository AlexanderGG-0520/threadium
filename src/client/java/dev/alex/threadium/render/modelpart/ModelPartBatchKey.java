package dev.alex.threadium.render.modelpart;

import net.minecraft.client.renderer.rendertype.RenderType;

/** Exact identity key; RenderType owns the immutable pipeline, textures and output target for this generation. */
public final class ModelPartBatchKey {
    private final ModelPartGpuBackend.MeshHandle mesh;
    private final RenderType type;
    private final Object pipeline, outputTarget, format, topology;
    private final long epoch, group;

    public ModelPartBatchKey(ModelPartGpuBackend.MeshHandle mesh, RenderType type, long epoch, long group) {
        this.mesh = mesh;
        this.type = type;
        pipeline = type.pipeline();
        outputTarget = type.outputTarget();
        format = type.format();
        topology = type.primitiveTopology();
        this.epoch = epoch;
        this.group = group;
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
        int h = mesh.hashCode();
        h = 31 * h + System.identityHashCode(type);
        h = 31 * h + System.identityHashCode(pipeline);
        h = 31 * h + System.identityHashCode(outputTarget);
        h = 31 * h + System.identityHashCode(format);
        h = 31 * h + System.identityHashCode(topology);
        h = 31 * h + Long.hashCode(epoch);
        return 31 * h + Long.hashCode(group);
    }
}
