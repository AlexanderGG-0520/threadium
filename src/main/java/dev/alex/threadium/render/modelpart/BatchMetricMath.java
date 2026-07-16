package dev.alex.threadium.render.modelpart;

public final class BatchMetricMath {
    private BatchMetricMath() {}
    public static double instancesPerDraw(long instances,long draws){return draws==0?0d:(double)instances/draws;}
    public static double drawReduction(long instances,long draws){return instances==0?0d:1d-(double)draws/instances;}
    public static double multiCoverage(long multiInstances,long instances){return instances==0?0d:(double)multiInstances/instances;}
}
