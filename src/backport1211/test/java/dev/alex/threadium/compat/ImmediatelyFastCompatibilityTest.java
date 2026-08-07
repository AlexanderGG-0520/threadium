package dev.alex.threadium.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ImmediatelyFastCompatibilityTest {
    @Test
    void verifiesOnlyAuditedMinecraft1211Line() {
        assertTrue(ImmediatelyFastCompatibility.isVerifiedVersion("1.6.11+1.21.1"));
        assertTrue(ImmediatelyFastCompatibility.isVerifiedVersion("1.6.11+1.21.1-fabric"));
        assertFalse(ImmediatelyFastCompatibility.isVerifiedVersion("1.6.10+1.21.1"));
        assertFalse(ImmediatelyFastCompatibility.isVerifiedVersion("1.6.11+1.21.4"));
        assertFalse(ImmediatelyFastCompatibility.isVerifiedVersion(null));
    }

    @Test
    void pinsAuditedBatchableBufferSourceClass() {
        assertEquals(
                "net.raphimc.immediatelyfast.feature.core.BatchableBufferSource",
                ImmediatelyFastCompatibility.BATCHABLE_BUFFER_SOURCE_CLASS);
    }

    @Test
    void runtimeStateDoesNotTreatUnknownVersionAsVerified() {
        assertTrue(ImmediatelyFastCompatibility.runtimeStateForTest(true, "1.6.11+1.21.1")
                .verified());
        assertFalse(
                ImmediatelyFastCompatibility.runtimeStateForTest(true, "future").verified());
        assertFalse(ImmediatelyFastCompatibility.runtimeStateForTest(false, "absent")
                .verified());
    }
}
