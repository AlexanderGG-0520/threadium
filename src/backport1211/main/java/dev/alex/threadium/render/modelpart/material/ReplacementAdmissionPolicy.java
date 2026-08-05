package dev.alex.threadium.render.modelpart.material;

import java.util.Objects;

/**
 * Pure fail-closed admission policy for a material resolution and an exactly classified Vanilla RenderLayer.
 *
 * <p>Special fixed-function state is accepted only after the descriptor proves that the 1.21.1 backend implements
 * that exact layer. Outline providers remain excluded until their color and underlying drawer are captured explicitly.
 */
public record ReplacementAdmissionPolicy(
        MaterialResolutionStatus resolutionStatus,
        MaterialProviderSource providerSource,
        boolean directRebound,
        boolean exactVanillaLayer,
        boolean materialInputsResolved,
        boolean backendModeSupported,
        boolean outlineInputsResolved) {
    public ReplacementAdmissionPolicy {
        Objects.requireNonNull(resolutionStatus, "resolutionStatus");
        Objects.requireNonNull(providerSource, "providerSource");
    }

    public boolean allowed() {
        boolean trustedProvider = providerSource == MaterialProviderSource.IMMEDIATE
                || providerSource == MaterialProviderSource.IMMEDIATELY_FAST;
        return resolutionStatus == MaterialResolutionStatus.DIRECT_UNIQUE
                && trustedProvider
                && !directRebound
                && exactVanillaLayer
                && materialInputsResolved
                && backendModeSupported
                && outlineInputsResolved;
    }
}
