package dev.alex.threadium.benchmark;

public final class CompletionNotificationGuard {
    private boolean emitted;

    public boolean claim() {
        if (emitted) return false;
        emitted = true;
        return true;
    }

    public boolean emitted() {
        return emitted;
    }
}
