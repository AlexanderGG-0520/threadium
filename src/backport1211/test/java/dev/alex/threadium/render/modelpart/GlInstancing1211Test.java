package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GlInstancing1211Test {
    @Test
    void prefersCore33WhenBothPathsAreAvailable() {
        assertEquals(
                GlInstancing1211.DivisorApi.CORE_33,
                GlInstancing1211.select(true, true));
    }

    @Test
    void usesArbFallbackOnOpenGl32Contexts() {
        assertEquals(
                GlInstancing1211.DivisorApi.ARB,
                GlInstancing1211.select(false, true));
    }

    @Test
    void failsClosedWhenNoDivisorEntryPointExists() {
        assertEquals(
                GlInstancing1211.DivisorApi.UNAVAILABLE,
                GlInstancing1211.select(false, false));
    }
}
