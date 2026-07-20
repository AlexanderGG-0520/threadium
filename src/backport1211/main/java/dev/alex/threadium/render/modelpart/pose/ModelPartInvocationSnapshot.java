package dev.alex.threadium.render.modelpart.pose;

import dev.alex.threadium.render.modelpart.structure.ImmutableModelPartMesh;
import java.util.Objects;

/** One immutable frame-local observation; render-instance values remain outside reusable bone-pose identity. */
public record ModelPartInvocationSnapshot(
        ImmutableModelPartMesh mesh,
        ImmutableModelPartBonePose pose,
        ImmutableRootRenderTransform rootTransform,
        int light,
        int overlay,
        int color,
        long worldGeneration,
        long resourceGeneration) {
    public ModelPartInvocationSnapshot {
        Objects.requireNonNull(mesh, "mesh");
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(rootTransform, "rootTransform");
    }
}
