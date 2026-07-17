package dev.alex.threadium.render.modelpart;

/** Explicit contract between the generic interception service and its sole vanilla-call gate. */
public enum ModelPartInterceptionResult {
    PASS_THROUGH,
    DEBUG_OVERLAY_ONLY,
    GPU_REPLACED
}
