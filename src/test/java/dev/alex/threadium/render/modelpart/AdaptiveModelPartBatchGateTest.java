package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AdaptiveModelPartBatchGateTest {
    @Test
    void stableLargeBatchBecomesEligibleOnNextFrame() {
        AdaptiveModelPartBatchGate gate = new AdaptiveModelPartBatchGate();
        Object owner = new Object();
        Object root = new Object();
        Object type = new Object();

        gate.beginFrame();
        for (int i = 0; i < 16; i++) {
            assertFalse(gate.observeAndShouldReplace(owner, root, type, 16));
        }

        gate.beginFrame();
        for (int i = 0; i < 16; i++) {
            assertTrue(gate.observeAndShouldReplace(owner, root, type, 16));
        }
    }

    @Test
    void smallBatchRemainsVanilla() {
        AdaptiveModelPartBatchGate gate = new AdaptiveModelPartBatchGate();
        Object owner = new Object();
        Object root = new Object();
        Object type = new Object();

        gate.beginFrame();
        for (int i = 0; i < 15; i++) {
            assertFalse(gate.observeAndShouldReplace(owner, root, type, 16));
        }

        gate.beginFrame();
        assertFalse(gate.observeAndShouldReplace(owner, root, type, 16));
    }

    @Test
    void identitiesAndFeatureRenderersDoNotCrossContaminate() {
        AdaptiveModelPartBatchGate gate = new AdaptiveModelPartBatchGate();
        Object firstOwner = new Object();
        Object secondOwner = new Object();
        Object firstRoot = new Object();
        Object secondRoot = new Object();
        Object firstType = new Object();
        Object secondType = new Object();

        gate.beginFrame();
        for (int i = 0; i < 4; i++) {
            gate.observeAndShouldReplace(firstOwner, firstRoot, firstType, 4);
        }

        gate.beginFrame();
        assertTrue(gate.observeAndShouldReplace(firstOwner, firstRoot, firstType, 4));
        assertFalse(gate.observeAndShouldReplace(secondOwner, firstRoot, firstType, 4));
        assertFalse(gate.observeAndShouldReplace(firstOwner, secondRoot, firstType, 4));
        assertFalse(gate.observeAndShouldReplace(firstOwner, firstRoot, secondType, 4));
    }

    @Test
    void unrelatedGroupOrderingDoesNotInvalidateStableOwner() {
        AdaptiveModelPartBatchGate gate = new AdaptiveModelPartBatchGate();
        Object stableOwner = new Object();
        Object transientOwner = new Object();
        Object root = new Object();
        Object type = new Object();

        gate.beginFrame();
        for (int i = 0; i < 4; i++) gate.observeAndShouldReplace(stableOwner, root, type, 4);

        gate.beginFrame();
        gate.observeAndShouldReplace(transientOwner, new Object(), new Object(), 4);
        assertTrue(gate.observeAndShouldReplace(stableOwner, root, type, 4));
    }

    @Test
    void thresholdOneKeepsBenchmarkReplacementImmediate() {
        AdaptiveModelPartBatchGate gate = new AdaptiveModelPartBatchGate();
        gate.beginFrame();
        assertTrue(gate.observeAndShouldReplace(new Object(), new Object(), new Object(), 1));
    }

    @Test
    void singletonHeavyFlushStartsAndExpiresCooldown() {
        AdaptiveModelPartBatchGate gate = primedGate(16);
        Object owner = OWNER;

        gate.recordFlush(owner, new ModelPartGpuBackend.FlushStats(
                16, 16, 16, 16, 0, 0, 16, 0, 1, 0, 1, 1, 16L * 96L, 16L * 112L));
        assertEquals(AdaptiveModelPartBatchGate.UNPROFITABLE_COOLDOWN_FRAMES, gate.cooldownFrames(owner));

        gate.beginFrame();
        assertFalse(gate.observeAndShouldReplace(owner, ROOT, TYPE, 16));

        for (int i = 1; i < AdaptiveModelPartBatchGate.UNPROFITABLE_COOLDOWN_FRAMES; i++) gate.beginFrame();
        assertEquals(0, gate.cooldownFrames(owner));
    }

    @Test
    void efficientFlushDoesNotBlockNextStableFrame() {
        AdaptiveModelPartBatchGate gate = primedGate(16);
        gate.recordFlush(OWNER, new ModelPartGpuBackend.FlushStats(
                16, 1, 16, 1, 0, 0, 0, 1, 16, 16, 1, 1, 16L * 96L, 16L * 112L));
        assertEquals(0, gate.cooldownFrames(OWNER));

        gate.beginFrame();
        for (int i = 0; i < 16; i++) {
            assertTrue(gate.observeAndShouldReplace(OWNER, ROOT, TYPE, 16));
        }
    }

    private static final Object OWNER = new Object();
    private static final Object ROOT = new Object();
    private static final Object TYPE = new Object();

    private static AdaptiveModelPartBatchGate primedGate(int count) {
        AdaptiveModelPartBatchGate gate = new AdaptiveModelPartBatchGate();
        gate.beginFrame();
        for (int i = 0; i < count; i++) gate.observeAndShouldReplace(OWNER, ROOT, TYPE, count);
        gate.beginFrame();
        return gate;
    }
}
