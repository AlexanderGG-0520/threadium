package dev.alex.threadium.benchmark;

import java.util.ArrayList;
import java.util.List;

/** Pure formatting and countdown policy for lifecycle notifications. */
public final class BenchmarkCompletionNotice {
    public static final long AUTO_EXIT_DELAY_NANOS=5_000_000_000L;
    private BenchmarkCompletionNotice(){}
    public static boolean autoExitEnabled(String value){return Boolean.parseBoolean(value==null?"false":value);}
    public static List<String> complete(String status,String mode,String scene,int trial,int samples,String path,List<String> reasons,boolean autoExit){ArrayList<String> lines=new ArrayList<>();lines.add("[Threadium Benchmark] Benchmark complete.");lines.add("Status: "+status);lines.add("Mode: "+mode);lines.add("Scene: "+scene);lines.add("Trial: "+trial);lines.add("Samples: "+samples);lines.add("Result: "+path);if(!reasons.isEmpty()){lines.add("Invalid reasons:");for(String reason:reasons)lines.add("- "+reason);}lines.add(autoExit?"Minecraft will close automatically in 5 seconds.":"You may exit Minecraft now.");return List.copyOf(lines);}
    public static List<String> aborted(String path){return List.of("[Threadium Benchmark] Benchmark aborted.","Result: "+path,"Camera and movement controls have been restored.");}
    public static String actionBar(String status){return status.equals("ABORTED")?"Threadium benchmark aborted":"Threadium benchmark complete — "+status+" — You may exit now";}
}
