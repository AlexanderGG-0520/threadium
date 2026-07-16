package dev.alex.threadium.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DifferentialReadbackTrackerTest {
    @Test void comparisonCannotStartUntilAllFourUniqueBuffersComplete(){var tracker=new DifferentialReadbackTracker();assertTrue(tracker.complete(DifferentialReadbackTracker.Slot.REFERENCE_COLOR));assertFalse(tracker.complete(DifferentialReadbackTracker.Slot.REFERENCE_COLOR));assertFalse(tracker.ready());tracker.complete(DifferentialReadbackTracker.Slot.REFERENCE_DEPTH);tracker.complete(DifferentialReadbackTracker.Slot.CANDIDATE_COLOR);assertFalse(tracker.ready());tracker.complete(DifferentialReadbackTracker.Slot.CANDIDATE_DEPTH);assertTrue(tracker.ready());assertEquals(4,tracker.completedCount());}
    @Test void verticalOrientationIsNormalizedDeterministically(){assertEquals(6,DifferentialReadbackTracker.normalizedIndex(2,0,4,2));assertEquals(2,DifferentialReadbackTracker.normalizedIndex(2,1,4,2));assertThrows(IndexOutOfBoundsException.class,()->DifferentialReadbackTracker.normalizedIndex(4,0,4,2));}
}
