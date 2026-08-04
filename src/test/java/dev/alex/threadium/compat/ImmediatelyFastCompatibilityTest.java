package dev.alex.threadium.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ImmediatelyFastCompatibilityTest {
    @Test
    void verifiesOnlyAuditedMinecraft262Line() {
        assertTrue(ImmediatelyFastCompatibility.isVerifiedVersion("1.16.2+26.2-fabric"));
        assertTrue(ImmediatelyFastCompatibility.isVerifiedVersion("1.16.2+26.2"));
        assertFalse(ImmediatelyFastCompatibility.isVerifiedVersion("1.16.1+26.2-fabric"));
        assertFalse(ImmediatelyFastCompatibility.isVerifiedVersion("1.16.2+1.21.11-fabric"));
        assertFalse(ImmediatelyFastCompatibility.isVerifiedVersion(null));
    }

    @Test
    void runtimeStateDoesNotTreatUnknownVersionAsVerified() {
        assertTrue(ImmediatelyFastCompatibility.runtimeStateForTest(true, "1.16.2+26.2-fabric")
                .verified());
        assertFalse(
                ImmediatelyFastCompatibility.runtimeStateForTest(true, "future").verified());
        assertFalse(ImmediatelyFastCompatibility.runtimeStateForTest(false, "absent")
                .verified());
    }
}
