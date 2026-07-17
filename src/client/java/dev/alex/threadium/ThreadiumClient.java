package dev.alex.threadium;

import dev.alex.threadium.benchmark.BenchmarkStartupOwnership;
import dev.alex.threadium.benchmark.ThreadiumBenchmark;
import dev.alex.threadium.benchmark.ThreadiumBenchmarkCommands;
import dev.alex.threadium.config.ThreadiumConfig;
import dev.alex.threadium.lifecycle.ThreadiumLifecycle;
import dev.alex.threadium.metrics.ThreadiumMetrics;
import dev.alex.threadium.render.modelpart.ModelPartRenderService;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ThreadiumClient implements ClientModInitializer {
    public static final String MOD_ID = "threadium";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        var benchmarkMode = ThreadiumBenchmark.requestedMode();
        ThreadiumConfig config = benchmarkMode
                .map(mode -> ThreadiumConfig.load().forBenchmark(mode))
                .orElseGet(ThreadiumConfig::load);
        ThreadiumMetrics metrics = new ThreadiumMetrics(config);
        ThreadiumBenchmarkCommands.register();
        benchmarkMode.ifPresent(mode -> {
            BenchmarkStartupOwnership ownership = BenchmarkStartupOwnership.forMode(mode);
            if (ownership.benchmarkController()) ThreadiumBenchmark.initialize(mode, metrics);
        });
        ThreadiumLifecycle.initialize(config, metrics);
        if (benchmarkMode.isEmpty()
                || BenchmarkStartupOwnership.forMode(benchmarkMode.get()).modelPartRenderer())
            ModelPartRenderService.initialize(config);
        LOGGER.info("Threadium {} initialized", "0.1.0-SNAPSHOT");
    }
}
