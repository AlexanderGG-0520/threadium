package dev.alex.threadium.compat;

import dev.alex.threadium.ThreadiumClient;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.loader.api.FabricLoader;

/** Optional, fail-closed Iris shader-pack gate without a hard Iris dependency. */
public final class IrisCompatibility {
    private static final String IRIS_API = "net.irisshaders.iris.api.v0.IrisApi";
    private static final boolean IRIS_LOADED = FabricLoader.getInstance().isModLoaded("iris");
    private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean();
    private static final Method GET_INSTANCE;
    private static final Method IS_SHADER_PACK_IN_USE;
    private static volatile boolean shaderPackInUse;

    static {
        Method getInstance = null;
        Method isShaderPackInUse = null;
        if (IRIS_LOADED) {
            try {
                Class<?> api = Class.forName(IRIS_API, false, IrisCompatibility.class.getClassLoader());
                getInstance = api.getMethod("getInstance");
                isShaderPackInUse = api.getMethod("isShaderPackInUse");
            } catch (Throwable failure) {
                logFailure(failure);
            }
        }
        GET_INSTANCE = getInstance;
        IS_SHADER_PACK_IN_USE = isShaderPackInUse;
        shaderPackInUse = queryShaderPackState();
    }

    private IrisCompatibility() {}

    public static boolean replacementAllowed() {
        return !shaderPackInUse;
    }

    /** Re-evaluates Iris state after a client resource reload, including shader-pack changes. */
    public static void refresh() {
        shaderPackInUse = queryShaderPackState();
    }

    static boolean shouldDisable(boolean irisLoaded, boolean apiAvailable, Boolean active) {
        if (!irisLoaded) return false;
        if (!apiAvailable) return true;
        return !Boolean.FALSE.equals(active);
    }

    private static boolean queryShaderPackState() {
        if (!IRIS_LOADED) return false;
        if (GET_INSTANCE == null || IS_SHADER_PACK_IN_USE == null) return true;
        try {
            Object api = GET_INSTANCE.invoke(null);
            Object result = IS_SHADER_PACK_IN_USE.invoke(api);
            return shouldDisable(true, true, result instanceof Boolean value ? value : null);
        } catch (Throwable failure) {
            logFailure(failure);
            return true;
        }
    }

    private static void logFailure(Throwable failure) {
        if (FAILURE_LOGGED.compareAndSet(false, true)) {
            ThreadiumClient.LOGGER.error(
                    "Unable to query Iris shader state; disabling Minecraft 1.21.1 ModelPart replacement for safety",
                    failure);
        }
    }
}
