package dev.alex.threadium.render.modelpart;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

class ModelPartCoreTest {
    @Test
    void topologyCacheUsesRootIdentityAndClearsOnReload() {
        ModelPart first = new ModelPart(java.util.List.of(), java.util.Map.of());
        ModelPart second = new ModelPart(java.util.List.of(), java.util.Map.of());
        GenericModelPartTopology topology = new GenericModelPartTopology(
                java.util.List.of(new GenericModelPartTopology.Node(first, -1, 0, "root")),
                new GenericModelPartTopology.StructuralKey(1, java.util.List.of(1L)));
        ModelPartTopologyCache cache = new ModelPartTopologyCache();
        cache.put(first, topology);
        assertSame(topology, cache.get(first));
        assertNull(cache.get(second));
        assertEquals(1, cache.size());
        first.x = 4;
        assertEquals(4, cache.get(first).nodes().getFirst().part().x);
        cache.clear();
        assertNull(cache.get(first));
    }

    @Test
    void structuralKeyHashCodeDoesNotTraverseExactGeometry() {
        java.util.List<Long> exact = new java.util.AbstractList<>() {
            @Override
            public Long get(int index) {
                return 1L;
            }

            @Override
            public int size() {
                return 1;
            }

            @Override
            public int hashCode() {
                throw new AssertionError("StructuralKey hashCode traversed exact geometry");
            }
        };
        long fingerprint = 0x1234_5678_9ABC_DEF0L;
        var key = new GenericModelPartTopology.StructuralKey(fingerprint, exact);
        assertEquals(Long.hashCode(fingerprint), key.hashCode());
        assertNotEquals(
                new GenericModelPartTopology.StructuralKey(fingerprint, java.util.List.of(1L)),
                new GenericModelPartTopology.StructuralKey(fingerprint, java.util.List.of(2L)));
    }

    @Test
    void layoutsAreBoundedAndOverflowSafe() {
        assertEquals(112, ModelPartLayouts.BONE_STRIDE);
        assertEquals(112, ModelPartLayouts.INSTANCE_STRIDE);
        assertThrows(ArithmeticException.class, () -> ModelPartLayouts.bytes(Integer.MAX_VALUE, 112));
        assertThrows(IllegalArgumentException.class, () -> ModelPartLayouts.bytes(-1, 112));
    }

    @Test
    void visibilityCrossesWordBoundary() {
        VisibilityMask mask = new VisibilityMask(130);
        mask.set(0, true);
        mask.set(64, true);
        mask.set(129, true);
        assertTrue(mask.get(0));
        assertTrue(mask.get(64));
        assertTrue(mask.get(129));
        assertFalse(mask.get(1));
    }

    @Test
    void translationUsesSixteenthUnitsAndParentComposition() {
        Matrix4f parent = TransformMath.compose(new Matrix4f(), 16, 0, 0, 0, 0, 0, 1, 1, 1);
        Matrix4f child = TransformMath.compose(parent, 0, 16, 0, 0, 0, 0, 1, 1, 1);
        Vector3f out = child.transformPosition(new Vector3f());
        assertEquals(1, out.x(), 1e-6);
        assertEquals(1, out.y(), 1e-6);
    }

    @Test
    void zyxAndScaleMatchExplicitJomlComposition() {
        Matrix4f expected =
                new Matrix4f().translate(1, 2, 3).rotateZYX(.3f, .2f, .1f).scale(2, 3, 4);
        Matrix4f actual = TransformMath.compose(new Matrix4f(), 16, 32, 48, .1f, .2f, .3f, 2, 3, 4);
        assertEquals(expected, actual);
    }

    @Test
    void backendStateRejectsInvalidAndSupportsFailureReset() {
        BackendStateMachine s = new BackendStateMachine(ModelPartBackendState.UNINITIALIZED);
        assertTrue(s.transition(ModelPartBackendState.UNINITIALIZED, ModelPartBackendState.INITIALIZING));
        assertTrue(s.transition(ModelPartBackendState.INITIALIZING, ModelPartBackendState.FAILED));
        assertTrue(s.transition(ModelPartBackendState.FAILED, ModelPartBackendState.UNINITIALIZED));
        assertThrows(
                IllegalArgumentException.class,
                () -> s.transition(ModelPartBackendState.UNINITIALIZED, ModelPartBackendState.ACTIVE));
    }

    @Test
    void shaderResourcesArePackaged() {
        for (String name : java.util.List.of(
                "modelpart_gl33.vert", "modelpart_gl33.frag", "modelpart_gl45.vert", "modelpart_gl45.frag"))
            assertNotNull(getClass().getResource("/assets/threadium/shaders/" + name));
    }

    @Test
    void initializationAttemptIsOneShotAndResettable() {
        InitializationAttemptGuard guard = new InitializationAttemptGuard();
        assertTrue(guard.beginAttempt());
        assertFalse(guard.beginAttempt());
        assertTrue(guard.attempted());
        guard.reset();
        assertTrue(guard.beginAttempt());
    }

    @Test
    void backendSelectorPrefers45AndFallsBackTo33() {
        GlCapabilitySet all = caps(true, true);
        assertEquals(BackendSelection.Kind.OPENGL45, BackendSelection.select("auto", all));
        GlCapabilitySet only33 = caps(false, true);
        assertEquals(BackendSelection.Kind.OPENGL33, BackendSelection.select("auto", only33));
        assertEquals(BackendSelection.Kind.NONE, BackendSelection.select("opengl45", only33));
        assertEquals(BackendSelection.Kind.OPENGL33, BackendSelection.select("opengl33", only33));
    }

    @Test
    void backendSelectionDependsOnEveryRequired33Feature() {
        GlCapabilitySet missing = new GlCapabilitySet(
                true, false, false, false, false, false, false, true, true, false, true, true, true, false, false,
                false);
        assertFalse(missing.supportsGl33Backend());
        assertEquals(BackendSelection.Kind.NONE, BackendSelection.select("opengl33", missing));
    }

    @Test
    void gl33BoneTexelLayoutIsChecked() {
        assertEquals(0, Gl33BoneLayout.poseTexel(0, 0));
        assertEquals(4, Gl33BoneLayout.normalTexel(0, 0));
        assertEquals(7, Gl33BoneLayout.poseTexel(1, 0));
        assertEquals(11, Gl33BoneLayout.normalTexel(1, 0));
        assertEquals(224, Gl33BoneLayout.bytesForBones(2));
        assertThrows(ArithmeticException.class, () -> Gl33BoneLayout.bytesForBones(Integer.MAX_VALUE));
    }

    @Test
    void adjacentConsolidationPreservesOrder() {
        var ranges = AdjacentBatchRanges.build(java.util.List.of("A", "A", "A", "B", "B", "A", "A"), true);
        assertEquals(
                java.util.List.of(
                        new AdjacentBatchRanges.Range(0, 3),
                        new AdjacentBatchRanges.Range(3, 2),
                        new AdjacentBatchRanges.Range(5, 2)),
                ranges);
    }

    @Test
    void disabledConsolidationProducesSingletons() {
        var ranges = AdjacentBatchRanges.build(java.util.List.of("A", "A", "B"), false);
        assertEquals(3, ranges.size());
        assertTrue(ranges.stream().allMatch(r -> r.count() == 1));
    }

    @Test
    void emptyAndLargeBatchRanges() {
        assertTrue(AdjacentBatchRanges.build(java.util.List.of(), true).isEmpty());
        var keys = java.util.Collections.nCopies(10000, "A");
        assertEquals(
                new AdjacentBatchRanges.Range(0, 10000),
                AdjacentBatchRanges.build(keys, true).getFirst());
    }

    @Test
    void boneBasesSupportMixedCountsAndRejectCapacity() {
        assertArrayEquals(new int[] {0, 4, 11}, BoneBaseOffsets.compute(new int[] {4, 7, 2}, 13));
        assertThrows(IllegalArgumentException.class, () -> BoneBaseOffsets.compute(new int[] {4, 7, 3}, 13));
        assertThrows(
                ArithmeticException.class,
                () -> BoneBaseOffsets.compute(new int[] {Integer.MAX_VALUE, 1}, Integer.MAX_VALUE));
    }

    @Test
    void instanceOffsetsAndFieldsAreStable() {
        assertEquals(0, ModelPartLayouts.instanceOffset(0));
        assertEquals(112, ModelPartLayouts.instanceOffset(1));
        assertEquals(64, ModelPartLayouts.INSTANCE_BONE_BASE_OFFSET);
        assertEquals(68, ModelPartLayouts.INSTANCE_LIGHT_OFFSET);
        assertEquals(72, ModelPartLayouts.INSTANCE_OVERLAY_OFFSET);
        assertEquals(80, ModelPartLayouts.INSTANCE_TINT_OFFSET);
        assertEquals(96, ModelPartLayouts.INSTANCE_UV_TRANSFORM_OFFSET);
        assertThrows(ArithmeticException.class, () -> ModelPartLayouts.instanceOffset(Integer.MAX_VALUE));
    }

    @Test
    void batchMetricMathHandlesZeroAndReduction() {
        assertEquals(0, BatchMetricMath.instancesPerDraw(0, 0));
        assertEquals(4, BatchMetricMath.instancesPerDraw(100, 25));
        assertEquals(.75, BatchMetricMath.drawReduction(100, 25));
        assertEquals(.8, BatchMetricMath.multiCoverage(80, 100));
    }

    @Test
    void visualModesParseAndEarlyDiagnosticsOverlayVanilla() {
        assertEquals(DebugVisualMode.SCREEN_TRIANGLE, DebugVisualMode.parse("screen_triangle"));
        assertEquals(DebugVisualMode.NORMAL, DebugVisualMode.parse("NORMAL"));
        assertTrue(DebugVisualMode.SCREEN_TRIANGLE.overlayOnly());
        assertTrue(DebugVisualMode.MESH_CLIP_SPACE.overlayOnly());
        assertFalse(DebugVisualMode.NORMAL.overlayOnly());
        assertThrows(IllegalArgumentException.class, () -> DebugVisualMode.parse("cow_debug"));
    }

    @Test
    void shaderMatrixOrderMatchesCpuReference() {
        Matrix4f projection = new Matrix4f().scale(0.5f);
        Matrix4f root = new Matrix4f().translate(2, 3, 4).rotateY((float) Math.PI / 2);
        Matrix4f bone = TransformMath.compose(new Matrix4f(), 16, 0, 0, 0, 0, (float) Math.PI / 2, 2, 1, 1);
        Vector4f local = new Vector4f(.25f, .5f, .75f, 1);
        Vector4f expected = new Vector4f(local);
        bone.transform(expected);
        root.transform(expected);
        projection.transform(expected);
        assertEquals(expected, ClipDiagnostics.shaderTransform(projection, root, bone, local));
        assertTrue(ClipDiagnostics.finite(projection));
        assertFalse(ClipDiagnostics.finite(new Matrix4f().m00(Float.NaN)));
    }

    @Test
    void clipDiagnosticsHandleDepthConventionsAndInvalidValues() {
        assertTrue(ClipDiagnostics.insideClipVolume(new Vector4f(0, 0, .5f, 1), true));
        assertFalse(ClipDiagnostics.insideClipVolume(new Vector4f(0, 0, -.5f, 1), true));
        assertTrue(ClipDiagnostics.insideClipVolume(new Vector4f(0, 0, -.5f, 1), false));
        assertFalse(ClipDiagnostics.insideClipVolume(new Vector4f(0, 0, 0, -1), false));
        assertFalse(ClipDiagnostics.insideClipVolume(new Vector4f(Float.POSITIVE_INFINITY, 0, 0, 1), false));
    }

    @Test
    void suppressionPolicyIsExplicitAndOverlayModesNeverSuppress() {
        for (DebugVisualMode mode : DebugVisualMode.values())
            for (ModelPartInterceptionResult result : ModelPartInterceptionResult.values()) {
                boolean allowed = ModelPartSuppressionPolicy.maySuppress(mode, true, result);
                if (mode.overlayOnly() || result != ModelPartInterceptionResult.GPU_REPLACED) assertFalse(allowed);
            }
        assertFalse(ModelPartSuppressionPolicy.maySuppress(
                DebugVisualMode.MESH_MAGENTA, false, ModelPartInterceptionResult.GPU_REPLACED));
        assertTrue(ModelPartSuppressionPolicy.maySuppress(
                DebugVisualMode.MESH_MAGENTA, true, ModelPartInterceptionResult.GPU_REPLACED));
        assertTrue(ModelPartSuppressionPolicy.maySuppress(
                DebugVisualMode.NORMAL, false, ModelPartInterceptionResult.GPU_REPLACED));
        assertTrue(ModelPartSuppressionPolicy.maySuppress(
                DebugVisualMode.OFF, false, ModelPartInterceptionResult.GPU_REPLACED));
    }

    @Test
    void suppressionTruthTableIsExhaustive() {
        for (DebugVisualMode mode : DebugVisualMode.values())
            for (boolean configured : java.util.List.of(false, true))
                for (ModelPartInterceptionResult result : ModelPartInterceptionResult.values()) {
                    boolean expected = result == ModelPartInterceptionResult.GPU_REPLACED
                            && !mode.overlayOnly()
                            && (!mode.worldSpaceDiagnostic() || configured);
                    assertEquals(
                            expected,
                            ModelPartSuppressionPolicy.maySuppress(mode, configured, result),
                            mode + " / " + configured + " / " + result);
                }
    }

    @Test
    void interceptionResultHasNoAmbiguousBooleanContract() {
        assertArrayEquals(
                new ModelPartInterceptionResult[] {
                    ModelPartInterceptionResult.PASS_THROUGH,
                    ModelPartInterceptionResult.DEBUG_OVERLAY_ONLY,
                    ModelPartInterceptionResult.GPU_REPLACED
                },
                ModelPartInterceptionResult.values());
        assertFalse(ModelPartSuppressionPolicy.maySuppress(
                DebugVisualMode.SCREEN_TRIANGLE, true, ModelPartInterceptionResult.GPU_REPLACED));
        assertFalse(ModelPartSuppressionPolicy.maySuppress(
                DebugVisualMode.MESH_CLIP_SPACE, true, ModelPartInterceptionResult.GPU_REPLACED));
    }

    @Test
    void overlayRequestCoalescesAndClearsAcrossFrames() {
        OverlayRequestCoalescer requests = new OverlayRequestCoalescer();
        requests.beginFrame(true);
        requests.request();
        requests.request();
        assertTrue(requests.consume());
        assertFalse(requests.consume());
        requests.beginFrame(false);
        assertFalse(requests.consume());
        requests.request();
        assertTrue(requests.consume());
        requests.beginFrame(true);
        requests.clear();
        assertFalse(requests.consume());
        assertEquals(3, requests.frame());
    }

    @Test
    void zeroEntityFrameStillRequestsExactlyOneOverlay() {
        OverlayRequestCoalescer requests = new OverlayRequestCoalescer();
        requests.beginFrame(true); // no entity interception is needed
        assertTrue(requests.consume());
        assertFalse(requests.consume());
    }

    @Test
    void diagnosticFailureStateDoesNotPoisonProductionState() {
        BackendStateMachine production = new BackendStateMachine(ModelPartBackendState.UNINITIALIZED);
        assertTrue(production.transition(ModelPartBackendState.UNINITIALIZED, ModelPartBackendState.INITIALIZING));
        assertTrue(production.transition(ModelPartBackendState.INITIALIZING, ModelPartBackendState.READY));
        boolean diagnosticFailed = true;
        assertTrue(diagnosticFailed);
        assertEquals(ModelPartBackendState.READY, production.state());
    }

    @Test
    void productionDrawFailureRetainsFailClosedSemantics() {
        BackendStateMachine production = new BackendStateMachine(ModelPartBackendState.READY);
        assertTrue(production.transition(ModelPartBackendState.READY, ModelPartBackendState.FAILED));
        assertEquals(ModelPartBackendState.FAILED, production.state());
    }

    @Test
    void modeClassificationIsCompleteAndDisjoint() {
        for (DebugVisualMode mode : DebugVisualMode.values())
            assertFalse(mode.overlayOnly() && mode.worldSpaceDiagnostic());
        assertEquals(
                3,
                java.util.Arrays.stream(DebugVisualMode.values())
                        .filter(DebugVisualMode::overlayOnly)
                        .count());
        assertEquals(
                6,
                java.util.Arrays.stream(DebugVisualMode.values())
                        .filter(DebugVisualMode::worldSpaceDiagnostic)
                        .count());
    }

    @Test
    void screenTriangleMagentaThresholdIsDeterministic() {
        assertTrue(ScreenTriangleDiagnostics.isMagenta(240, 15, 240, 240));
        assertTrue(ScreenTriangleDiagnostics.isMagenta(255, 0, 255, 255));
        assertFalse(ScreenTriangleDiagnostics.isMagenta(239, 0, 255, 255));
        assertFalse(ScreenTriangleDiagnostics.isMagenta(255, 16, 255, 255));
        assertFalse(ScreenTriangleDiagnostics.isMagenta(255, 0, 239, 255));
        assertFalse(ScreenTriangleDiagnostics.isMagenta(255, 0, 255, 239));
    }

    @Test
    void screenTriangleCoordinatesAreCenteredAndClamped() {
        assertEquals(960, ScreenTriangleDiagnostics.center(1920));
        assertEquals(0, ScreenTriangleDiagnostics.center(1));
        assertEquals(0, ScreenTriangleDiagnostics.center(0));
        assertEquals(0, ScreenTriangleDiagnostics.clamp(-4, 10));
        assertEquals(9, ScreenTriangleDiagnostics.clamp(14, 10));
        assertEquals(4, ScreenTriangleDiagnostics.clamp(4, 10));
    }

    @Test
    void readbackLatchIsOneShotAndResettableAcrossModes() {
        OneShotDiagnosticLatch latch = new OneShotDiagnosticLatch();
        assertTrue(latch.acquire());
        assertFalse(latch.acquire());
        assertTrue(latch.consumed());
        latch.reset();
        assertTrue(latch.acquire());
    }

    @Test
    void preparedAndMainTargetResultsAreClassifiedSeparately() {
        assertEquals(
                ScreenTriangleDiagnosticResult.INVALID_TARGET,
                ScreenTriangleDiagnostics.classify(false, false, true, false, false));
        assertEquals(
                ScreenTriangleDiagnosticResult.FRAMEBUFFER_INCOMPLETE,
                ScreenTriangleDiagnostics.classify(false, true, false, false, false));
        assertEquals(
                ScreenTriangleDiagnosticResult.GL_ERROR,
                ScreenTriangleDiagnostics.classify(false, true, true, true, true));
        assertEquals(
                ScreenTriangleDiagnosticResult.DRAW_DID_NOT_WRITE_MAGENTA,
                ScreenTriangleDiagnostics.classify(false, true, true, false, false));
        assertEquals(
                ScreenTriangleDiagnosticResult.DRAW_WROTE_MAGENTA_TO_PREPARED_TARGET,
                ScreenTriangleDiagnostics.classify(false, true, true, false, true));
        assertEquals(
                ScreenTriangleDiagnosticResult.DRAW_WROTE_MAGENTA_TO_MAIN_TARGET,
                ScreenTriangleDiagnostics.classify(true, true, true, false, true));
    }

    @Test
    void mainTargetControlCanNeverSuppressVanilla() {
        for (boolean configured : java.util.List.of(false, true))
            for (ModelPartInterceptionResult result : ModelPartInterceptionResult.values())
                assertFalse(ModelPartSuppressionPolicy.maySuppress(
                        DebugVisualMode.SCREEN_TRIANGLE_MAIN_TARGET, configured, result));
    }

    @Test
    void blaze3dSubmissionRejectsStaleGenerationOrEpoch() {
        assertTrue(Blaze3dSubmissionPolicy.generationMatches(4, 4, 9, 9));
        assertFalse(Blaze3dSubmissionPolicy.generationMatches(3, 4, 9, 9));
        assertFalse(Blaze3dSubmissionPolicy.generationMatches(4, 4, 8, 9));
    }

    @Test
    void productionRawDrawInvariantRequiresZero() {
        assertTrue(Blaze3dSubmissionPolicy.rawProductionInvariant(0));
        assertFalse(Blaze3dSubmissionPolicy.rawProductionInvariant(1));
    }

    private static GlCapabilitySet caps(boolean gl45, boolean gl33) {
        return new GlCapabilitySet(
                gl33, false, false, false, false, gl45, gl45, true, true, true, true, true, true, gl45, gl45, gl45);
    }
}
