package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DifferentialExecutionScope1211Test {
    @AfterEach
    void reset() {
        DifferentialExecutionScope.reset();
    }

    @Test
    void referenceBypassObservesOnlyExpectedPipeline() {
        try (var scope =
                DifferentialExecutionScope.enter(DifferentialExecutionScope.Mode.REFERENCE_BYPASS, "entity_solid")) {
            assertTrue(DifferentialExecutionScope.referenceBypass("entity_cutout"));
            assertTrue(DifferentialExecutionScope.referenceBypass("entity_solid"));
            assertEquals(1, scope.result().observed());
            assertEquals(0, scope.result().accepted());
        }
    }

    @Test
    void candidateRetainsAcceptanceSuppressionAndFallbackReason() {
        try (var scope = DifferentialExecutionScope.enter(
                DifferentialExecutionScope.Mode.CANDIDATE_REQUIRE_ACCEPT, "entity_solid")) {
            assertTrue(DifferentialExecutionScope.candidateRequiresAcceptance("entity_solid"));
            DifferentialExecutionScope.completed("entity_cutout", true);
            DifferentialExecutionScope.completed("entity_solid", false);
            DifferentialExecutionScope.fallbackReason("capacity");
            DifferentialExecutionScope.suppression();
            var result = scope.result();
            assertEquals(1, result.observed());
            assertEquals(0, result.accepted());
            assertEquals(1, result.fallback());
            assertEquals(1, result.suppressions());
            assertEquals("capacity", result.fallbackReason());
            assertEquals(1, result.totalAccepted());
            assertEquals(1, result.totalFallback());
        }
    }

    @Test
    void nestingAndCrossThreadUseAreRejected() {
        try (var ignored =
                DifferentialExecutionScope.enter(DifferentialExecutionScope.Mode.REFERENCE_BYPASS, "entity_solid")) {
            assertThrows(
                    IllegalStateException.class,
                    () -> DifferentialExecutionScope.enter(
                            DifferentialExecutionScope.Mode.CANDIDATE_REQUIRE_ACCEPT, "entity_solid"));
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                try {
                    DifferentialExecutionScope.referenceBypass("entity_solid");
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            });
            thread.start();
            assertDoesNotThrow(() -> {
                thread.join();
            });
            assertInstanceOf(IllegalStateException.class, failure.get());
        }
    }
}
