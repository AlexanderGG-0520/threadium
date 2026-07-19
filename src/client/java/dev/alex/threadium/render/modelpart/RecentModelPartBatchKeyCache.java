package dev.alex.threadium.render.modelpart;

import net.minecraft.client.renderer.rendertype.RenderType;

/** One recent immutable structural key; the owner clears it at every group, frame, and lifecycle boundary. */
final class RecentModelPartBatchKeyCache {
    private ModelPartBatchKey recent;

    ModelPartBatchKey getOrCreate(ModelPartGpuBackend.MeshHandle mesh, RenderType type, long epoch, long groupId) {
        if (recent != null && recent.matches(mesh, type, epoch, groupId)) return recent;
        recent = new ModelPartBatchKey(mesh, type, epoch, groupId);
        return recent;
    }

    void clearIfReferences(ModelPartGpuBackend.MeshHandle mesh) {
        if (recent != null && recent.referencesMesh(mesh)) clear();
    }

    void clear() {
        recent = null;
    }

    int size() {
        return recent == null ? 0 : 1;
    }
}
