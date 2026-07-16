package dev.alex.threadium.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerationCounterTest {
    @Test void reportsEachGenerationExactlyOnce() {
        GenerationCounter counter = new GenerationCounter();
        assertEquals(0, counter.current());
        assertEquals(1, counter.advance());
        assertEquals(2, counter.advance());
        assertEquals(2, counter.current());
    }
}
