package dev.alex.threadium.scheduler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShutdownGuardTest {
    @Test
    void repeatedShutdownBeginsOnlyOnce() {
        ShutdownGuard guard = new ShutdownGuard();
        assertFalse(guard.isShutdown());
        assertTrue(guard.beginShutdown());
        assertFalse(guard.beginShutdown());
        assertTrue(guard.isShutdown());
    }
}
