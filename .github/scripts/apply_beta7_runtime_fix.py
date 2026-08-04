from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}: {old[:80]!r}")
    p.write_text(text.replace(old, new, 1))


# Report the version from Fabric metadata instead of a stale source constant.
replace_once(
    "src/client/java/dev/alex/threadium/ThreadiumClient.java",
    "import net.fabricmc.api.ClientModInitializer;\n",
    "import net.fabricmc.api.ClientModInitializer;\nimport net.fabricmc.loader.api.FabricLoader;\n",
)
replace_once(
    "src/client/java/dev/alex/threadium/ThreadiumClient.java",
    '        LOGGER.info("Threadium {} initialized", "0.1.0-SNAPSHOT");\n',
    '''        String version = FabricLoader.getInstance()\n                .getModContainer(MOD_ID)\n                .map(container -> container.getMetadata().getVersion().getFriendlyString())\n                .orElse("unknown");\n        LOGGER.info("Threadium {} initialized", version);\n''',
)

# Use the existing hot-path switch consistently for staged-vertex and timing metrics.
replace_once(
    "src/client/java/dev/alex/threadium/metrics/ThreadiumMetrics.java",
    "        StagedVertexMetrics.configure(config.metricsEnabled());\n",
    "        StagedVertexMetrics.configure(config.metricsEnabled() && hotPathMetricsEnabled());\n",
)
replace_once(
    "src/client/java/dev/alex/threadium/metrics/ThreadiumMetrics.java",
    "    private static boolean hotPathMetricsEnabled() {\n",
    "    public static boolean hotPathMetricsEnabled() {\n",
)
replace_once(
    "src/client/java/dev/alex/threadium/metrics/ThreadiumMetrics.java",
    "        if (metrics == null || !metrics.runtimeConfig.metricsEnabled()) return;\n        switch (metric) {\n",
    "        if (metrics == null || !hotPathMetricsEnabled() || !metrics.runtimeConfig.metricsEnabled()) return;\n        switch (metric) {\n",
)
replace_once(
    "src/client/java/dev/alex/threadium/metrics/ThreadiumMetrics.java",
    "        StagedVertexMetrics.configure(snapshot.metricsEnabled());\n",
    "        StagedVertexMetrics.configure(snapshot.metricsEnabled() && hotPathMetricsEnabled());\n",
)

# Make detailed ModelPart counters no-op unless explicitly requested or a benchmark is active.
metrics_path = Path("src/client/java/dev/alex/threadium/render/modelpart/ModelPartGpuMetrics.java")
metrics = metrics_path.read_text()
metrics = metrics.replace(
    "package dev.alex.threadium.render.modelpart;\n\n",
    "package dev.alex.threadium.render.modelpart;\n\nimport dev.alex.threadium.metrics.ThreadiumMetrics;\n",
    1,
)
metrics = metrics.replace("new LongAdder()", "hotCounter()")
marker = "public final class ModelPartGpuMetrics {\n"
if metrics.count(marker) != 1:
    raise SystemExit("ModelPartGpuMetrics class marker mismatch")
metrics = metrics.replace(
    marker,
    '''public final class ModelPartGpuMetrics {\n    private static final class HotPathLongAdder extends LongAdder {\n        @Override\n        public void add(long value) {\n            if (detailedMetricsEnabled()) super.add(value);\n        }\n    }\n\n    static boolean detailedMetricsEnabled() {\n        return ThreadiumMetrics.hotPathMetricsEnabled();\n    }\n\n    private static LongAdder hotCounter() {\n        return new HotPathLongAdder();\n    }\n\n''',
    1,
)
for signature in (
    "    void pipelineAccepted(ModelPartPipelineDescriptor descriptor) {\n",
    "    void pipelineFallback(ModelPartPipelineDescriptor descriptor) {\n",
    "    void pipelineDrawCommand(ModelPartPipelineDescriptor descriptor) {\n",
    "    void pipelineInstances(ModelPartPipelineDescriptor descriptor, int instances, int maximumBatch) {\n",
):
    if metrics.count(signature) != 1:
        raise SystemExit(f"ModelPartGpuMetrics signature mismatch: {signature!r}")
    metrics = metrics.replace(signature, signature + "        if (!detailedMetricsEnabled()) return;\n", 1)
metrics_path.write_text(metrics)

# Avoid the remaining atomic maximum update when detailed counters are disabled.
replace_once(
    "src/client/java/dev/alex/threadium/render/modelpart/SelectingModelPartGpuBackend.java",
    "        metrics.maximumInstancesPerDraw.accumulateAndGet(stats.maximumInstancesPerDraw(), Math::max);\n",
    "        if (ModelPartGpuMetrics.detailedMetricsEnabled())\n            metrics.maximumInstancesPerDraw.accumulateAndGet(stats.maximumInstancesPerDraw(), Math::max);\n",
)

# Expose a minimal production snapshot and clearly mark detailed metrics as disabled.
replace_once(
    "src/client/java/dev/alex/threadium/render/modelpart/ModelPartRenderService.java",
    "    public String metricsSnapshot() {\n        long drawn = metrics.drawnInstances.sumThenReset(),\n",
    '''    public String metricsSnapshot() {\n        if (!ModelPartGpuMetrics.detailedMetricsEnabled())\n            return "gpuEntity={state=" + backend.state() + ",selectedBackend=" + backend.selected()\n                    + ",detailedMetrics=false,cachedMeshes=" + cache.size() + ",meshGpuBytes=" + cache.bytes() + '}';\n        long drawn = metrics.drawnInstances.sumThenReset(),\n''',
)
replace_once(
    "src/client/java/dev/alex/threadium/render/modelpart/ModelPartRenderService.java",
    "        return \"gpuEntity={state=\" + backend.state() + \",selectedBackend=\" + backend.selected()\n",
    "        return \"gpuEntity={state=\" + backend.state() + \",selectedBackend=\" + backend.selected()\n                + \",detailedMetrics=true\"\n",
)
replace_once(
    "src/client/java/dev/alex/threadium/render/modelpart/ModelPartRenderService.java",
    "    public String visualMetricsSnapshot() {\n        return VisualDiagnosticMetrics.snapshot(DebugVisualMode.parse(config.gpuDebugVisualMode()))\n",
    '''    public String visualMetricsSnapshot() {\n        if (!ModelPartGpuMetrics.detailedMetricsEnabled())\n            return VisualDiagnosticMetrics.snapshot(DebugVisualMode.parse(config.gpuDebugVisualMode()))\n                    + ",modelPartDetailedMetrics=false";\n        return VisualDiagnosticMetrics.snapshot(DebugVisualMode.parse(config.gpuDebugVisualMode()))\n                + ",modelPartDetailedMetrics=true"\n''',
)

# Consolidate consecutive unsorted plans sharing the same attachments and scissor state into one RenderPass.
backend = "src/client/java/dev/alex/threadium/render/modelpart/Blaze3dModelPartBackend.java"
replace_once(
    backend,
    '''            for (int planIndex = 0; planIndex < planCount; planIndex++) {\n                PlannedDraw plan = plannedDraws.get(planIndex);\n                if (plan.sorted) {\n                    int planSortedCalls = submitSorted(encoder, plan, uploadedInstances, packedIndexBySource);\n                    calls += planSortedCalls;\n                    sortedInstances += plan.sourceCount;\n                    sortedCalls += planSortedCalls;\n                } else {\n                    InstanceSubmissionOrderPlanner.Plan submission = plan.submission;\n                    submitBatches(encoder, plan, uploadedInstances);\n                    for (int batch = 0; batch < submission.batchCount(); batch++) {\n                        int count = submission.instanceCounts()[batch];\n                        calls++;\n                        batchableCalls++;\n                        batchableInstances += count;\n                        maximum = Math.max(maximum, count);\n                        if (count == 1) singletons++;\n                        else {\n                            multi++;\n                            multiInstances += count;\n                        }\n                    }\n                }\n            }\n''',
    '''            int planIndex = 0;\n            while (planIndex < planCount) {\n                PlannedDraw plan = plannedDraws.get(planIndex);\n                if (plan.sorted) {\n                    int planSortedCalls = submitSorted(encoder, plan, uploadedInstances, packedIndexBySource);\n                    calls += planSortedCalls;\n                    sortedInstances += plan.sourceCount;\n                    sortedCalls += planSortedCalls;\n                    planIndex++;\n                    continue;\n                }\n                int runEnd = compatibleUnsortedRunEnd(planIndex, planCount);\n                submitBatchRun(encoder, planIndex, runEnd, uploadedInstances);\n                for (int runIndex = planIndex; runIndex < runEnd; runIndex++) {\n                    InstanceSubmissionOrderPlanner.Plan submission = plannedDraws.get(runIndex).submission;\n                    for (int batch = 0; batch < submission.batchCount(); batch++) {\n                        int count = submission.instanceCounts()[batch];\n                        calls++;\n                        batchableCalls++;\n                        batchableInstances += count;\n                        maximum = Math.max(maximum, count);\n                        if (count == 1) singletons++;\n                        else {\n                            multi++;\n                            multiInstances += count;\n                        }\n                    }\n                }\n                planIndex = runEnd;\n            }\n''',
)
replace_once(
    backend,
    '''    private void submitBatches(\n            com.mojang.blaze3d.systems.CommandEncoder encoder, PlannedDraw plan, GpuBufferSlice uploadedInstances) {\n        PreparedRenderType prepared = plan.prepared;\n        RenderTarget target = prepared.outputTarget().getRenderTarget();\n        var color = RenderSystem.outputColorTextureOverride != null\n                ? RenderSystem.outputColorTextureOverride\n                : target.getColorTextureView();\n        var depth = target.useDepth\n                ? (RenderSystem.outputDepthTextureOverride != null\n                        ? RenderSystem.outputDepthTextureOverride\n                        : target.getDepthTextureView())\n                : null;\n        try (RenderPass pass = encoder.createRenderPass(plan, color, Optional.empty(), depth, OptionalDouble.empty())) {\n            metrics.blaze3dPassesCreated.increment();\n            bindPreparedPass(pass, prepared);\n            ModelPartPipelineDescriptor descriptor = ModelPartPipelineDescriptor.from(prepared.pipeline());\n            InstanceSubmissionOrderPlanner.Plan submission = plan.submission;\n            for (int batch = 0; batch < submission.batchCount(); batch++) {\n                int sourceIndex = submission.representativeSourceIndices()[batch];\n                int firstPackedInstance = submission.firstPackedInstances()[batch];\n                int count = submission.instanceCounts()[batch];\n                MeshHandle handle = queued.mesh(sourceIndex);\n                Mesh mesh = meshes.get(handle);\n                pass.setVertexBuffer(0, mesh.vertices.slice());\n                pass.setVertexBuffer(1, instanceSlice(uploadedInstances, firstPackedInstance, count));\n                pass.setIndexBuffer(mesh.indices, IndexType.INT);\n                pass.drawIndexed(handle.indexCount(), count, 0, 0, 0);\n                metrics.pipelineDraw(descriptor, count);\n                metrics.blaze3dDrawCommands.increment();\n                metrics.blaze3dInstancesSubmitted.add(count);\n            }\n        }\n    }\n''',
    '''    private int compatibleUnsortedRunEnd(int start, int planCount) {\n        PlannedDraw first = plannedDraws.get(start);\n        RenderTarget firstTarget = first.prepared.outputTarget().getRenderTarget();\n        Object color = colorAttachment(firstTarget);\n        Object depth = depthAttachment(firstTarget);\n        Object scissor = first.prepared.scissorState();\n        int end = start + 1;\n        while (end < planCount) {\n            PlannedDraw next = plannedDraws.get(end);\n            if (next.sorted) break;\n            RenderTarget nextTarget = next.prepared.outputTarget().getRenderTarget();\n            if (!compatiblePassState(\n                    color,\n                    depth,\n                    scissor,\n                    colorAttachment(nextTarget),\n                    depthAttachment(nextTarget),\n                    next.prepared.scissorState())) break;\n            end++;\n        }\n        return end;\n    }\n\n    static boolean compatiblePassState(\n            Object color, Object depth, Object scissor, Object nextColor, Object nextDepth, Object nextScissor) {\n        return color == nextColor && depth == nextDepth && scissor.equals(nextScissor);\n    }\n\n    private static Object colorAttachment(RenderTarget target) {\n        return RenderSystem.outputColorTextureOverride != null\n                ? RenderSystem.outputColorTextureOverride\n                : target.getColorTextureView();\n    }\n\n    private static Object depthAttachment(RenderTarget target) {\n        if (!target.useDepth) return null;\n        return RenderSystem.outputDepthTextureOverride != null\n                ? RenderSystem.outputDepthTextureOverride\n                : target.getDepthTextureView();\n    }\n\n    private void submitBatchRun(\n            com.mojang.blaze3d.systems.CommandEncoder encoder,\n            int start,\n            int end,\n            GpuBufferSlice uploadedInstances) {\n        PlannedDraw first = plannedDraws.get(start);\n        RenderTarget target = first.prepared.outputTarget().getRenderTarget();\n        var color = RenderSystem.outputColorTextureOverride != null\n                ? RenderSystem.outputColorTextureOverride\n                : target.getColorTextureView();\n        var depth = target.useDepth\n                ? (RenderSystem.outputDepthTextureOverride != null\n                        ? RenderSystem.outputDepthTextureOverride\n                        : target.getDepthTextureView())\n                : null;\n        try (RenderPass pass = encoder.createRenderPass(first, color, Optional.empty(), depth, OptionalDouble.empty())) {\n            metrics.blaze3dPassesCreated.increment();\n            for (int planIndex = start; planIndex < end; planIndex++)\n                submitBatches(pass, plannedDraws.get(planIndex), uploadedInstances);\n        }\n    }\n\n    private void submitBatches(RenderPass pass, PlannedDraw plan, GpuBufferSlice uploadedInstances) {\n        PreparedRenderType prepared = plan.prepared;\n        bindPreparedPass(pass, prepared);\n        ModelPartPipelineDescriptor descriptor = ModelPartPipelineDescriptor.from(prepared.pipeline());\n        InstanceSubmissionOrderPlanner.Plan submission = plan.submission;\n        for (int batch = 0; batch < submission.batchCount(); batch++) {\n            int sourceIndex = submission.representativeSourceIndices()[batch];\n            int firstPackedInstance = submission.firstPackedInstances()[batch];\n            int count = submission.instanceCounts()[batch];\n            MeshHandle handle = queued.mesh(sourceIndex);\n            Mesh mesh = meshes.get(handle);\n            pass.setVertexBuffer(0, mesh.vertices.slice());\n            pass.setVertexBuffer(1, instanceSlice(uploadedInstances, firstPackedInstance, count));\n            pass.setIndexBuffer(mesh.indices, IndexType.INT);\n            pass.drawIndexed(handle.indexCount(), count, 0, 0, 0);\n            metrics.pipelineDraw(descriptor, count);\n            metrics.blaze3dDrawCommands.increment();\n            metrics.blaze3dInstancesSubmitted.add(count);\n        }\n    }\n''',
)

# Document the production/default metrics behavior and opt-in diagnostics.
doc = Path("docs/gpu-host-validation.md")
doc_text = doc.read_text()
needle = "Run from the repository root with Java 25 available. These commands use the\nnormal Gradle wrapper and do not create a release artifact with Sodium bundled.\n"
replacement = needle + "\nDetailed hot-path metrics are disabled during normal play. Add\n`-Dthreadium.metrics.hotPath=true` only for diagnostic runs; do not use that\nproperty for FPS or frame-time comparisons.\n"
if doc_text.count(needle) != 1:
    raise SystemExit("gpu-host-validation.md marker mismatch")
doc.write_text(doc_text.replace(needle, replacement, 1))

# Add focused tests for identity-based attachment grouping.
test_path = Path("src/test/java/dev/alex/threadium/render/modelpart/Blaze3dPassCompatibilityTest.java")
test_path.write_text('''package dev.alex.threadium.render.modelpart;\n\nimport static org.junit.jupiter.api.Assertions.assertFalse;\nimport static org.junit.jupiter.api.Assertions.assertTrue;\n\nimport org.junit.jupiter.api.Test;\n\nclass Blaze3dPassCompatibilityTest {\n    @Test\n    void acceptsIdenticalAttachmentsAndEqualScissorState() {\n        Object color = new Object();\n        Object depth = new Object();\n        assertTrue(Blaze3dModelPartBackend.compatiblePassState(\n                color, depth, "disabled", color, depth, new String("disabled")));\n    }\n\n    @Test\n    void rejectsDifferentAttachmentIdentityOrScissorState() {\n        Object color = new Object();\n        Object depth = new Object();\n        assertFalse(Blaze3dModelPartBackend.compatiblePassState(\n                color, depth, "disabled", new Object(), depth, "disabled"));\n        assertFalse(Blaze3dModelPartBackend.compatiblePassState(\n                color, depth, "disabled", color, new Object(), "disabled"));\n        assertFalse(Blaze3dModelPartBackend.compatiblePassState(\n                color, depth, "disabled", color, depth, "enabled"));\n    }\n}\n''')

# Temporary automation files must not land in the implementation commit.
Path(".github/scripts/apply_beta7_runtime_fix.py").unlink(missing_ok=True)
Path(".github/workflows/apply-beta7-runtime-fix.yml").unlink(missing_ok=True)
