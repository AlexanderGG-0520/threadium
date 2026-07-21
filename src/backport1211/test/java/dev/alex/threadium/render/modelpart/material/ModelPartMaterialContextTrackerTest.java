package dev.alex.threadium.render.modelpart.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

final class ModelPartMaterialContextTrackerTest {
    @Test
    void oneExactRegistrationResolvesDirectUnique() {
        Fixture fixture = new Fixture();
        assertEquals(
                ModelPartMaterialContextTracker.RegistrationResult.REGISTERED,
                fixture.tracker.register(fixture.provider, fixture.layer, fixture.consumer));
        MaterialContextResolution<Object, Object> resolution = fixture.tracker.resolve(fixture.consumer);
        assertEquals(MaterialResolutionStatus.DIRECT_UNIQUE, resolution.status());
        assertSame(fixture.provider, resolution.provider());
        assertSame(fixture.layer, resolution.layer());
    }

    @Test
    void explicitProviderSourceIsPreservedWithoutChangingIdentitySemantics() {
        Fixture fixture = new Fixture();
        fixture.tracker.register(fixture.provider, fixture.layer, fixture.consumer, MaterialProviderSource.OUTLINE);
        MaterialContextResolution<Object, Object> resolution = fixture.tracker.resolve(fixture.consumer);
        assertEquals(MaterialProviderSource.OUTLINE, resolution.providerSource());
        assertEquals(MaterialResolutionStatus.DIRECT_UNIQUE, resolution.status());
    }

    @Test
    void repeatedExactRegistrationRefreshesWithoutLosingUniqueness() {
        Fixture fixture = new Fixture();
        fixture.tracker.register(fixture.provider, fixture.layer, fixture.consumer);
        assertEquals(
                ModelPartMaterialContextTracker.RegistrationResult.REFRESHED,
                fixture.tracker.register(fixture.provider, fixture.layer, fixture.consumer));
        assertEquals(
                MaterialResolutionStatus.DIRECT_UNIQUE,
                fixture.tracker.resolve(fixture.consumer).status());
        assertEquals(1, fixture.tracker.diagnostics().trackedConsumersThisFrame());
        assertEquals(1, fixture.tracker.diagnostics().materialRegistrationRefreshes());
    }

    @Test
    void equalButNotIdenticalConsumerDoesNotResolve() {
        Fixture fixture = new Fixture();
        IdentityToken equalConsumer = new IdentityToken("consumer");
        fixture.tracker.register(fixture.provider, fixture.layer, fixture.consumer);
        assertNotSame(fixture.consumer, equalConsumer);
        assertEquals(
                MaterialResolutionStatus.UNRESOLVED,
                fixture.tracker.resolve(equalConsumer).status());
    }

    @Test
    void equalButNotIdenticalLayerIsARebinding() {
        Fixture fixture = new Fixture();
        IdentityToken equalLayer = new IdentityToken("layer");
        fixture.tracker.register(fixture.provider, fixture.layer, fixture.consumer);
        fixture.tracker.register(fixture.provider, equalLayer, fixture.consumer);
        MaterialContextResolution<Object, Object> resolution = fixture.tracker.resolve(fixture.consumer);
        assertEquals(MaterialResolutionStatus.DIRECT_REBOUND, resolution.status());
        assertSame(equalLayer, resolution.layer());
    }

    @Test
    void sameConsumerReboundToDifferentLayerRemainsRebound() {
        Fixture fixture = new Fixture();
        Object otherLayer = new Object();
        fixture.tracker.register(fixture.provider, fixture.layer, fixture.consumer);
        fixture.tracker.register(fixture.provider, otherLayer, fixture.consumer);
        fixture.tracker.register(fixture.provider, fixture.layer, fixture.consumer);
        assertEquals(
                MaterialResolutionStatus.DIRECT_REBOUND,
                fixture.tracker.resolve(fixture.consumer).status());
        assertEquals(2, fixture.tracker.diagnostics().materialConsumerRebindings());
    }

    @Test
    void crossProviderRebindingIsDetected() {
        Fixture fixture = new Fixture();
        fixture.tracker.register(fixture.provider, fixture.layer, fixture.consumer);
        fixture.tracker.register(new Object(), fixture.layer, fixture.consumer);
        assertEquals(
                MaterialResolutionStatus.DIRECT_REBOUND,
                fixture.tracker.resolve(fixture.consumer).status());
        assertEquals(1, fixture.tracker.diagnostics().materialCrossProviderRebindings());
    }

    @Test
    void latestSuccessfulRelationshipIsReportedForReboundConsumer() {
        Fixture fixture = new Fixture();
        Object latestProvider = new Object();
        Object latestLayer = new Object();
        fixture.tracker.register(fixture.provider, fixture.layer, fixture.consumer);
        fixture.tracker.register(latestProvider, latestLayer, fixture.consumer);
        MaterialContextResolution<Object, Object> resolution = fixture.tracker.resolve(fixture.consumer);
        assertSame(latestProvider, resolution.provider());
        assertSame(latestLayer, resolution.layer());
    }

    @Test
    void newFrameReleasesBindingsAndMakesOldConsumerUnresolved() {
        Fixture fixture = new Fixture();
        fixture.tracker.register(fixture.provider, fixture.layer, fixture.consumer);
        fixture.tracker.beginFrame();
        assertEquals(
                MaterialResolutionStatus.UNRESOLVED,
                fixture.tracker.resolve(fixture.consumer).status());
        assertEquals(0, fixture.tracker.diagnostics().trackedConsumersThisFrame());
        assertEquals(1, fixture.tracker.diagnostics().frameGeneration());
    }

    @Test
    void lifecycleClearReleasesBindingsAndCumulativeCounters() {
        Fixture fixture = new Fixture();
        fixture.tracker.register(fixture.provider, fixture.layer, fixture.consumer);
        fixture.tracker.observeResolution(fixture.consumer);
        fixture.tracker.clearLifecycle();
        ModelPartMaterialContextTracker.Diagnostics diagnostics = fixture.tracker.diagnostics();
        assertEquals(0, diagnostics.trackedConsumersThisFrame());
        assertEquals(0, diagnostics.materialProviderRequests());
        assertEquals(0, diagnostics.materialResolutionAttempts());
    }

    @Test
    void consumerCapacityRejectsBeforeRetention() {
        ModelPartMaterialContextTracker<Object, Object, Object> tracker = tracker(1, 2, 2, 2, 32);
        tracker.register(new Object(), new Object(), new Object());
        Object rejected = new Object();
        assertEquals(
                ModelPartMaterialContextTracker.RegistrationResult.CAPACITY_REJECTED,
                tracker.register(new Object(), new Object(), rejected));
        assertEquals(1, tracker.diagnostics().trackedConsumersThisFrame());
        assertEquals(
                MaterialResolutionStatus.CAPACITY_REJECTED,
                tracker.resolve(rejected).status());
    }

    @Test
    void existingConsumerCanRefreshWhenConsumerCapacityIsFull() {
        ModelPartMaterialContextTracker<Object, Object, Object> tracker = tracker(1, 1, 1, 1, 32);
        Object provider = new Object();
        Object layer = new Object();
        Object consumer = new Object();
        tracker.register(provider, layer, consumer);
        assertEquals(
                ModelPartMaterialContextTracker.RegistrationResult.REFRESHED,
                tracker.register(provider, layer, consumer));
    }

    @Test
    void providerLimitIsIdentityBasedAndEnforced() {
        ModelPartMaterialContextTracker<Object, Object, Object> tracker = tracker(2, 1, 2, 1, 32);
        Object layer = new Object();
        tracker.register(new IdentityToken("provider"), layer, new Object());
        assertEquals(
                ModelPartMaterialContextTracker.RegistrationResult.CAPACITY_REJECTED,
                tracker.register(new IdentityToken("provider"), layer, new Object()));
        assertEquals(1, tracker.diagnostics().distinctProvidersThisFrame());
    }

    @Test
    void layerLimitIsIdentityBasedAndEnforced() {
        ModelPartMaterialContextTracker<Object, Object, Object> tracker = tracker(2, 1, 1, 1, 32);
        Object provider = new Object();
        tracker.register(provider, new IdentityToken("layer"), new Object());
        assertEquals(
                ModelPartMaterialContextTracker.RegistrationResult.CAPACITY_REJECTED,
                tracker.register(provider, new IdentityToken("layer"), new Object()));
        assertEquals(1, tracker.diagnostics().distinctRenderLayersThisFrame());
    }

    @Test
    void capacityRejectedRebindingMarksExistingConsumerAmbiguous() {
        ModelPartMaterialContextTracker<Object, Object, Object> tracker = tracker(1, 1, 1, 1, 32);
        Object provider = new Object();
        Object consumer = new Object();
        tracker.register(provider, new Object(), consumer);
        tracker.register(provider, new Object(), consumer);
        assertEquals(
                MaterialResolutionStatus.DIRECT_REBOUND,
                tracker.resolve(consumer).status());
    }

    @Test
    void unresolvedClassDiagnosticsAreBounded() {
        ModelPartMaterialContextTracker<Object, Object, Object> tracker = tracker(1, 1, 1, 2, 256);
        tracker.observeResolution("text");
        tracker.observeResolution(Integer.valueOf(1));
        tracker.observeResolution(new Object());
        assertEquals(2, tracker.diagnostics().unresolvedConsumerClassesThisFrame());
    }

    @Test
    void diagnosticTextIsTruncatedAndNewlineSafe() {
        assertEquals("ab  ", ModelPartMaterialContextTracker.sanitizeDiagnosticText("ab\n\rxyz", 4));
    }

    @Test
    void registrationSequenceIsMonotonicAndResetsPerFrame() {
        Fixture fixture = new Fixture();
        fixture.tracker.register(fixture.provider, fixture.layer, fixture.consumer);
        long first = fixture.tracker.resolve(fixture.consumer).registrationSequence();
        fixture.tracker.register(fixture.provider, fixture.layer, fixture.consumer);
        long second = fixture.tracker.resolve(fixture.consumer).registrationSequence();
        assertTrue(second > first);
        fixture.tracker.beginFrame();
        fixture.tracker.register(fixture.provider, fixture.layer, fixture.consumer);
        assertEquals(1, fixture.tracker.resolve(fixture.consumer).registrationSequence());
    }

    @Test
    void pureResolutionDoesNotChangeDiagnostics() {
        Fixture fixture = new Fixture();
        fixture.tracker.register(fixture.provider, fixture.layer, fixture.consumer);
        ModelPartMaterialContextTracker.Diagnostics before = fixture.tracker.diagnostics();
        fixture.tracker.resolve(fixture.consumer);
        assertEquals(before, fixture.tracker.diagnostics());
    }

    @Test
    void observedUnknownResolutionDoesNotRetainConsumerIdentity() {
        Fixture fixture = new Fixture();
        fixture.tracker.observeResolution(new Object());
        assertEquals(0, fixture.tracker.diagnostics().trackedConsumersThisFrame());
        assertEquals(1, fixture.tracker.diagnostics().materialUnresolvedResolutions());
    }

    @Test
    void frameClearDropsEveryIdentityGaugeAndClassName() {
        Fixture fixture = new Fixture();
        fixture.tracker.register(fixture.provider, fixture.layer, fixture.consumer);
        fixture.tracker.observeResolution(new Object());
        fixture.tracker.beginFrame();
        ModelPartMaterialContextTracker.Diagnostics diagnostics = fixture.tracker.diagnostics();
        assertEquals(0, diagnostics.trackedConsumersThisFrame());
        assertEquals(0, diagnostics.distinctProvidersThisFrame());
        assertEquals(0, diagnostics.distinctRenderLayersThisFrame());
        assertEquals(0, diagnostics.unresolvedConsumerClassesThisFrame());
    }

    @Test
    void invalidRegistrationCannotLeavePartialState() {
        Fixture fixture = new Fixture();
        assertThrows(
                NullPointerException.class, () -> fixture.tracker.register(fixture.provider, null, fixture.consumer));
        assertEquals(0, fixture.tracker.diagnostics().materialProviderRequests());
        assertEquals(0, fixture.tracker.diagnostics().trackedConsumersThisFrame());
    }

    @Test
    void misleadingEqualsHashCodeAndToStringAreNeverInvoked() {
        Fixture fixture = new Fixture();
        ExplosiveIdentity provider = new ExplosiveIdentity();
        ExplosiveIdentity layer = new ExplosiveIdentity();
        ExplosiveIdentity consumer = new ExplosiveIdentity();
        fixture.tracker.register(provider, layer, consumer);
        assertEquals(
                MaterialResolutionStatus.DIRECT_UNIQUE,
                fixture.tracker.resolve(consumer).status());
    }

    @Test
    void repeatedRefreshesDoNotCreateEventHistoryOrAdditionalBindings() {
        Fixture fixture = new Fixture();
        fixture.tracker.register(fixture.provider, fixture.layer, fixture.consumer);
        for (int index = 0; index < 10_000; index++) {
            fixture.tracker.register(fixture.provider, fixture.layer, fixture.consumer);
        }
        assertEquals(1, fixture.tracker.diagnostics().trackedConsumersThisFrame());
        assertEquals(10_000, fixture.tracker.diagnostics().materialRegistrationRefreshes());
    }

    @Test
    void observedResolutionCountersMatchEveryExplicitStatus() {
        Fixture fixture = new Fixture();
        fixture.tracker.register(fixture.provider, fixture.layer, fixture.consumer);
        fixture.tracker.observeResolution(fixture.consumer);
        fixture.tracker.register(fixture.provider, new Object(), fixture.consumer);
        fixture.tracker.observeResolution(fixture.consumer);
        fixture.tracker.observeResolution(new Object());
        ModelPartMaterialContextTracker.Diagnostics diagnostics = fixture.tracker.diagnostics();
        assertEquals(3, diagnostics.materialResolutionAttempts());
        assertEquals(1, diagnostics.materialDirectUniqueResolutions());
        assertEquals(1, diagnostics.materialDirectReboundResolutions());
        assertEquals(1, diagnostics.materialUnresolvedResolutions());
    }

    @Test
    void diagnosticSnapshotCannotBeMutatedByCaller() {
        Fixture fixture = new Fixture();
        fixture.tracker.observeResolution(new Object());
        Set<String> classes = fixture.tracker.diagnostics().unresolvedConsumerClasses();
        assertThrows(UnsupportedOperationException.class, () -> classes.add("other"));
    }

    @Test
    void failureCounterIsBoundedAggregateState() {
        Fixture fixture = new Fixture();
        fixture.tracker.recordTrackingFailure();
        fixture.tracker.recordTrackingFailure();
        assertEquals(2, fixture.tracker.diagnostics().materialTrackingFailures());
        assertFalse(fixture.tracker.diagnostics().unresolvedConsumerClasses().contains("failure"));
    }

    @Test
    void invalidLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ModelPartMaterialContextTracker.Limits(0, 1, 1, 1, 1));
    }

    private static ModelPartMaterialContextTracker<Object, Object, Object> tracker(
            int consumers, int providers, int layers, int classes, int textLength) {
        return new ModelPartMaterialContextTracker<>(
                new ModelPartMaterialContextTracker.Limits(consumers, providers, layers, classes, textLength));
    }

    private static final class Fixture {
        private final ModelPartMaterialContextTracker<Object, Object, Object> tracker =
                new ModelPartMaterialContextTracker<>();
        private final Object provider = new IdentityToken("provider");
        private final Object layer = new IdentityToken("layer");
        private final Object consumer = new IdentityToken("consumer");
    }

    private record IdentityToken(String ignored) {
        @Override
        public boolean equals(Object object) {
            return object instanceof IdentityToken;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }

    private static final class ExplosiveIdentity {
        @Override
        public boolean equals(Object object) {
            throw new AssertionError("equals must not be called");
        }

        @Override
        public int hashCode() {
            throw new AssertionError("hashCode must not be called");
        }

        @Override
        public String toString() {
            throw new AssertionError("toString must not be called");
        }
    }
}
