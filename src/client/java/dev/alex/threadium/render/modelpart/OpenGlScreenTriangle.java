package dev.alex.threadium.render.modelpart;

import dev.alex.threadium.ThreadiumClient;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

/** Fullscreen, texture-free diagnostic draw with a retained four-byte readback buffer. */
final class OpenGlScreenTriangle implements AutoCloseable {
    record DrawResult(
            ScreenTriangleDiagnosticResult result,
            boolean readbackCompleted,
            boolean beforeMagenta,
            boolean afterMagenta,
            int beforeR,
            int beforeG,
            int beforeB,
            int beforeA,
            int afterR,
            int afterG,
            int afterB,
            int afterA) {}

    private int program, vao;
    private final ByteBuffer pixel = MemoryUtil.memAlloc(4);

    void initialize() {
        program = link(
                "#version 330 core\nconst vec2 POSITIONS[3]=vec2[](vec2(-1.0,-1.0),vec2(3.0,-1.0),vec2(-1.0,3.0));void main(){gl_Position=vec4(POSITIONS[gl_VertexID],0.0,1.0);}",
                "#version 330 core\nlayout(location=0) out vec4 outColor;void main(){outColor=vec4(1.0,0.0,1.0,1.0);}");
        vao = GL30C.glGenVertexArrays();
    }

    DrawResult draw(
            String mode,
            String hook,
            boolean mainTarget,
            OpenGlRenderTarget.Binding target,
            int errorBeforeBind,
            int errorAfterBind) {
        boolean valid = target.width() > 0 && target.height() > 0 && target.colorTexture() != 0 && vao != 0;
        boolean complete = target.status() == GL30C.GL_FRAMEBUFFER_COMPLETE;
        if (!valid || !complete)
            return new DrawResult(
                    ScreenTriangleDiagnostics.classify(mainTarget, valid, complete, false, false),
                    false,
                    false,
                    false,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, target.framebuffer());
        GL11C.glDrawBuffer(GL30C.GL_COLOR_ATTACHMENT0);
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, target.framebuffer());
        GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0);
        int errorAfterAttachments = GL11C.glGetError();
        int status = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);
        int errorAfterStatus = GL11C.glGetError();
        GL20C.glUseProgram(program);
        int errorAfterProgram = GL11C.glGetError();
        GL30C.glBindVertexArray(vao);
        int errorAfterVao = GL11C.glGetError();
        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthMask(false);
        GL11C.glDisable(GL11C.GL_CULL_FACE);
        GL11C.glDisable(GL11C.GL_BLEND);
        GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
        GL11C.glDisable(GL30C.GL_RASTERIZER_DISCARD);
        GL11C.glDisable(GL11C.GL_STENCIL_TEST);
        GL11C.glColorMask(true, true, true, true);
        GL11C.glPolygonMode(GL11C.GL_FRONT_AND_BACK, GL11C.GL_FILL);
        GL11C.glViewport(0, 0, target.width(), target.height());
        int errorAfterSetup = GL11C.glGetError();
        int[] viewport = new int[4], scissorBox = new int[4], polygonMode = new int[2];
        boolean[] colorMask = new boolean[4];
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
        GL11C.glGetIntegerv(GL11C.GL_SCISSOR_BOX, scissorBox);
        GL11C.glGetIntegerv(GL11C.GL_POLYGON_MODE, polygonMode);
        OpenGlStateSnapshot.readColorMask(colorMask);
        int x = ScreenTriangleDiagnostics.center(target.width()), y = ScreenTriangleDiagnostics.center(target.height());
        int[] before = readPixel(x, y);
        int errorAfterBeforeRead = GL11C.glGetError();
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
        int errorAfterDraw = GL11C.glGetError();
        int[] after = readPixel(x, y);
        int errorAfterReadback = GL11C.glGetError();
        boolean beforeMagenta = ScreenTriangleDiagnostics.isMagenta(before[0], before[1], before[2], before[3]),
                afterMagenta = ScreenTriangleDiagnostics.isMagenta(after[0], after[1], after[2], after[3]);
        boolean glError = firstError(
                        errorBeforeBind,
                        errorAfterBind,
                        errorAfterAttachments,
                        errorAfterStatus,
                        errorAfterProgram,
                        errorAfterVao,
                        errorAfterSetup,
                        errorAfterBeforeRead,
                        errorAfterDraw,
                        errorAfterReadback)
                != GL11C.GL_NO_ERROR;
        ScreenTriangleDiagnosticResult result = ScreenTriangleDiagnostics.classify(
                mainTarget, true, status == GL30C.GL_FRAMEBUFFER_COMPLETE, glError, afterMagenta);
        ThreadiumClient.LOGGER.info(
                "Threadium screen triangle readback: mode={}, result={}, frameHook={}, drawFbo={}, readFbo={}, status=0x{}, drawBuffer=0x{}, readBuffer=0x{}, colorTexture={{id={},target=0x{},level={}}}, depthTexture={{id={},target=0x{},level={}}}, samples={}, target={}x{}, program={}, vao={}, viewport={}, scissorEnabled={}, scissorBox={}, colorMask={}, depthTest={}, depthMask={}, depthFunc=0x{}, cullEnabled={}, cullMode=0x{}, frontFace=0x{}, blendEnabled={}, rasterizerDiscard={}, polygonMode={}, sample={},{} beforeRGBA={},{},{},{} afterRGBA={},{},{},{} beforeWasMagenta={}, afterWasMagenta={}, glErrors={{beforeBind=0x{},afterBind=0x{},attachments=0x{},status=0x{},program=0x{},vao=0x{},setup=0x{},beforeRead=0x{},draw=0x{},afterRead=0x{}}}",
                mode,
                result,
                hook,
                target.framebuffer(),
                GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING),
                Integer.toHexString(status),
                Integer.toHexString(GL11C.glGetInteger(GL11C.GL_DRAW_BUFFER)),
                Integer.toHexString(GL11C.glGetInteger(GL11C.GL_READ_BUFFER)),
                target.colorTexture(),
                Integer.toHexString(target.colorTarget()),
                target.colorLevel(),
                target.depthTexture(),
                Integer.toHexString(target.depthTarget()),
                target.depthLevel(),
                target.samples(),
                target.width(),
                target.height(),
                program,
                vao,
                Arrays.toString(viewport),
                GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST),
                Arrays.toString(scissorBox),
                Arrays.toString(colorMask),
                GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST),
                GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK),
                Integer.toHexString(GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC)),
                GL11C.glIsEnabled(GL11C.GL_CULL_FACE),
                Integer.toHexString(GL11C.glGetInteger(GL11C.GL_CULL_FACE_MODE)),
                Integer.toHexString(GL11C.glGetInteger(GL11C.GL_FRONT_FACE)),
                GL11C.glIsEnabled(GL11C.GL_BLEND),
                GL11C.glIsEnabled(GL30C.GL_RASTERIZER_DISCARD),
                Arrays.toString(polygonMode),
                x,
                y,
                before[0],
                before[1],
                before[2],
                before[3],
                after[0],
                after[1],
                after[2],
                after[3],
                beforeMagenta,
                afterMagenta,
                Integer.toHexString(errorBeforeBind),
                Integer.toHexString(errorAfterBind),
                Integer.toHexString(errorAfterAttachments),
                Integer.toHexString(errorAfterStatus),
                Integer.toHexString(errorAfterProgram),
                Integer.toHexString(errorAfterVao),
                Integer.toHexString(errorAfterSetup),
                Integer.toHexString(errorAfterBeforeRead),
                Integer.toHexString(errorAfterDraw),
                Integer.toHexString(errorAfterReadback));
        VisualDiagnosticMetrics.triangles.incrementAndGet();
        return new DrawResult(
                result,
                !glError,
                beforeMagenta,
                afterMagenta,
                before[0],
                before[1],
                before[2],
                before[3],
                after[0],
                after[1],
                after[2],
                after[3]);
    }

    boolean drawOnly(OpenGlRenderTarget.Binding target) {
        if (target.width() <= 0
                || target.height() <= 0
                || target.colorTexture() == 0
                || vao == 0
                || target.status() != GL30C.GL_FRAMEBUFFER_COMPLETE) return false;
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, target.framebuffer());
        GL11C.glDrawBuffer(GL30C.GL_COLOR_ATTACHMENT0);
        GL20C.glUseProgram(program);
        GL30C.glBindVertexArray(vao);
        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthMask(false);
        GL11C.glDisable(GL11C.GL_CULL_FACE);
        GL11C.glDisable(GL11C.GL_BLEND);
        GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
        GL11C.glDisable(GL30C.GL_RASTERIZER_DISCARD);
        GL11C.glDisable(GL11C.GL_STENCIL_TEST);
        GL11C.glColorMask(true, true, true, true);
        GL11C.glPolygonMode(GL11C.GL_FRONT_AND_BACK, GL11C.GL_FILL);
        GL11C.glViewport(0, 0, target.width(), target.height());
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
        VisualDiagnosticMetrics.triangles.incrementAndGet();
        return GL11C.glGetError() == GL11C.GL_NO_ERROR;
    }

    private int[] readPixel(int x, int y) {
        pixel.clear();
        GL11C.glReadPixels(x, y, 1, 1, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixel);
        return new int[] {
            Byte.toUnsignedInt(pixel.get(0)),
            Byte.toUnsignedInt(pixel.get(1)),
            Byte.toUnsignedInt(pixel.get(2)),
            Byte.toUnsignedInt(pixel.get(3))
        };
    }

    int program() {
        return program;
    }

    int vao() {
        return vao;
    }

    @Override
    public void close() {
        if (program != 0) GL20C.glDeleteProgram(program);
        if (vao != 0) GL30C.glDeleteVertexArrays(vao);
        program = vao = 0;
        MemoryUtil.memFree(pixel);
    }

    private static int firstError(int... errors) {
        for (int error : errors) if (error != GL11C.GL_NO_ERROR) return error;
        return GL11C.GL_NO_ERROR;
    }

    private static int link(String vs, String fs) {
        int v = compile(GL20C.GL_VERTEX_SHADER, vs),
                f = compile(GL20C.GL_FRAGMENT_SHADER, fs),
                p = GL20C.glCreateProgram();
        GL20C.glAttachShader(p, v);
        GL20C.glAttachShader(p, f);
        GL20C.glLinkProgram(p);
        GL20C.glDeleteShader(v);
        GL20C.glDeleteShader(f);
        if (GL20C.glGetProgrami(p, GL20C.GL_LINK_STATUS) == 0)
            throw new IllegalStateException(GL20C.glGetProgramInfoLog(p));
        return p;
    }

    private static int compile(int type, String source) {
        int s = GL20C.glCreateShader(type);
        GL20C.glShaderSource(s, source);
        GL20C.glCompileShader(s);
        if (GL20C.glGetShaderi(s, GL20C.GL_COMPILE_STATUS) == 0)
            throw new IllegalStateException(GL20C.glGetShaderInfoLog(s));
        return s;
    }
}
