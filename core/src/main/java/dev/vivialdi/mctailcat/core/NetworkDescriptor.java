package dev.vivialdi.mctailcat.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The handoff between a server and its players: everything a client needs to
 * reach the server over tailcat.
 *
 * <p>The dedicated server writes this file on startup; clients read it from a
 * shared path, a URL, a modpack that ships it, or a pasted address. It is the
 * only thing that has to travel from operator to player.
 */
public final class NetworkDescriptor {

    /** Bumped only if the field layout stops being backward compatible. */
    public static final int FORMAT_VERSION = 1;

    /**
     * A tailcat address: the literal {@code tc} prefix followed by base64url
     * payload. Matching loosely on purpose -- tailcat makes no wire-format
     * stability promise, so this rejects obvious mistakes without second
     * guessing the tool about what a valid address looks like.
     */
    private static final Pattern ADDRESS = Pattern.compile("tc[A-Za-z0-9_\\-]{16,}");

    /**
     * The client-side settings an operator locks in when publishing.
     *
     * <p>Some of what a player needs is not derivable from the address. A
     * server behind a self-hosted relay is the clear case: without the same
     * {@code --derpmap-url} the operator runs, a client cannot reach it at all,
     * and a correct address does not help. Carrying those flags in the
     * published file is what makes the handoff one artifact rather than a file
     * plus a paragraph of instructions.
     *
     * <p>Serialised as an optional {@code client} object, and omitted entirely
     * when nothing is locked in, so a file from a server that needs none of
     * this looks exactly as it always has.
     */
    public static final class ClientSettings {

        private static final ClientSettings NONE = new ClientSettings(null, null);

        private final List<String> tailcatArgs;
        private final String serverListSuffix;

        public ClientSettings(List<String> tailcatArgs, String serverListSuffix) {
            List<String> copy = new ArrayList<>();
            if (tailcatArgs != null) {
                for (String arg : tailcatArgs) {
                    if (arg != null && !arg.isBlank()) {
                        copy.add(arg.trim());
                    }
                }
            }
            this.tailcatArgs = Collections.unmodifiableList(copy);
            this.serverListSuffix = serverListSuffix;
        }

        /** The "operator locked nothing in" case. */
        public static ClientSettings none() {
            return NONE;
        }

        /** Flags every client must pass to tailcat to reach this server. */
        public List<String> tailcatArgs() {
            return tailcatArgs;
        }

        /** Suffix for the multiplayer list entry, or null to leave it to the player. */
        public String serverListSuffix() {
            return serverListSuffix;
        }

        public boolean isEmpty() {
            return tailcatArgs.isEmpty() && serverListSuffix == null;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            if (!tailcatArgs.isEmpty()) {
                map.put("tailcatArgs", new ArrayList<Object>(tailcatArgs));
            }
            if (serverListSuffix != null) {
                map.put("serverListSuffix", serverListSuffix);
            }
            return map;
        }

        static ClientSettings fromMap(Map<String, Object> map) {
            if (map == null || map.isEmpty()) {
                return NONE;
            }
            List<String> args = new ArrayList<>();
            for (Object item : Json.array(map, "tailcatArgs")) {
                if (item instanceof String && !((String) item).isBlank()) {
                    args.add(((String) item).trim());
                }
            }
            // Absent and empty differ: absent leaves the player's own suffix
            // alone, empty is an operator asking for no suffix at all.
            String suffix = map.containsKey("serverListSuffix")
                    ? Json.string(map, "serverListSuffix", "")
                    : null;
            return new ClientSettings(args, suffix);
        }
    }

    private final String name;
    private final String address;
    private final int port;
    private final String motd;
    private final String updatedAt;
    private final ClientSettings client;

    public NetworkDescriptor(String name, String address, int port, String motd, String updatedAt) {
        this(name, address, port, motd, updatedAt, ClientSettings.none());
    }

    public NetworkDescriptor(String name, String address, int port, String motd, String updatedAt,
            ClientSettings client) {
        this.name = name;
        this.address = address;
        this.port = port;
        this.motd = motd;
        this.updatedAt = updatedAt;
        this.client = client == null ? ClientSettings.none() : client;
    }

    public static NetworkDescriptor of(String name, String address, int port, String motd) {
        return of(name, address, port, motd, ClientSettings.none());
    }

    public static NetworkDescriptor of(String name, String address, int port, String motd,
            ClientSettings client) {
        return new NetworkDescriptor(name, address, port, motd, Instant.now().toString(), client);
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

    public String motd() {
        return motd;
    }

    public String updatedAt() {
        return updatedAt;
    }

    /** Never null; {@link ClientSettings#isEmpty()} when nothing was locked in. */
    public ClientSettings client() {
        return client;
    }

    public boolean isUsable() {
        return isValidAddress(address) && port > 0 && port <= 65535;
    }

    public static boolean isValidAddress(String address) {
        return address != null && ADDRESS.matcher(address.trim()).matches();
    }

    /**
     * Extracts the first tailcat address in a block of text.
     *
     * <p>Used both for scraping {@code tailcat serve}'s startup banner and for
     * accepting whatever a player pastes into their config, which is often the
     * whole line the operator copied.
     */
    public static String findAddress(String text) {
        if (text == null) {
            return null;
        }
        var matcher = ADDRESS.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("formatVersion", FORMAT_VERSION);
        map.put("name", name);
        map.put("address", address);
        map.put("port", port);
        map.put("motd", motd);
        map.put("updatedAt", updatedAt);
        if (!client.isEmpty()) {
            map.put("client", client.toMap());
        }
        return map;
    }

    public static NetworkDescriptor fromMap(Map<String, Object> map) {
        int version = Json.integer(map, "formatVersion", FORMAT_VERSION);
        if (version > FORMAT_VERSION) {
            Log.warn("Tailcat network file is format version " + version
                    + " but this mod understands " + FORMAT_VERSION + "; reading what it can");
        }
        return new NetworkDescriptor(
                Json.string(map, "name", "Tailcat Server"),
                Json.string(map, "address", "").trim(),
                Json.integer(map, "port", ServerProperties.DEFAULT_PORT),
                Json.string(map, "motd", ""),
                Json.string(map, "updatedAt", ""),
                ClientSettings.fromMap(Json.object(map.get("client"))));
    }

    public static NetworkDescriptor parse(String json) {
        return fromMap(Json.parseObject(json));
    }

    public String toJson() {
        return Json.write(toMap());
    }

    /** Writes atomically: a player reading the file mid-write must not see half of it. */
    public void write(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temp, toJson().getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static NetworkDescriptor read(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return "NetworkDescriptor{name=" + name + ", address=" + address + ", port=" + port + '}';
    }
}
