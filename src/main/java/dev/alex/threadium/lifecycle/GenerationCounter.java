package dev.alex.threadium.lifecycle;

import java.util.concurrent.atomic.AtomicLong;

/** Monotonic invalidation generation with explicit, JVM-testable advancement. */
public final class GenerationCounter {
    private final AtomicLong value = new AtomicLong();

    public long current() { return value.get(); }
    public long advance() { return value.incrementAndGet(); }
}
