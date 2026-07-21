package dev.alex.threadium.render.modelpart.material;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Render-thread-owned bounded M3B diagnostics. Its value histogram cannot affect M3A identity resolution. */
public final class MaterialPathCharacterizer<C> {
    public static final Limits DEFAULT_LIMITS = new Limits(256, 64, 16, 256);
    private static final int MAXIMUM_DIRECT_PATH_IDENTITIES = 4_096;

    private static final Comparator<PathAggregate> SUMMARY_ORDER = Comparator.comparingLong(PathAggregate::count)
            .reversed()
            .thenComparing(aggregate -> stableText(aggregate.key()));

    private final Limits limits;
    private final IdentityHashMap<C, UnresolvedProbe> unresolvedProbes = new IdentityHashMap<>();
    private final IdentityHashMap<C, DirectPath> directPaths = new IdentityHashMap<>();
    private final LinkedHashMap<MaterialPathKey, MutablePathAggregate> histogram = new LinkedHashMap<>();

    private long frameGeneration;
    // Unlike the tracker's binding-local registration sequence, this orders both registrations and resolutions.
    private long eventSequence;
    private boolean providerObservedThisFrame;
    private long materialCharacterizationEvents;
    private long materialDirectUniquePaths;
    private long materialDirectReboundPaths;
    private long materialUnresolvedPaths;
    private long materialCapacityRejectedPaths;
    private long materialResolutionsBeforeAnyProvider;
    private long materialUnresolvedProbeRegistrations;
    private long materialUnresolvedProbeCapacityRejections;
    private long materialUnresolvedThenRegistered;
    private long materialUnresolvedNeverRegistered;
    private long materialImmediateProviderResolutions;
    private long materialOutlineProviderResolutions;
    private long materialOtherVerifiedProviderResolutions;
    private long materialPathKeyRegistrations;
    private long materialPathKeyRefreshes;
    private long materialPathKeyCapacityRejections;
    private long materialPathOverflowEvents;
    private long materialCharacterizationFailures;
    private long materialProviderEvents;

    public MaterialPathCharacterizer() {
        this(DEFAULT_LIMITS);
    }

    public MaterialPathCharacterizer(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public RegistrationObservation observeRegistration(
            C consumer, MaterialPathDiagnosticData data, boolean retainedByIdentityTracker) {
        boolean resolvedLate = retainedByIdentityTracker && unresolvedProbes.containsKey(consumer);
        long sequence = recordRegistration(consumer, data, retainedByIdentityTracker);
        return new RegistrationObservation(sequence, resolvedLate);
    }

    public long recordRegistration(C consumer, MaterialPathDiagnosticData data, boolean retainedByIdentityTracker) {
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(data, "data");
        long sequence = nextEventSequence();
        providerObservedThisFrame = true;
        materialProviderEvents++;

        if (retainedByIdentityTracker) {
            UnresolvedProbe probe = unresolvedProbes.remove(consumer);
            if (probe != null) {
                long occurrences = probe.totalOccurrences();
                materialUnresolvedThenRegistered = add(materialUnresolvedThenRegistered, occurrences);
                finalizeProbe(probe, data, true, sequence);
            }
        }
        return sequence;
    }

    public ResolutionObservation observeResolution(
            C consumer, MaterialResolutionStatus status, MaterialPathDiagnosticData data) {
        boolean beforeFirstProvider = !providerObservedThisFrame;
        long sequence = recordResolution(consumer, status, data);
        return new ResolutionObservation(sequence, beforeFirstProvider, status);
    }

    public long recordResolution(C consumer, MaterialResolutionStatus status, MaterialPathDiagnosticData data) {
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(data, "data");
        long sequence = nextEventSequence();
        boolean beforeFirstProvider = !providerObservedThisFrame;
        materialCharacterizationEvents = add(materialCharacterizationEvents, 1);
        if (beforeFirstProvider) materialResolutionsBeforeAnyProvider = add(materialResolutionsBeforeAnyProvider, 1);

        switch (status) {
            case DIRECT_UNIQUE -> {
                materialDirectUniquePaths = add(materialDirectUniquePaths, 1);
                recordProviderResolution(data.providerSource());
                recordPath(directKey(consumer, status, data, beforeFirstProvider), 1, sequence, sequence);
            }
            case DIRECT_REBOUND -> {
                materialDirectReboundPaths = add(materialDirectReboundPaths, 1);
                recordProviderResolution(data.providerSource());
                recordPath(directKey(consumer, status, data, beforeFirstProvider), 1, sequence, sequence);
            }
            case CAPACITY_REJECTED -> {
                materialCapacityRejectedPaths = add(materialCapacityRejectedPaths, 1);
                recordPath(key(status, data, false, beforeFirstProvider), 1, sequence, sequence);
            }
            case UNRESOLVED -> {
                materialUnresolvedPaths = add(materialUnresolvedPaths, 1);
                recordUnresolved(consumer, data.consumerClass(), sequence, beforeFirstProvider);
            }
        }
        return sequence;
    }

    public long recordUnresolvedResolution(
            C consumer, MaterialResolutionStatus status, MaterialPathDiagnosticData unresolvedData) {
        if (status != MaterialResolutionStatus.UNRESOLVED && status != MaterialResolutionStatus.CAPACITY_REJECTED) {
            throw new IllegalArgumentException("Status is not unresolved");
        }
        if (unresolvedData.providerSource() != MaterialProviderSource.UNAVAILABLE) {
            throw new IllegalArgumentException("Unresolved diagnostics contain a provider");
        }
        return recordResolution(consumer, status, unresolvedData);
    }

    public void beginFrame() {
        finalizeUnresolvedNeverRegistered();
        unresolvedProbes.clear();
        directPaths.clear();
        eventSequence = 0;
        providerObservedThisFrame = false;
        frameGeneration = add(frameGeneration, 1);
    }

    public LifecycleSummary lifecycleSummary() {
        finalizeUnresolvedNeverRegistered();
        unresolvedProbes.clear();
        directPaths.clear();
        return new LifecycleSummary(diagnostics(), orderedPaths(limits.maximumSummaryLines()));
    }

    public void clearLifecycle() {
        unresolvedProbes.clear();
        directPaths.clear();
        histogram.clear();
        frameGeneration = 0;
        eventSequence = 0;
        providerObservedThisFrame = false;
        materialCharacterizationEvents = 0;
        materialDirectUniquePaths = 0;
        materialDirectReboundPaths = 0;
        materialUnresolvedPaths = 0;
        materialCapacityRejectedPaths = 0;
        materialResolutionsBeforeAnyProvider = 0;
        materialUnresolvedProbeRegistrations = 0;
        materialUnresolvedProbeCapacityRejections = 0;
        materialUnresolvedThenRegistered = 0;
        materialUnresolvedNeverRegistered = 0;
        materialImmediateProviderResolutions = 0;
        materialOutlineProviderResolutions = 0;
        materialOtherVerifiedProviderResolutions = 0;
        materialPathKeyRegistrations = 0;
        materialPathKeyRefreshes = 0;
        materialPathKeyCapacityRejections = 0;
        materialPathOverflowEvents = 0;
        materialCharacterizationFailures = 0;
        materialProviderEvents = 0;
    }

    public void recordFailure() {
        materialCharacterizationFailures = add(materialCharacterizationFailures, 1);
    }

    public Diagnostics diagnostics() {
        return new Diagnostics(
                materialCharacterizationEvents,
                materialDirectUniquePaths,
                materialDirectReboundPaths,
                materialUnresolvedPaths,
                materialCapacityRejectedPaths,
                materialResolutionsBeforeAnyProvider,
                materialUnresolvedProbeRegistrations,
                materialUnresolvedProbeCapacityRejections,
                materialUnresolvedThenRegistered,
                materialUnresolvedNeverRegistered,
                materialImmediateProviderResolutions,
                materialOutlineProviderResolutions,
                materialOtherVerifiedProviderResolutions,
                materialPathKeyRegistrations,
                materialPathKeyRefreshes,
                materialPathKeyCapacityRejections,
                materialPathOverflowEvents,
                materialCharacterizationFailures,
                materialProviderEvents,
                unresolvedProbes.size(),
                histogram.size(),
                eventSequence,
                frameGeneration);
    }

    public List<PathAggregate> orderedPaths(int maximumEntries) {
        if (maximumEntries < 0) throw new IllegalArgumentException("maximumEntries must not be negative");
        List<PathAggregate> paths = new ArrayList<>(histogram.size());
        for (MutablePathAggregate aggregate : histogram.values()) paths.add(aggregate.snapshot());
        paths.sort(SUMMARY_ORDER);
        return List.copyOf(paths.subList(0, Math.min(maximumEntries, paths.size())));
    }

    private void recordUnresolved(C consumer, String consumerClass, long sequence, boolean beforeFirstProvider) {
        UnresolvedProbe probe = unresolvedProbes.get(consumer);
        if (probe == null) {
            if (unresolvedProbes.size() >= limits.maximumUnresolvedProbes()) {
                materialUnresolvedProbeCapacityRejections = add(materialUnresolvedProbeCapacityRejections, 1);
                MaterialPathDiagnosticData data = MaterialPathDiagnosticData.unresolved(consumerClass);
                recordPath(
                        key(MaterialResolutionStatus.UNRESOLVED, data, false, beforeFirstProvider),
                        1,
                        sequence,
                        sequence);
                return;
            }
            probe = new UnresolvedProbe(consumerClass);
            unresolvedProbes.put(consumer, probe);
            materialUnresolvedProbeRegistrations = add(materialUnresolvedProbeRegistrations, 1);
        }
        probe.record(sequence, beforeFirstProvider);
    }

    private void finalizeUnresolvedNeverRegistered() {
        for (UnresolvedProbe probe : unresolvedProbes.values()) {
            materialUnresolvedNeverRegistered = add(materialUnresolvedNeverRegistered, probe.totalOccurrences());
            finalizeProbe(probe, null, false, probe.lastEventSequence());
        }
    }

    private void finalizeProbe(
            UnresolvedProbe probe,
            MaterialPathDiagnosticData registeredData,
            boolean lateRegistration,
            long finalEventSequence) {
        MaterialPathDiagnosticData data = registeredData == null
                ? MaterialPathDiagnosticData.unresolved(probe.consumerClass)
                : new MaterialPathDiagnosticData(
                        registeredData.providerSource(),
                        registeredData.providerClass(),
                        probe.consumerClass,
                        registeredData.layerClass(),
                        registeredData.layerDescription(),
                        registeredData.crossProviderRebound());
        if (probe.before.count > 0) {
            recordPath(
                    key(MaterialResolutionStatus.UNRESOLVED, data, lateRegistration, true),
                    probe.before.count,
                    probe.before.firstEvent,
                    finalEventSequence);
        }
        if (probe.after.count > 0) {
            recordPath(
                    key(MaterialResolutionStatus.UNRESOLVED, data, lateRegistration, false),
                    probe.after.count,
                    probe.after.firstEvent,
                    finalEventSequence);
        }
    }

    private void recordProviderResolution(MaterialProviderSource source) {
        switch (source) {
            case IMMEDIATE -> materialImmediateProviderResolutions = add(materialImmediateProviderResolutions, 1);
            case OUTLINE -> materialOutlineProviderResolutions = add(materialOutlineProviderResolutions, 1);
            case OTHER_VERIFIED ->
                materialOtherVerifiedProviderResolutions = add(materialOtherVerifiedProviderResolutions, 1);
            case UNAVAILABLE -> {
                // Explicitly absent diagnostic data is permitted and never inferred from class text.
            }
        }
    }

    private MaterialPathKey directKey(
            C consumer, MaterialResolutionStatus status, MaterialPathDiagnosticData data, boolean beforeFirstProvider) {
        DirectPath existing = directPaths.get(consumer);
        if (existing != null
                && existing.status == status
                && (existing.data == data || existing.data.equals(data))
                && existing.beforeFirstProvider == beforeFirstProvider) {
            return existing.key;
        }
        MaterialPathKey key = key(status, data, false, beforeFirstProvider);
        if (existing != null || directPaths.size() < MAXIMUM_DIRECT_PATH_IDENTITIES) {
            directPaths.put(consumer, new DirectPath(status, data, beforeFirstProvider, key));
        }
        return key;
    }

    private void recordPath(MaterialPathKey key, long occurrences, long firstEvent, long lastEvent) {
        MutablePathAggregate existing = histogram.get(key);
        if (existing != null) {
            existing.refresh(occurrences, frameGeneration, lastEvent);
            materialPathKeyRefreshes = add(materialPathKeyRefreshes, occurrences);
            return;
        }
        if (histogram.size() >= limits.maximumPathKeys()) {
            materialPathKeyCapacityRejections = add(materialPathKeyCapacityRejections, 1);
            materialPathOverflowEvents = add(materialPathOverflowEvents, occurrences);
            return;
        }
        histogram.put(key, new MutablePathAggregate(key, occurrences, frameGeneration, firstEvent, lastEvent));
        materialPathKeyRegistrations = add(materialPathKeyRegistrations, 1);
        if (occurrences > 1) materialPathKeyRefreshes = add(materialPathKeyRefreshes, occurrences - 1);
    }

    private long nextEventSequence() {
        if (eventSequence == Long.MAX_VALUE) throw new ArithmeticException("Material event sequence overflow");
        return ++eventSequence;
    }

    private static long add(long value, long increment) {
        return Math.addExact(value, increment);
    }

    private static MaterialPathKey key(
            MaterialResolutionStatus status,
            MaterialPathDiagnosticData data,
            boolean lateRegistration,
            boolean beforeFirstProvider) {
        return new MaterialPathKey(
                status,
                data.providerSource(),
                data.providerClass(),
                data.consumerClass(),
                data.layerClass(),
                data.layerDescription(),
                lateRegistration,
                beforeFirstProvider,
                data.crossProviderRebound());
    }

    private static String stableText(MaterialPathKey key) {
        return key.status() + "|" + key.providerSource() + "|" + key.providerClass() + "|" + key.consumerClass()
                + "|" + key.layerClass() + "|" + key.layerDescription() + "|" + key.lateRegistration() + "|"
                + key.resolutionBeforeFirstProvider() + "|" + key.crossProviderRebound();
    }

    public record Limits(
            int maximumUnresolvedProbes, int maximumPathKeys, int maximumSummaryLines, int maximumDiagnosticLength) {
        public Limits {
            if (maximumUnresolvedProbes <= 0
                    || maximumPathKeys <= 0
                    || maximumSummaryLines < 0
                    || maximumDiagnosticLength <= 0) {
                throw new IllegalArgumentException("Material characterization limits are invalid");
            }
        }
    }

    public record RegistrationObservation(long eventSequence, boolean unresolvedThenRegistered) {}

    public record ResolutionObservation(
            long eventSequence, boolean resolutionBeforeFirstProvider, MaterialResolutionStatus originalStatus) {}

    public record PathAggregate(
            MaterialPathKey key,
            long count,
            long firstFrameGeneration,
            long lastFrameGeneration,
            long firstEventSequence,
            long lastEventSequence) {}

    public record LifecycleSummary(Diagnostics diagnostics, List<PathAggregate> paths) {
        public LifecycleSummary {
            paths = List.copyOf(paths);
        }

        public boolean hasEvents() {
            return diagnostics.materialCharacterizationEvents() > 0;
        }
    }

    public record Diagnostics(
            long materialCharacterizationEvents,
            long materialDirectUniquePaths,
            long materialDirectReboundPaths,
            long materialUnresolvedPaths,
            long materialCapacityRejectedPaths,
            long materialResolutionsBeforeAnyProvider,
            long materialUnresolvedProbeRegistrations,
            long materialUnresolvedProbeCapacityRejections,
            long materialUnresolvedThenRegistered,
            long materialUnresolvedNeverRegistered,
            long materialImmediateProviderResolutions,
            long materialOutlineProviderResolutions,
            long materialOtherVerifiedProviderResolutions,
            long materialPathKeyRegistrations,
            long materialPathKeyRefreshes,
            long materialPathKeyCapacityRejections,
            long materialPathOverflowEvents,
            long materialCharacterizationFailures,
            long materialProviderEvents,
            int unresolvedProbesThisFrame,
            int retainedPathKeys,
            long lastMaterialEventSequence,
            long frameGeneration) {}

    private static final class UnresolvedProbe {
        private final String consumerClass;
        private final PendingBucket before = new PendingBucket();
        private final PendingBucket after = new PendingBucket();

        private UnresolvedProbe(String consumerClass) {
            this.consumerClass = consumerClass;
        }

        private void record(long sequence, boolean beforeFirstProvider) {
            (beforeFirstProvider ? before : after).record(sequence);
        }

        private long totalOccurrences() {
            return add(before.count, after.count);
        }

        private long lastEventSequence() {
            return Math.max(before.lastEvent, after.lastEvent);
        }
    }

    private static final class PendingBucket {
        private long count;
        private long firstEvent;
        private long lastEvent;

        private void record(long sequence) {
            if (count == 0) firstEvent = sequence;
            count = add(count, 1);
            lastEvent = sequence;
        }
    }

    private static final class MutablePathAggregate {
        private final MaterialPathKey key;
        private long count;
        private final long firstFrame;
        private long lastFrame;
        private final long firstEvent;
        private long lastEvent;

        private MutablePathAggregate(MaterialPathKey key, long count, long frame, long firstEvent, long lastEvent) {
            this.key = key;
            this.count = count;
            this.firstFrame = frame;
            this.lastFrame = frame;
            this.firstEvent = firstEvent;
            this.lastEvent = lastEvent;
        }

        private void refresh(long occurrences, long frame, long event) {
            count = add(count, occurrences);
            lastFrame = frame;
            lastEvent = event;
        }

        private PathAggregate snapshot() {
            return new PathAggregate(key, count, firstFrame, lastFrame, firstEvent, lastEvent);
        }
    }

    private final class DirectPath {
        private final MaterialResolutionStatus status;
        private final MaterialPathDiagnosticData data;
        private final boolean beforeFirstProvider;
        private final MaterialPathKey key;

        private DirectPath(
                MaterialResolutionStatus status,
                MaterialPathDiagnosticData data,
                boolean beforeFirstProvider,
                MaterialPathKey key) {
            this.status = status;
            this.data = data;
            this.beforeFirstProvider = beforeFirstProvider;
            this.key = key;
        }
    }
}
