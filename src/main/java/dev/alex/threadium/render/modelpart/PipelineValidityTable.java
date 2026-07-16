package dev.alex.threadium.render.modelpart;

import java.util.IdentityHashMap;
import java.util.Map;

public final class PipelineValidityTable<T> {
    private final Map<T, PipelineValidity> values = new IdentityHashMap<>();

    public void initialize(Iterable<T> pipelines, long generation) {
        for (T pipeline : pipelines) values.put(pipeline, PipelineValidity.uninitialized(generation));
    }

    public void put(T pipeline, PipelineValidity validity) { values.put(pipeline, validity); }

    public PipelineValidity get(T pipeline, long generation) {
        PipelineValidity validity = values.get(pipeline);
        return validity == null ? PipelineValidity.uninitialized(generation) : validity.forGeneration(generation);
    }

    public void markStale(long nextGeneration) {
        values.replaceAll((pipeline, validity) -> new PipelineValidity(PipelineValidityState.STALE, nextGeneration, "resource generation changed"));
    }
}
