package dev.alex.threadium.render.modelpart;

/** Frame-local request latch; repeated requests can produce at most one draw. */
public final class OverlayRequestCoalescer {
    private long frame;
    private boolean requested, consumed;

    public void beginFrame(boolean request) { frame++; requested=request; consumed=false; }
    public void request() { requested=true; }
    public boolean consume() { if(!requested||consumed)return false;consumed=true;requested=false;return true; }
    public void clear() { requested=false;consumed=false; }
    public boolean requested() { return requested; }
    public long frame() { return frame; }
}
