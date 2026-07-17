package dev.alex.threadium.render.phase;

import net.minecraft.client.renderer.feature.submit.SubmitNode;

/** Detached arrays captured on the render thread. Submit nodes are opaque identities on workers. */
public record TranslucentPhaseSnapshot(
        long generation, int phaseSlot, long pipelineEpoch, DetachedTranslucentSnapshot data) {
    public TranslucentPhaseSnapshot(
            long generation, int phaseSlot, long pipelineEpoch, SubmitNode[] submits, float[] distances) {
        this(generation, phaseSlot, pipelineEpoch, new DetachedTranslucentSnapshot(submits, distances));
    }

    public TranslucentPhaseSnapshot {
        if (data == null) throw new NullPointerException("data");
    }

    public int size() {
        return data.size();
    }
}
