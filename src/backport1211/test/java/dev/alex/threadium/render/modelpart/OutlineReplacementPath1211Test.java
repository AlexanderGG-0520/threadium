package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OutlineReplacementPath1211Test {
    private static final Path CLIENT = Path.of("src", "backport1211", "client");

    @Test
    void wrapperDelegateAndColorAreCapturedBeforeAdmission() throws IOException {
        String service = Files.readString(CLIENT.resolve(
                Path.of("java", "dev", "alex", "threadium", "render", "entity", "ModelPartReplacementService.java")));

        assertTrue(service.contains("instanceof OutlineVertexConsumerAccessor"));
        assertTrue(service.contains("accessor.threadium$getDelegate()"));
        assertTrue(service.contains("accessor.threadium$getColor()"));
        assertTrue(service.contains("RenderLayer1211Descriptor.inspectOutline(resolution)"));
    }

    @Test
    void affectedOutlineUsesAtomicBaseAndOutlineQueue() throws IOException {
        String service = Files.readString(CLIENT.resolve(
                Path.of("java", "dev", "alex", "threadium", "render", "entity", "ModelPartReplacementService.java")));
        String backend = Files.readString(CLIENT.resolve(Path.of(
                "java", "dev", "alex", "threadium", "render", "modelpart", "ModelPartGpuInstanceBackend.java")));
        String immediateMixin = Files.readString(CLIENT.resolve(
                Path.of("java", "dev", "alex", "threadium", "mixin", "VertexConsumerProviderImmediateMixin.java")));

        assertTrue(service.contains("backend.queueOutlinePair("));
        assertTrue(backend.contains("public boolean queueOutlinePair("));
        assertTrue(backend.contains("outlineProvider,"));
        assertTrue(backend.contains("outlineDescriptor,"));
        assertTrue(backend.contains("private void appendPair("));
        assertTrue(immediateMixin.contains("ModelPartReplacementService.flushProviderLayer(this, layer);"));
    }
}
