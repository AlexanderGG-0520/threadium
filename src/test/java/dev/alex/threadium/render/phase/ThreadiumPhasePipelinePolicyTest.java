package dev.alex.threadium.render.phase;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ThreadiumPhasePipelinePolicyTest {
    @Test
    void thresholdBoundaryUsesVanillaOnlyBelowMinimum() {
        assertTrue(ThreadiumPhasePipeline.shouldUseVanillaTranslucentPhase(127, 128));
        assertFalse(ThreadiumPhasePipeline.shouldUseVanillaTranslucentPhase(128, 128));
        assertFalse(ThreadiumPhasePipeline.shouldUseVanillaTranslucentPhase(129, 128));
    }
}
