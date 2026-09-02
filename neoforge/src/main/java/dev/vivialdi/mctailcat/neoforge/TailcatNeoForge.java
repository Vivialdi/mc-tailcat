package dev.vivialdi.mctailcat.neoforge;

import dev.vivialdi.mctailcat.core.Log;
import dev.vivialdi.mctailcat.core.TailcatClientRuntime;
import dev.vivialdi.mctailcat.core.TailcatServerRuntime;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        // Core logs to stdout by default. Fabric folds stdout into the game log
        // as [STDOUT] lines; NeoForge does not, so without this the banner with
        // the operator's address never reaches logs/latest.log -- which is the
        // file a hosting panel shows them. Route through the real logger.
        Logger logger = LoggerFactory.getLogger("Tailcat");
        Log.setSink(new Log.Sink() {
            @Override
            public void info(String message) {
                logger.info(message);
            }

            @Override
            public void warn(String message) {
                logger.warn(message);
            }

            @Override
            public void error(String message, Throwable error) {
                if (error != null) {
                    logger.error(message, error);
                } else {
                    logger.error(message);
                }
            }
        });

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
