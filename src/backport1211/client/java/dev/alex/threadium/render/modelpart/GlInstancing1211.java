package dev.alex.threadium.render.modelpart;

import org.lwjgl.opengl.ARBInstancedArrays;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GL33C;

/** Selects the safe instanced-attribute divisor entry point for Minecraft 1.21.1's current OpenGL context. */
final class GlInstancing1211 {
    private GlInstancing1211() {}

    static DivisorApi detectCurrent() {
        GLCapabilities capabilities = GL.getCapabilities();
        return select(capabilities.OpenGL33, capabilities.GL_ARB_instanced_arrays);
    }

    static DivisorApi select(boolean openGl33, boolean arbInstancedArrays) {
        if (openGl33) return DivisorApi.CORE_33;
        if (arbInstancedArrays) return DivisorApi.ARB;
        return DivisorApi.UNAVAILABLE;
    }

    static void vertexAttribDivisor(DivisorApi api, int index, int divisor) {
        switch (api) {
            case CORE_33 -> GL33C.glVertexAttribDivisor(index, divisor);
            case ARB -> ARBInstancedArrays.glVertexAttribDivisorARB(index, divisor);
            case UNAVAILABLE -> throw new IllegalStateException(
                    "Instanced vertex attribute divisors are unavailable in the current OpenGL context");
        }
    }

    enum DivisorApi {
        CORE_33,
        ARB,
        UNAVAILABLE
    }
}
