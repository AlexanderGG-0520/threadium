package dev.alex.threadium.render.modelpart;

public enum ModelPartBackendState {
    DISABLED,
    UNINITIALIZED,
    INITIALIZING,
    READY,
    ACTIVE,
    FAILED;

    public boolean accepts() {
        return this == READY || this == ACTIVE;
    }
}
