package dev.alex.threadium.benchmark;

import java.util.Locale;
import java.util.Optional;

public enum ModelPartBenchmarkMode {
    VANILLA(false, false),
    SINGLETON(true, false),
    BATCHING(true, true);

    private final boolean replacementEnabled;
    private final boolean consolidationEnabled;

    ModelPartBenchmarkMode(boolean replacementEnabled, boolean consolidationEnabled) {
        this.replacementEnabled = replacementEnabled;
        this.consolidationEnabled = consolidationEnabled;
    }

    public boolean replacementEnabled() {
        return replacementEnabled;
    }

    public boolean consolidationEnabled() {
        return consolidationEnabled;
    }

    public static Optional<ModelPartBenchmarkMode> parseOptional(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
    }
}
