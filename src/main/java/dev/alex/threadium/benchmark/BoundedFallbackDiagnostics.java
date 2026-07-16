package dev.alex.threadium.benchmark;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Bounded unique fallback aggregation; recording performs no logging. */
public final class BoundedFallbackDiagnostics {
    private final int capacity;private final LinkedHashMap<Key,MutableEntry> entries=new LinkedHashMap<>();private long omitted;
    public BoundedFallbackDiagnostics(int capacity){if(capacity<1)throw new IllegalArgumentException("capacity");this.capacity=capacity;}
    public synchronized void record(String reason,String pipeline,String renderType,String modelClass,String validity){Key key=new Key(clean(reason),clean(pipeline),clean(renderType),clean(modelClass),clean(validity));MutableEntry entry=entries.get(key);if(entry!=null){entry.count++;return;}if(entries.size()>=capacity){omitted++;return;}entries.put(key,new MutableEntry(key));}
    public synchronized Snapshot snapshotAndReset(){ArrayList<Entry> copy=new ArrayList<>(entries.size());for(MutableEntry entry:entries.values())copy.add(new Entry(entry.key.reason,entry.key.pipeline,entry.key.renderType,entry.key.modelClass,entry.key.validity,entry.count));Snapshot result=new Snapshot(List.copyOf(copy),omitted);entries.clear();omitted=0;return result;}
    private static String clean(Object value){return value==null?"unknown":value.toString();}
    private record Key(String reason,String pipeline,String renderType,String modelClass,String validity){}
    private static final class MutableEntry{private final Key key;private long count=1;private MutableEntry(Key key){this.key=key;}}
    public record Entry(String reason,String pipeline,String renderType,String modelClass,String validity,long count){}
    public record Snapshot(List<Entry> entries,long omittedUniqueOccurrences){public static Snapshot empty(){return new Snapshot(List.of(),0);}}
}
