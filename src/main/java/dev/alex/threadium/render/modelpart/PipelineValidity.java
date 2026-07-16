package dev.alex.threadium.render.modelpart;

public record PipelineValidity(PipelineValidityState state, long generation, String reason) {
    public static PipelineValidity uninitialized(long generation) {
        return new PipelineValidity(PipelineValidityState.UNINITIALIZED, generation, "not compiled");
    }

    public boolean permitsReplacement(long currentGeneration) {
        return state == PipelineValidityState.VALID && generation == currentGeneration;
    }

    public PipelineValidity forGeneration(long currentGeneration) {
        return generation == currentGeneration ? this : new PipelineValidity(PipelineValidityState.STALE, generation, "compiled for generation " + generation);
    }
}
