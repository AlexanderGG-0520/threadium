package dev.alex.threadium.benchmark;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.InactivityFpsLimit;

/** Temporarily forces the benchmark-safe inactivity limiter without persisting the option. */
final class BenchmarkInactivityFpsOverride {
    private InactivityFpsLimit original;
    private InactivityFpsLimit effective;
    private boolean captured;
    private boolean restored = true;
    private boolean stable = true;

    boolean apply(Supplier<InactivityFpsLimit> getter, Consumer<InactivityFpsLimit> setter) {
        if (captured && !restored) throw new IllegalStateException("Inactivity FPS limit override already active");
        original = Objects.requireNonNull(getter.get(), "original inactivity FPS limit");
        effective = original;
        captured = true;
        restored = false;
        stable = true;
        try {
            setter.accept(InactivityFpsLimit.MINIMIZED);
            effective = Objects.requireNonNull(getter.get(), "effective inactivity FPS limit");
            stable = effective == InactivityFpsLimit.MINIMIZED;
            if (!stable) restore(getter, setter);
            return stable;
        } catch (RuntimeException | Error failure) {
            try {
                restore(getter, setter);
            } catch (RuntimeException | Error restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
            throw failure;
        }
    }

    void observe(Supplier<InactivityFpsLimit> getter) {
        if (captured && !restored) stable &= getter.get() == InactivityFpsLimit.MINIMIZED;
    }

    boolean restore(Supplier<InactivityFpsLimit> getter, Consumer<InactivityFpsLimit> setter) {
        if (!captured || restored) return true;
        setter.accept(original);
        restored = getter.get() == original;
        return restored;
    }

    String originalName() {
        return original == null ? "not-captured" : original.name();
    }

    String effectiveName() {
        return effective == null ? "not-captured" : effective.name();
    }

    boolean stable() {
        return stable;
    }

    boolean restored() {
        return restored;
    }

    boolean captured() {
        return captured;
    }
}
