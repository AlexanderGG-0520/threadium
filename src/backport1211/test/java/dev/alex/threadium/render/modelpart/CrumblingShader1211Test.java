package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CrumblingShader1211Test {
    private static final Path SHADER_DIRECTORY =
            Path.of("src", "backport1211", "client", "resources", "assets", "threadium", "shaders");

    @Test
    void vertexShaderReplaysVanillaSheetedDecalProjection() throws IOException {
        String shader = Files.readString(SHADER_DIRECTORY.resolve("modelpart_gl33_1211.vert"));

        assertTrue(shader.contains("layout(location=12) in int iDecalBase;"));
        assertTrue(shader.contains("uniform samplerBuffer Decals;"));
        assertTrue(shader.contains("vec2 threadiumDecalUv(vec3 position, vec3 normal)"));
        assertTrue(shader.contains("absoluteNormal.y >= absoluteNormal.x && absoluteNormal.y >= absoluteNormal.z"));
        assertTrue(shader.contains("absoluteNormal.z >= absoluteNormal.x"));
        assertTrue(shader.contains("mat4 inverseTexture = mat4("));
        assertTrue(shader.contains("mat3 inverseNormal = mat3("));
        assertTrue(shader.contains("vUv = threadiumDecalUv(decalPosition, decalNormal) * inverseNormal0.w;"));
    }

    @Test
    void fragmentShaderMatchesVanillaCrumblingCutoutAndModulation() throws IOException {
        String shader = Files.readString(SHADER_DIRECTORY.resolve("modelpart_gl33_1211.frag"));
        int crumblingStart = shader.indexOf("if (uShaderMode == 8)");
        int genericCutout = shader.indexOf("if (uAlphaCutout != 0");

        assertTrue(crumblingStart >= 0);
        assertTrue(genericCutout > crumblingStart);
        String crumblingBlock = shader.substring(crumblingStart, genericCutout);
        assertTrue(crumblingBlock.contains("if (color.a < 0.1) discard;"));
        assertTrue(crumblingBlock.contains("outColor = color * uColorModulator;"));
        assertFalse(crumblingBlock.contains("fog"));
        assertFalse(crumblingBlock.contains("vLight"));
        assertFalse(crumblingBlock.contains("vOverlay"));
    }
}
