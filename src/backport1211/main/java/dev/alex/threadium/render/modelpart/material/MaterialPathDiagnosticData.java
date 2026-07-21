package dev.alex.threadium.render.modelpart.material;

import java.util.Objects;

/** Minecraft-object-free diagnostic text captured at an observation boundary. */
public record MaterialPathDiagnosticData(
        MaterialProviderSource providerSource,
        String providerClass,
        String consumerClass,
        String layerClass,
        String layerDescription,
        boolean crossProviderRebound) {
    public static final String ABSENT = "<absent>";
    public static final String FORMATTING_FAILED = "<formatting-failed>";

    public MaterialPathDiagnosticData {
        Objects.requireNonNull(providerSource, "providerSource");
        Objects.requireNonNull(providerClass, "providerClass");
        Objects.requireNonNull(consumerClass, "consumerClass");
        Objects.requireNonNull(layerClass, "layerClass");
        Objects.requireNonNull(layerDescription, "layerDescription");
    }

    public static MaterialPathDiagnosticData unresolved(String consumerClass) {
        return new MaterialPathDiagnosticData(
                MaterialProviderSource.UNAVAILABLE, ABSENT, consumerClass, ABSENT, ABSENT, false);
    }
}
