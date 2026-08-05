package dev.alex.threadium.render.modelpart.material;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReplacementAdmissionPolicyTest {
    @Test
    void exactSupportedImmediateMaterialIsAllowed() {
        assertTrue(policy(MaterialResolutionStatus.DIRECT_UNIQUE, MaterialProviderSource.IMMEDIATE).allowed());
    }

    @Test
    void exactSupportedImmediatelyFastMaterialIsAllowed() {
        assertTrue(policy(MaterialResolutionStatus.DIRECT_UNIQUE, MaterialProviderSource.IMMEDIATELY_FAST).allowed());
    }

    @Test
    void outlineProviderRequiresSeparateColorAndDrawerProof() {
        assertFalse(policy(MaterialResolutionStatus.DIRECT_UNIQUE, MaterialProviderSource.OUTLINE).allowed());
        assertFalse(new ReplacementAdmissionPolicy(
                        MaterialResolutionStatus.DIRECT_UNIQUE,
                        MaterialProviderSource.IMMEDIATE,
                        false,
                        true,
                        true,
                        true,
                        false)
                .allowed());
    }

    @Test
    void reboundUnresolvedUnknownAndUnimplementedMaterialsFallBack() {
        assertFalse(new ReplacementAdmissionPolicy(
                        MaterialResolutionStatus.DIRECT_REBOUND,
                        MaterialProviderSource.IMMEDIATE,
                        true,
                        true,
                        true,
                        true,
                        true)
                .allowed());
        assertFalse(policy(MaterialResolutionStatus.UNRESOLVED, MaterialProviderSource.UNAVAILABLE).allowed());
        assertFalse(new ReplacementAdmissionPolicy(
                        MaterialResolutionStatus.DIRECT_UNIQUE,
                        MaterialProviderSource.IMMEDIATE,
                        false,
                        false,
                        true,
                        true,
                        true)
                .allowed());
        assertFalse(new ReplacementAdmissionPolicy(
                        MaterialResolutionStatus.DIRECT_UNIQUE,
                        MaterialProviderSource.IMMEDIATE,
                        false,
                        true,
                        true,
                        false,
                        true)
                .allowed());
    }

    private static ReplacementAdmissionPolicy policy(
            MaterialResolutionStatus status, MaterialProviderSource providerSource) {
        return new ReplacementAdmissionPolicy(status, providerSource, false, true, true, true, true);
    }
}
