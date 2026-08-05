package dev.alex.threadium.render.modelpart.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RenderLayer1211GlintPolicyTest {
    @Test
    void glintUsesOrderedAdjacentGpuSubmission() {
        RenderLayer1211Descriptor.Kind glint = RenderLayer1211Descriptor.Kind.GLINT;

        assertEquals(RenderLayer1211Descriptor.ShaderMode.GLINT, glint.shaderMode());
        assertEquals(RenderLayer1211Descriptor.SubmissionPolicy.ORDERED_ADJACENT_BATCHED, glint.submissionPolicy());
        assertTrue(glint.alphaCutout());
        assertTrue(glint.backendSupported());
    }
}
