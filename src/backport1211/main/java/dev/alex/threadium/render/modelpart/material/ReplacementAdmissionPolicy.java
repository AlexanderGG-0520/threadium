package dev.alex.threadium.render.modelpart.material;

/**
 * Pure fail-closed admission policy for the first Minecraft 1.21.1 replacement path.
 *
 * <p>Every condition is independently required. A caller must prove the exact supported RenderLayer shape before
 * setting {@code exactSupportedLayer}; diagnostic names or class-name heuristics are never sufficient.
 */
public record ReplacementAdmissionPolicy(
        MaterialResolutionStatus resolutionStatus,
        MaterialProviderSource providerSource,
        boolean crossProviderRebound,
        boolean exactSupportedLayer,
        boolean textureResolved,
        boolean outline,
        boolean translucent,
        boolean crumbling) {
    public boolean allowed() {
        return resolutionStatus == MaterialResolutionStatus.DIRECT_UNIQUE
                && trustedProvider(providerSource)
                && !crossProviderRebound
                && exactSupportedLayer
                && textureResolved
                && !outline
                && !translucent
                && !crumbling;
    }

    static boolean trustedProvider(MaterialProviderSource source) {
        return source == MaterialProviderSource.IMMEDIATE || source == MaterialProviderSource.IMMEDIATELY_FAST;
    }
}
