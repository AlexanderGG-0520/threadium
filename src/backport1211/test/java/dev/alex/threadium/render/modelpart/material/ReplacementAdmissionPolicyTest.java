package dev.alex.threadium.render.modelpart.material;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReplacementAdmissionPolicyTest {
    @Test
    void exactImmediateMaterialIsAllowed() {
        assertTrue(policy(MaterialResolutionStatus.DIRECT_UNIQUE, MaterialProviderSource.IMMEDIATE).allowed());
    }

    @Test
    void exactImmediatelyFastMaterialIsAllowed() {
        assertTrue(policy(MaterialResolutionStatus.DIRECT_UNIQUE, MaterialProviderSource.IMMEDIATELY_FAST).allowed());
    }

    @Test
    void outlineProviderIsNeverTrustedForSuppression() {
        assertFalse(policy(MaterialResolutionStatus.DIRECT_UNIQUE, MaterialProviderSource.OUTLINE).allowed());
    }

    @Test
    void reboundAndUnresolvedMaterialsFallBack() {
        assertFalse(new ReplacementAdmissionPolicy(
                        MaterialResolutionStatus.DIRECT_REBOUND,
                        MaterialProviderSource.IMMEDIATE,
                        true,
                        true,
                        true,
                        false,
                        false,
                        false)
                .allowed());
        assertFalse(policy(MaterialResolutionStatus.UNRESOLVED, MaterialProviderSource.UNAVAILABLE).allowed());
    }

    @Test
    void everyUnprovenRenderPropertyFallsBack() {
        assertFalse(new ReplacementAdmissionPolicy(
                        MaterialResolutionStatus.DIRECT_UNIQUE,
                        MaterialProviderSource.IMMEDIATE,
                        false,
                        false,
                        true,
                        false,
                        false,
                        false)
                .allowed());
        assertFalse(new ReplacementAdmissionPolicy(
                        MaterialResolutionStatus.DIRECT_UNIQUE,
                        MaterialProviderSource.IMMEDIATE,
                        false,
                        true,
                        false,
                        false,
                        false,
                        false)
                .allowed());
        assertFalse(new ReplacementAdmissionPolicy(
                        MaterialResolutionStatus.DIRECT_UNIQUE,
                        MaterialProviderSource.IMMEDIATE,
                        false,
                        true,
                        true,
                        true,
                        false,
                        false)
                .allowed());
        assertFalse(new ReplacementAdmissionPolicy(
                        MaterialResolutionStatus.DIRECT_UNIQUE,
                        MaterialProviderSource.IMMEDIATE,
                        false,
                        true,
                        true,
                        false,
                        true,
                        false)
                .allowed());
        assertFalse(new ReplacementAdmissionPolicy(
                        MaterialResolutionStatus.DIRECT_UNIQUE,
                        MaterialProviderSource.IMMEDIATE,
                        false,
                        true,
                        true,
                        false,
                        false,
                        true)
                .allowed());
    }

    private static ReplacementAdmissionPolicy policy(
            MaterialResolutionStatus status, MaterialProviderSource providerSource) {
        return new ReplacementAdmissionPolicy(status, providerSource, false, true, true, false, false, false);
    }
}
