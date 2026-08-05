package dev.alex.threadium;

import dev.alex.threadium.render.entity.ModelPartReplacementService;
import dev.alex.threadium.render.entity.PassThroughEntityRenderService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Minecraft 1.21.1 bootstrap with an opt-in, fail-closed cached ModelPart replacement path. */
public final class ThreadiumClient implements ClientModInitializer {
    public static final String MOD_ID = "threadium";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        PassThroughEntityRenderService.initialize();
        ModelPartReplacementService.initialize();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> invalidateWorld());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> invalidateWorld());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            ModelPartReplacementService.shutdown();
            PassThroughEntityRenderService.shutdown();
        });
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES)
                .registerReloadListener(new BackportResourceReloadListener());
        LOGGER.info(
                "Threadium 1.21.1 backport initialized; experimental cached replacement is {}",
                ModelPartReplacementService.configured() ? "enabled" : "disabled");
    }

    private static void invalidateWorld() {
        ModelPartReplacementService.invalidateWorld();
        PassThroughEntityRenderService.invalidateWorld();
    }

    private static final class BackportResourceReloadListener implements SimpleSynchronousResourceReloadListener {
        private static final Identifier ID = Identifier.of(MOD_ID, "backport-resource-generation");

        @Override
        public Identifier getFabricId() {
            return ID;
        }

        @Override
        public void reload(ResourceManager manager) {
            ModelPartReplacementService.invalidateResources();
            PassThroughEntityRenderService.invalidateResources();
        }
    }
}
