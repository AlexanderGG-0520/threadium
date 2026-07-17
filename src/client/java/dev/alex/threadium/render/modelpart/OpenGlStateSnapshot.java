package dev.alex.threadium.render.modelpart;

import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Flush-scoped complete raw-GL state snapshot; never used per entity. */
final class OpenGlStateSnapshot {
    private final int program,
            vao,
            arrayBuffer,
            activeTexture,
            drawFramebuffer,
            readFramebuffer,
            drawBuffer,
            readBuffer;
    private final int depthFunc,
            frontFace,
            cullFaceMode,
            uniformBuffer0,
            blendSrcRgb,
            blendDstRgb,
            blendSrcAlpha,
            blendDstAlpha,
            blendEquationRgb,
            blendEquationAlpha;
    private final int[] textures = new int[4], viewport = new int[4], scissorBox = new int[4], polygonMode = new int[2];
    private final boolean blend, depth, cull, scissor, rasterizerDiscard, stencil, depthMask;
    private final boolean[] colorMask = new boolean[4];

    private OpenGlStateSnapshot() {
        program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        vao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        arrayBuffer = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
        activeTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        drawFramebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        readFramebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        drawBuffer = GL11C.glGetInteger(GL11C.GL_DRAW_BUFFER);
        readBuffer = GL11C.glGetInteger(GL11C.GL_READ_BUFFER);
        depthFunc = GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);
        frontFace = GL11C.glGetInteger(GL11C.GL_FRONT_FACE);
        cullFaceMode = GL11C.glGetInteger(GL11C.GL_CULL_FACE_MODE);
        uniformBuffer0 = GL30C.glGetIntegeri(GL31C.GL_UNIFORM_BUFFER_BINDING, 0);
        blendSrcRgb = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB);
        blendDstRgb = GL11C.glGetInteger(GL14C.GL_BLEND_DST_RGB);
        blendSrcAlpha = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_ALPHA);
        blendDstAlpha = GL11C.glGetInteger(GL14C.GL_BLEND_DST_ALPHA);
        blendEquationRgb = GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_RGB);
        blendEquationAlpha = GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_ALPHA);
        blend = GL11C.glIsEnabled(GL11C.GL_BLEND);
        depth = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
        cull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
        scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
        rasterizerDiscard = GL11C.glIsEnabled(GL30C.GL_RASTERIZER_DISCARD);
        stencil = GL11C.glIsEnabled(GL11C.GL_STENCIL_TEST);
        depthMask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
        GL11C.glGetIntegerv(GL11C.GL_SCISSOR_BOX, scissorBox);
        GL11C.glGetIntegerv(GL11C.GL_POLYGON_MODE, polygonMode);
        readColorMask(colorMask);
        for (int i = 0; i < 4; i++) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + i);
            textures[i] = GL11C.glGetInteger(i == 3 ? GL31C.GL_TEXTURE_BINDING_BUFFER : GL11C.GL_TEXTURE_BINDING_2D);
        }
        GL13C.glActiveTexture(activeTexture);
    }

    static OpenGlStateSnapshot capture() {
        return new OpenGlStateSnapshot();
    }

    void restore() {
        GL20C.glUseProgram(program);
        GL30C.glBindVertexArray(vao);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, arrayBuffer);
        for (int i = 0; i < 4; i++) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + i);
            GL11C.glBindTexture(i == 3 ? GL31C.GL_TEXTURE_BUFFER : GL11C.GL_TEXTURE_2D, textures[i]);
        }
        GL13C.glActiveTexture(activeTexture);
        set(GL11C.GL_BLEND, blend);
        GL14C.glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
        GL20C.glBlendEquationSeparate(blendEquationRgb, blendEquationAlpha);
        set(GL11C.GL_DEPTH_TEST, depth);
        set(GL11C.GL_CULL_FACE, cull);
        set(GL11C.GL_SCISSOR_TEST, scissor);
        set(GL30C.GL_RASTERIZER_DISCARD, rasterizerDiscard);
        set(GL11C.GL_STENCIL_TEST, stencil);
        GL11C.glDepthFunc(depthFunc);
        GL11C.glDepthMask(depthMask);
        GL11C.glCullFace(cullFaceMode);
        GL11C.glFrontFace(frontFace);
        GL11C.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
        GL11C.glPolygonMode(GL11C.GL_FRONT_AND_BACK, polygonMode[0]);
        GL11C.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        GL11C.glDrawBuffer(drawBuffer);
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFramebuffer);
        GL11C.glReadBuffer(readBuffer);
        GL11C.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        GL30C.glBindBufferBase(GL31C.GL_UNIFORM_BUFFER, 0, uniformBuffer0);
    }

    private static void set(int capability, boolean enabled) {
        if (enabled) GL11C.glEnable(capability);
        else GL11C.glDisable(capability);
    }

    static void readColorMask(boolean[] destination) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var values = stack.malloc(4);
            GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, values);
            for (int i = 0; i < 4; i++) destination[i] = values.get(i) != 0;
        }
    }
}
