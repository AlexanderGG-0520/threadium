package dev.alex.threadium.render.modelpart;

public final class ScreenTriangleDiagnostics {
    private ScreenTriangleDiagnostics() {}
    public static int center(int size) { return size <= 0 ? 0 : Math.min(size - 1, size / 2); }
    public static int clamp(int coordinate,int size) { return size <= 0 ? 0 : Math.max(0,Math.min(size-1,coordinate)); }
    public static boolean isMagenta(int r,int g,int b,int a) { return r>=240&&g<=15&&b>=240&&a>=240; }
    public static ScreenTriangleDiagnosticResult classify(boolean mainTarget,boolean valid,boolean complete,boolean glError,boolean afterMagenta){
        if(!valid)return ScreenTriangleDiagnosticResult.INVALID_TARGET;
        if(!complete)return ScreenTriangleDiagnosticResult.FRAMEBUFFER_INCOMPLETE;
        if(glError)return ScreenTriangleDiagnosticResult.GL_ERROR;
        if(!afterMagenta)return ScreenTriangleDiagnosticResult.DRAW_DID_NOT_WRITE_MAGENTA;
        return mainTarget?ScreenTriangleDiagnosticResult.DRAW_WROTE_MAGENTA_TO_MAIN_TARGET:ScreenTriangleDiagnosticResult.DRAW_WROTE_MAGENTA_TO_PREPARED_TARGET;
    }
}
