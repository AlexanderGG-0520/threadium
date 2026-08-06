package dev.alex.threadium.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IrisCompatibilityTest {
    @Test
    void allowsReplacementWhenIrisIsAbsentOrInactive() {
        assertFalse(IrisCompatibility.shouldDisable(false, false, null));
        assertFalse(IrisCompatibility.shouldDisable(true, true, false));
    }

    @Test
    void failsClosedForActiveUnknownOrMalformedIrisState() {
        assertTrue(IrisCompatibility.shouldDisable(true, true, true));
        assertTrue(IrisCompatibility.shouldDisable(true, false, null));
        assertTrue(IrisCompatibility.shouldDisable(true, true, null));
    }
}
