package dev.vivialdi.mctailcat.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the client does: import server details, put them in the
 * multiplayer list, and stand up the loopback tunnels that back them.
 *
 * <p>Split deliberately into a fast synchronous phase and a slow background
 * one. The multiplayer list is written up front using each server's
 * <em>deterministic</em> local port, so the entry is correct before the player
 * could possibly reach the multiplayer screen, even though the tunnel behind
 * it is still coming up.
 */
public final class TailcatClientRuntime {

    private final Path gameDir;
    private final Path configDir;

    private final List<TcpForwarder> forwarders = new ArrayList<>();
    private final Map<ClientConfig.Entry, Integer> assignedPorts = new LinkedHashMap<>();

    private ClientConfig config;

    public TailcatClientRuntime(Path gameDir, Path configDir) {
        this.gameDir = gameDir;
        this.configDir = configDir;
    }

    public void start() {
        Path configFile = configDir.resolve("tailcat-client.json");
        config = ClientConfig.load(configFile);
        if (!config.enabled) {
            Log.info("Tailcat is disabled in config/tailcat-client.json");
            return;
        }

        if (importConfiguredSources()) {
            config.save(configFile);
        }

        List<ClientConfig.Entry> usable = new ArrayList<>();
        for (ClientConfig.Entry entry : config.servers) {
            if (entry.isUsable()) {
                usable.add(entry);
            } else if (entry.enabled && !entry.address.isBlank()) {
                Log.warn("Ignoring Tailcat server '" + entry.name + "': '" + entry.address
                        + "' is not a valid tailcat address");
            }
        }

        if (usable.isEmpty()) {
            Log.info("No Tailcat servers configured yet. Add one to "
                    + configFile.toAbsolutePath() + " -- or point importFrom at the"
                    + " tailcat-network.json your server published.");
            return;
        }

        // Reserve the deterministic port for each server, then write the list
        // immediately so it is ready before the player opens Multiplayer.
        for (ClientConfig.Entry entry : usable) {
            assignedPorts.put(entry, PortAllocator.preferredPort(entry.address, entry.port));
        }
        if (config.addToServerList) {
            updateServerList();
        }

        Thread thread = new Thread(() -> startForwarders(usable), "tailcat-client-start");
        thread.setDaemon(true);
        thread.start();
    }

    /** Pulls in descriptors from every configured source. Returns true if config changed. */
    private boolean importConfiguredSources() {
        boolean changed = false;
        for (String source : config.importFrom) {
            NetworkDescriptor descriptor = DescriptorSource.load(source);
            if (descriptor == null) {
                continue;
            }
            if (!descriptor.isUsable()) {
                Log.warn("Tailcat details from '" + source + "' are incomplete; ignoring them");
                continue;
            }
            if (config.merge(descriptor)) {
                Log.info("Imported Tailcat server '" + descriptor.name() + "' from " + source);
                changed = true;
            }
        }
        return changed;
    }

    private void startForwarders(List<ClientConfig.Entry> usable) {
        Path installDir = gameDir.resolve("tailcat");
        Path stateDir = config.isolateState ? installDir.resolve("state") : null;

        Path executable;
        try {
            executable = TailcatBinary.resolve(installDir, config.tailcatPath, config.downloadTailcat);
        } catch (IOException e) {
            Log.error("Tailcat is unavailable, so its servers cannot be reached", e);
            return;
        }

        boolean portsMoved = false;
        for (ClientConfig.Entry entry : usable) {
            int preferred = assignedPorts.getOrDefault(entry, -1);
            int localPort = preferred >= 0 && PortAllocator.isAvailable(preferred)
                    ? preferred
                    : PortAllocator.allocate(entry.address, entry.port);

            if (localPort < 0) {
                Log.error("No free local port for Tailcat server '" + entry.name + "'");
                continue;
            }
            if (localPort != preferred) {
                assignedPorts.put(entry, localPort);
                portsMoved = true;
            }

            TcpForwarder forwarder =
                    new TcpForwarder(executable, stateDir, entry.address, entry.port, localPort);
            try {
                forwarder.start();
                forwarders.add(forwarder);
                Log.info("Tailcat server '" + entry.name + "' is ready at " + forwarder.localAddress());
            } catch (IOException e) {
                Log.error("Could not open a local port for Tailcat server '" + entry.name + "'", e);
            }
        }

        // Only rewrite if a port had to move, so the common case touches the
        // player's server list exactly once per launch.
        if (portsMoved && config.addToServerList) {
            updateServerList();
        }
    }

    private void updateServerList() {
        Path file = gameDir.resolve("servers.dat");
        ServerListFile list = ServerListFile.load(file);
        if (list == null) {
            return;
        }

        boolean changed = false;
        for (Map.Entry<ClientConfig.Entry, Integer> assignment : assignedPorts.entrySet()) {
            ClientConfig.Entry entry = assignment.getKey();
            changed |= list.upsert(displayName(entry), "127.0.0.1:" + assignment.getValue());
        }

        if (!changed) {
            return;
        }
        try {
            list.save();
            Log.info("Updated the multiplayer server list at " + file.toAbsolutePath());
        } catch (IOException e) {
            Log.error("Could not update " + file, e);
        }
    }

    private String displayName(ClientConfig.Entry entry) {
        String suffix = config.serverListSuffix == null ? "" : config.serverListSuffix;
        return entry.name + suffix;
    }

    public void stop() {
        for (TcpForwarder forwarder : forwarders) {
            forwarder.close();
        }
        forwarders.clear();
    }
}
