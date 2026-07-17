package dev.alex.threadium.render.modelpart;

public enum ScreenTriangleDiagnosticResult {
    NOT_RUN,
    INVALID_TARGET,
    FRAMEBUFFER_INCOMPLETE,
    GL_ERROR,
    DRAW_DID_NOT_WRITE_MAGENTA,
    DRAW_WROTE_MAGENTA_TO_PREPARED_TARGET,
    DRAW_WROTE_MAGENTA_TO_MAIN_TARGET
}
