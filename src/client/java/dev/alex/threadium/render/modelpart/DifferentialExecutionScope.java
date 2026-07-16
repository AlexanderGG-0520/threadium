package dev.alex.threadium.render.modelpart;

/** Render-thread-only interception policy used by the differential harness. */
public final class DifferentialExecutionScope {
    public enum Mode { NORMAL, REFERENCE_BYPASS, CANDIDATE_REQUIRE_ACCEPT }
    public record Snapshot(Mode mode,String expected,long observed,long accepted,long fallback,long suppressions,String fallbackReason,long totalAccepted,long totalFallback){}
    private static Mode mode=Mode.NORMAL; private static String expected; private static Thread owner;
    private static long observed,accepted,fallback,suppressions,totalAccepted,totalFallback; private static String fallbackReason;
    private DifferentialExecutionScope(){}
    public static Token enter(Mode requested,String canonical){if(requested==Mode.NORMAL)throw new IllegalArgumentException("NORMAL is not a scope");if(mode!=Mode.NORMAL)throw new IllegalStateException("Nested differential scope: "+mode);mode=requested;expected=canonical;owner=Thread.currentThread();observed=accepted=fallback=suppressions=totalAccepted=totalFallback=0;fallbackReason=null;return new Token(requested,canonical);}
    public static boolean referenceBypass(String canonical){checkOwner();if(mode!=Mode.REFERENCE_BYPASS)return false;if(matches(canonical))observed++;return true;}
    public static void completed(String canonical,ModelPartInterceptionResult result){checkOwner();if(mode!=Mode.CANDIDATE_REQUIRE_ACCEPT)return;if(result==ModelPartInterceptionResult.GPU_REPLACED)totalAccepted++;else totalFallback++;if(!matches(canonical))return;observed++;if(result==ModelPartInterceptionResult.GPU_REPLACED)accepted++;else fallback++;}
    public static void suppression(){checkOwner();if(mode==Mode.CANDIDATE_REQUIRE_ACCEPT)suppressions++;}
    public static void fallbackReason(String reason){checkOwner();if(mode==Mode.CANDIDATE_REQUIRE_ACCEPT&&fallbackReason==null)fallbackReason=reason;}
    public static Snapshot snapshot(){return new Snapshot(mode,expected,observed,accepted,fallback,suppressions,fallbackReason,totalAccepted,totalFallback);}
    public static boolean active(){return mode!=Mode.NORMAL;}
    public static void reset(){mode=Mode.NORMAL;expected=null;owner=null;observed=accepted=fallback=suppressions=totalAccepted=totalFallback=0;fallbackReason=null;}
    private static boolean matches(String canonical){return expected!=null&&expected.equals(canonical);}
    private static void checkOwner(){if(mode!=Mode.NORMAL&&owner!=Thread.currentThread())throw new IllegalStateException("Differential scope accessed off its render thread");}
    public static final class Token implements AutoCloseable {private final Mode entered;private final String expected;private boolean closed;private Token(Mode entered,String expected){this.entered=entered;this.expected=expected;}public Snapshot result(){if(closed)throw new IllegalStateException("scope closed");checkOwner();return snapshot();}@Override public void close(){if(closed)return;checkOwner();if(mode!=entered||!java.util.Objects.equals(DifferentialExecutionScope.expected,expected))throw new IllegalStateException("scope ownership changed");closed=true;reset();}}
}
