package dev.alex.threadium.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ThreadiumRuntimeConfigTest {
    private static final ThreadiumRuntimeConfig.Snapshot ON = snapshot(true, true, true, true, 4, 2);

    @Test
    void successfulSavePublishesImmediateSettingsOnly() {
        var controller = new ThreadiumRuntimeConfig.Controller(ON);
        var saved = snapshot(false, false, false, false, 32, 4);

        assertTrue(controller.publishAfterSave(saved, true));
        assertEquals(32, controller.effective().gpuMinimumGroupSubmits());
        assertFalse(controller.effective().metricsEnabled());
        assertTrue(controller.effective().gpuEntityEnabled());
        assertTrue(controller.framePending());
        assertTrue(controller.worldPending());
    }

    @Test
    void failedSaveLeavesDesiredAndEffectiveStateUntouched() {
        var controller = new ThreadiumRuntimeConfig.Controller(ON);
        assertFalse(controller.publishAfterSave(snapshot(false, false, false, false, 32, 4), false));
        assertSame(ON, controller.desired());
        assertSame(ON, controller.effective());
    }

    @Test
    void gpuDisableWaitsForSafeFrameBoundaryAndAppliesOnce() {
        var controller = new ThreadiumRuntimeConfig.Controller(ON);
        controller.publishAfterSave(snapshot(true, false, true, true, 4, 2), true);

        assertFalse(controller.applyFrameBoundary(false).applied());
        assertTrue(controller.effective().gpuEntityEnabled());
        var applied = controller.applyFrameBoundary(true);
        assertTrue(applied.applied());
        assertFalse(applied.after().gpuEntityEnabled());
        assertFalse(controller.applyFrameBoundary(true).applied());
    }

    @Test
    void gpuEnableWaitsForSafeFrameBoundary() {
        var off = snapshot(true, false, true, true, 4, 2);
        var controller = new ThreadiumRuntimeConfig.Controller(off);
        controller.publishAfterSave(ON, true);

        assertFalse(controller.applyFrameBoundary(false).applied());
        assertFalse(controller.effective().gpuEntityEnabled());
        assertTrue(controller.applyFrameBoundary(true).after().gpuEntityEnabled());
    }

    @Test
    void latestSaveWinsBeforeBoundary() {
        var controller = new ThreadiumRuntimeConfig.Controller(ON);
        controller.publishAfterSave(snapshot(true, false, true, true, 4, 2), true);
        controller.publishAfterSave(snapshot(true, true, false, true, 4, 2), true);
        assertTrue(controller.applyFrameBoundary(true).after().gpuEntityEnabled());

        controller.publishAfterSave(ON, true);
        controller.publishAfterSave(snapshot(true, false, true, true, 4, 2), true);
        assertFalse(controller.applyFrameBoundary(true).after().gpuEntityEnabled());
    }

    @Test
    void immediateBatchSettingsAreCoherent() {
        var controller = new ThreadiumRuntimeConfig.Controller(ON);
        var saved = new ThreadiumRuntimeConfig.Snapshot(
                true, true, false, true, true, 2, true, true, 64, false, false, 120);
        controller.publishAfterSave(saved, true);

        var effective = controller.effective();
        assertEquals(64, effective.gpuMinimumGroupSubmits());
        assertFalse(effective.gpuAllowVanillaFallback());
        assertFalse(effective.gpuBatchConsolidation());
        assertEquals(120, effective.metricsOutputIntervalSeconds());
    }

    @Test
    void phaseAndRetainedTextApplyAtFrameBoundary() {
        var controller = new ThreadiumRuntimeConfig.Controller(ON);
        controller.publishAfterSave(snapshot(true, true, false, false, 4, 2), true);
        assertTrue(controller.effective().phasePipelineEnabled());
        assertTrue(controller.effective().retainedTextEnabled());

        var effective = controller.applyFrameBoundary(true).after();
        assertFalse(effective.phasePipelineEnabled());
        assertFalse(effective.retainedTextEnabled());
    }

    @Test
    void workerSettingsRemainPendingUntilWorldBoundary() {
        var controller = new ThreadiumRuntimeConfig.Controller(ON);
        controller.publishAfterSave(snapshot(true, true, true, true, 4, 4), true);
        controller.applyFrameBoundary(true);
        assertEquals(2, controller.effective().workerCountOverride());
        assertTrue(controller.worldPending());
        assertEquals(4, controller.applyWorldBoundary().after().workerCountOverride());
        assertFalse(controller.worldPending());
    }

    @Test
    void disconnectBoundaryConsumesLatestPendingState() {
        var controller = new ThreadiumRuntimeConfig.Controller(ON);
        var latest = snapshot(false, false, false, false, 16, 3);
        controller.publishAfterSave(latest, true);
        assertEquals(latest, controller.applyWorldBoundary().after());
        assertFalse(controller.framePending());
        assertFalse(controller.worldPending());
    }

    @Test
    void resourceReloadDoesNotLosePendingFrameTransition() {
        var controller = new ThreadiumRuntimeConfig.Controller(ON);
        controller.publishAfterSave(snapshot(true, false, true, true, 4, 2), true);

        assertFalse(controller.applyFrameBoundary(false).applied());
        assertTrue(controller.framePending());
        assertFalse(controller.applyFrameBoundary(true).after().gpuEntityEnabled());
        assertFalse(controller.framePending());
    }

    @Test
    void unchangedSaveDoesNotRequestARebuild() {
        var controller = new ThreadiumRuntimeConfig.Controller(ON);
        controller.publishAfterSave(ON, true);
        assertFalse(controller.applyFrameBoundary(true).applied());
        assertFalse(controller.applyWorldBoundary().applied());
    }

    @Test
    void repeatedTogglesDoNotRetainOlderSnapshots() {
        var controller = new ThreadiumRuntimeConfig.Controller(ON);
        for (int i = 0; i < 32; i++) {
            var next = snapshot(true, (i & 1) == 0, true, true, 4, 2);
            controller.publishAfterSave(next, true);
        }
        assertFalse(controller.applyFrameBoundary(true).after().gpuEntityEnabled());
        assertFalse(controller.framePending());
    }

    private static ThreadiumRuntimeConfig.Snapshot snapshot(
            boolean metrics, boolean gpu, boolean phase, boolean retained, int minimum, int workers) {
        return new ThreadiumRuntimeConfig.Snapshot(
                true, metrics, false, false, phase, workers, retained, gpu, minimum, true, true, 30);
    }
}
