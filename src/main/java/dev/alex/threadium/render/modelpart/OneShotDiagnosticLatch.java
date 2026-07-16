package dev.alex.threadium.render.modelpart;

public final class OneShotDiagnosticLatch {
    private boolean consumed;
    public boolean acquire(){if(consumed)return false;consumed=true;return true;}
    public void reset(){consumed=false;}
    public boolean consumed(){return consumed;}
}
