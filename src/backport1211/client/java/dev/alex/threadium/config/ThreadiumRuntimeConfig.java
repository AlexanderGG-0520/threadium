package dev.alex.threadium.config;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Lock-free publication boundary for configuration applied at render-frame boundaries. */
public final class ThreadiumRuntimeConfig {
    private static final AtomicLong REVISION = new AtomicLong();
    private static volatile ThreadiumConfig current = ThreadiumConfig.defaults();

    private ThreadiumRuntimeConfig() {}

    public static void initialize() {
        publish(ThreadiumConfig.load());
    }

    public static ThreadiumConfig current() {
        return current;
    }

    public static long revision() {
        return REVISION.get();
    }

    public static void publishSaved(ThreadiumConfig config) {
        publish(config);
    }

    private static void publish(ThreadiumConfig config) {
        current = Objects.requireNonNull(config, "config");
        REVISION.incrementAndGet();
    }
}
