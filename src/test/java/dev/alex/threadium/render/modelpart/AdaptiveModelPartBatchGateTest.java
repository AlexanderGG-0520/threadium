package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AdaptiveModelPartBatchGateTest {
    @Test
    void stableLargeBatchBecomesEligibleOnNextFrame() {
        AdaptiveModelPartBatchGate gate = new AdaptiveModelPartBatchGate();
        Object root = new Object();
        Object type = new Object();

        gate.beginFrame();
        int firstGroup = gate.nextGroupOrdinal();
        for (int i = 0; i < 16; i++) {
            assertFalse(gate.observeAndShouldReplace(firstGroup, root, type, 16));
        }

        gate.beginFrame();
        int secondGroup = gate.nextGroupOrdinal();
        for (int i = 0; i < 16; i++) {
            assertTrue(gate.observeAndShouldReplace(secondGroup, root, type, 16));
        }
    }

    @Test
    void smallBatchRemainsVanilla() {
        AdaptiveModelPartBatchGate gate = new AdaptiveModelPartBatchGate();
        Object root = new Object();
        Object type = new Object();

        gate.beginFrame();
        int firstGroup = gate.nextGroupOrdinal();
        for (int i = 0; i < 15; i++) {
            assertFalse(gate.observeAndShouldReplace(firstGroup, root, type, 16));
        }

        gate.beginFrame();
        int secondGroup = gate.nextGroupOrdinal();
        assertFalse(gate.observeAndShouldReplace(secondGroup, root, type, 16));
    }

    @Test
    void identitiesAndGroupsDoNotCrossContaminate() {
        AdaptiveModelPartBatchGate gate = new AdaptiveModelPartBatchGate();
        Object firstRoot = new Object();
        Object secondRoot = new Object();
        Object firstType = new Object();
        Object secondType = new Object();

        gate.beginFrame();
        int firstGroup = gate.nextGroupOrdinal();
        gate.nextGroupOrdinal();
        for (int i = 0; i < 4; i++) {
            gate.observeAndShouldReplace(firstGroup, firstRoot, firstType, 4);
        }

        gate.beginFrame();
        int matchingGroup = gate.nextGroupOrdinal();
        int matchingOtherGroup = gate.nextGroupOrdinal();
        assertTrue(gate.observeAndShouldReplace(matchingGroup, firstRoot, firstType, 4));
        assertFalse(gate.observeAndShouldReplace(matchingGroup, secondRoot, firstType, 4));
        assertFalse(gate.observeAndShouldReplace(matchingGroup, firstRoot, secondType, 4));
        assertFalse(gate.observeAndShouldReplace(matchingOtherGroup, firstRoot, firstType, 4));
    }

    @Test
    void thresholdOneKeepsBenchmarkReplacementImmediate() {
        AdaptiveModelPartBatchGate gate = new AdaptiveModelPartBatchGate();
        gate.beginFrame();
        assertTrue(gate.observeAndShouldReplace(gate.nextGroupOrdinal(), new Object(), new Object(), 1));
    }
}
