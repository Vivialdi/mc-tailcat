package dev.vivialdi.mctailcat.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Reads the dedicated server's {@code server.properties}.
 *
 * <p>Going to the file rather than the running server object keeps this module
 * free of Minecraft types, and the file's format has been unchanged for the
 * whole modern era.
 */
public final class ServerProperties {

    public static final int DEFAULT_PORT = 25565;

    private final Properties properties = new Properties();

    private ServerProperties() {
    }

    public static ServerProperties load(Path file) {
        ServerProperties result = new ServerProperties();
        if (!Files.isRegularFile(file)) {
            Log.warn("No server.properties at " + file + "; assuming port " + DEFAULT_PORT);
            return result;
        }
        try (InputStream in = Files.newInputStream(file)) {
            // Properties.load applies the ISO-8859-1 + \\uXXXX rules the game
            // writes the file with.
            result.properties.load(in);
        } catch (IOException e) {
            Log.error("Could not read " + file + "; assuming port " + DEFAULT_PORT, e);
        }
        return result;
    }

    /** The port the server listens on, falling back to the vanilla default. */
    public int port() {
        String value = properties.getProperty("server-port", "").trim();
        if (!value.isEmpty()) {
            try {
                int port = Integer.parseInt(value);
                if (port > 0 && port <= 65535) {
                    return port;
                }
                Log.warn("server-port=" + port + " is out of range; using " + DEFAULT_PORT);
            } catch (NumberFormatException e) {
                Log.warn("server-port='" + value + "' is not a number; using " + DEFAULT_PORT);
            }
        }
        return DEFAULT_PORT;
    }

    /**
     * The interface the server binds to, or empty for all interfaces.
     *
     * <p>Worth surfacing because a server bound to a single public address will
     * refuse the loopback connection tailcat hands it.
     */
    public String boundAddress() {
        return properties.getProperty("server-ip", "").trim();
    }

    public String motd() {
        return properties.getProperty("motd", "").trim();
    }

    public String get(String key, String fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : value.trim();
    }
}
