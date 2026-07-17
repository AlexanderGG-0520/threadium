package dev.alex.threadium.render.modelpart;

import java.util.concurrent.atomic.AtomicLong;

final class VisualDiagnosticMetrics {
    static final AtomicLong triangles = new AtomicLong(),
            meshes = new AtomicLong(),
            glErrors = new AtomicLong(),
            incomplete = new AtomicLong();
    static final AtomicLong readbackAttempts = new AtomicLong(),
            readbackSuccesses = new AtomicLong(),
            readbackFailures = new AtomicLong(),
            beforeMagenta = new AtomicLong(),
            afterMagenta = new AtomicLong(),
            readbackGlErrors = new AtomicLong(),
            invalidTarget = new AtomicLong(),
            invalidVao = new AtomicLong();

    static String snapshot(DebugVisualMode mode) {
        return "debugVisualMode=" + mode.configName() + ",debugTriangleDraws=" + triangles.getAndSet(0)
                + ",debugMeshDraws=" + meshes.getAndSet(0) + ",debugGlErrors=" + glErrors.getAndSet(0)
                + ",debugFramebufferIncomplete=" + incomplete.getAndSet(0) + ",debugTriangleReadbackAttempts="
                + readbackAttempts.get() + ",debugTriangleReadbackSuccesses=" + readbackSuccesses.get()
                + ",debugTriangleReadbackFailures=" + readbackFailures.get() + ",debugTriangleBeforeMagenta="
                + beforeMagenta.get() + ",debugTriangleAfterMagenta=" + afterMagenta.get()
                + ",debugTriangleReadbackGlErrors=" + readbackGlErrors.get() + ",debugTriangleFboIncomplete="
                + incomplete.get() + ",debugTriangleInvalidTarget=" + invalidTarget.get() + ",debugTriangleInvalidVao="
                + invalidVao.get();
    }
}
