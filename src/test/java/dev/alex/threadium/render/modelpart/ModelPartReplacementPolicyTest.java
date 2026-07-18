package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModelPartReplacementPolicyTest {
    @Test
    void groupThresholdIsInclusive() {
        assertFalse(ModelPartRenderService.groupMeetsMinimum(15, 16));
        assertTrue(ModelPartRenderService.groupMeetsMinimum(16, 16));
        assertTrue(ModelPartRenderService.groupMeetsMinimum(17, 16));
    }

    @Test
    void sortedPipelinesStayOnVanillaPath() {
        assertFalse(ModelPartRenderService.requiresVanillaForSortedPipeline(false));
        assertTrue(ModelPartRenderService.requiresVanillaForSortedPipeline(true));
    }

    @Test
    void failedOrUnavailableBackendCannotSuppressVanilla() {
        assertFalse(ModelPartSuppressionPolicy.maySuppress(
                DebugVisualMode.NORMAL, false, ModelPartInterceptionResult.PASS_THROUGH));
    }
}
