package dev.alex.threadium.lifecycle;

/** Safe render-thread boundary used by deferred 1.21.1 runtime configuration changes. */
public final class ThreadiumFrameBoundary {
    private ThreadiumFrameBoundary() {}

    public static void applyPendingConfiguration() {
        // Phase A bootstrap: no state is published yet. The hook intentionally remains pass-through.
    }
}
