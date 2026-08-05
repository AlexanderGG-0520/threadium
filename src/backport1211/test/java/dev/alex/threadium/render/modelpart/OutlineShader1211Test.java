package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OutlineShader1211Test {
    private static final Path SHADER_DIRECTORY =
            Path.of("src", "backport1211", "client", "resources", "assets", "threadium", "shaders");

    @Test
    void fragmentShaderMatchesVanillaOutlineMaskAndColorSemantics() throws IOException {
        String shader = Files.readString(SHADER_DIRECTORY.resolve("modelpart_gl33_1211.frag"));
        int outlineStart = shader.indexOf("if (uShaderMode == 6)");
        int glintStart = shader.indexOf("if (uShaderMode == 7)");

        assertTrue(outlineStart >= 0);
        assertTrue(glintStart > outlineStart);

        String outlineBlock = shader.substring(outlineStart, glintStart);
        assertTrue(outlineBlock.contains("if (color.a == 0.0) discard;"));
        assertTrue(
                outlineBlock.contains("outColor = vec4(uColorModulator.rgb * vVertexColor.rgb, uColorModulator.a);"));
    }

    @Test
    void vertexShaderCarriesFixedOutlineColorWithoutLighting() throws IOException {
        String shader = Files.readString(SHADER_DIRECTORY.resolve("modelpart_gl33_1211.vert"));

        assertTrue(shader.contains("bool directional = uShaderMode == 0 || uShaderMode == 1"));
        assertTrue(shader.contains("bool whiteVertexColor = uShaderMode == 7 || uShaderMode == 8;"));
        assertTrue(shader.contains(
                ": (directional ? mixLight(uLight0Direction, uLight1Direction, normal, iTint) : iTint);"));
    }
}
