package dev.alex.threadium.benchmark;

public final class BenchmarkStartValidationPolicy {
    private BenchmarkStartValidationPolicy(){}
    public static boolean timedOut(boolean pending,long now,long deadline){return pending&&now>=deadline;}
    public static boolean mayEnterSetup(BenchmarkPopulationSnapshot.Combined snapshot,int expected){return snapshot.valid(expected);}
    public static boolean mayWriteMarker(BenchmarkPopulationSnapshot.Server snapshot,int expected){return snapshot.valid(expected);}
}
