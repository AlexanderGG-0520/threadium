package dev.alex.threadium.render.phase;

import net.minecraft.client.renderer.feature.submit.SubmitNode;

/** Flat ordered output; no PreparedFrame, grouper, renderer, or phase storage is referenced. */
public record TranslucentPhaseResult(long generation, int phaseSlot, long pipelineEpoch, SubmitNode[] orderedSubmits) {}
