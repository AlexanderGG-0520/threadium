package dev.alex.threadium.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DifferentialResultPolicyTest {
    private static DifferentialResultPolicy.Evidence evidence(long accepted,long fallback,long backend,long submission,DifferentialImageComparator.Metrics metrics){return new DifferentialResultPolicy.Evidence(true,true,true,true,true,true,1,accepted,fallback,backend,submission,metrics,null);}
    private static DifferentialImageComparator.Metrics pass(){return DifferentialImageComparator.compare(new int[]{0xff000000},new int[]{0xff000000},null,null,new DifferentialImageComparator.Tolerance(0,0,false));}
    @Test void completeMatchingEvidencePasses(){assertEquals(DifferentialResultPolicy.State.PASS,DifferentialResultPolicy.classify(evidence(1,0,0,0,pass())));}
    @Test void fallbackPreventsPass(){assertEquals(DifferentialResultPolicy.State.FAIL,DifferentialResultPolicy.classify(evidence(1,1,0,0,pass())));}
    @Test void backendAndSubmissionFailuresPreventPass(){assertEquals(DifferentialResultPolicy.State.FAIL,DifferentialResultPolicy.classify(evidence(1,0,1,0,pass())));assertEquals(DifferentialResultPolicy.State.FAIL,DifferentialResultPolicy.classify(evidence(1,0,0,1,pass())));}
    @Test void missingResourceIsHarnessError(){var e=new DifferentialResultPolicy.Evidence(true,false,false,false,false,false,0,0,0,0,0,null,null);assertEquals(DifferentialResultPolicy.State.HARNESS_ERROR,DifferentialResultPolicy.classify(e));}
    @Test void userNotesCannotOverrideMismatch(){var mismatch=DifferentialImageComparator.compare(new int[]{0xff000000},new int[]{0xffffffff},null,null,new DifferentialImageComparator.Tolerance(0,0,false));assertEquals(DifferentialResultPolicy.State.FAIL,DifferentialResultPolicy.classify(evidence(1,0,0,0,mismatch)));}
}
