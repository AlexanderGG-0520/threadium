package dev.alex.threadium.lifecycle;

import dev.alex.threadium.render.entity.ModelPartReplacementService;
import dev.alex.threadium.render.entity.PassThroughEntityRenderService;

/** Safe render-thread boundary used by deferred 1.21.1 runtime configuration and lifecycle changes. */
public final class ThreadiumFrameBoundary {
    private ThreadiumFrameBoundary() {}

    public static void applyPendingConfiguration() {
        ModelPartReplacementService.beginFrame();
        PassThroughEntityRenderService.beginFrame();
    }
}
