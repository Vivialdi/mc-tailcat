package dev.vivialdi.mctailcat.fabric;

import dev.vivialdi.mctailcat.core.Log;
import dev.vivialdi.mctailcat.core.TailcatServerRuntime;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Dedicated-server entrypoint.
 *
 * <p>Note what this class does <em>not</em> touch: no Minecraft types, no
 * Fabric API, no mixins. Only Fabric Loader's own entrypoint interface and its
 * directory lookups, both of which have been stable across every modern
 * Minecraft release -- which is what lets one jar run on all of them.
 */
public final class TailcatServerEntrypoint implements DedicatedServerModInitializer {

    private static TailcatServerRuntime runtime;

    @Override
    public void onInitializeServer() {
        FabricLoader loader = FabricLoader.getInstance();
        Log.info("Starting Tailcat for the dedicated server");

        runtime = new TailcatServerRuntime(loader.getGameDir(), loader.getConfigDir());
        runtime.start();

        // A shutdown hook rather than a lifecycle event: it needs no Fabric API
        // dependency and still fires on /stop, on Ctrl-C, and on a crash.
        Runtime.getRuntime().addShutdownHook(new Thread(TailcatServerEntrypoint::shutdown,
                "tailcat-server-shutdown"));
    }

    private static void shutdown() {
        if (runtime != null) {
            runtime.stop();
        }
    }

    /** The published server details, or null if Tailcat is not up yet. */
    public static TailcatServerRuntime runtime() {
        return runtime;
    }
}
