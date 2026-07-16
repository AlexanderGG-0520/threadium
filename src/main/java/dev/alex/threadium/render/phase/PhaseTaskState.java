package dev.alex.threadium.render.phase;

public enum PhaseTaskState {
    PENDING, RUNNING, ASYNC_COMPLETED, SYNC_FALLBACK, MERGED, FAILED, CANCELLED
}
