package dev.vivialdi.mctailcat.neoforge;

import dev.vivialdi.mctailcat.core.Log;
import dev.vivialdi.mctailcat.core.TailcatClientRuntime;
import dev.vivialdi.mctailcat.core.TailcatServerRuntime;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.neoforged.fml.loading.FMLPaths;

/**
 * The NeoForge adapter: the same runtimes the Fabric entrypoints start, hung
 * off NeoForge's lifecycle events instead.
 *
 * <p>Deliberately as thin as the Fabric side. Everything that matters lives in
 * {@code core}, which has no idea which loader it is running under; this
 * class only answers "when do we start" and "where are the directories".
 */
@Mod("tailcat")
public final class TailcatNeoForge {

    private static TailcatServerRuntime server;
    private static TailcatClientRuntime client;

    public TailcatNeoForge(IEventBus modBus) {
        modBus.addListener(this::onServerSetup);
        modBus.addListener(this::onClientSetup);
    }

    private void onServerSetup(FMLDedicatedServerSetupEvent event) {
        Log.info("Starting Tailcat for the dedicated server");
        server = new TailcatServerRuntime(FMLPaths.GAMEDIR.get(), FMLPaths.CONFIGDIR.get());
        server.start();

        // A shutdown hook rather than a lifecycle event, as on Fabric: it fires
        // on /stop, on Ctrl-C, and on a crash alike.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (server != null) {
                server.stop();
            }
        }, "tailcat-server-shutdown"));
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        Log.info("Starting Tailcat for the client");
        client = new TailcatClientRuntime(FMLPaths.GAMEDIR.get(), FMLPaths.CONFIGDIR.get());
        client.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (client != null) {
                client.stop();
            }
        }, "tailcat-client-shutdown"));
    }

    /** The published server details, or null if Tailcat is not up yet. */
    public static TailcatServerRuntime serverRuntime() {
        return server;
    }
}
