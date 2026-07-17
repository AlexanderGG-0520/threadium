package dev.alex.threadium.benchmark;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.alex.threadium.ThreadiumClient;
import dev.alex.threadium.lifecycle.ThreadiumLifecycle;
import dev.alex.threadium.metrics.StagedVertexMetrics;
import dev.alex.threadium.metrics.ThreadiumMetrics;
import dev.alex.threadium.render.modelpart.ModelPartRenderService;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Startup-configured, command-started benchmark controller. */
public final class ThreadiumBenchmark {
    private static final String MODE_PROPERTY = "threadium.benchmark.mode";
    private static ThreadiumBenchmark current;
    private final ModelPartBenchmarkMode mode;
    private final String scene;
    private final int trial;
    private final long setupNanos, warmupNanos, measurementNanos;
    private final boolean autoExit;
    private BoundedFrameSamples samples;
    private BenchmarkLifecyclePolicy.Phase phase = BenchmarkLifecyclePolicy.Phase.IDLE;
    private long phaseStart,
            previousFrameStart,
            measurementStart,
            autoExitAt,
            actionBarUntil,
            startTickCount,
            tickCount,
            validationDeadline;
    private long worldGeneration, resourceGeneration;
    private int width, height, entityCount;
    private double playerX, playerY, playerZ, cameraX, cameraY, cameraZ;
    private float playerYaw, playerPitch, cameraYaw, cameraPitch;
    private boolean focused = true,
            stableCamera = true,
            stableWorld = true,
            stableEntities = true,
            stableResolution = true,
            tickProgressConfirmed,
            validationPending;
    private final CompletionNotificationGuard completionGuard = new CompletionNotificationGuard();
    private ThreadiumMetrics metrics;
    private String abortReason, finalStatus, resultPath, lastStartRejection;
    private BenchmarkPopulationSnapshot.Server lastServerPopulation;
    private BenchmarkPopulationSnapshot.Client lastClientPopulation;

    private ThreadiumBenchmark(ModelPartBenchmarkMode mode) {
        this.mode = mode;
        scene = property("threadium.benchmark.scene", "static").toLowerCase(java.util.Locale.ROOT);
        trial = integer("threadium.benchmark.trial", 1, 1, 9999);
        setupNanos = seconds("threadium.benchmark.setupSeconds", 10);
        warmupNanos = seconds("threadium.benchmark.warmupSeconds", 30);
        measurementNanos = seconds("threadium.benchmark.measurementSeconds", 60);
        autoExit = BenchmarkCompletionNotice.autoExitEnabled(System.getProperty("threadium.benchmark.autoExit"));
        newSamples();
    }

    public static java.util.Optional<ModelPartBenchmarkMode> requestedMode() {
        return ModelPartBenchmarkMode.parseOptional(System.getProperty(MODE_PROPERTY));
    }

    public static void initialize(ModelPartBenchmarkMode mode, ThreadiumMetrics metrics) {
        current = new ThreadiumBenchmark(mode);
        current.metrics = metrics;
        boolean commandsRegistered = ThreadiumBenchmarkCommands.register();
        ThreadiumClient.LOGGER.info("Benchmark mode: {}", mode);
        ThreadiumClient.LOGGER.info("Benchmark controller constructed: true");
        ThreadiumClient.LOGGER.info("Benchmark commands registered: {}", commandsRegistered);
        ThreadiumClient.LOGGER.info("Benchmark tick callback registered: true");
        ThreadiumClient.LOGGER.info(
                "Renderer metrics source: {}", mode == ModelPartBenchmarkMode.VANILLA ? "NO_OP" : "MODEL_PART_SERVICE");
        ThreadiumClient.LOGGER.info(
                "Threadium benchmark configured and idle: mode={}, scene={}, "
                        + "trial={}; use /threadium benchmark start",
                mode,
                current.scene,
                current.trial);
    }

    public static boolean active() {
        return current != null;
    }

    public static boolean trialActive() {
        return current != null && BenchmarkLifecyclePolicy.cameraLocked(current.phase);
    }

    public static void tick(Minecraft client) {
        if (current != null) current.onTick(client);
    }

    public static void frameStart(Minecraft client) {
        if (current != null) current.onFrameStart(client);
    }

    public static void frameEnd(Minecraft client) {}

    public static void resourceReloaded() {
        if (trialActive()) current.stableWorld = false;
    }

    public static String start(Minecraft client) {
        if (current == null) return "Benchmark mode is not enabled.";
        return current.beginStartValidation(client);
    }

    public static void executeStart(FabricClientCommandSource source) {
        ThreadiumClient.LOGGER.info("Benchmark start command invoked");
        if (current == null) {
            source.sendError(Component.literal("[Threadium Benchmark] Start rejected: benchmark mode not enabled"));
            return;
        }
        String rejection = current.beginStartValidation(source.getClient());
        if (rejection == null)
            source.sendFeedback(Component.literal("[Threadium Benchmark] Validating benchmark scene..."));
        else source.sendError(Component.literal("[Threadium Benchmark] Start rejected: " + rejection));
    }

    public static String abort(Minecraft client) {
        if (current == null) return "Benchmark mode is not enabled.";
        return current.abortTrial(client);
    }

    public static List<String> status(Minecraft client) {
        if (current == null) return List.of("Threadium benchmark mode is not enabled.");
        return current.statusLines(client);
    }

    public static void bootstrap(Minecraft client, Consumer<String> reply) {
        if (current == null) {
            reply.accept("Benchmark mode is not enabled.");
            return;
        }
        if (!BenchmarkSceneBootstrap.enabled()) {
            reply.accept("Scene bootstrap requires -PthreadiumBenchmarkBootstrap=true.");
            return;
        }
        if (!current.expectedWorld(client)) {
            reply.accept("This is not the expected benchmark world: " + BenchmarkSceneSpec.STATIC.worldIdentifier());
            return;
        }
        reply.accept("Benchmark scene bootstrap scheduled on the integrated server.");
        BenchmarkSceneBootstrap.bootstrap(client, reply);
    }

    private String beginStartValidation(Minecraft client) {
        ThreadiumClient.LOGGER.info("Current phase: {}", phase);
        if (validationPending) return rejectStart("another start validation is pending");
        if (!scene.equals("static")) return rejectStart("benchmark scene is not implemented: " + scene);
        if (phase != BenchmarkLifecyclePolicy.Phase.IDLE)
            return rejectStart("trial already active or completed: " + phase);
        if (client.level == null) return rejectStart("no client world");
        if (client.player == null) return rejectStart("no client player");
        var server = client.getSingleplayerServer();
        ThreadiumClient.LOGGER.info(
                "World: {}",
                server == null ? "unavailable" : server.getWorldData().getLevelName());
        ThreadiumClient.LOGGER.info("Integrated server available: {}", server != null);
        if (server == null) return rejectStart("no integrated server");
        if (client.gameRenderer.mainCamera().entity() != client.player)
            return rejectStart("detached or non-player camera");
        if (!expectedWorld(client))
            return rejectStart("incorrect benchmark world; expected " + BenchmarkSceneSpec.STATIC.worldIdentifier());
        if (!BenchmarkSceneBootstrap.markerValid())
            return rejectStart("missing scene marker, scene version mismatch, or " + "scene hash mismatch");
        validationPending = true;
        validationDeadline = System.nanoTime() + 5_000_000_000L;
        server.execute(() -> {
            var snapshot = BenchmarkPopulationValidator.server(server.overworld());
            client.execute(() -> finishStartValidation(client, snapshot));
        });
        return null;
    }

    private void finishStartValidation(Minecraft client, BenchmarkPopulationSnapshot.Server serverSnapshot) {
        if (!validationPending) return;
        validationPending = false;
        lastServerPopulation = serverSnapshot;
        lastClientPopulation = BenchmarkPopulationValidator.client(client);
        var combined = new BenchmarkPopulationSnapshot.Combined(lastServerPopulation, lastClientPopulation);
        ThreadiumClient.LOGGER.info(
                "Benchmark server entities: {}",
                lastServerPopulation.describe(BenchmarkSceneSpec.STATIC.entityCount()));
        ThreadiumClient.LOGGER.info(
                "Benchmark client entities: {}",
                lastClientPopulation.describe(BenchmarkSceneSpec.STATIC.entityCount()));
        String rejection = combined.rejection(BenchmarkSceneSpec.STATIC.entityCount());
        if (rejection != null) {
            rejectStart(rejection);
            chat(client, "[Threadium Benchmark] Start rejected: " + rejection);
            return;
        }
        ThreadiumClient.LOGGER.info("Scene validation: PASS");
        captureConditions(client);
        resetValidation();
        newSamples();
        resetMetrics();
        BenchmarkLifecyclePolicy.Phase previous = phase;
        phase = BenchmarkLifecyclePolicy.Phase.SETUP;
        phaseStart = System.nanoTime();
        startTickCount = tickCount;
        tickProgressConfirmed = false;
        lastStartRejection = null;
        enforceTransform(client);
        chat(client, "[Threadium Benchmark] Start accepted. Entering SETUP.");
        chat(client, "[Threadium Benchmark] Setup started. Keep the game focused.");
        ThreadiumClient.LOGGER.info("Transition: {} -> {}", previous, phase);
    }

    private String rejectStart(String reason) {
        lastStartRejection = reason;
        ThreadiumClient.LOGGER.warn("Benchmark start rejected: {}", reason);
        return reason;
    }

    private String abortTrial(Minecraft client) {
        if (!BenchmarkLifecyclePolicy.mayAbort(phase)) return "No benchmark trial is active.";
        abortReason = "aborted by client command";
        phase = BenchmarkLifecyclePolicy.Phase.ABORTED;
        try {
            finish(client, System.nanoTime(), "ABORTED", List.of(abortReason), false);
        } catch (IOException failure) {
            return "Benchmark aborted, but result writing failed: " + failure.getMessage();
        }
        return "Threadium benchmark aborted; camera lock released and client " + "remains open.";
    }

    private void onTick(Minecraft client) {
        tickCount++;
        long now = System.nanoTime();
        if (validationPending && now >= validationDeadline) {
            validationPending = false;
            rejectStart("server scene validation timed out");
            chat(client, "[Threadium Benchmark] Start rejected: server scene " + "validation timed out");
        }
        if (phase == BenchmarkLifecyclePolicy.Phase.SETUP && !tickProgressConfirmed && tickCount > startTickCount) {
            tickProgressConfirmed = true;
            ThreadiumClient.LOGGER.info("Benchmark lifecycle client ticks confirmed");
        }
        if ((phase == BenchmarkLifecyclePolicy.Phase.COMPLETE || phase == BenchmarkLifecyclePolicy.Phase.ABORTED)
                && client.player != null) {
            if (now < actionBarUntil) actionBar(client, BenchmarkCompletionNotice.actionBar(finalStatus));
            if (autoExitAt > 0 && now >= autoExitAt) {
                autoExitAt = 0;
                client.stop();
            }
            return;
        }
        if (!BenchmarkLifecyclePolicy.cameraLocked(phase) || client.level == null || client.player == null) return;
        enforceTransform(client);
        if (phase == BenchmarkLifecyclePolicy.Phase.SETUP && now - phaseStart >= setupNanos)
            transition(BenchmarkLifecyclePolicy.Phase.WARMUP, now, client);
        else if (phase == BenchmarkLifecyclePolicy.Phase.WARMUP && now - phaseStart >= warmupNanos)
            transition(BenchmarkLifecyclePolicy.Phase.MEASUREMENT, now, client);
        else if (phase == BenchmarkLifecyclePolicy.Phase.MEASUREMENT) {
            stableEntities &= sceneState(client).valid();
            if (now - measurementStart >= measurementNanos) complete(client, now);
        }
    }

    private void onFrameStart(Minecraft client) {
        long now = System.nanoTime();
        if (phase == BenchmarkLifecyclePolicy.Phase.SETUP
                && !tickProgressConfirmed
                && now - phaseStart > 2_000_000_000L
                && tickCount == startTickCount) {
            phase = BenchmarkLifecyclePolicy.Phase.IDLE;
            lastStartRejection = "Benchmark lifecycle is not receiving client ticks.";
            chat(client, "[Threadium Benchmark] " + lastStartRejection);
            ThreadiumClient.LOGGER.error(lastStartRejection);
            return;
        }
        if (phase != BenchmarkLifecyclePolicy.Phase.MEASUREMENT) return;
        enforceTransform(client);
        validateConditions(client);
        if (previousFrameStart != 0) samples.add(now - previousFrameStart);
        previousFrameStart = now;
    }

    private void transition(BenchmarkLifecyclePolicy.Phase next, long now, Minecraft client) {
        phase = next;
        phaseStart = now;
        if (next == BenchmarkLifecyclePolicy.Phase.WARMUP) {
            chat(client, "[Threadium Benchmark] Warmup started: " + warmupNanos / 1_000_000_000L + " seconds.");
            actionBar(client, "Threadium benchmark warmup started");
        }
        if (next == BenchmarkLifecyclePolicy.Phase.MEASUREMENT) {
            resetMetrics();
            measurementStart = now;
            previousFrameStart = 0;
            chat(
                    client,
                    "[Threadium Benchmark] Measurement started: " + measurementNanos / 1_000_000_000L + " seconds.");
            actionBar(client, "Threadium benchmark measurement started");
            ThreadiumClient.LOGGER.info(
                    "Threadium benchmark measurement started: mode={}, scene={}, "
                            + "trial={}, sceneHash={}, entities={}",
                    mode,
                    scene,
                    trial,
                    BenchmarkSceneSpec.STATIC.hash(),
                    entityCount);
        }
    }

    private void captureConditions(Minecraft client) {
        worldGeneration = ThreadiumLifecycle.worldGeneration();
        resourceGeneration = ThreadiumLifecycle.resourceGeneration();
        width = client.getWindow().getWidth();
        height = client.getWindow().getHeight();
        entityCount = BenchmarkSceneSpec.STATIC.entityCount();
        playerX = client.player.getX();
        playerY = client.player.getY();
        playerZ = client.player.getZ();
        playerYaw = client.player.getYRot();
        playerPitch = client.player.getXRot();
        var camera = client.gameRenderer.mainCamera();
        cameraX = camera.position().x;
        cameraY = camera.position().y;
        cameraZ = camera.position().z;
        cameraYaw = camera.yRot();
        cameraPitch = camera.xRot();
    }

    private void resetValidation() {
        focused = true;
        stableCamera = true;
        stableWorld = true;
        stableEntities = true;
        stableResolution = true;
        abortReason = null;
    }

    private void enforceTransform(Minecraft client) {
        client.player.setPos(playerX, playerY, playerZ);
        client.player.setYRot(playerYaw);
        client.player.setXRot(playerPitch);
        client.player.setDeltaMovement(0, 0, 0);
    }

    private void validateConditions(Minecraft client) {
        focused &= client.isWindowActive();
        stableWorld &= worldGeneration == ThreadiumLifecycle.worldGeneration()
                && resourceGeneration == ThreadiumLifecycle.resourceGeneration()
                && client.level != null
                && expectedWorld(client)
                && BenchmarkSceneBootstrap.markerValid();
        stableResolution &= width == client.getWindow().getWidth()
                && height == client.getWindow().getHeight();
        var camera = client.gameRenderer.mainCamera();
        stableCamera &= close(playerX, client.player.getX())
                && close(playerY, client.player.getY())
                && close(playerZ, client.player.getZ())
                && Math.abs(playerYaw - client.player.getYRot()) < .001f
                && Math.abs(playerPitch - client.player.getXRot()) < .001f
                && close(cameraX, camera.position().x)
                && close(cameraY, camera.position().y)
                && close(cameraZ, camera.position().z)
                && Math.abs(cameraYaw - camera.yRot()) < .001f
                && Math.abs(cameraPitch - camera.xRot()) < .001f;
    }

    private boolean expectedWorld(Minecraft client) {
        var server = client.getSingleplayerServer();
        return server != null
                && BenchmarkSceneSpec.STATIC
                        .worldIdentifier()
                        .equals(server.getWorldData().getLevelName())
                && client.level != null
                && client.level.dimension().identifier().toString().equals(BenchmarkSceneSpec.STATIC.dimension());
    }

    private SceneState sceneState(Minecraft client) {
        var snapshot = BenchmarkPopulationValidator.client(client);
        return new SceneState(
                snapshot.trackedCowsInSceneBounds(),
                snapshot.trackedCowsInSceneBounds(),
                snapshot.cowsMatchingExpectedPositions());
    }

    private record SceneState(int owned, int cows, int placed) {
        boolean valid() {
            return owned == BenchmarkSceneSpec.STATIC.entityCount() && cows == owned && placed == owned;
        }

        String description() {
            return "owned=" + owned + ", cows=" + cows + ", placed=" + placed + ", expected="
                    + BenchmarkSceneSpec.STATIC.entityCount();
        }
    }

    private void complete(Minecraft client, long now) {
        try {
            finish(client, now, "VALID", List.of(), true);
        } catch (IOException failure) {
            ThreadiumClient.LOGGER.error("Could not write Threadium benchmark result", failure);
        }
    }

    private void finish(
            Minecraft client, long now, String requestedStatus, List<String> initialReasons, boolean validate)
            throws IOException {
        var timing = metrics.benchmarkTimingSnapshotAndReset();
        var staged = StagedVertexMetrics.benchmarkTimingSnapshotAndReset();
        ModelPartRenderService service = ModelPartRenderService.get();
        var model =
                service == null ? ModelPartRenderService.BenchmarkMetrics.zero() : service.benchmarkSnapshotAndReset();
        var diagnostics = service == null
                ? BoundedFallbackDiagnostics.Snapshot.empty()
                : service.benchmarkFallbackDiagnosticsAndReset();
        ArrayList<String> reasons = new ArrayList<>(initialReasons);
        if (validate) {
            var counters = new BenchmarkTrialValidator.Counters(
                    model.productionReplacementAccepts(),
                    model.vanillaSuppressions(),
                    model.queuedInstances(),
                    model.drawnInstances(),
                    model.drawCalls(),
                    model.multiInstanceBatches(),
                    model.maximumInstancesPerDraw(),
                    model.backendFailures(),
                    model.blaze3dSubmissionFailures(),
                    model.rawProductionDrawCalls(),
                    model.vanillaFallbacks(),
                    model.pipelineInvalid(),
                    model.pipelineStale(),
                    model.pipelineUnknown());
            reasons.addAll(BenchmarkTrialValidator.validate(
                    mode,
                    counters,
                    focused,
                    stableCamera,
                    stableWorld,
                    stableEntities,
                    stableResolution,
                    samples.overflowed(),
                    now - measurementStart,
                    measurementNanos,
                    samples.size()));
            if (client.options.enableVsync().get()) reasons.add("VSync enabled");
            if (client.options.framerateLimit().get() < 260) reasons.add("FPS limit below 260");
        }
        finalStatus = requestedStatus.equals("ABORTED") ? "ABORTED" : reasons.isEmpty() ? "VALID" : "INVALID";
        phase = finalStatus.equals("ABORTED")
                ? BenchmarkLifecyclePolicy.Phase.ABORTED
                : BenchmarkLifecyclePolicy.Phase.COMPLETE;
        writeResult(
                client,
                Math.max(0, now - measurementStart),
                timing,
                staged,
                model,
                diagnostics,
                finalStatus,
                List.copyOf(reasons));
        resultPath = latestResultPath();
        appendPopulationMetadata(Path.of(resultPath), client);
        notifyCompletion(client, List.copyOf(reasons));
        ThreadiumClient.LOGGER.info(
                "Threadium benchmark complete: status={}, " + "reasons={}, samples={}, result={}",
                finalStatus,
                reasons,
                samples.size(),
                resultPath);
    }

    private void resetMetrics() {
        metrics.benchmarkTimingSnapshotAndReset();
        StagedVertexMetrics.benchmarkTimingSnapshotAndReset();
        ModelPartRenderService service = ModelPartRenderService.get();
        if (service != null) {
            service.benchmarkSnapshotAndReset();
            service.benchmarkFallbackDiagnosticsAndReset();
        }
    }

    private void newSamples() {
        samples = new BoundedFrameSamples(integer("threadium.benchmark.maxSamples", 120000, 100, 2_000_000));
    }

    private List<String> statusLines(Minecraft client) {
        long now = System.nanoTime(),
                duration =
                        switch (phase) {
                            case SETUP -> setupNanos;
                            case WARMUP -> warmupNanos;
                            case MEASUREMENT -> measurementNanos;
                            default -> 0;
                        };
        long elapsed = phaseStart == 0 ? 0 : Math.max(0, now - phaseStart);
        var clientPopulation = client.level == null
                ? new BenchmarkPopulationSnapshot.Client(0, 0, 0)
                : BenchmarkPopulationValidator.client(client);
        ArrayList<String> lines = new ArrayList<>(List.of(
                "Threadium benchmark: mode=" + mode + ", scene=" + scene + ", trial=" + trial,
                "Active: " + BenchmarkLifecyclePolicy.cameraLocked(phase) + ", Phase: "
                        + phase + ", Status: "
                        + (finalStatus == null ? "pending" : finalStatus),
                "Camera locked: " + BenchmarkLifecyclePolicy.cameraLocked(phase),
                "Validation pending: " + validationPending,
                "elapsedSeconds=" + elapsed / 1_000_000_000.0 + ", remainingSeconds="
                        + Math.max(0, duration - elapsed) / 1_000_000_000.0,
                "world=" + BenchmarkSceneSpec.STATIC.worldIdentifier() + ", sceneVersion="
                        + BenchmarkSceneSpec.STATIC.version() + ", sceneHash="
                        + BenchmarkSceneSpec.STATIC.hash(),
                "serverEntities: "
                        + (lastServerPopulation == null
                                ? "not validated"
                                : lastServerPopulation.describe(BenchmarkSceneSpec.STATIC.entityCount())),
                "clientEntities: " + clientPopulation.describe(BenchmarkSceneSpec.STATIC.entityCount()),
                "ownershipSource: server scoreboard tag",
                "clientMatchSource: type + deterministic expected position",
                "samples=" + samples.size(),
                "Result: " + (resultPath == null ? "pending" : resultPath),
                "output=run/benchmarks/threadium"));
        var bootstrap = BenchmarkSceneBootstrap.status();
        lines.add("bootstrapActive=" + bootstrap.active() + ", bootstrapPhase="
                + bootstrap.phase() + ", bootstrapFailureReason="
                + bootstrap.failureReason());
        lines.add("selectedForRemoval=" + bootstrap.selectedForRemoval() + ", activeOwnedRemaining="
                + bootstrap.activeOwnedRemaining() + ", spawnedSuccessfully="
                + bootstrap.spawnedSuccessfully() + ", finalOwned="
                + bootstrap.finalOwned() + ", finalDuplicates="
                + bootstrap.finalDuplicates());
        if (lastStartRejection != null) lines.add("Latest start rejection: " + lastStartRejection);
        ModelPartRenderService modelParts = ModelPartRenderService.get();
        if (modelParts != null)
            for (var coverage : modelParts.pipelineCoverage())
                if (coverage.accepted() != 0 || coverage.fallbacks() != 0)
                    lines.add("pipeline=" + coverage.pipeline() + ", accepted=" + coverage.accepted() + ", fallback="
                            + coverage.fallbacks() + ", draws=" + coverage.drawCalls() + ", instances="
                            + coverage.instances() + ", maxBatch=" + coverage.maximumBatchSize() + ", mode="
                            + coverage.batchingMode());
        if (modelParts != null) lines.add(modelParts.sortedPipelineMetrics());
        return List.copyOf(lines);
    }

    private void notifyCompletion(Minecraft client, List<String> reasons) {
        if (!completionGuard.claim()) return;
        List<String> lines = finalStatus.equals("ABORTED")
                ? BenchmarkCompletionNotice.aborted(resultPath)
                : BenchmarkCompletionNotice.complete(
                        finalStatus, mode.name(), scene, trial, samples.size(), resultPath, reasons, autoExit);
        for (String line : lines) chat(client, line);
        actionBar(client, BenchmarkCompletionNotice.actionBar(finalStatus));
        long now = System.nanoTime();
        actionBarUntil = now + BenchmarkCompletionNotice.AUTO_EXIT_DELAY_NANOS;
        if (autoExit && !finalStatus.equals("ABORTED"))
            autoExitAt = now + BenchmarkCompletionNotice.AUTO_EXIT_DELAY_NANOS;
    }

    private String latestResultPath() throws IOException {
        Path directory = FabricLoader.getInstance().getGameDir().resolve("benchmarks/threadium");
        String token = "_" + mode.name().toLowerCase(java.util.Locale.ROOT) + "_" + scene
                + "_trial-" + String.format(java.util.Locale.ROOT, "%02d", trial)
                + ".json";
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(token))
                    .max(java.util.Comparator.comparingLong(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toMillis();
                        } catch (IOException failure) {
                            return Long.MIN_VALUE;
                        }
                    }))
                    .orElseThrow(() -> new IOException("Benchmark summary path not found"))
                    .toString();
        }
    }

    private void appendPopulationMetadata(Path path, Minecraft client) throws IOException {
        var server = lastServerPopulation == null
                ? new BenchmarkPopulationSnapshot.Server(0, 0, 0, 0, BenchmarkSceneSpec.STATIC.entityCount(), 0)
                : lastServerPopulation;
        var tracked = client.level == null
                ? new BenchmarkPopulationSnapshot.Client(0, 0, 0)
                : BenchmarkPopulationValidator.client(client);
        String original = Files.readString(path, StandardCharsets.UTF_8);
        int closing = original.lastIndexOf('}');
        if (closing < 0) throw new IOException("Unexpected benchmark JSON ending");
        String population = "  \"serverOwnedEntities\": " + server.ownedEntities() + ",\n  \"serverOwnedCows\": "
                + server.ownedCows() + ",\n  \"serverCorrectlyPlacedCows\": "
                + server.correctlyPlacedCows()
                + ",\n  \"serverUnexpectedOwnedEntities\": "
                + server.unexpectedOwnedEntities()
                + ",\n  \"serverMissingExpectedPositions\": "
                + server.missingExpectedPositions()
                + ",\n  \"serverDuplicateExpectedPositions\": "
                + server.duplicateExpectedPositions()
                + ",\n  \"clientTrackedCowsInSceneBounds\": "
                + tracked.trackedCowsInSceneBounds()
                + ",\n  \"clientCowsMatchingExpectedPositions\": "
                + tracked.cowsMatchingExpectedPositions()
                + ",\n  \"clientUnexpectedEntitiesInSceneBounds\": "
                + tracked.unexpectedEntitiesInSceneBounds()
                + (",\n  \"ownershipSource\": \"server scoreboard tag\",\n  "
                        + "\"clientMatchSource\": \"type + deterministic expected position\"\n");
        String head = original.substring(0, closing).stripTrailing();
        Files.writeString(path, head + ",\n" + population + "}\n", StandardCharsets.UTF_8);
    }

    private static void chat(Minecraft client, String message) {
        if (client.player != null) client.player.sendSystemMessage(Component.literal(message));
    }

    private static void actionBar(Minecraft client, String message) {
        if (client.player != null) client.player.sendOverlayMessage(Component.literal(message));
    }

    private void writeResult(
            Minecraft client,
            long measuredNanos,
            ThreadiumMetrics.BenchmarkTiming timing,
            StagedVertexMetrics.BenchmarkTiming staged,
            ModelPartRenderService.BenchmarkMetrics model,
            BoundedFallbackDiagnostics.Snapshot diagnostics,
            String status,
            List<String> reasons)
            throws IOException {
        Path directory = FabricLoader.getInstance().getGameDir().resolve("benchmarks/threadium");
        Files.createDirectories(directory);
        String base = BenchmarkFileNames.base(Instant.now(), mode, scene, trial);
        long[] frames = samples.copy();
        FrameStatistics stats = FrameStatistics.calculate(frames);
        try (BufferedWriter out =
                Files.newBufferedWriter(directory.resolve(base + "_frames.csv"), StandardCharsets.UTF_8)) {
            out.write("frameIndex,frameTimeNanos\n");
            for (int i = 0; i < frames.length; i++) {
                out.write(i + "," + frames[i] + "\n");
            }
        }
        String sodium = FabricLoader.getInstance().isModLoaded("sodium")
                ? FabricLoader.getInstance()
                        .getModContainer("sodium")
                        .map(c -> c.getMetadata().getVersion().getFriendlyString())
                        .orElse("unknown")
                : "absent";
        ModelPartRenderService modelPartService = ModelPartRenderService.get();
        List<String> pipelineCoverage = modelPartService == null
                ? List.of()
                : modelPartService.pipelineCoverage().stream()
                        .map(c -> c.pipeline() + ":accepted=" + c.accepted() + ",fallback=" + c.fallbacks() + ",draws="
                                + c.drawCalls() + ",instances=" + c.instances() + ",maxBatch=" + c.maximumBatchSize()
                                + ",mode=" + c.batchingMode())
                        .toList();
        String text = "{\n" + field("schemaVersion", 2)
                + field("timestamp", Instant.now().toString())
                + field("status", status) + field("invalidReasons", reasons)
                + field("abortReason", abortReason) + field("mode", mode.name())
                + field("scene", scene) + field("trial", trial)
                + field("phaseCompletionState", phase.name())
                + field("benchmarkWorldIdentifier", BenchmarkSceneSpec.STATIC.worldIdentifier())
                + field("benchmarkSceneVersion", BenchmarkSceneSpec.STATIC.version())
                + field("benchmarkSceneHash", BenchmarkSceneSpec.STATIC.hash())
                + field("expectedEntityCount", BenchmarkSceneSpec.STATIC.entityCount())
                + field("actualEntityCount", sceneState(client).owned())
                + field(
                        "expectedEntityTypeCounts",
                        List.of(BenchmarkSceneSpec.STATIC.entityType() + "=" + BenchmarkSceneSpec.STATIC.entityCount()))
                + field("fallbackDiagnostics", diagnostics.entries())
                + field("fallbackDiagnosticsOmitted", diagnostics.omittedUniqueOccurrences())
                + field("pipelineCoverage", pipelineCoverage)
                + field("measurementNanos", measuredNanos)
                + field("sampleCount", stats.sampleCount())
                + field("averageFrameNanos", stats.averageNanos())
                + field("medianFrameNanos", stats.medianNanos())
                + field("p95FrameNanos", stats.p95Nanos())
                + field("p99FrameNanos", stats.p99Nanos())
                + field("p999FrameNanos", stats.p999Nanos())
                + field("averageFps", stats.averageFps())
                + field("onePercentLowFps", stats.onePercentLowFps())
                + field("pointOnePercentLowFps", stats.pointOnePercentLowFps())
                + field("standardDeviationNanos", stats.standardDeviationNanos())
                + field("worldRenderAverageNanos", timing.worldRender().averageNanos())
                + field(
                        "featurePreparationAverageNanos",
                        staged.featurePreparation().averageNanos())
                + field(
                        "groupPreparationAverageNanos",
                        staged.groupPreparation().averageNanos())
                + field("productionReplacementAccepts", model.productionReplacementAccepts())
                + field("vanillaSuppressions", model.vanillaSuppressions())
                + field("queuedInstances", model.queuedInstances())
                + field("drawnInstances", model.drawnInstances())
                + field("drawCalls", model.drawCalls())
                + field("multiInstanceBatches", model.multiInstanceBatches())
                + field("maximumInstancesPerDraw", model.maximumInstancesPerDraw())
                + field("instancesPerDraw", model.instancesPerDraw())
                + field("drawReductionRatio", model.drawReductionRatio())
                + field("multiInstanceCoverage", model.multiInstanceCoverage())
                + field("boneBytesUploaded", model.boneBytesUploaded())
                + field("instanceBytesUploaded", model.instanceBytesUploaded())
                + field("modelLayoutCacheHits", model.modelLayoutCacheHits())
                + field("modelLayoutCacheMisses", model.modelLayoutCacheMisses())
                + field("modelTopologyTraversals", model.modelTopologyTraversals())
                + field("topologyPreparationNanos", model.topologyPreparationNanos())
                + field("posePaletteLookups", model.posePaletteLookups())
                + field("posePaletteHits", model.posePaletteHits())
                + field("posePaletteMisses", model.posePaletteMisses())
                + field("uniqueBonePalettes", model.uniqueBonePalettes())
                + field("reusedBonePalettes", model.reusedBonePalettes())
                + field("boneMatricesComposed", model.boneMatricesComposed())
                + field("boneMatricesAvoided", model.boneMatricesAvoided())
                + field("boneBytesRequested", model.boneBytesRequested())
                + field("boneBytesAvoided", model.boneBytesAvoided())
                + field("poseLookupNanos", model.poseLookupNanos())
                + field("boneCompositionNanos", model.boneCompositionNanos())
                + field("bonePackingNanos", model.bonePackingNanos())
                + field("backendFailures", model.backendFailures())
                + field("blaze3dSubmissionFailures", model.blaze3dSubmissionFailures())
                + field("rawProductionDrawCalls", model.rawProductionDrawCalls())
                + field("sodium", sodium)
                + field("gpu", RenderSystem.getDevice().getDeviceInfo().toString())
                + field("resolution", width + "x" + height)
                + field("fov", client.options.fov().get())
                + field("vsync", client.options.enableVsync().get())
                + field("fpsLimit", client.options.framerateLimit().get())
                + field("capturedPlayerTransform", List.of(playerX, playerY, playerZ, playerYaw, playerPitch))
                + lastField("capturedCameraTransform", List.of(playerX, playerY, playerZ, playerYaw, playerPitch))
                + "}\n";
        Files.writeString(directory.resolve(base + ".json"), text, StandardCharsets.UTF_8);
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < 1e-5;
    }

    private static String property(String key, String fallback) {
        return System.getProperty(key, fallback);
    }

    private static int integer(String key, int fallback, int min, int max) {
        int value = Integer.parseInt(property(key, Integer.toString(fallback)));
        if (value < min || value > max) throw new IllegalArgumentException(key);
        return value;
    }

    private static long seconds(String key, int fallback) {
        return Math.multiplyExact(integer(key, fallback, 0, 3600), 1_000_000_000L);
    }

    private static String field(String key, Object value) {
        return "  \"" + escape(key) + "\": " + json(value) + ",\n";
    }

    private static String lastField(String key, Object value) {
        return "  \"" + escape(key) + "\": " + json(value) + '\n';
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Iterable<?> iterable) {
            ArrayList<String> values = new ArrayList<>();
            for (Object item : iterable) values.add(json(item));
            return '[' + String.join(",", values) + ']';
        }
        return '"' + escape(value.toString()) + '"';
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
