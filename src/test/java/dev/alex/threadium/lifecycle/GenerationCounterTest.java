package dev.alex.threadium.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GenerationCounterTest {
    @Test
    void reportsEachGenerationExactlyOnce() {
        GenerationCounter counter = new GenerationCounter();
        assertEquals(0, counter.current());
        assertEquals(1, counter.advance());
        assertEquals(2, counter.advance());
        assertEquals(2, counter.current());
    }
}
