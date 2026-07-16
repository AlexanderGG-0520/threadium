package dev.alex.threadium.benchmark;

import dev.alex.threadium.ThreadiumClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;

/** Multi-tick, server-authoritative and re-entry-safe bootstrap. */
final class BenchmarkSceneBootstrap {
  static final String PROPERTY = "threadium.benchmark.bootstrap";
  private static final int REMOVAL_TIMEOUT = 100, SPAWN_TIMEOUT = 100,
                           STABLE_TICKS = 3;
  private static long nextGeneration;
  private static Session active;
  private static Status latest = Status.idle();
  enum Phase {
    IDLE,
    DELETE_MARKER,
    DISCOVER_STALE,
    REMOVE_STALE,
    WAIT_FOR_REMOVAL,
    CREATE_PLATFORM,
    SPAWN_ENTITIES,
    WAIT_FOR_SPAWN_STABILITY,
    VALIDATE,
    WRITE_MARKER,
    COMPLETE,
    FAILED
  }
  record Status(boolean active, Phase phase, String failureReason,
                int selectedForRemoval, int activeOwnedRemaining,
                int spawnedSuccessfully, int finalOwned, int finalDuplicates) {
    static Status idle() {
      return new Status(false, Phase.IDLE, null, 0, 0, 0, 0, 0);
    }
  }
  static {
    ServerTickEvents.END_SERVER_TICK.register(BenchmarkSceneBootstrap::tick);
  }
  private BenchmarkSceneBootstrap() {}
  static boolean enabled() {
    return Boolean.parseBoolean(System.getProperty(PROPERTY, "false"));
  }
  static Path markerPath() {
    return FabricLoader.getInstance()
        .getGameDir()
        .resolve("saves")
        .resolve(BenchmarkSceneSpec.STATIC.worldIdentifier())
        .resolve("threadium-benchmark-scene.marker");
  }
  static boolean markerValid() {
    try {
      return Files.readString(markerPath(), StandardCharsets.UTF_8)
          .equals(BenchmarkSceneSpec.STATIC.markerContents());
    } catch (IOException missing) {
      return false;
    }
  }
  static synchronized CompletableFuture<String>
  bootstrap(Minecraft client, Consumer<String> progress) {
    if (active != null) {
      progress.accept("[Threadium Benchmark] Bootstrap rejected: another " +
                      "bootstrap is already active.");
      return CompletableFuture.failedFuture(
          new IllegalStateException("another bootstrap is already active"));
    }
    var server = client.getSingleplayerServer();
    if (server == null)
      return CompletableFuture.failedFuture(
          new IllegalStateException("An integrated-server world is required"));
    Session session = new Session(++nextGeneration, server, client, progress);
    active = session;
    latest = session.status();
    return session.result;
  }
  static synchronized Status status() { return latest; }
  private static synchronized void tick(MinecraftServer server) {
    Session session = active;
    if (session == null || session.server != server)
      return;
    try {
      session.advance();
      latest = session.status();
      if (session.phase == Phase.COMPLETE || session.phase == Phase.FAILED)
        active = null;
    } catch (Throwable failure) {
      session.fail(failure);
      latest = session.status();
      active = null;
    }
  }
  private static final class Session {
    final long generation;
    final MinecraftServer server;
    final Minecraft client;
    final Consumer<String> progress;
    final CompletableFuture<String> result = new CompletableFuture<>();
    Phase phase = Phase.DELETE_MARKER;
    List<net.minecraft.world.entity.Entity> stale = List.of();
    int selected, remaining, spawned, waitTicks, stableTicks, finalOwned,
        finalDuplicates;
    String failure;
    Session(long generation, MinecraftServer server, Minecraft client,
            Consumer<String> progress) {
      this.generation = generation;
      this.server = server;
      this.client = client;
      this.progress = progress;
    }
    void advance() throws IOException {
      ServerLevel level = server.overworld();
      switch (phase) {
      case DELETE_MARKER -> {
        Files.deleteIfExists(markerPath());
        phase = Phase.DISCOVER_STALE;
      }
      case DISCOVER_STALE -> {
        ArrayList<net.minecraft.world.entity.Entity> found = new ArrayList<>();
        int removed = 0;
        for (var entity : level.getAllEntities())
          if (entity.entityTags().contains("threadium_benchmark")) {
            if (BenchmarkPopulationValidator.isActiveBenchmarkEntity(level,
                                                                     entity))
              found.add(entity);
            else
              removed++;
          }
        stale = List.copyOf(found);
        selected = stale.size();
        remaining = selected;
        message("[Threadium Benchmark] Bootstrap: removing " + selected +
                " stale benchmark entities...");
        ThreadiumClient.LOGGER.info(
            "Bootstrap discovery: enumeratedOwned={}, activeOwned={}, " +
            "removedOwned={}",
            selected + removed, selected, removed);
        phase = Phase.REMOVE_STALE;
      }
      case REMOVE_STALE -> {
        for (var entity : stale)
          if (BenchmarkPopulationValidator.isActiveBenchmarkEntity(level,
                                                                   entity))
            entity.discard();
        stale = List.of();
        waitTicks = 0;
        message("[Threadium Benchmark] Bootstrap: waiting for stale entity " +
                "removal...");
        phase = Phase.WAIT_FOR_REMOVAL;
      }
      case WAIT_FOR_REMOVAL -> {
        remaining = activeCount(level);
        if (remaining == 0)
          phase = Phase.CREATE_PLATFORM;
        else if (++waitTicks >= REMOVAL_TIMEOUT)
          throw new IllegalStateException(
              "Cleanup timeout: selectedForRemoval=" + selected +
              ", activeOwnedRemaining=" + remaining +
              ", firstRemaining=" + firstRemaining(level));
      }
      case CREATE_PLATFORM -> {
        var source = server.createCommandSourceStack().withSuppressedOutput();
        run(source, "gamerule doMobSpawning false");
        run(source, "gamerule doWeatherCycle false");
        run(source, "gamerule doDaylightCycle false");
        run(source, "time set " + BenchmarkSceneSpec.STATIC.fixedTime());
        run(source, "weather clear");
        run(source, "difficulty peaceful");
        run(source, "fill -22 63 -4 22 64 42 minecraft:stone");
        phase = Phase.SPAWN_ENTITIES;
      }
      case SPAWN_ENTITIES -> {
        message("[Threadium Benchmark] Bootstrap: spawning 256 cows...");
        spawned = 0;
        for (var placement : BenchmarkSceneSpec.STATIC.placements()) {
          var created = BuiltInRegistries.ENTITY_TYPE
                            .getValue(Identifier.fromNamespaceAndPath("minecraft", "cow"))
                            .create(level, EntitySpawnReason.COMMAND);
          if (!(created instanceof Cow cow))
            continue;
          cow.addTag("threadium_benchmark");
          cow.setNoAi(true);
          cow.setInvulnerable(true);
          cow.setSilent(true);
          cow.setPersistenceRequired();
          cow.setNoGravity(true);
          cow.setDeltaMovement(0, 0, 0);
          cow.setPos(placement.x(), placement.y(), placement.z());
          cow.setYRot(placement.yaw());
          cow.setXRot(0);
          cow.setYBodyRot(placement.yaw());
          cow.setYHeadRot(placement.yaw());
          cow.setBaby(false);
          cow.setGlowingTag(false);
          if (level.addFreshEntity(cow))
            spawned++;
        }
        if (spawned != BenchmarkSceneSpec.STATIC.entityCount())
          throw new IllegalStateException(
              "Spawn insertion failure: successful=" + spawned);
        waitTicks = 0;
        stableTicks = 0;
        phase = Phase.WAIT_FOR_SPAWN_STABILITY;
      }
      case WAIT_FOR_SPAWN_STABILITY -> {
        int count = activeCount(level);
        if (count == BenchmarkSceneSpec.STATIC.entityCount())
          stableTicks++;
        else
          stableTicks = 0;
        if (stableTicks >= STABLE_TICKS)
          phase = Phase.VALIDATE;
        else if (++waitTicks >= SPAWN_TIMEOUT)
          throw new IllegalStateException(
              "Spawn stability timeout: activeOwned=" + count +
              ", stableTicks=" + stableTicks);
      }
      case VALIDATE -> {
        message("[Threadium Benchmark] Bootstrap: validating scene...");
        var snapshot = BenchmarkPopulationValidator.server(level);
        finalOwned = snapshot.ownedEntities();
        finalDuplicates = snapshot.duplicateExpectedPositions();
        if (!snapshot.valid(BenchmarkSceneSpec.STATIC.entityCount()))
          throw new IllegalStateException(
              "Bootstrap postcondition failed: " +
              snapshot.describe(BenchmarkSceneSpec.STATIC.entityCount()));
        phase = Phase.WRITE_MARKER;
      }
      case WRITE_MARKER -> {
        Files.createDirectories(markerPath().getParent());
        Files.writeString(markerPath(),
                          BenchmarkSceneSpec.STATIC.markerContents(),
                          StandardCharsets.UTF_8);
        if (!markerValid())
          throw new IOException("Written marker did not verify");
        server.saveEverything(false, true, false);
        phase = Phase.COMPLETE;
        String text =
            "[Threadium Benchmark] Bootstrap complete. owned=256, cows=256, " +
            "correctlyPlaced=256, duplicates=0, missing=0, sceneHash=" +
            BenchmarkSceneSpec.STATIC.hash();
        message(text);
        result.complete(text);
      }
      default -> {
      }
      }
    }
    void fail(Throwable cause) {
      phase = Phase.FAILED;
      failure = cause.getMessage();
      try {
        Files.deleteIfExists(markerPath());
      } catch (IOException suppressed) {
        cause.addSuppressed(suppressed);
      }
      message("[Threadium Benchmark] Bootstrap failed: " + failure);
      ThreadiumClient.LOGGER.error("Threadium benchmark scene bootstrap failed",
                                   cause);
      result.completeExceptionally(cause);
    }
    Status status() {
      return new Status(phase != Phase.COMPLETE && phase != Phase.FAILED, phase,
                        failure, selected, remaining, spawned, finalOwned,
                        finalDuplicates);
    }
    int activeCount(ServerLevel level) {
      int count = 0;
      for (var entity : level.getAllEntities())
        if (BenchmarkPopulationValidator.isActiveBenchmarkEntity(level, entity))
          count++;
      return count;
    }
    String firstRemaining(ServerLevel level) {
      for (var entity : level.getAllEntities())
        if (BenchmarkPopulationValidator.isActiveBenchmarkEntity(level, entity))
          return entity.getType() + " uuid=" + entity.getUUID() +
              " pos=" + entity.position() + " removed=" + entity.isRemoved();
      return "none";
    }
    void run(net.minecraft.commands.CommandSourceStack source, String command) {
      server.getCommands().performPrefixedCommand(source, command);
    }
    void message(String text) { client.execute(() -> progress.accept(text)); }
  }
}
