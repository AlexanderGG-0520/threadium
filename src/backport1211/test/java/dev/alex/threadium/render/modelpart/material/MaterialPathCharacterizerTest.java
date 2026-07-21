package dev.alex.threadium.render.modelpart.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class MaterialPathCharacterizerTest {
    @Test
    void explicitProviderSourcePropagatesIntoPath() {
        MaterialPathCharacterizer<Object> characterizer = characterizer();
        characterizer.observeRegistration(new Object(), data(MaterialProviderSource.OUTLINE, "layer"), true);
        characterizer.observeResolution(
                new Object(), MaterialResolutionStatus.DIRECT_UNIQUE, data(MaterialProviderSource.OUTLINE, "layer"));
        assertEquals(
                MaterialProviderSource.OUTLINE, onlyPath(characterizer).key().providerSource());
    }

    @Test
    void providerSourceIsNotInferredFromClassText() {
        MaterialPathDiagnosticData data = new MaterialPathDiagnosticData(
                MaterialProviderSource.OUTLINE, "looks.like.Immediate", "consumer", "layer", "description", false);
        MaterialPathCharacterizer<Object> characterizer = characterizer();
        characterizer.observeResolution(new Object(), MaterialResolutionStatus.DIRECT_UNIQUE, data);
        assertEquals(
                MaterialProviderSource.OUTLINE, onlyPath(characterizer).key().providerSource());
    }

    @Test
    void unifiedSequenceOrdersRegistrationThenResolution() {
        MaterialPathCharacterizer<Object> characterizer = characterizer();
        long registration = characterizer
                .observeRegistration(new Object(), data(MaterialProviderSource.IMMEDIATE, "a"), true)
                .eventSequence();
        long resolution = characterizer
                .observeResolution(
                        new Object(),
                        MaterialResolutionStatus.DIRECT_UNIQUE,
                        data(MaterialProviderSource.IMMEDIATE, "a"))
                .eventSequence();
        assertEquals(1, registration);
        assertEquals(2, resolution);
    }

    @Test
    void unifiedSequenceOrdersResolutionThenLateRegistration() {
        MaterialPathCharacterizer<Object> characterizer = characterizer();
        Object consumer = new Object();
        long resolution = characterizer
                .observeResolution(
                        consumer,
                        MaterialResolutionStatus.UNRESOLVED,
                        MaterialPathDiagnosticData.unresolved("consumer"))
                .eventSequence();
        MaterialPathCharacterizer.RegistrationObservation registration =
                characterizer.observeRegistration(consumer, data(MaterialProviderSource.IMMEDIATE, "a"), true);
        assertEquals(1, resolution);
        assertEquals(2, registration.eventSequence());
        assertTrue(registration.unresolvedThenRegistered());
    }

    @Test
    void unifiedSequenceResetsAtFrameBoundary() {
        MaterialPathCharacterizer<Object> characterizer = characterizer();
        characterizer.observeRegistration(new Object(), data(MaterialProviderSource.IMMEDIATE, "a"), true);
        characterizer.beginFrame();
        assertEquals(
                1,
                characterizer
                        .observeRegistration(new Object(), data(MaterialProviderSource.IMMEDIATE, "a"), true)
                        .eventSequence());
    }

    @Test
    void unresolvedBeforeAnyProviderIsCounted() {
        MaterialPathCharacterizer<Object> characterizer = characterizer();
        MaterialPathCharacterizer.ResolutionObservation observation = characterizer.observeResolution(
                new Object(), MaterialResolutionStatus.UNRESOLVED, MaterialPathDiagnosticData.unresolved("consumer"));
        assertTrue(observation.resolutionBeforeFirstProvider());
        assertEquals(1, characterizer.diagnostics().materialResolutionsBeforeAnyProvider());
    }

    @Test
    void unresolvedAfterUnrelatedProviderIsDistinguished() {
        MaterialPathCharacterizer<Object> characterizer = characterizer();
        characterizer.observeRegistration(new Object(), data(MaterialProviderSource.IMMEDIATE, "a"), true);
        MaterialPathCharacterizer.ResolutionObservation observation = characterizer.observeResolution(
                new Object(), MaterialResolutionStatus.UNRESOLVED, MaterialPathDiagnosticData.unresolved("consumer"));
        assertFalse(observation.resolutionBeforeFirstProvider());
    }

    @Test
    void sameConsumerUnresolvedThenRegisteredIsDetected() {
        MaterialPathCharacterizer<Object> characterizer = characterizer();
        Object consumer = new Object();
        characterizer.observeResolution(
                consumer, MaterialResolutionStatus.UNRESOLVED, MaterialPathDiagnosticData.unresolved("consumer"));
        characterizer.observeRegistration(consumer, data(MaterialProviderSource.IMMEDIATE, "a"), true);
        assertEquals(1, characterizer.diagnostics().materialUnresolvedThenRegistered());
        assertTrue(onlyPath(characterizer).key().lateRegistration());
    }

    @Test
    void equalButNotIdenticalConsumerDoesNotTriggerLateRegistration() {
        MaterialPathCharacterizer<Object> characterizer = characterizer();
        IdentityToken first = new IdentityToken("same");
        IdentityToken second = new IdentityToken("same");
        characterizer.observeResolution(
                first, MaterialResolutionStatus.UNRESOLVED, MaterialPathDiagnosticData.unresolved("consumer"));
        assertFalse(characterizer
                .observeRegistration(second, data(MaterialProviderSource.IMMEDIATE, "a"), true)
                .unresolvedThenRegistered());
    }

    @Test
    void unresolvedProbeCapacityIsIndependentAndBounded() {
        MaterialPathCharacterizer<Object> characterizer = characterizer(1, 8, 8);
        characterizer.observeResolution(
                new Object(), MaterialResolutionStatus.UNRESOLVED, MaterialPathDiagnosticData.unresolved("first"));
        characterizer.observeResolution(
                new Object(), MaterialResolutionStatus.UNRESOLVED, MaterialPathDiagnosticData.unresolved("second"));
        assertEquals(1, characterizer.diagnostics().unresolvedProbesThisFrame());
        assertEquals(1, characterizer.diagnostics().materialUnresolvedProbeCapacityRejections());
    }

    @Test
    void rejectedUnresolvedProbeDoesNotRetainConsumer() {
        MaterialPathCharacterizer<Object> characterizer = characterizer(1, 8, 8);
        Object retained = new Object();
        Object rejected = new Object();
        characterizer.observeResolution(
                retained, MaterialResolutionStatus.UNRESOLVED, MaterialPathDiagnosticData.unresolved("first"));
        characterizer.observeResolution(
                rejected, MaterialResolutionStatus.UNRESOLVED, MaterialPathDiagnosticData.unresolved("second"));
        assertFalse(characterizer
                .observeRegistration(rejected, data(MaterialProviderSource.IMMEDIATE, "a"), true)
                .unresolvedThenRegistered());
    }

    @Test
    void unresolvedReferencesAreReleasedAtFrameClear() {
        MaterialPathCharacterizer<Object> characterizer = characterizer();
        characterizer.observeResolution(
                new Object(), MaterialResolutionStatus.UNRESOLVED, MaterialPathDiagnosticData.unresolved("consumer"));
        characterizer.beginFrame();
        assertEquals(0, characterizer.diagnostics().unresolvedProbesThisFrame());
    }

    @Test
    void unresolvedNeverRegisteredFinalizesAtFrameClear() {
        MaterialPathCharacterizer<Object> characterizer = characterizer();
        characterizer.observeResolution(
                new Object(), MaterialResolutionStatus.UNRESOLVED, MaterialPathDiagnosticData.unresolved("consumer"));
        characterizer.beginFrame();
        assertEquals(1, characterizer.diagnostics().materialUnresolvedNeverRegistered());
        assertFalse(onlyPath(characterizer).key().lateRegistration());
    }

    @Test
    void lateRegistrationDoesNotRewriteOriginalResolutionStatus() {
        MaterialPathCharacterizer<Object> characterizer = characterizer();
        Object consumer = new Object();
        MaterialPathCharacterizer.ResolutionObservation original = characterizer.observeResolution(
                consumer, MaterialResolutionStatus.UNRESOLVED, MaterialPathDiagnosticData.unresolved("consumer"));
        characterizer.observeRegistration(consumer, data(MaterialProviderSource.IMMEDIATE, "a"), true);
        assertEquals(MaterialResolutionStatus.UNRESOLVED, original.originalStatus());
        assertEquals(
                MaterialResolutionStatus.UNRESOLVED,
                onlyPath(characterizer).key().status());
    }

    @Test
    void directUniquePathIsAggregated() {
        assertStatusCount(MaterialResolutionStatus.DIRECT_UNIQUE, 1, 0, 0, 0);
    }

    @Test
    void directReboundPathIsAggregated() {
        assertStatusCount(MaterialResolutionStatus.DIRECT_REBOUND, 0, 1, 0, 0);
    }

    @Test
    void unresolvedPathIsAggregatedAtFrameClose() {
        MaterialPathCharacterizer<Object> characterizer = characterizer();
        characterizer.observeResolution(
                new Object(), MaterialResolutionStatus.UNRESOLVED, MaterialPathDiagnosticData.unresolved("consumer"));
        characterizer.beginFrame();
        assertEquals(1, characterizer.diagnostics().materialUnresolvedPaths());
        assertEquals(1, onlyPath(characterizer).count());
    }

    @Test
    void capacityRejectedPathIsAggregated() {
        assertStatusCount(MaterialResolutionStatus.CAPACITY_REJECTED, 0, 0, 0, 1);
    }

    @Test
    void providerSourcesProduceSeparateKeys() {
        MaterialPathCharacterizer<Object> characterizer = characterizer();
        characterizer.observeResolution(
                new Object(), MaterialResolutionStatus.DIRECT_UNIQUE, data(MaterialProviderSource.IMMEDIATE, "same"));
        characterizer.observeResolution(
                new Object(), MaterialResolutionStatus.DIRECT_UNIQUE, data(MaterialProviderSource.OUTLINE, "same"));
        assertEquals(2, characterizer.diagnostics().retainedPathKeys());
    }

    @Test
    void consumerClassesProduceSeparateKeys() {
        MaterialPathCharacterizer<Object> characterizer = characterizer();
        characterizer.observeResolution(
                new Object(), MaterialResolutionStatus.DIRECT_UNIQUE, data("consumer.A", "layer"));
        characterizer.observeResolution(
                new Object(), MaterialResolutionStatus.DIRECT_UNIQUE, data("consumer.B", "layer"));
        assertEquals(2, characterizer.diagnostics().retainedPathKeys());
    }

    @Test
    void layerDescriptionsProduceSeparateKeys() {
        MaterialPathCharacterizer<Object> characterizer = characterizer();
        characterizer.observeResolution(
                new Object(), MaterialResolutionStatus.DIRECT_UNIQUE, data(MaterialProviderSource.IMMEDIATE, "first"));
        characterizer.observeResolution(
                new Object(), MaterialResolutionStatus.DIRECT_UNIQUE, data(MaterialProviderSource.IMMEDIATE, "second"));
        assertEquals(2, characterizer.diagnostics().retainedPathKeys());
    }

    @Test
    void repeatedPathRefreshesCountWithoutNewKey() {
        MaterialPathCharacterizer<Object> characterizer = characterizer();
        MaterialPathDiagnosticData data = data(MaterialProviderSource.IMMEDIATE, "same");
        for (int index = 0; index < 100; index++) {
            characterizer.observeResolution(new Object(), MaterialResolutionStatus.DIRECT_UNIQUE, data);
        }
        assertEquals(1, characterizer.diagnostics().retainedPathKeys());
        assertEquals(100, onlyPath(characterizer).count());
        assertEquals(99, characterizer.diagnostics().materialPathKeyRefreshes());
    }

    @Test
    void pathCapacityCreatesOverflowCountWithoutNewKeys() {
        MaterialPathCharacterizer<Object> characterizer = characterizer(2, 1, 1);
        characterizer.observeResolution(
                new Object(), MaterialResolutionStatus.DIRECT_UNIQUE, data(MaterialProviderSource.IMMEDIATE, "first"));
        characterizer.observeResolution(
                new Object(), MaterialResolutionStatus.DIRECT_UNIQUE, data(MaterialProviderSource.IMMEDIATE, "second"));
        assertEquals(1, characterizer.diagnostics().retainedPathKeys());
        assertEquals(1, characterizer.diagnostics().materialPathKeyCapacityRejections());
        assertEquals(1, characterizer.diagnostics().materialPathOverflowEvents());
    }

    @Test
    void histogramHasNoPerEventHistoryGrowth() {
        MaterialPathCharacterizer<Object> characterizer = characterizer();
        MaterialPathDiagnosticData data = data(MaterialProviderSource.IMMEDIATE, "same");
        for (int index = 0; index < 10_000; index++) {
            characterizer.observeResolution(new Object(), MaterialResolutionStatus.DIRECT_UNIQUE, data);
        }
        assertEquals(1, characterizer.orderedPaths(64).size());
        assertEquals(10_000, onlyPath(characterizer).count());
    }

    @Test
    void diagnosticTextSanitizesTruncatesAndRemovesControls() {
        assertEquals("ab  ", MaterialDiagnosticText.sanitize("ab\n\u0000xyz", 4));
        assertEquals("abc", MaterialDiagnosticText.sanitize("abcdef", 3));
    }

    @Test
    void diagnosticTextNormalizesHiddenLambdaAddressesAndIdentityHashes() {
        assertEquals(
                "net.minecraft.SomeClass$$Lambda@<identity>",
                MaterialDiagnosticText.sanitize("net.minecraft.SomeClass$$Lambda/0x00007f7248c64230@4379952f", 256));
        assertEquals("ClassName@<identity>", MaterialDiagnosticText.sanitize("ClassName@0123abcd", 256));
        assertEquals("ClassName@<identity>", MaterialDiagnosticText.sanitize("ClassName@ABCDEF12", 256));
        assertEquals("A@<identity> B@<identity>", MaterialDiagnosticText.sanitize("A@0123abcd B@ABCDEF12", 256));
    }

    @Test
    void diagnosticTextPreservesDistinguishableNonIdentityAtSigns() {
        assertEquals(
                "minecraft:textures/entity/cow@2x.png user@example.com",
                MaterialDiagnosticText.sanitize("minecraft:textures/entity/cow@2x.png user@example.com", 256));
    }

    @Test
    void normalizationOccursBeforeTruncation() {
        assertEquals("A$$Lambda@<", MaterialDiagnosticText.sanitize("A$$Lambda/0x1234567890@abcdef12", 11));
    }

    @Test
    void normalizedDescriptionsProduceStableKeysAndOrdering() {
        String first = MaterialDiagnosticText.sanitize("Layer$$Lambda/0x1111@aaaaaaaa", 256);
        String second = MaterialDiagnosticText.sanitize("Layer$$Lambda/0x2222@bbbbbbbb", 256);
        assertEquals(first, second);
        MaterialPathCharacterizer<Object> characterizer = characterizer();
        characterizer.observeResolution(
                new Object(), MaterialResolutionStatus.DIRECT_UNIQUE, data(MaterialProviderSource.IMMEDIATE, first));
        characterizer.observeResolution(
                new Object(), MaterialResolutionStatus.DIRECT_UNIQUE, data(MaterialProviderSource.IMMEDIATE, second));
        assertEquals(1, characterizer.diagnostics().retainedPathKeys());
        assertEquals(first, onlyPath(characterizer).key().layerDescription());
    }

    @Test
    void diagnosticFormattingFailureUsesPlaceholder() {
        Object explosive = new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("expected");
            }
        };
        assertEquals(MaterialPathDiagnosticData.FORMATTING_FAILED, MaterialDiagnosticText.describe(explosive, 256));
    }

    @Test
    void summaryOrderingIsCountDescendingThenStableText() {
        MaterialPathCharacterizer<Object> characterizer = characterizer();
        MaterialPathDiagnosticData alpha = data(MaterialProviderSource.IMMEDIATE, "alpha");
        MaterialPathDiagnosticData beta = data(MaterialProviderSource.IMMEDIATE, "beta");
        characterizer.observeResolution(new Object(), MaterialResolutionStatus.DIRECT_UNIQUE, beta);
        characterizer.observeResolution(new Object(), MaterialResolutionStatus.DIRECT_UNIQUE, alpha);
        characterizer.observeResolution(new Object(), MaterialResolutionStatus.DIRECT_UNIQUE, alpha);
        List<MaterialPathCharacterizer.PathAggregate> paths = characterizer.orderedPaths(16);
        assertEquals("alpha", paths.get(0).key().layerDescription());
        assertEquals("beta", paths.get(1).key().layerDescription());
    }

    @Test
    void summaryLineLimitIsEnforced() {
        MaterialPathCharacterizer<Object> characterizer = characterizer(4, 8, 2);
        for (int index = 0; index < 5; index++) {
            characterizer.observeResolution(
                    new Object(),
                    MaterialResolutionStatus.DIRECT_UNIQUE,
                    data(MaterialProviderSource.IMMEDIATE, "layer-" + index));
        }
        assertEquals(2, characterizer.lifecycleSummary().paths().size());
    }

    @Test
    void lifecycleSummaryFinalizesUnresolvedBeforeReturning() {
        MaterialPathCharacterizer<Object> characterizer = characterizer();
        characterizer.observeResolution(
                new Object(), MaterialResolutionStatus.UNRESOLVED, MaterialPathDiagnosticData.unresolved("consumer"));
        MaterialPathCharacterizer.LifecycleSummary summary = characterizer.lifecycleSummary();
        assertEquals(1, summary.diagnostics().materialUnresolvedNeverRegistered());
        assertEquals(0, summary.diagnostics().unresolvedProbesThisFrame());
    }

    @Test
    void zeroEventLifecycleProducesNoSummarySignal() {
        assertFalse(characterizer().lifecycleSummary().hasEvents());
    }

    @Test
    void frameResetRetainsLifecycleHistogram() {
        MaterialPathCharacterizer<Object> characterizer = characterizer();
        characterizer.observeResolution(
                new Object(), MaterialResolutionStatus.DIRECT_UNIQUE, data(MaterialProviderSource.IMMEDIATE, "a"));
        characterizer.beginFrame();
        assertEquals(1, characterizer.diagnostics().retainedPathKeys());
    }

    @Test
    void lifecycleResetClearsHistogramReferencesAndCounters() {
        MaterialPathCharacterizer<Object> characterizer = characterizer();
        characterizer.observeResolution(
                new Object(), MaterialResolutionStatus.DIRECT_UNIQUE, data(MaterialProviderSource.IMMEDIATE, "a"));
        characterizer.clearLifecycle();
        assertEquals(0, characterizer.diagnostics().retainedPathKeys());
        assertEquals(0, characterizer.diagnostics().materialCharacterizationEvents());
    }

    @Test
    void maliciousEqualityCannotAlterProbeIdentity() {
        MaterialPathCharacterizer<Object> characterizer = characterizer();
        ExplosiveIdentity first = new ExplosiveIdentity();
        ExplosiveIdentity second = new ExplosiveIdentity();
        characterizer.observeResolution(
                first, MaterialResolutionStatus.UNRESOLVED, MaterialPathDiagnosticData.unresolved("consumer"));
        assertFalse(characterizer
                .observeRegistration(second, data(MaterialProviderSource.IMMEDIATE, "a"), true)
                .unresolvedThenRegistered());
    }

    @Test
    void diagnosticValueEqualityCannotInfluenceIdentityResolution() {
        ModelPartMaterialContextTracker<Object, Object, Object> tracker = new ModelPartMaterialContextTracker<>();
        Object provider = new Object();
        Object layer = new Object();
        Object registered = new IdentityToken("same");
        Object different = new IdentityToken("same");
        tracker.register(provider, layer, registered, MaterialProviderSource.IMMEDIATE);
        assertEquals(
                MaterialResolutionStatus.UNRESOLVED, tracker.resolve(different).status());
    }

    @Test
    void characterizationFailureCannotAffectMaterialResolution() {
        ModelPartMaterialContextTracker<Object, Object, Object> tracker = new ModelPartMaterialContextTracker<>();
        Object provider = new Object();
        Object layer = new Object();
        Object consumer = new Object();
        tracker.register(provider, layer, consumer, MaterialProviderSource.IMMEDIATE);
        MaterialPathCharacterizer<Object> characterizer = characterizer();
        characterizer.recordFailure();
        assertEquals(
                MaterialResolutionStatus.DIRECT_UNIQUE,
                tracker.resolve(consumer).status());
    }

    @Test
    void retainedPathContainsOnlyValueData() {
        MaterialPathCharacterizer<Object> characterizer = characterizer();
        Object consumer = new Object();
        characterizer.observeResolution(
                consumer, MaterialResolutionStatus.DIRECT_UNIQUE, data(MaterialProviderSource.IMMEDIATE, "a"));
        MaterialPathKey key = onlyPath(characterizer).key();
        assertTrue(key.providerClass() instanceof String);
        assertTrue(key.consumerClass() instanceof String);
        assertTrue(key.layerDescription() instanceof String);
    }

    @Test
    void providerResolutionCountersRemainSeparate() {
        MaterialPathCharacterizer<Object> characterizer = characterizer();
        for (MaterialProviderSource source : List.of(
                MaterialProviderSource.IMMEDIATE,
                MaterialProviderSource.OUTLINE,
                MaterialProviderSource.OTHER_VERIFIED)) {
            characterizer.observeResolution(
                    new Object(), MaterialResolutionStatus.DIRECT_UNIQUE, data(source, source.name()));
        }
        MaterialPathCharacterizer.Diagnostics diagnostics = characterizer.diagnostics();
        assertEquals(1, diagnostics.materialImmediateProviderResolutions());
        assertEquals(1, diagnostics.materialOutlineProviderResolutions());
        assertEquals(1, diagnostics.materialOtherVerifiedProviderResolutions());
    }

    @Test
    void crossProviderReboundIsPartOfDiagnosticKey() {
        MaterialPathCharacterizer<Object> characterizer = characterizer();
        MaterialPathDiagnosticData normal = data(MaterialProviderSource.IMMEDIATE, "a");
        MaterialPathDiagnosticData cross = new MaterialPathDiagnosticData(
                normal.providerSource(),
                normal.providerClass(),
                normal.consumerClass(),
                normal.layerClass(),
                normal.layerDescription(),
                true);
        characterizer.observeResolution(new Object(), MaterialResolutionStatus.DIRECT_REBOUND, normal);
        characterizer.observeResolution(new Object(), MaterialResolutionStatus.DIRECT_REBOUND, cross);
        assertEquals(2, characterizer.diagnostics().retainedPathKeys());
    }

    @Test
    void invalidLimitsAndSummaryRequestsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> characterizer(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> characterizer().orderedPaths(-1));
    }

    private static void assertStatusCount(
            MaterialResolutionStatus status, long unique, long rebound, long unresolved, long capacity) {
        MaterialPathCharacterizer<Object> characterizer = characterizer();
        characterizer.observeResolution(new Object(), status, data(MaterialProviderSource.IMMEDIATE, "a"));
        if (status == MaterialResolutionStatus.UNRESOLVED) characterizer.beginFrame();
        MaterialPathCharacterizer.Diagnostics diagnostics = characterizer.diagnostics();
        assertEquals(unique, diagnostics.materialDirectUniquePaths());
        assertEquals(rebound, diagnostics.materialDirectReboundPaths());
        assertEquals(unresolved, diagnostics.materialUnresolvedPaths());
        assertEquals(capacity, diagnostics.materialCapacityRejectedPaths());
    }

    private static MaterialPathCharacterizer<Object> characterizer() {
        return characterizer(256, 64, 16);
    }

    private static MaterialPathCharacterizer<Object> characterizer(int probes, int paths, int summaryLines) {
        return new MaterialPathCharacterizer<>(new MaterialPathCharacterizer.Limits(probes, paths, summaryLines, 256));
    }

    private static MaterialPathDiagnosticData data(MaterialProviderSource source, String layerDescription) {
        return new MaterialPathDiagnosticData(
                source, "provider.Class", "consumer.Class", "layer.Class", layerDescription, false);
    }

    private static MaterialPathDiagnosticData data(String consumerClass, String layerDescription) {
        return new MaterialPathDiagnosticData(
                MaterialProviderSource.IMMEDIATE,
                "provider.Class",
                consumerClass,
                "layer.Class",
                layerDescription,
                false);
    }

    private static MaterialPathCharacterizer.PathAggregate onlyPath(MaterialPathCharacterizer<?> characterizer) {
        List<MaterialPathCharacterizer.PathAggregate> paths = characterizer.orderedPaths(64);
        assertEquals(1, paths.size());
        return paths.getFirst();
    }

    private record IdentityToken(String value) {
        @Override
        public boolean equals(Object other) {
            return other instanceof IdentityToken;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }

    private static final class ExplosiveIdentity {
        @Override
        public boolean equals(Object other) {
            throw new AssertionError("equals must not be called");
        }

        @Override
        public int hashCode() {
            throw new AssertionError("hashCode must not be called");
        }
    }
}
