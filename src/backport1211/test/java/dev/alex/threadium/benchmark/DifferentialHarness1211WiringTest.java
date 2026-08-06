package dev.alex.threadium.benchmark;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DifferentialHarness1211WiringTest {
    private static final Path CLIENT = Path.of("src", "backport1211", "client", "java");

    @Test
    void commandTreeAndRenderTailAreWired() throws IOException {
        String client = Files.readString(CLIENT.resolve(Path.of("dev", "alex", "threadium", "ThreadiumClient.java")));
        String commands = Files.readString(CLIENT.resolve(
                Path.of("dev", "alex", "threadium", "benchmark", "ThreadiumValidationCommands1211.java")));
        String mixin = Files.readString(
                CLIENT.resolve(Path.of("dev", "alex", "threadium", "mixin", "GameRendererMixin.java")));

        assertTrue(client.contains("ThreadiumValidationCommands1211.register()"));
        assertTrue(commands.contains("pipeline-differential"));
        assertTrue(commands.contains("run-phase"));
        assertTrue(commands.contains("rerun-failed"));
        assertTrue(mixin.contains("at = @At(\"RETURN\")"));
        assertTrue(mixin.contains("PipelineDifferentialRunner1211.renderTail()"));
    }

    @Test
    void runnerCapturesReferenceCandidateColorDepthAndEvidence() throws IOException {
        String runner = Files.readString(CLIENT.resolve(
                Path.of("dev", "alex", "threadium", "benchmark", "PipelineDifferentialRunner1211.java")));

        assertTrue(runner.contains("DifferentialExecutionScope.Mode.REFERENCE_BYPASS"));
        assertTrue(runner.contains("DifferentialExecutionScope.Mode.CANDIDATE_REQUIRE_ACCEPT"));
        assertTrue(runner.contains("GL11C.glReadPixels"));
        assertTrue(runner.contains("GL11C.GL_DEPTH_COMPONENT"));
        assertTrue(runner.contains("DifferentialResultPolicy.classify"));
        assertTrue(runner.contains("Arrays.equals(reference.poseBits(), candidate.poseBits())"));
        assertTrue(runner.contains("_reference.png"));
        assertTrue(runner.contains("_candidate.png"));
        assertTrue(runner.contains("_difference.png"));
    }

    @Test
    void serviceAndSuppressionHookExposeDifferentialExecution() throws IOException {
        String service = Files.readString(CLIENT.resolve(
                Path.of("dev", "alex", "threadium", "render", "entity", "ModelPartReplacementService.java")));
        String modelPartMixin =
                Files.readString(CLIENT.resolve(Path.of("dev", "alex", "threadium", "mixin", "ModelPartMixin.java")));

        assertTrue(service.contains("DifferentialExecutionScope.referenceBypass"));
        assertTrue(service.contains("DifferentialExecutionScope.candidateRequiresAcceptance"));
        assertTrue(service.contains("DifferentialExecutionScope.completed"));
        assertTrue(service.contains("DifferentialExecutionScope.fallbackReason"));
        assertTrue(modelPartMixin.contains("DifferentialExecutionScope.suppression()"));
    }
}
