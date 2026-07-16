package dev.alex.threadium.benchmark;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class BenchmarkFileNames {
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC);
    private BenchmarkFileNames(){}
    public static String base(Instant timestamp,ModelPartBenchmarkMode mode,String scene,int trial){return TIME.format(timestamp)+'_'+mode.name().toLowerCase(java.util.Locale.ROOT)+'_'+sanitize(scene)+"_trial-"+String.format(java.util.Locale.ROOT,"%02d",trial);}
    static String sanitize(String value){String clean=value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]+","-").replaceAll("^-+|-+$","");return clean.isEmpty()?"scene":clean;}
}
