package dev.vivialdi.mctailcat.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Everything the client does: work out which servers it should reach, put them
 * in the multiplayer list, and stand up the loopback tunnels that back them.
 *
 * <p>Servers arrive from three places, in this order: the player's own config,
 * the sources they pointed {@code importFrom} at, and any descriptor discovered
 * in the standard locations -- which is how a modpack ships a server without
 * the player configuring anything. The player's entries come first so their own
 * name for a server survives, but settings the operator locked in still apply
 * however the server was found.
 *
 * <p>Split deliberately into a fast synchronous phase and a slow background
 * one. Reserving each server's local port, binding it, and writing the
 * multiplayer list are all cheap, and all happen before this returns; only
 * fetching the tailcat binary, which on a first launch means a download, is
 * left to the background. So the entry a player sees is backed by a real
 * listener from the moment the game finishes loading, and a connection made
 * while the download is still running waits for it rather than being refused.
 */
public final class TailcatClientRuntime {

    private final Path gameDir;
    private final Path configDir;

    private final List<TcpForwarder> forwarders = new CopyOnWriteArrayList<>();
    private final Map<ServerTarget, Integer> assignedPorts = new LinkedHashMap<>();

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

        List<ServerTarget> targets = resolveTargets();
        if (targets.isEmpty()) {
            Log.info("No Tailcat servers configured yet. Drop the tailcat-network.json your"
                    + " server published into " + configDir.toAbsolutePath()
                    + ", or add an address to " + configFile.toAbsolutePath() + ".");
            return;
        }

        // Bind every port before writing the list, so the entry a player sees
        // is never ahead of the listener behind it.
        CompletableFuture<Path> executable = new CompletableFuture<>();
        openListeners(targets, executable);
        if (config.addToServerList) {
            updateServerList();
        }

        Thread thread = new Thread(() -> resolveExecutable(executable), "tailcat-client-start");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Every server this client should reach, with duplicates folded together.
     *
     * <p>Discovered servers are deliberately not written back into the player's
     * config: the file the operator published stays authoritative for them, so
     * a pack update that changes the address simply takes effect instead of
     * leaving a stale entry behind next to the new one.
     */
    private List<ServerTarget> resolveTargets() {
        List<ServerTarget> targets = new ArrayList<>();

        for (ClientConfig.Entry entry : config.servers) {
            if (entry.isUsable()) {
                targets.add(ServerTarget.of(entry));
            } else if (entry.enabled && !entry.address.isBlank()) {
                Log.warn("Ignoring Tailcat server '" + entry.name + "': '" + entry.address
                        + "' is not a valid tailcat address");
            }
        }

        if (config.autoDiscover) {
            for (DescriptorSource.Found found : DescriptorSource.discover(gameDir, configDir)) {
                NetworkDescriptor descriptor = found.descriptor();
                if (!descriptor.isUsable()) {
                    Log.warn("Tailcat details in " + found.origin()
                            + " are incomplete; ignoring them");
                    continue;
                }
                Log.info("Found Tailcat server '" + descriptor.name() + "' in " + found.origin());
                targets.add(ServerTarget.of(descriptor, found.origin()));
            }
        }

        return ServerTarget.deduplicate(targets);
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

    /**
     * Binds a loopback port for every server, before the tailcat binary that
     * will carry their traffic is known.
     *
     * <p>Binding is a local syscall and costs nothing worth deferring, while
     * fetching the binary can mean a download. Doing them in this order is what
     * makes the entry written next correct on a first launch rather than a
     * launch later: the port is live immediately, and a connection that beats
     * the download waits inside {@link TcpForwarder} for it.
     */
    private void openListeners(List<ServerTarget> targets, CompletableFuture<Path> executable) {
        Path stateDir = config.isolateState ? gameDir.resolve("tailcat").resolve("state") : null;

        for (ServerTarget target : targets) {
            int localPort = PortAllocator.allocate(target.address(), target.port());
            if (localPort < 0) {
                Log.error("No free local port for Tailcat server '" + target.name() + "'");
                continue;
            }

            TcpForwarder forwarder =
                    new TcpForwarder(executable, stateDir, target.address(), target.port(),
                            localPort, target.effectiveArgs(config.tailcatArgs));
            try {
                forwarder.start();
                forwarders.add(forwarder);
                assignedPorts.put(target, localPort);
                Log.info("Tailcat server '" + target.name() + "' is ready at "
                        + forwarder.localAddress());
            } catch (IOException e) {
                Log.error("Could not open a local port for Tailcat server '" + target.name() + "'", e);
            }
        }
    }

    /**
     * Finds the tailcat binary, downloading it if this is a first launch, and
     * hands it to the listeners already waiting on it.
     *
     * <p>If it cannot be had, the listeners are closed rather than left
     * accepting connections they can never carry: a refused connection shows up
     * as a server that is down, which is the truth, where a socket that accepts
     * and then hangs looks like a broken server instead of a missing tool.
     */
    private void resolveExecutable(CompletableFuture<Path> executable) {
        Path installDir = gameDir.resolve("tailcat");
        try {
            executable.complete(
                    TailcatBinary.resolve(installDir, config.tailcatPath, config.downloadTailcat));
        } catch (IOException e) {
            Log.error("Tailcat is unavailable, so its servers cannot be reached", e);
            executable.completeExceptionally(e);
            stop();
        }
    }

    private void updateServerList() {
        Path file = gameDir.resolve("servers.dat");
        ServerListFile list = ServerListFile.load(file);
        if (list == null) {
            return;
        }

        boolean changed = false;
        for (Map.Entry<ServerTarget, Integer> assignment : assignedPorts.entrySet()) {
            ServerTarget target = assignment.getKey();
            changed |= list.upsert(target.displayName(config.serverListSuffix),
                    "127.0.0.1:" + assignment.getValue());
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

    public void stop() {
        for (TcpForwarder forwarder : forwarders) {
            forwarder.close();
        }
        forwarders.clear();
    }
}
