package dev.alex.threadium.render.modelpart;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.system.MemoryStack;

/** Flush-scoped raw OpenGL state snapshot for the Minecraft 1.21.1 backend. */
final class OpenGlStateSnapshot1211 {
    private final int program;
    private final int vao;
    private final int arrayBuffer;
    private final int activeTexture;
    private final int drawFramebuffer;
    private final int readFramebuffer;
    private final int drawBuffer;
    private final int readBuffer;
    private final int depthFunc;
    private final int frontFace;
    private final int cullFaceMode;
    private final int blendSrcRgb;
    private final int blendDstRgb;
    private final int blendSrcAlpha;
    private final int blendDstAlpha;
    private final int blendEquationRgb;
    private final int blendEquationAlpha;
    private final int[] texture2d = new int[4];
    private final int textureBuffer;
    private final int[] viewport = new int[4];
    private final int[] scissorBox = new int[4];
    private final int[] polygonMode = new int[2];
    private final boolean blend;
    private final boolean depth;
    private final boolean cull;
    private final boolean scissor;
    private final boolean rasterizerDiscard;
    private final boolean stencil;
    private final boolean depthMask;
    private final boolean[] colorMask = new boolean[4];

    private OpenGlStateSnapshot1211() {
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
        for (int unit = 0; unit < texture2d.length; unit++) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
            texture2d[unit] = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        }
        GL13C.glActiveTexture(GL13C.GL_TEXTURE3);
        textureBuffer = GL11C.glGetInteger(GL31C.GL_TEXTURE_BINDING_BUFFER);
        GL13C.glActiveTexture(activeTexture);
    }

    static OpenGlStateSnapshot1211 capture() {
        return new OpenGlStateSnapshot1211();
    }

    void restore() {
        GL20C.glUseProgram(program);
        GL30C.glBindVertexArray(vao);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, arrayBuffer);
        for (int unit = 0; unit < texture2d.length; unit++) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture2d[unit]);
        }
        GL13C.glActiveTexture(GL13C.GL_TEXTURE3);
        GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, textureBuffer);
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
    }

    private static void set(int capability, boolean enabled) {
        if (enabled) GL11C.glEnable(capability);
        else GL11C.glDisable(capability);
    }

    private static void readColorMask(boolean[] destination) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var values = stack.malloc(4);
            GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, values);
            for (int index = 0; index < destination.length; index++) destination[index] = values.get(index) != 0;
        }
    }
}
