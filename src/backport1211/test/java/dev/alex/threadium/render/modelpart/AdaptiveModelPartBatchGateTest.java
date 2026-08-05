package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class AdaptiveModelPartBatchGateTest {
    @Test
    void stableGroupBecomesEligibleOnTheFollowingFrame() {
        AdaptiveModelPartBatchGate gate = new AdaptiveModelPartBatchGate();
        Object owner = new Object();
        Object root = new Object();
        Object type = new Object();

        gate.beginFrame();
        for (int index = 0; index < 16; index++) {
            assertFalse(gate.observeAndShouldReplace(owner, root, type, 16));
        }
        gate.beginFrame();
        for (int index = 0; index < 16; index++) {
            assertTrue(gate.observeAndShouldReplace(owner, root, type, 16));
        }
    }

    @Test
    void exactIdentitiesDoNotCrossContaminate() {
        AdaptiveModelPartBatchGate gate = new AdaptiveModelPartBatchGate();
        Object owner = new Object();
        Object root = new Object();
        Object type = new Object();

        gate.beginFrame();
        for (int index = 0; index < 4; index++) gate.observeAndShouldReplace(owner, root, type, 4);
        gate.beginFrame();

        assertTrue(gate.observeAndShouldReplace(owner, root, type, 4));
        assertFalse(gate.observeAndShouldReplace(new Object(), root, type, 4));
        assertFalse(gate.observeAndShouldReplace(owner, new Object(), type, 4));
        assertFalse(gate.observeAndShouldReplace(owner, root, new Object(), 4));
    }

    @Test
    void singletonHeavyFlushStartsThenExpiresCooldown() {
        AdaptiveModelPartBatchGate gate = new AdaptiveModelPartBatchGate();
        Object owner = new Object();
        Object root = new Object();
        Object type = new Object();
        gate.beginFrame();
        for (int index = 0; index < 16; index++) gate.observeAndShouldReplace(owner, root, type, 16);
        gate.beginFrame();
        for (int index = 0; index < 16; index++) assertTrue(gate.observeAndShouldReplace(owner, root, type, 16));

        gate.recordFlush(owner, new ModelPartFlushStats(16, 16, 16, 16, 0, 0, 16, 0, 1, 0, 1, 1, 1536, 1792));
        assertEquals(AdaptiveModelPartBatchGate.UNPROFITABLE_COOLDOWN_FRAMES, gate.cooldownFrames(owner));
        gate.beginFrame();
        assertFalse(gate.observeAndShouldReplace(owner, root, type, 16));

        for (int index = 1; index < AdaptiveModelPartBatchGate.UNPROFITABLE_COOLDOWN_FRAMES; index++) gate.beginFrame();
        assertEquals(0, gate.cooldownFrames(owner));
    }

    @Test
    void profitableMultiInstanceFlushKeepsGroupEligible() {
        AdaptiveModelPartBatchGate gate = new AdaptiveModelPartBatchGate();
        Object owner = new Object();
        Object root = new Object();
        Object type = new Object();
        gate.beginFrame();
        for (int index = 0; index < 8; index++) gate.observeAndShouldReplace(owner, root, type, 8);
        gate.beginFrame();
        for (int index = 0; index < 8; index++) assertTrue(gate.observeAndShouldReplace(owner, root, type, 8));

        gate.recordFlush(owner, new ModelPartFlushStats(8, 2, 8, 2, 0, 0, 0, 2, 4, 8, 1, 1, 768, 896));

        assertEquals(0, gate.cooldownFrames(owner));
    }
}
