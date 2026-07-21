package dev.alex.threadium.render.modelpart.material;

import java.util.Objects;

/** Value key for diagnostics only. It is never consulted by material resolution or rendering policy. */
public record MaterialPathKey(
        MaterialResolutionStatus status,
        MaterialProviderSource providerSource,
        String providerClass,
        String consumerClass,
        String layerClass,
        String layerDescription,
        boolean lateRegistration,
        boolean resolutionBeforeFirstProvider,
        boolean crossProviderRebound) {
    public MaterialPathKey {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(providerSource, "providerSource");
        Objects.requireNonNull(providerClass, "providerClass");
        Objects.requireNonNull(consumerClass, "consumerClass");
        Objects.requireNonNull(layerClass, "layerClass");
        Objects.requireNonNull(layerDescription, "layerDescription");
    }
}
