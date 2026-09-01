package dev.vivialdi.mctailcat.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One server the client will stand a tunnel up for, after every source has had
 * its say.
 *
 * <p>A player can name a server in their own config, import one from a path or
 * URL, and receive one from a descriptor their modpack ships -- and the same
 * server can arrive by more than one of those routes at once. Resolving all of
 * them into a single list keyed by address, before any port is reserved, is
 * what keeps a server from appearing twice in the multiplayer list and two
 * tunnels from being opened for it.
 *
 * <p>Settings the operator locked in ride along here rather than being folded
 * into the client's global config, because they are per-server: two servers on
 * two different self-hosted relays each need their own flags.
 */
public final class ServerTarget {

    private final String name;
    private final String address;
    private final int port;
    private final List<String> lockedArgs;
    private final String lockedSuffix;
    private final String origin;

    private ServerTarget(String name, String address, int port, List<String> lockedArgs,
            String lockedSuffix, String origin) {
        this.name = name;
        this.address = address;
        this.port = port;
        this.lockedArgs = Collections.unmodifiableList(new ArrayList<>(lockedArgs));
        this.lockedSuffix = lockedSuffix;
        this.origin = origin;
    }

    /** A server the player wrote into {@code tailcat-client.json} themselves. */
    public static ServerTarget of(ClientConfig.Entry entry) {
        return new ServerTarget(entry.name, entry.address.trim(), entry.port,
                List.of(), null, "config/tailcat-client.json");
    }

    /** A server described by a published {@code tailcat-network.json}. */
    public static ServerTarget of(NetworkDescriptor descriptor, String origin) {
        NetworkDescriptor.ClientSettings settings = descriptor.client();
        return new ServerTarget(descriptor.name(), descriptor.address().trim(), descriptor.port(),
                settings.tailcatArgs(), settings.serverListSuffix(), origin);
    }

    public String name() {
        return name;
    }

    public String address() {
        return address;
    }

    public int port() {
        return port;
    }

    /** Where this server came from, for logs the operator will have to read. */
    public String origin() {
        return origin;
    }

    public boolean isUsable() {
        return NetworkDescriptor.isValidAddress(address) && port > 0 && port <= 65535;
    }

    /**
     * The player's own flags, then the operator's.
     *
     * <p>Order matters if the same flag appears twice -- tailcat takes the last
     * one -- and the operator's belongs last on purpose: a wrong relay is a
     * server nobody can reach, which is worse than a player's preference being
     * overridden.
     */
    public List<String> effectiveArgs(List<String> playerArgs) {
        List<String> args = new ArrayList<>();
        if (playerArgs != null) {
            args.addAll(playerArgs);
        }
        for (String locked : lockedArgs) {
            if (!args.contains(locked)) {
                args.add(locked);
            }
        }
        return args;
    }

    /** The multiplayer list name, using the operator's suffix if they set one. */
    public String displayName(String playerSuffix) {
        String suffix = lockedSuffix != null ? lockedSuffix
                : (playerSuffix == null ? "" : playerSuffix);
        return name + suffix;
    }

    /**
     * Folds a duplicate of this server into it: the first source to name a
     * server keeps the name, and every source's locked-in flags apply.
     */
    public ServerTarget mergedWith(ServerTarget other) {
        List<String> args = new ArrayList<>(lockedArgs);
        for (String arg : other.lockedArgs) {
            if (!args.contains(arg)) {
                args.add(arg);
            }
        }
        return new ServerTarget(name, address, port, args,
                lockedSuffix != null ? lockedSuffix : other.lockedSuffix, origin);
    }

    /**
     * Resolves a list that may contain the same server more than once, keeping
     * the first mention of each address and merging the rest into it.
     */
    public static List<ServerTarget> deduplicate(List<ServerTarget> targets) {
        Map<String, ServerTarget> byAddress = new LinkedHashMap<>();
        for (ServerTarget target : targets) {
            ServerTarget existing = byAddress.get(target.address);
            byAddress.put(target.address, existing == null ? target : existing.mergedWith(target));
        }
        return new ArrayList<>(byAddress.values());
    }

    @Override
    public String toString() {
        return "ServerTarget{name=" + name + ", address=" + address + ", port=" + port + '}';
    }
}
