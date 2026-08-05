package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GlintShader1211Test {
    private static final Path SHADER_DIRECTORY = Path.of(
            "src", "backport1211", "client", "resources", "assets", "threadium", "shaders");

    @Test
    void fragmentShaderAppliesVanillaGlintAlphaCutoutAndFogOrder() throws IOException {
        String shader = Files.readString(SHADER_DIRECTORY.resolve("modelpart_gl33_1211.frag"));
        int glintStart = shader.indexOf("if (uShaderMode == 7)");
        int genericCutout = shader.indexOf("if (uAlphaCutout != 0");

        assertTrue(glintStart >= 0);
        assertTrue(genericCutout > glintStart);

        String glintBlock = shader.substring(glintStart, genericCutout);
        int colorModulation = glintBlock.indexOf("color *= uColorModulator;");
        int alphaCutout = glintBlock.indexOf("if (color.a < 0.1) discard;");

        assertTrue(colorModulation >= 0);
        assertTrue(alphaCutout > colorModulation);
        assertTrue(glintBlock.contains("float fade = fogFade() * uGlintAlpha;"));
        assertTrue(glintBlock.contains("outColor = vec4(color.rgb * fade, color.a);"));
    }

    @Test
    void vertexShaderUsesDynamicTextureMatrixAndModelSpaceFogDistance() throws IOException {
        String shader = Files.readString(SHADER_DIRECTORY.resolve("modelpart_gl33_1211.vert"));

        assertTrue(shader.contains("uShaderMode == 7"));
        assertTrue(shader.contains("uTextureMatrix * vec4(aUv, 0.0, 1.0)"));
        assertTrue(shader.contains("vDistance = fogDistance(modelPosition.xyz, uFogShape);"));
    }
}
