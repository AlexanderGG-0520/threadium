package dev.alex.threadium.benchmark;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.network.chat.Component;

/** One client command tree shared by benchmark and developer validation tools. */
public final class ThreadiumBenchmarkCommands {
    private static boolean registered;

    private ThreadiumBenchmarkCommands() {}

    public static synchronized boolean register() {
        if (registered) return true;
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("threadium")
                        .then(ClientCommands.literal("benchmark")
                                .then(ClientCommands.literal("start").executes(c -> {
                                    ThreadiumBenchmark.executeStart(c.getSource());
                                    return 1;
                                }))
                                .then(ClientCommands.literal("abort").executes(c -> {
                                    reply(
                                            c.getSource(),
                                            ThreadiumBenchmark.abort(
                                                    c.getSource().getClient()));
                                    return 1;
                                }))
                                .then(ClientCommands.literal("status").executes(c -> {
                                    for (String line : ThreadiumBenchmark.status(
                                            c.getSource().getClient())) reply(c.getSource(), line);
                                    return 1;
                                }))
                                .then(ClientCommands.literal("bootstrap").executes(c -> {
                                    ThreadiumBenchmark.bootstrap(
                                            c.getSource().getClient(), m -> reply(c.getSource(), m));
                                    return 1;
                                })))
                        .then(ClientCommands.literal("pipeline-differential")
                                .then(ClientCommands.literal("run")
                                        .then(ClientCommands.argument("pipeline", StringArgumentType.word())
                                                .executes(c -> {
                                                    reply(
                                                            c.getSource(),
                                                            PipelineDifferentialRunner.run(
                                                                    c.getSource()
                                                                            .getClient(),
                                                                    StringArgumentType.getString(c, "pipeline")));
                                                    return 1;
                                                })))
                                .then(ClientCommands.literal("run-phase")
                                        .then(ClientCommands.argument("phase", StringArgumentType.word())
                                                .executes(c -> {
                                                    reply(
                                                            c.getSource(),
                                                            PipelineDifferentialRunner.runPhase(
                                                                    c.getSource()
                                                                            .getClient(),
                                                                    StringArgumentType.getString(c, "phase")));
                                                    return 1;
                                                })))
                                .then(ClientCommands.literal("run-all").executes(c -> {
                                    reply(
                                            c.getSource(),
                                            PipelineDifferentialRunner.runAll(
                                                    c.getSource().getClient()));
                                    return 1;
                                }))
                                .then(ClientCommands.literal("rerun-failed").executes(c -> {
                                    reply(
                                            c.getSource(),
                                            PipelineDifferentialRunner.rerunFailed(
                                                    c.getSource().getClient()));
                                    return 1;
                                }))
                                .then(ClientCommands.literal("status").executes(c -> {
                                    for (String line : PipelineDifferentialRunner.status()) reply(c.getSource(), line);
                                    return 1;
                                }))
                                .then(ClientCommands.literal("abort").executes(c -> {
                                    reply(c.getSource(), PipelineDifferentialRunner.abort());
                                    return 1;
                                })))
                        .then(ClientCommands.literal("pipeline-coverage")
                                .then(ClientCommands.literal("bootstrap").executes(c -> {
                                    reply(
                                            c.getSource(),
                                            PipelineCoverageHarness.bootstrap(
                                                    c.getSource().getClient()));
                                    return 1;
                                }))
                                .then(ClientCommands.literal("status").executes(c -> {
                                    for (String line : PipelineCoverageHarness.status()) reply(c.getSource(), line);
                                    return 1;
                                }))
                                .then(ClientCommands.literal("clear").executes(c -> {
                                    reply(c.getSource(), PipelineCoverageHarness.clear());
                                    return 1;
                                }))
                                .then(ClientCommands.literal("stage")
                                        .then(ClientCommands.argument("count", IntegerArgumentType.integer(1, 20))
                                                .executes(c -> {
                                                    reply(
                                                            c.getSource(),
                                                            PipelineCoverageHarness.stage(
                                                                    IntegerArgumentType.getInteger(c, "count")));
                                                    return 1;
                                                })))
                                .then(ClientCommands.literal("reset-counters").executes(c -> {
                                    reply(c.getSource(), PipelineCoverageHarness.captureStart());
                                    return 1;
                                }))
                                .then(ClientCommands.literal("teleport")
                                        .then(ClientCommands.argument("pipeline", StringArgumentType.word())
                                                .executes(c -> {
                                                    reply(
                                                            c.getSource(),
                                                            PipelineCoverageHarness.teleport(
                                                                    c.getSource()
                                                                            .getClient(),
                                                                    StringArgumentType.getString(c, "pipeline")));
                                                    return 1;
                                                })))
                                .then(ClientCommands.literal("capture")
                                        .then(ClientCommands.literal("start").executes(c -> {
                                            reply(c.getSource(), PipelineCoverageHarness.captureStart());
                                            return 1;
                                        }))
                                        .then(ClientCommands.literal("stop").executes(c -> {
                                            reply(c.getSource(), PipelineCoverageHarness.captureStop());
                                            return 1;
                                        })))
                                .then(ClientCommands.literal("visual-pass")
                                        .then(ClientCommands.argument("pipeline", StringArgumentType.word())
                                                .executes(c -> {
                                                    reply(
                                                            c.getSource(),
                                                            PipelineCoverageHarness.visual(
                                                                    StringArgumentType.getString(c, "pipeline"),
                                                                    true,
                                                                    null));
                                                    return 1;
                                                })))
                                .then(ClientCommands.literal("visual-fail")
                                        .then(ClientCommands.argument("pipeline", StringArgumentType.word())
                                                .then(ClientCommands.argument(
                                                                "reason", StringArgumentType.greedyString())
                                                        .executes(c -> {
                                                            reply(
                                                                    c.getSource(),
                                                                    PipelineCoverageHarness.visual(
                                                                            StringArgumentType.getString(c, "pipeline"),
                                                                            false,
                                                                            StringArgumentType.getString(c, "reason")));
                                                            return 1;
                                                        })))))));
        registered = true;
        return true;
    }

    private static void reply(
            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, String message) {
        source.sendFeedback(Component.literal(message));
    }
}
