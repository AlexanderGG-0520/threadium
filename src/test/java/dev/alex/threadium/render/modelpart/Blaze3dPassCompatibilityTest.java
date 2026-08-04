package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Blaze3dPassCompatibilityTest {
    @Test
    void acceptsIdenticalAttachmentsAndEqualScissorState() {
        Object color = new Object();
        Object depth = new Object();
        assertTrue(Blaze3dModelPartBackend.compatiblePassState(
                color, depth, "disabled", color, depth, new String("disabled")));
    }

    @Test
    void rejectsDifferentAttachmentIdentityOrScissorState() {
        Object color = new Object();
        Object depth = new Object();
        assertFalse(
                Blaze3dModelPartBackend.compatiblePassState(color, depth, "disabled", new Object(), depth, "disabled"));
        assertFalse(
                Blaze3dModelPartBackend.compatiblePassState(color, depth, "disabled", color, new Object(), "disabled"));
        assertFalse(Blaze3dModelPartBackend.compatiblePassState(color, depth, "disabled", color, depth, "enabled"));
    }
}
