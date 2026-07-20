package dev.alex.threadium.render.modelpart.material;

/** Conservative result of resolving an exact frame-local consumer identity. */
public enum MaterialResolutionStatus {
    DIRECT_UNIQUE,
    DIRECT_REBOUND,
    UNRESOLVED,
    CAPACITY_REJECTED
}
