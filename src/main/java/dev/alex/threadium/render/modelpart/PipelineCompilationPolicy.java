package dev.alex.threadium.render.modelpart;

public final class PipelineCompilationPolicy {
    private PipelineCompilationPolicy() {}

    public static PipelineValidity evaluate(long generation, ValidityProbe probe) {
        try {
            return probe.isValid()
                    ? new PipelineValidity(PipelineValidityState.VALID, generation, "valid")
                    : new PipelineValidity(PipelineValidityState.INVALID, generation, "compiled pipeline is invalid");
        } catch (Throwable failure) {
            return new PipelineValidity(PipelineValidityState.INVALID, generation, failure.toString());
        }
    }

    @FunctionalInterface
    public interface ValidityProbe {
        boolean isValid() throws Throwable;
    }
}
