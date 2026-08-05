package dev.alex.threadium.render.modelpart.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RenderLayer1211OutlinePolicyTest {
    @Test
    void outlineKindsUseOrderedAdjacentGpuSubmission() {
        for (RenderLayer1211Descriptor.Kind outline : new RenderLayer1211Descriptor.Kind[] {
            RenderLayer1211Descriptor.Kind.OUTLINE_CULL, RenderLayer1211Descriptor.Kind.OUTLINE_NO_CULL
        }) {
            assertEquals(RenderLayer1211Descriptor.ShaderMode.OUTLINE, outline.shaderMode());
            assertEquals(
                    RenderLayer1211Descriptor.SubmissionPolicy.ORDERED_ADJACENT_BATCHED, outline.submissionPolicy());
            assertFalse(outline.alphaCutout());
            assertTrue(outline.backendSupported());
        }
    }
}
