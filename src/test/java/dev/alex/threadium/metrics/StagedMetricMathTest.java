package dev.alex.threadium.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StagedMetricMathTest {
    @Test void derivesQuadsOnlyForCompleteQuadTopology() {
        assertEquals(4, StagedMetricMath.quadCount(true, 16));
        assertEquals(0, StagedMetricMath.quadCount(false, 16));
        assertEquals(0, StagedMetricMath.quadCount(true, 15));
        assertEquals(0, StagedMetricMath.quadCount(true, -4));
    }

    @Test void overlapIsPositiveOnlyWhenReadyStrictlyPrecedesRequired() {
        assertEquals(25, StagedMetricMath.availableOverlap(100, 125));
        assertEquals(0, StagedMetricMath.availableOverlap(100, 100));
        assertEquals(0, StagedMetricMath.availableOverlap(125, 100));
        assertEquals(0, StagedMetricMath.availableOverlap(0, 100));
    }
}
