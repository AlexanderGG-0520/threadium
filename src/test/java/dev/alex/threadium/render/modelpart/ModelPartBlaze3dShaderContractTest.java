package dev.alex.threadium.render.modelpart;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ModelPartBlaze3dShaderContractTest {
    @Test void normalOverlayCoordinatesRemainDecodedTexels() {
        assertArrayEquals(new int[]{0, 10}, decodePackedCoordinates(pack(0, 10)));
    }

    @Test void hurtOverlayRetainsVThree() {
        assertArrayEquals(new int[]{0, 3}, decodePackedCoordinates(pack(0, 3)));
    }

    @Test void overlayUsesDirectTexelsWhileLightStillDividesBySixteen() throws IOException {
        String shader = resource("/assets/threadium/shaders/core/modelpart_blaze3d.fsh");
        String overlayLine = lineContaining(shader, "ivec2 overlayCoords");
        String lightLine = lineContaining(shader, "ivec2 light");
        assertFalse(overlayLine.contains("/ 16"));
        assertTrue(lightLine.contains("/ 16"));
        assertTrue(shader.contains("texelFetch(Sampler1, overlayCoords, 0)"));
    }

    @Test void vertexPositionAppliesBoneRootThenViewThenProjectionExactlyOnce() throws IOException {
        String shader = resource("/assets/threadium/shaders/core/modelpart_blaze3d.vsh");
        assertTrue(shader.contains("#moj_import <minecraft:dynamictransforms.glsl>"));
        assertTrue(shader.contains("vec4 modelPosition = RootMatrix * bonePose * vec4(Position, 1.0);"));
        assertTrue(shader.contains("vec4 cameraSpacePosition = ModelViewMat * modelPosition;"));
        assertTrue(shader.contains("gl_Position = ProjMat * cameraSpacePosition;"));
        assertEquals(1, occurrences(shader, "RootMatrix * bonePose"));
        assertEquals(1, occurrences(shader, "ModelViewMat *"));
        assertEquals(1, occurrences(shader, "ProjMat *"));
    }

    @Test void instanceSlicePlanningContractRemainsIntact() {
        var slices = InstanceBatchSlicePlanner.plan(256, ModelPartLayouts.INSTANCE_STRIDE, java.util.List.of(1, 1, 1), 3);
        assertEquals(java.util.List.of(256L, 368L, 480L), slices.stream().map(InstanceBatchSlicePlanner.Slice::byteOffset).toList());
    }

    private static int pack(int u, int v) { return u | v << 16; }

    private static int[] decodePackedCoordinates(int packed) { return new int[]{packed & 0xFFFF, packed >> 16 & 0xFFFF}; }

    private static String resource(String path) throws IOException {
        try (var input = ModelPartBlaze3dShaderContractTest.class.getResourceAsStream(path)) {
            assertNotNull(input, path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String lineContaining(String source, String text) {
        return source.lines().filter(line -> line.contains(text)).findFirst().orElseThrow();
    }

    private static int occurrences(String source, String text) { return source.split(java.util.regex.Pattern.quote(text), -1).length - 1; }
}
