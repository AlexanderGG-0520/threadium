package dev.alex.threadium.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.InactivityFpsLimit;
import org.junit.jupiter.api.Test;

class BenchmarkInactivityFpsOverrideTest {
    @Test
    void appliesMinimizedAndRestoresOriginalValue() {
        var value = new AtomicReference<>(InactivityFpsLimit.AFK);
        var override = new BenchmarkInactivityFpsOverride();

        assertTrue(override.apply(value::get, value::set));
        assertEquals(InactivityFpsLimit.MINIMIZED, value.get());
        assertEquals("AFK", override.originalName());
        assertEquals("MINIMIZED", override.effectiveName());
        assertTrue(override.stable());
        assertFalse(override.restored());

        assertTrue(override.restore(value::get, value::set));
        assertEquals(InactivityFpsLimit.AFK, value.get());
        assertTrue(override.restored());
    }

    @Test
    void observationPermanentlyRecordsMidTrialMutation() {
        var value = new AtomicReference<>(InactivityFpsLimit.AFK);
        var override = new BenchmarkInactivityFpsOverride();
        assertTrue(override.apply(value::get, value::set));

        value.set(InactivityFpsLimit.AFK);
        override.observe(value::get);
        value.set(InactivityFpsLimit.MINIMIZED);
        override.observe(value::get);

        assertFalse(override.stable());
        assertTrue(override.restore(value::get, value::set));
    }

    @Test
    void failedApplicationRestoresTheCapturedValue() {
        var value = new AtomicReference<>(InactivityFpsLimit.AFK);
        var override = new BenchmarkInactivityFpsOverride();

        assertFalse(override.apply(value::get, ignored -> {}));
        assertEquals(InactivityFpsLimit.AFK, value.get());
        assertFalse(override.stable());
        assertTrue(override.restored());
    }
}
