package dev.alex.threadium.validation;

public final class DifferentialResultPolicy {
    private DifferentialResultPolicy() {}

    public enum State {
        PASS,
        FAIL,
        NOT_EXECUTED,
        HARNESS_ERROR
    }

    public record Evidence(
            boolean fixtureInitialized,
            boolean resourcesPresent,
            boolean referenceSucceeded,
            boolean candidateSucceeded,
            boolean isolationVerified,
            boolean poseBitsIdentical,
            long encountered,
            long accepted,
            long fallback,
            long backendFailures,
            long submissionFailures,
            DifferentialImageComparator.Metrics color,
            DifferentialImageComparator.Metrics depthOrOutline) {}

    public static State classify(Evidence evidence) {
        if (!evidence.fixtureInitialized
                || !evidence.resourcesPresent
                || !evidence.referenceSucceeded
                || !evidence.candidateSucceeded
                || !evidence.isolationVerified
                || !evidence.poseBitsIdentical) return State.HARNESS_ERROR;
        if (evidence.encountered == 0
                || evidence.accepted == 0
                || evidence.fallback != 0
                || evidence.backendFailures != 0
                || evidence.submissionFailures != 0) return State.FAIL;
        if (evidence.color != null && !evidence.color.passes()
                || evidence.depthOrOutline != null && !evidence.depthOrOutline.passes()) return State.FAIL;
        return State.PASS;
    }
}
