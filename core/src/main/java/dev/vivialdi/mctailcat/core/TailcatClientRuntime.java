package dev.vivialdi.mctailcat.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
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

    /**
     * The runtime this launch is using, for anything that needs to reach it
     * without having been handed a reference — a user interface added by a
     * companion mod, most of all. Null before the client starts and after it
     * stops.
     */
    private static volatile TailcatClientRuntime active;

    private final List<TcpForwarder> forwarders = new CopyOnWriteArrayList<>();
    private final Map<ServerTarget, Integer> assignedPorts = new LinkedHashMap<>();

    private ClientConfig config;
    private Path configFile;
    private CompletableFuture<Path> executable;

    public TailcatClientRuntime(Path gameDir, Path configDir) {
        this.gameDir = gameDir;
        this.configDir = configDir;
    }

    /** The running client runtime, or null if there isn't one. */
    public static TailcatClientRuntime current() {
        return active;
    }

    public void start() {
        configFile = configDir.resolve("tailcat-client.json");
        config = ClientConfig.load(configFile);
        active = this;
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
        executable = new CompletableFuture<>();
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

    /**
     * Adds a server while the game is running, as if the player had put it in
     * their config and relaunched.
     *
     * <p>The whole point is that they do not have to relaunch: the port is
     * bound, the multiplayer list is written, and the tunnel comes up behind
     * it, all before this returns. Minecraft re-reads {@code servers.dat} when
     * the multiplayer screen is opened, so the entry is there the next time the
     * player looks at it.
     *
     * <p>Unlike a discovered server this one <em>is</em> written to the
     * player's config, because they typed it: it is theirs, and it should
     * survive the next launch.
     *
     * @return an empty string on success, or a reason the address was refused
     */
    public synchronized String addServer(String address, String name, int port) {
        if (config == null) {
            return "Tailcat is not running.";
        }
        String trimmed = address == null ? "" : address.trim();
        // Accept a whole pasted line, which is usually what an operator sends.
        String found = NetworkDescriptor.findAddress(trimmed);
        if (found == null) {
            return "That does not contain a Tailcat address.";
        }
        int remotePort = port > 0 && port <= 65535 ? port : ServerProperties.DEFAULT_PORT;
        String label = name == null || name.isBlank() ? "Tailcat Server" : name.trim();

        for (ServerTarget existing : assignedPorts.keySet()) {
            if (existing.address().equals(found)) {
                // A tunnel is already up for this address, but the player may
                // have deleted the multiplayer entry and be adding it back --
                // which is the only way they can, short of relaunching. So put
                // the entry back rather than refusing and leaving them stuck.
                if (config.addToServerList) {
                    updateServerList(List.of(existing));
                }
                Log.info("Tailcat server at that address was already running; "
                        + "made sure it is in the multiplayer list");
                return "";
            }
        }

        ClientConfig.Entry entry = new ClientConfig.Entry(label, found, remotePort);
        config.servers.add(entry);
        config.save(configFile);

        ServerTarget target = ServerTarget.of(entry);
        if (executable == null) {
            // start() bailed out early, so nothing is resolving the binary.
            executable = new CompletableFuture<>();
            Thread thread = new Thread(() -> resolveExecutable(executable), "tailcat-client-start");
            thread.setDaemon(true);
            thread.start();
        }
        openListeners(List.of(target), executable);
        if (!assignedPorts.containsKey(target)) {
            return "No free local port was available for that server.";
        }
        if (config.addToServerList) {
            updateServerList(List.of(target));
        }
        Log.info("Added Tailcat server '" + label + "' at the player's request");
        return "";
    }

    /**
     * The servers the player typed in themselves, which are the only ones they
     * can remove — a server their modpack ships comes back from the published
     * file whatever anyone does.
     */
    public synchronized List<ClientConfig.Entry> typedServers() {
        return config == null ? List.of() : List.copyOf(config.servers);
    }

    /**
     * Forgets a server the player added.
     *
     * <p>Deleting the row in the multiplayer screen is not enough on its own:
     * the entry is written again at the next launch from the config, so a
     * mistyped address would otherwise haunt the list forever with no way to
     * be rid of it. This drops it from the config, closes its tunnel, and
     * takes the row out of the list.
     *
     * @return true if there was such a server to forget
     */
    public synchronized boolean removeServer(String address) {
        if (config == null || address == null) {
            return false;
        }
        String wanted = address.trim();
        if (!config.servers.removeIf(entry -> wanted.equals(entry.address))) {
            return false;
        }
        config.save(configFile);

        ServerTarget target = null;
        for (ServerTarget candidate : assignedPorts.keySet()) {
            if (candidate.address().equals(wanted)) {
                target = candidate;
                break;
            }
        }
        if (target != null) {
            Integer port = assignedPorts.remove(target);
            for (TcpForwarder forwarder : forwarders) {
                if (port != null && forwarder.localPort() == port) {
                    forwarder.close();
                    forwarders.remove(forwarder);
                    break;
                }
            }
            if (config.addToServerList) {
                Path file = gameDir.resolve("servers.dat");
                ServerListFile list = ServerListFile.load(file);
                if (list != null && list.remove(target.displayName(config.serverListSuffix))) {
                    try {
                        list.save();
                    } catch (IOException e) {
                        Log.error("Could not update " + file, e);
                    }
                }
            }
        }
        Log.info("Forgot the Tailcat server at " + wanted);
        return true;
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
        updateServerList(assignedPorts.keySet());
    }

    /**
     * Writes list entries for just these servers.
     *
     * <p>Which servers matters. At startup it is all of them, but when the
     * player adds one it must be that one alone: rewriting every entry would
     * resurrect rows the player had deleted, so adding one server would
     * silently bring back another they had just got rid of.
     */
    private void updateServerList(Collection<ServerTarget> targets) {
        Path file = gameDir.resolve("servers.dat");
        ServerListFile list = ServerListFile.load(file);
        if (list == null) {
            return;
        }

        boolean changed = false;
        for (ServerTarget target : targets) {
            Integer port = assignedPorts.get(target);
            if (port == null) {
                continue;
            }
            changed |= list.upsert(target.displayName(config.serverListSuffix),
                    PortAllocator.localAddress(port));
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
        if (active == this) {
            active = null;
        }
    }
}
