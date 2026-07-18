package dev.alex.threadium;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Minecraft 1.21.1 bootstrap. Rendering replacement remains disabled until ownership is ported safely. */
public final class ThreadiumClient implements ClientModInitializer {
    public static final String MOD_ID = "threadium";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("Threadium 1.21.1 backport bootstrap initialized; rendering replacement is disabled");
    }
}
