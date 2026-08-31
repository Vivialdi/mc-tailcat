package dev.vivialdi.mctailcat.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The handoff between a server and its players: everything a client needs to
 * reach the server over tailcat.
 *
 * <p>The dedicated server writes this file on startup; clients read it from a
 * shared path, a URL, or a pasted address. It is the only thing that has to
 * travel from operator to player.
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

    private final String name;
    private final String address;
    private final int port;
    private final String motd;
    private final String updatedAt;

    public NetworkDescriptor(String name, String address, int port, String motd, String updatedAt) {
        this.name = name;
        this.address = address;
        this.port = port;
        this.motd = motd;
        this.updatedAt = updatedAt;
    }

    public static NetworkDescriptor of(String name, String address, int port, String motd) {
        return new NetworkDescriptor(name, address, port, motd, Instant.now().toString());
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
                Json.string(map, "updatedAt", ""));
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
