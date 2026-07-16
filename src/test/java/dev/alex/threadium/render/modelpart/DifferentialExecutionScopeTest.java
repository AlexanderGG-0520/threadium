package dev.alex.threadium.render.modelpart;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DifferentialExecutionScopeTest {
    @AfterEach void reset(){DifferentialExecutionScope.reset();}

    @Test void referenceBypassObservesWithoutAcceptanceOrSuppression(){
        try(var scope=DifferentialExecutionScope.enter(DifferentialExecutionScope.Mode.REFERENCE_BYPASS,"entity_solid")){
            assertTrue(DifferentialExecutionScope.referenceBypass("entity_solid"));
            var result=scope.result();assertEquals(1,result.observed());assertEquals(0,result.accepted());assertEquals(0,result.suppressions());
        }
        assertFalse(DifferentialExecutionScope.active());
    }
    @Test void candidateRequiresExactDescriptor(){
        try(var scope=DifferentialExecutionScope.enter(DifferentialExecutionScope.Mode.CANDIDATE_REQUIRE_ACCEPT,"entity_solid")){
            DifferentialExecutionScope.completed("entity_cutout",ModelPartInterceptionResult.GPU_REPLACED);
            DifferentialExecutionScope.completed("entity_solid",ModelPartInterceptionResult.GPU_REPLACED);DifferentialExecutionScope.suppression();
            var result=scope.result();assertEquals(1,result.observed());assertEquals(1,result.accepted());assertEquals(1,result.suppressions());
        }
    }
    @Test void candidateFallbackIsRetained(){try(var scope=DifferentialExecutionScope.enter(DifferentialExecutionScope.Mode.CANDIDATE_REQUIRE_ACCEPT,"entity_solid")){DifferentialExecutionScope.completed("entity_solid",ModelPartInterceptionResult.PASS_THROUGH);assertEquals(1,scope.result().fallback());}}
    @Test void nestingIsRejectedAndFinallyRestoresNormal(){
        assertThrows(IllegalStateException.class,()->{try(var ignored=DifferentialExecutionScope.enter(DifferentialExecutionScope.Mode.REFERENCE_BYPASS,"entity_solid")){DifferentialExecutionScope.enter(DifferentialExecutionScope.Mode.CANDIDATE_REQUIRE_ACCEPT,"entity_solid");}});
        assertFalse(DifferentialExecutionScope.active());
    }
    @Test void ownerThreadIsEnforced(){
        try(var ignored=DifferentialExecutionScope.enter(DifferentialExecutionScope.Mode.REFERENCE_BYPASS,"entity_solid")){
            AtomicReference<Throwable> failure=new AtomicReference<>();Thread thread=new Thread(()->{try{DifferentialExecutionScope.referenceBypass("entity_solid");}catch(Throwable t){failure.set(t);}});thread.start();assertDoesNotThrow(()->thread.join());assertInstanceOf(IllegalStateException.class,failure.get());
        }
    }
    @Test void resetClearsLeakedScope(){DifferentialExecutionScope.enter(DifferentialExecutionScope.Mode.REFERENCE_BYPASS,"entity_solid");DifferentialExecutionScope.reset();assertFalse(DifferentialExecutionScope.active());assertEquals(DifferentialExecutionScope.Mode.NORMAL,DifferentialExecutionScope.snapshot().mode());}
}
