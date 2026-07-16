package dev.alex.threadium.render.modelpart;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PipelineValidityPolicyTest {
    private static final long GENERATION = 7;

    @Test void onlyValidCurrentGenerationCanProceed() {
        for (PipelineValidityState state : PipelineValidityState.values()) {
            PipelineValidity validity = new PipelineValidity(state, GENERATION, state.name());
            assertEquals(state == PipelineValidityState.VALID, validity.permitsReplacement(GENERATION));
        }
        assertFalse(new PipelineValidity(PipelineValidityState.VALID, GENERATION - 1, "old").permitsReplacement(GENERATION));
    }

    @Test void compilationExceptionIsInvalidAndFallsBack() {
        PipelineValidity result = PipelineCompilationPolicy.evaluate(GENERATION, () -> { throw new IllegalStateException("compile failed"); });
        assertEquals(PipelineValidityState.INVALID, result.state());
        assertEquals(ModelPartInterceptionResult.PASS_THROUGH, Blaze3dSubmissionPolicy.replacementResult(result, GENERATION, true));
        assertTrue(result.reason().contains("compile failed"));
    }

    @Test void validityIsIndependentPerSourcePipeline() {
        Object solid = new Object(), cutout = new Object(), cutoutCull = new Object();
        PipelineValidityTable<Object> table = new PipelineValidityTable<>();
        table.initialize(List.of(solid, cutout, cutoutCull), GENERATION);
        table.put(solid, new PipelineValidity(PipelineValidityState.VALID, GENERATION, "valid"));
        assertTrue(table.get(solid, GENERATION).permitsReplacement(GENERATION));
        assertEquals(PipelineValidityState.UNINITIALIZED, table.get(cutout, GENERATION).state());
        assertEquals(PipelineValidityState.UNINITIALIZED, table.get(cutoutCull, GENERATION).state());
    }

    @Test void generationChangeMarksEveryPipelineStale() {
        Object solid = new Object(), cutout = new Object();
        PipelineValidityTable<Object> table = new PipelineValidityTable<>();
        table.initialize(List.of(solid, cutout), GENERATION);
        table.put(solid, new PipelineValidity(PipelineValidityState.VALID, GENERATION, "valid"));
        table.markStale(GENERATION + 1);
        assertEquals(PipelineValidityState.STALE, table.get(solid, GENERATION + 1).state());
        assertFalse(table.get(solid, GENERATION + 1).permitsReplacement(GENERATION + 1));
        assertEquals(PipelineValidityState.STALE, table.get(cutout, GENERATION + 1).state());
    }

    @Test void queueInsertionFailureStillPassesThrough() {
        PipelineValidity valid = new PipelineValidity(PipelineValidityState.VALID, GENERATION, "valid");
        assertEquals(ModelPartInterceptionResult.PASS_THROUGH, Blaze3dSubmissionPolicy.replacementResult(valid, GENERATION, false));
    }

    @Test void suppressionResultExistsOnlyAfterValidityAndInsertion() {
        PipelineValidity valid = new PipelineValidity(PipelineValidityState.VALID, GENERATION, "valid");
        assertEquals(ModelPartInterceptionResult.GPU_REPLACED, Blaze3dSubmissionPolicy.replacementResult(valid, GENERATION, true));
        for (PipelineValidityState state : List.of(PipelineValidityState.UNINITIALIZED, PipelineValidityState.INVALID, PipelineValidityState.STALE, PipelineValidityState.UNSUPPORTED_BACKEND)) {
            PipelineValidity rejected = new PipelineValidity(state, GENERATION, state.name());
            assertEquals(ModelPartInterceptionResult.PASS_THROUGH, Blaze3dSubmissionPolicy.replacementResult(rejected, GENERATION, true));
        }
    }

    @Test void invalidFallbackDoesNotImplySubmissionMetrics() {
        ModelPartGpuMetrics metrics = new ModelPartGpuMetrics();
        PipelineValidity invalid = PipelineCompilationPolicy.evaluate(GENERATION, () -> false);
        if (!invalid.permitsReplacement(GENERATION)) metrics.blaze3dInvalidPipelineFallbacks.increment();
        assertEquals(1, metrics.blaze3dInvalidPipelineFallbacks.sum());
        assertEquals(0, metrics.blaze3dGroupsSubmitted.sum());
        assertEquals(0, metrics.blaze3dDrawCommands.sum());
        assertEquals(0, metrics.blaze3dInstancesSubmitted.sum());
        assertEquals(0, metrics.vanillaSuppressions.sum());
    }
}
