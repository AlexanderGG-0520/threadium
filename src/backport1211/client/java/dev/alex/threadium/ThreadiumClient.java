package dev.alex.threadium;

import dev.alex.threadium.render.entity.PassThroughEntityRenderService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Minecraft 1.21.1 bootstrap. Rendering replacement remains disabled until ownership is ported safely. */
public final class ThreadiumClient implements ClientModInitializer {
    public static final String MOD_ID = "threadium";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        PassThroughEntityRenderService.initialize();
        ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> PassThroughEntityRenderService.invalidateWorld());
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> PassThroughEntityRenderService.invalidateWorld());
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES)
                .registerReloadListener(new BackportResourceReloadListener());
        LOGGER.info("Threadium 1.21.1 backport bootstrap initialized; rendering replacement is disabled");
    }

    private static final class BackportResourceReloadListener implements SimpleSynchronousResourceReloadListener {
        private static final Identifier ID = Identifier.of(MOD_ID, "backport-resource-generation");

        @Override
        public Identifier getFabricId() {
            return ID;
        }

        @Override
        public void reload(ResourceManager manager) {
            PassThroughEntityRenderService.invalidateResources();
        }
    }
}
