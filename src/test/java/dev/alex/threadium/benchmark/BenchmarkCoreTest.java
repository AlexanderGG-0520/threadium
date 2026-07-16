package dev.alex.threadium.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class BenchmarkCoreTest {
    @Test
    void modeParsingAndPolicyAreUnambiguous() {
        assertTrue(ModelPartBenchmarkMode.parseOptional(null).isEmpty());
        assertEquals(
                ModelPartBenchmarkMode.VANILLA,
                ModelPartBenchmarkMode.parseOptional("vanilla").orElseThrow());
        assertFalse(ModelPartBenchmarkMode.VANILLA.replacementEnabled());
        assertTrue(ModelPartBenchmarkMode.SINGLETON.replacementEnabled());
        assertFalse(ModelPartBenchmarkMode.SINGLETON.consolidationEnabled());
        assertTrue(ModelPartBenchmarkMode.BATCHING.consolidationEnabled());
        assertThrows(IllegalArgumentException.class, () -> ModelPartBenchmarkMode.parseOptional("mixed"));
    }

    @Test
    void boundedSamplesNeverGrowPastCapacity() {
        BoundedFrameSamples samples = new BoundedFrameSamples(2);
        assertTrue(samples.add(1));
        assertTrue(samples.add(2));
        assertFalse(samples.add(3));
        assertArrayEquals(new long[] {1, 2}, samples.copy());
        assertTrue(samples.overflowed());
    }

    @Test
    void frameStatisticsUseSlowestMeanForOnePercentLow() {
        long[] samples = new long[100];
        java.util.Arrays.fill(samples, 10_000_000);
        samples[99] = 100_000_000;
        FrameStatistics stats = FrameStatistics.calculate(samples);
        assertEquals(100, stats.sampleCount());
        assertEquals(10.0, stats.onePercentLowFps(), 1e-9);
        assertEquals(10_900_000, stats.averageNanos(), 1e-9);
        assertTrue(stats.p99Nanos() >= 10_000_000);
    }

    @Test
    void percentilesAreDeterministic() {
        FrameStatistics stats = FrameStatistics.calculate(new long[] {1, 2, 3, 4, 5});
        assertEquals(3, stats.medianNanos());
        assertEquals(4.8, stats.p95Nanos(), 1e-9);
        assertEquals(4.96, stats.p99Nanos(), 1e-9);
    }

    @Test
    void vanillaRejectsAnyReplacementActivity() {
        var counters = new BenchmarkTrialValidator.Counters(1, 1, 1, 1, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0);
        List<String> reasons = BenchmarkTrialValidator.validate(
                ModelPartBenchmarkMode.VANILLA, counters, true, true, true, true, true, false, 100, 100, 10);
        assertTrue(reasons.contains("VANILLA replacement activity"));
    }

    @Test
    void singletonRequiresOneDrawPerInstance() {
        var counters = new BenchmarkTrialValidator.Counters(10, 10, 10, 10, 9, 1, 2, 0, 0, 0, 0, 0, 0, 0);
        List<String> reasons = BenchmarkTrialValidator.validate(
                ModelPartBenchmarkMode.SINGLETON, counters, true, true, true, true, true, false, 100, 100, 10);
        assertTrue(reasons.contains("SINGLETON draw invariant"));
    }

    @Test
    void batchingAllowsReducedDrawCount() {
        var counters = new BenchmarkTrialValidator.Counters(10, 10, 10, 10, 2, 2, 8, 0, 0, 0, 0, 0, 0, 0);
        assertTrue(BenchmarkTrialValidator.validate(
                        ModelPartBenchmarkMode.BATCHING, counters, true, true, true, true, true, false, 100, 100, 10)
                .isEmpty());
    }

    @Test
    void batchingRequiresActualConsolidation() {
        var counters = new BenchmarkTrialValidator.Counters(10, 10, 10, 10, 10, 0, 1, 0, 0, 0, 0, 0, 0, 0);
        assertTrue(BenchmarkTrialValidator.validate(
                        ModelPartBenchmarkMode.BATCHING, counters, true, true, true, true, true, false, 100, 100, 10)
                .contains("BATCHING draw invariant"));
    }

    @Test
    void invalidTrialSignalsEnvironmentalAndBackendFailures() {
        var counters = new BenchmarkTrialValidator.Counters(1, 1, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1);
        List<String> reasons = BenchmarkTrialValidator.validate(
                ModelPartBenchmarkMode.SINGLETON, counters, false, false, false, false, false, true, 10, 100, 1);
        assertTrue(reasons.size() >= 10);
    }

    @Test
    void filenamesAreStableAndSanitized() {
        assertEquals(
                "2026-07-16T15-30-00Z_singleton_static-cows_trial-03",
                BenchmarkFileNames.base(
                        Instant.parse("2026-07-16T15:30:00Z"), ModelPartBenchmarkMode.SINGLETON, "Static Cows!", 3));
    }

    @Test
    void preStartIsUnlockedAndTrialPhasesLock() {
        assertFalse(BenchmarkLifecyclePolicy.cameraLocked(BenchmarkLifecyclePolicy.Phase.IDLE));
        assertTrue(BenchmarkLifecyclePolicy.cameraLocked(BenchmarkLifecyclePolicy.Phase.SETUP));
        assertTrue(BenchmarkLifecyclePolicy.cameraLocked(BenchmarkLifecyclePolicy.Phase.WARMUP));
        assertTrue(BenchmarkLifecyclePolicy.cameraLocked(BenchmarkLifecyclePolicy.Phase.MEASUREMENT));
        assertFalse(BenchmarkLifecyclePolicy.cameraLocked(BenchmarkLifecyclePolicy.Phase.ABORTED));
    }

    @Test
    void startAndAbortPoliciesRejectInvalidStates() {
        assertTrue(BenchmarkLifecyclePolicy.mayStart(BenchmarkLifecyclePolicy.Phase.IDLE, true, true, true));
        assertFalse(BenchmarkLifecyclePolicy.mayStart(BenchmarkLifecyclePolicy.Phase.IDLE, true, false, true));
        assertFalse(BenchmarkLifecyclePolicy.mayStart(BenchmarkLifecyclePolicy.Phase.WARMUP, true, true, true));
        assertTrue(BenchmarkLifecyclePolicy.mayAbort(BenchmarkLifecyclePolicy.Phase.SETUP));
        assertFalse(BenchmarkLifecyclePolicy.mayAbort(BenchmarkLifecyclePolicy.Phase.IDLE));
    }

    @Test
    void staticSceneLayoutIsStableUniqueAndComplete() {
        var scene = BenchmarkSceneSpec.STATIC;
        assertEquals(256, scene.entityCount());
        assertEquals(256, scene.placements().size());
        assertEquals(256, new HashSet<>(scene.placements()).size());
        assertEquals(scene.hash(), BenchmarkSceneSpec.STATIC.hash());
        assertEquals(64, scene.hash().length());
        assertTrue(scene.markerContents().contains("version=1"));
    }

    @Test
    void fallbackDiagnosticsAreBoundedAndAggregate() {
        var diagnostics = new BoundedFallbackDiagnostics(2);
        diagnostics.record("material", "p", "r", "m", "VALID");
        diagnostics.record("material", "p", "r", "m", "VALID");
        diagnostics.record("capacity", "p", "r", "m", "VALID");
        diagnostics.record("third", "p", "r", "m", "VALID");
        var snapshot = diagnostics.snapshotAndReset();
        assertEquals(2, snapshot.entries().size());
        assertEquals(2, snapshot.entries().getFirst().count());
        assertEquals(1, snapshot.omittedUniqueOccurrences());
        assertTrue(diagnostics.snapshotAndReset().entries().isEmpty());
    }

    @Test
    void completionNotificationCanOnlyBeClaimedOnce() {
        var guard = new CompletionNotificationGuard();
        assertTrue(guard.claim());
        assertFalse(guard.claim());
        assertTrue(guard.emitted());
    }

    @Test
    void validCompletionRetainsResultAndExitInstruction() {
        var lines = BenchmarkCompletionNotice.complete(
                "VALID", "VANILLA", "static", 2, 100, "run/result.json", List.of(), false);
        assertTrue(lines.contains("Status: VALID"));
        assertTrue(lines.contains("Result: run/result.json"));
        assertEquals("You may exit Minecraft now.", lines.getLast());
    }

    @Test
    void invalidCompletionListsReasons() {
        var lines = BenchmarkCompletionNotice.complete(
                "INVALID",
                "SINGLETON",
                "static",
                3,
                50,
                "result.json",
                List.of("entity count changed", "unexpected fallback"),
                false);
        assertTrue(lines.contains("Invalid reasons:"));
        assertTrue(lines.contains("- entity count changed"));
        assertTrue(lines.contains("- unexpected fallback"));
    }

    @Test
    void abortedCompletionConfirmsControlsRestored() {
        var lines = BenchmarkCompletionNotice.aborted("result.json");
        assertTrue(lines.getFirst().contains("aborted"));
        assertEquals("Result: result.json", lines.get(1));
        assertTrue(lines.getLast().contains("restored"));
        assertFalse(BenchmarkLifecyclePolicy.cameraLocked(BenchmarkLifecyclePolicy.Phase.ABORTED));
    }

    @Test
    void autoExitDefaultsOffAndUsesFiveSecondMinimum() {
        assertFalse(BenchmarkCompletionNotice.autoExitEnabled(null));
        assertFalse(BenchmarkCompletionNotice.autoExitEnabled("false"));
        assertTrue(BenchmarkCompletionNotice.autoExitEnabled("true"));
        assertTrue(BenchmarkCompletionNotice.AUTO_EXIT_DELAY_NANOS >= 5_000_000_000L);
        assertTrue(BenchmarkCompletionNotice.complete("VALID", "VANILLA", "static", 1, 2, "r", List.of(), true)
                .getLast()
                .contains("5 seconds"));
    }

    @Test
    void completedPhaseReleasesCameraBeforeNoticePolicy() {
        assertFalse(BenchmarkLifecyclePolicy.cameraLocked(BenchmarkLifecyclePolicy.Phase.COMPLETE));
        assertEquals(
                "Threadium benchmark complete — VALID — You may exit now",
                BenchmarkCompletionNotice.actionBar("VALID"));
    }

    @Test
    void realStartupOwnershipKeepsControllerIndependentOfRenderer() {
        for (var mode : ModelPartBenchmarkMode.values()) {
            var ownership = BenchmarkStartupOwnership.forMode(mode);
            assertTrue(ownership.benchmarkController());
            assertTrue(ownership.benchmarkCommands());
            assertEquals(mode.replacementEnabled(), ownership.modelPartRenderer());
        }
        var vanilla = BenchmarkStartupOwnership.forMode(ModelPartBenchmarkMode.VANILLA);
        assertFalse(vanilla.modelPartRenderer());
        assertEquals("NO_OP", vanilla.rendererMetricsSource());
    }

    @Test
    void serverOwnershipAndTagIndependentClientMatchingCanPass() {
        var server = new BenchmarkPopulationSnapshot.Server(256, 256, 256, 0, 0, 0);
        var clientWithoutTags = new BenchmarkPopulationSnapshot.Client(256, 256, 0);
        var combined = new BenchmarkPopulationSnapshot.Combined(server, clientWithoutTags);
        assertTrue(BenchmarkStartValidationPolicy.mayEnterSetup(combined, 256));
        assertNull(combined.rejection(256));
    }

    @Test
    void incompleteClientTrackingRejectsAfterValidServer() {
        var combined = new BenchmarkPopulationSnapshot.Combined(
                new BenchmarkPopulationSnapshot.Server(256, 256, 256, 0, 0, 0),
                new BenchmarkPopulationSnapshot.Client(188, 188, 0));
        assertFalse(combined.valid(256));
        assertTrue(combined.rejection(256).startsWith("Client benchmark population not fully tracked"));
    }

    @Test
    void missingServerOwnershipRejectsBeforeClient() {
        var server = new BenchmarkPopulationSnapshot.Server(0, 0, 0, 0, 256, 0);
        var combined =
                new BenchmarkPopulationSnapshot.Combined(server, new BenchmarkPopulationSnapshot.Client(256, 256, 0));
        assertTrue(combined.rejection(256).startsWith("Server benchmark population invalid"));
        assertFalse(BenchmarkStartValidationPolicy.mayWriteMarker(server, 256));
    }

    @Test
    void pendingValidationHasBoundedTimeout() {
        assertFalse(BenchmarkStartValidationPolicy.timedOut(true, 99, 100));
        assertTrue(BenchmarkStartValidationPolicy.timedOut(true, 100, 100));
        assertFalse(BenchmarkStartValidationPolicy.timedOut(false, 101, 100));
    }

    @Test
    void removedOldEntitiesAreExcludedButActiveDuplicatesFail() {
        var removedOldPlusNew = new BenchmarkPopulationSnapshot.Server(256, 256, 256, 0, 0, 0);
        assertTrue(removedOldPlusNew.valid(256));
        var activeOldPlusNew = new BenchmarkPopulationSnapshot.Server(512, 512, 256, 0, 0, 256);
        assertFalse(activeOldPlusNew.valid(256));
        assertEquals(256, activeOldPlusNew.duplicateExpectedPositions());
    }

    @Test
    void markerRecordsDelayedAuthoritativeSuccess() {
        String marker = BenchmarkSceneSpec.STATIC.markerContents();
        assertTrue(marker.contains("expectedEntities=256"));
        assertTrue(marker.contains("authoritativelyValidated=true"));
    }
}
