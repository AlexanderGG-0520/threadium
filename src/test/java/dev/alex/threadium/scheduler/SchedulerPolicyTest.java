package dev.alex.threadium.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SchedulerPolicyTest {
    @Test
    void reservesOneProcessorAndCapsWorkers() {
        assertEquals(1, SchedulerPolicy.workerCount(0, 1));
        assertEquals(1, SchedulerPolicy.workerCount(0, 2));
        assertEquals(3, SchedulerPolicy.workerCount(0, 8));
        assertEquals(4, SchedulerPolicy.workerCount(32, 8));
    }

    @Test
    void rejectsInvalidInputs() {
        assertThrows(IllegalArgumentException.class, () -> SchedulerPolicy.workerCount(-1, 8));
        assertThrows(IllegalArgumentException.class, () -> SchedulerPolicy.workerCount(0, 0));
    }
}
