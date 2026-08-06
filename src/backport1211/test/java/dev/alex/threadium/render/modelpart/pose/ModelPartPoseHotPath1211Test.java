package dev.alex.threadium.render.modelpart.pose;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ModelPartPoseHotPath1211Test {
    private static final Path CLIENT = Path.of("src", "backport1211", "client", "java");
    private static final Path MAIN = Path.of("src", "backport1211", "main", "java");

    @Test
    void poseInspectorReusesRenderThreadScratch() throws IOException {
        String inspector = Files.readString(CLIENT.resolve(
                Path.of("dev", "alex", "threadium", "render", "modelpart", "pose", "ModelPartPoseInspector.java")));

        assertTrue(inspector.contains("private final float[] rootPositionScratch"));
        assertTrue(inspector.contains("private final MinecraftReader reader = new MinecraftReader();"));
        assertTrue(inspector.contains("private final ModelPartPoseTraversal.Scratch<ModelPart> traversalScratch"));
        assertFalse(inspector.contains("float[] position = new float[ImmutableRootRenderTransform.POSITION_ELEMENTS]"));
        assertFalse(inspector.contains("MinecraftReader reader = new MinecraftReader(new MatrixStack())"));
    }

    @Test
    void traversalSupportsReusableIdentityBookkeeping() throws IOException {
        String traversal = Files.readString(MAIN.resolve(
                Path.of("dev", "alex", "threadium", "render", "modelpart", "pose", "ModelPartPoseTraversal.java")));

        assertTrue(traversal.contains("public static final class Scratch<T>"));
        assertTrue(traversal.contains("state.reset();"));
        assertTrue(traversal.contains("visited.clear();"));
        assertTrue(traversal.contains("cursor.nextBone = 0;"));
    }

    @Test
    void replacementServiceUsesFailClosedIrisGate() throws IOException {
        String service = Files.readString(CLIENT.resolve(
                Path.of("dev", "alex", "threadium", "render", "entity", "ModelPartReplacementService.java")));

        assertTrue(service.contains("IrisCompatibility.replacementAllowed()"));
        assertTrue(service.contains("IrisCompatibility.refresh();"));
        assertTrue(service.contains(
                "runtimeEnabled = config.replacementEnabled() && IrisCompatibility.replacementAllowed();"));
    }
}
