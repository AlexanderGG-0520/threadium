package dev.alex.threadium.benchmark;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

/** Developer command tree for the Minecraft 1.21.1 differential harness. */
public final class ThreadiumValidationCommands1211 {
    private static boolean registered;

    private ThreadiumValidationCommands1211() {}

    public static synchronized boolean register() {
        if (registered) return true;
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("threadium")
                    .then(ClientCommandManager.literal("pipeline-differential")
                            .then(ClientCommandManager.literal("run")
                                    .then(ClientCommandManager.argument("pipeline", StringArgumentType.word())
                                            .executes(context -> {
                                                reply(
                                                        context.getSource(),
                                                        PipelineDifferentialRunner1211.run(
                                                                context.getSource()
                                                                        .getClient(),
                                                                StringArgumentType.getString(context, "pipeline")));
                                                return 1;
                                            })))
                            .then(ClientCommandManager.literal("run-phase")
                                    .then(ClientCommandManager.argument("phase", StringArgumentType.word())
                                            .executes(context -> {
                                                reply(
                                                        context.getSource(),
                                                        PipelineDifferentialRunner1211.runPhase(
                                                                context.getSource()
                                                                        .getClient(),
                                                                StringArgumentType.getString(context, "phase")));
                                                return 1;
                                            })))
                            .then(ClientCommandManager.literal("run-all").executes(context -> {
                                reply(
                                        context.getSource(),
                                        PipelineDifferentialRunner1211.runAll(
                                                context.getSource().getClient()));
                                return 1;
                            }))
                            .then(ClientCommandManager.literal("rerun-failed").executes(context -> {
                                reply(
                                        context.getSource(),
                                        PipelineDifferentialRunner1211.rerunFailed(
                                                context.getSource().getClient()));
                                return 1;
                            }))
                            .then(ClientCommandManager.literal("status").executes(context -> {
                                PipelineDifferentialRunner1211.status()
                                        .forEach(line -> reply(context.getSource(), line));
                                return 1;
                            }))
                            .then(ClientCommandManager.literal("abort").executes(context -> {
                                reply(context.getSource(), PipelineDifferentialRunner1211.abort());
                                return 1;
                            }))));
        });
        registered = true;
        return true;
    }

    private static void reply(FabricClientCommandSource source, String message) {
        source.sendFeedback(Text.literal(message));
    }
}
