package dev.alex.threadium.validation;

/** Allocation-light deterministic color/depth comparison used after async GPU readback. */
public final class DifferentialImageComparator {
    private DifferentialImageComparator() {}

    public record Tolerance(int colorChannel, float depth, boolean compareDepth) {}

    public record Metrics(
            long pixelsCompared,
            long matchingPixels,
            long differingPixels,
            long coverageMaskDifferences,
            int maximumRedDifference,
            int maximumGreenDifference,
            int maximumBlueDifference,
            int maximumAlphaDifference,
            double meanAbsoluteColorDifference,
            long depthPixelsCompared,
            long depthDifferences,
            float maximumDepthDifference) {
        public boolean passes() {
            return differingPixels == 0 && coverageMaskDifferences == 0 && depthDifferences == 0;
        }
    }

    public static Metrics compare(
            int[] reference, int[] candidate, float[] referenceDepth, float[] candidateDepth, Tolerance tolerance) {
        if (reference.length != candidate.length) throw new IllegalArgumentException("color dimensions differ");
        if (tolerance.compareDepth
                && (referenceDepth == null
                        || candidateDepth == null
                        || referenceDepth.length != reference.length
                        || candidateDepth.length != reference.length))
            throw new IllegalArgumentException("depth dimensions differ");
        long matching = 0, differing = 0, masks = 0, depthDifferent = 0;
        int maxR = 0, maxG = 0, maxB = 0, maxA = 0;
        double absolute = 0;
        float maxDepth = 0;
        for (int i = 0; i < reference.length; i++) {
            int a = reference[i], b = candidate[i];
            int da = Math.abs((a >>> 24 & 255) - (b >>> 24 & 255)),
                    dr = Math.abs((a >>> 16 & 255) - (b >>> 16 & 255)),
                    dg = Math.abs((a >>> 8 & 255) - (b >>> 8 & 255)),
                    db = Math.abs((a & 255) - (b & 255));
            maxA = Math.max(maxA, da);
            maxR = Math.max(maxR, dr);
            maxG = Math.max(maxG, dg);
            maxB = Math.max(maxB, db);
            absolute += da + dr + dg + db;
            boolean maskA = (a >>> 24) != 0, maskB = (b >>> 24) != 0;
            if (maskA != maskB) masks++;
            if (da > tolerance.colorChannel
                    || dr > tolerance.colorChannel
                    || dg > tolerance.colorChannel
                    || db > tolerance.colorChannel) differing++;
            else matching++;
            if (tolerance.compareDepth) {
                float delta = Math.abs(referenceDepth[i] - candidateDepth[i]);
                maxDepth = Math.max(maxDepth, delta);
                if (delta > tolerance.depth) depthDifferent++;
            }
        }
        return new Metrics(
                reference.length,
                matching,
                differing,
                masks,
                maxR,
                maxG,
                maxB,
                maxA,
                absolute / (reference.length * 4.0),
                tolerance.compareDepth ? reference.length : 0,
                depthDifferent,
                maxDepth);
    }
}
