package dev.vivialdi.mctailcat.core;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Everything the dedicated server does, in one place, with no Minecraft types
 * involved. Loader modules just call {@link #start} and {@link #stop}.
 */
public final class TailcatServerRuntime {

    private final Path gameDir;
    private final Path configDir;

    private ServerConfig config;
    private TailcatService service;
    private NetworkDescriptor descriptor;
    private Path publishedTo;

    public TailcatServerRuntime(Path gameDir, Path configDir) {
        this.gameDir = gameDir;
        this.configDir = configDir;
    }

    public NetworkDescriptor descriptor() {
        return descriptor;
    }

    public Path publishedTo() {
        return publishedTo;
    }

    /**
     * Brings the server onto tailcat and publishes the file players need.
     *
     * <p>Runs on a background thread: bringing up a tunnel, and possibly
     * downloading tailcat first, must not hold up the world loading.
     */
    public void start() {
        config = ServerConfig.load(configDir.resolve("tailcat-server.json"));
        if (!config.enabled) {
            Log.info("Tailcat is disabled in config/tailcat-server.json");
            return;
        }

        Thread thread = new Thread(this::startBlocking, "tailcat-server-start");
        thread.setDaemon(true);
        thread.start();
    }

    private void startBlocking() {
        try {
            ServerProperties properties = ServerProperties.load(gameDir.resolve("server.properties"));
            int port = config.port > 0 ? config.port : properties.port();

            String bound = properties.boundAddress();
            if (!bound.isEmpty() && !isLoopbackFriendly(bound)) {
                // tailcat hands connections to the server over loopback, which
                // a server bound to one external address will refuse.
                Log.warn("server-ip is set to '" + bound + "', so the server is not listening on"
                        + " 127.0.0.1 and Tailcat connections will be refused. Clear server-ip in"
                        + " server.properties to accept them.");
            }

            Path installDir = gameDir.resolve("tailcat");
            Path stateDir = config.isolateState ? installDir.resolve("state") : null;
            Path executable = TailcatBinary.resolve(installDir, config.tailcatPath, config.downloadTailcat);

            service = new TailcatService(executable, stateDir, installDir.resolve("address.txt"),
                    config.keyName, port, config.fullAddress, config.fixedRegion, config.tailcatArgs);
            // Publish from the callback so a late address -- a slow first run,
            // or a restart much later on -- is still written out.
            service.setAddressListener(address -> publish(address, port, properties));

            if (!service.start()) {
                Log.error("Tailcat did not come up in time. It is still being retried in the"
                        + " background; until it does, players will need a direct connection.");
            }
        } catch (IOException e) {
            Log.error("Tailcat setup failed; the server is running without it", e);
        }
    }

    private static boolean isLoopbackFriendly(String bound) {
        return bound.equals("0.0.0.0") || bound.equals("::") || bound.equals("127.0.0.1")
                || bound.equalsIgnoreCase("localhost");
    }

    private synchronized void publish(String address, int port, ServerProperties properties) {
        String name = config.serverName;
        if (name == null || name.isBlank()) {
            String motd = properties.motd();
            name = motd.isBlank() ? "Minecraft Server" : stripFormatting(motd);
        }

        descriptor = NetworkDescriptor.of(name, address, port, properties.motd(),
                config.clientSettings());

        Path target = config.publishPath == null || config.publishPath.isBlank()
                ? gameDir.resolve(DescriptorSource.DEFAULT_FILENAME)
                : Paths.get(config.publishPath);
        try {
            descriptor.write(target);
            publishedTo = target;
        } catch (IOException e) {
            Log.error("Could not write the Tailcat network file to " + target, e);
        }

        announce(address, target);
    }

    /** Strips section-sign colour codes so the name reads cleanly in a server list. */
    static String stripFormatting(String text) {
        return text.replaceAll("(?i)§[0-9a-fk-or]", "").strip();
    }

    private void announce(String address, Path target) {
        Log.info("");
        Log.info("=========================== Tailcat ===========================");
        Log.info(" This server is reachable over Tailcat. No port forwarding");
        Log.info(" needed -- give players either of the following.");
        Log.info("");
        Log.info(" Address: " + address);
        if (publishedTo != null) {
            Log.info(" File:    " + target.toAbsolutePath());
        }
        Log.info("");
        if (publishedTo != null) {
            Log.info(" Send that file to your players. They drop it into their");
            Log.info(" config/ folder -- or a modpack ships it there -- and the");
            Log.info(" server appears in their multiplayer list, ready to click,");
            Log.info(" the next time the game starts. Nothing to configure.");
        } else {
            Log.info(" Players with the Tailcat mod installed can paste the");
            Log.info(" address into config/tailcat-client.json. The server then");
            Log.info(" appears in their multiplayer list automatically the next");
            Log.info(" time the game starts.");
        }
        Log.info("");
        Log.info(" Treat the address like an invitation: anyone who has it can");
        Log.info(" reach this server's Minecraft port.");
        Log.info("===============================================================");
        Log.info("");
    }

    public void stop() {
        if (service != null) {
            service.close();
            service = null;
        }
    }
}
