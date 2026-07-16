package dev.alex.threadium.scheduler;

import java.util.concurrent.atomic.AtomicBoolean;

/** Ensures a centrally owned executor begins shutdown at most once. */
public final class ShutdownGuard {
    private final AtomicBoolean shutdown = new AtomicBoolean();

    public boolean beginShutdown() { return shutdown.compareAndSet(false, true); }
    public boolean isShutdown() { return shutdown.get(); }
}
