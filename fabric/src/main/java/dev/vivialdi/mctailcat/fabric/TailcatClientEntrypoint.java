package dev.vivialdi.mctailcat.fabric;

import dev.vivialdi.mctailcat.core.Log;
import dev.vivialdi.mctailcat.core.TailcatClientRuntime;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Client entrypoint.
 *
 * <p>Runs before the game builds its multiplayer screen, so the server list is
 * already updated by the time a player can look at it.
 */
public final class TailcatClientEntrypoint implements ClientModInitializer {

    private static TailcatClientRuntime runtime;

    @Override
    public void onInitializeClient() {
        FabricLoader loader = FabricLoader.getInstance();
        Log.info("Starting Tailcat for the client");

        runtime = new TailcatClientRuntime(loader.getGameDir(), loader.getConfigDir());
        runtime.start();

        Runtime.getRuntime().addShutdownHook(new Thread(TailcatClientEntrypoint::shutdown,
                "tailcat-client-shutdown"));
    }

    private static void shutdown() {
        if (runtime != null) {
            runtime.stop();
        }
    }
}
