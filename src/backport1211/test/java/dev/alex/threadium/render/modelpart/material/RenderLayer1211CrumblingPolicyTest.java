package dev.alex.threadium.render.modelpart.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RenderLayer1211CrumblingPolicyTest {
    @Test
    void crumblingUsesSortedGpuSubmission() {
        RenderLayer1211Descriptor.Kind crumbling = RenderLayer1211Descriptor.Kind.CRUMBLING;

        assertEquals(RenderLayer1211Descriptor.ShaderMode.CRUMBLING, crumbling.shaderMode());
        assertEquals(RenderLayer1211Descriptor.SubmissionPolicy.SORTED_QUAD_STREAM, crumbling.submissionPolicy());
        assertTrue(crumbling.alphaCutout());
        assertTrue(crumbling.backendSupported());
    }
}
