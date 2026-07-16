package dev.alex.threadium.render.modelpart;

public final class Blaze3dSubmissionPolicy {
    private Blaze3dSubmissionPolicy() {}
    public static boolean generationMatches(long queuedGeneration,long currentGeneration,long queuedEpoch,long currentEpoch){return queuedGeneration==currentGeneration&&queuedEpoch==currentEpoch;}
    public static boolean rawProductionInvariant(long rawProductionDrawCalls){return rawProductionDrawCalls==0;}
    public static ModelPartInterceptionResult replacementResult(PipelineValidity validity,long generation,boolean queueInserted){return validity.permitsReplacement(generation)&&queueInserted?ModelPartInterceptionResult.GPU_REPLACED:ModelPartInterceptionResult.PASS_THROUGH;}
}
