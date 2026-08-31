package dev.vivialdi.mctailcat.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Dedicated-server side settings, stored at {@code config/tailcat-server.json}. */
public final class ServerConfig {

    public boolean enabled = true;

    /**
     * Display name players see in their multiplayer list. Empty means "derive
     * one from the server's MOTD".
     */
    public String serverName = "";

    /** Explicit path to a tailcat executable. Empty means find or download one. */
    public String tailcatPath = "";

    public boolean downloadTailcat = true;

    /**
     * Name of the saved tailcat key. Keeping a named key is what makes the
     * server's address survive a restart.
     */
    public String keyName = "minecraft";

    /**
     * Ask tailcat for a long, self-contained address that embeds relay details.
     * Slightly more robust for players on locked-down networks, but longer to
     * share.
     */
    public boolean fullAddress = false;

    /**
     * Bake a fixed DERP relay region into the saved key.
     *
     * <p>This is what actually keeps the published address stable. Without it
     * tailcat re-selects a region by latency at every startup and the address
     * changes with it, breaking entries players have already saved. Turn it off
     * only if the server moves between regions and you would rather have the
     * best relay than a stable address; changing it takes effect when the key
     * is next created, so also change keyName or delete the existing key.
     */
    public boolean fixedRegion = true;

    /** Port to expose. 0 means "read server-port from server.properties". */
    public int port = 0;

    /**
     * Where to write the file players need. Empty means
     * {@code <game dir>/tailcat-network.json}.
     */
    public String publishPath = "";

    /**
     * Keep tailcat's saved keys inside the server directory rather than the
     * host user's home. Makes the install self-contained and portable.
     */
    public boolean isolateState = true;

    /**
     * Extra flags passed to every tailcat invocation, before the positional
     * arguments tailcat requires them to precede.
     *
     * <p>Mainly for {@code --derpmap-url=...} when running your own relay, or
     * {@code --allow=nodekey:...} to restrict which clients may connect.
     */
    public java.util.List<String> tailcatArgs = new java.util.ArrayList<>();

    public static ServerConfig load(Path file) {
        ServerConfig config = new ServerConfig();
        if (Files.isRegularFile(file)) {
            try {
                Map<String, Object> map = Json.parseObject(
                        Files.readString(file, StandardCharsets.UTF_8));
                config.enabled = Json.bool(map, "enabled", config.enabled);
                config.serverName = Json.string(map, "serverName", config.serverName);
                config.tailcatPath = Json.string(map, "tailcatPath", config.tailcatPath);
                config.downloadTailcat = Json.bool(map, "downloadTailcat", config.downloadTailcat);
                config.keyName = Json.string(map, "keyName", config.keyName);
                config.fullAddress = Json.bool(map, "fullAddress", config.fullAddress);
                config.fixedRegion = Json.bool(map, "fixedRegion", config.fixedRegion);
                config.port = Json.integer(map, "port", config.port);
                config.publishPath = Json.string(map, "publishPath", config.publishPath);
                config.isolateState = Json.bool(map, "isolateState", config.isolateState);
                config.tailcatArgs = new java.util.ArrayList<>();
                for (Object item : Json.array(map, "tailcatArgs")) {
                    if (item instanceof String && !((String) item).isBlank()) {
                        config.tailcatArgs.add(((String) item).trim());
                    }
                }
            } catch (IOException | RuntimeException e) {
                Log.error("Could not read " + file + "; using defaults", e);
            }
        }
        // Rewrite on every load so a new option shows up in the file the first
        // time an operator runs a version that has it.
        config.save(file);
        return config;
    }

    public void save(Path file) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", enabled);
        map.put("serverName", serverName);
        map.put("tailcatPath", tailcatPath);
        map.put("downloadTailcat", downloadTailcat);
        map.put("keyName", keyName);
        map.put("fullAddress", fullAddress);
        map.put("fixedRegion", fixedRegion);
        map.put("port", port);
        map.put("publishPath", publishPath);
        map.put("isolateState", isolateState);
        map.put("tailcatArgs", new java.util.ArrayList<Object>(tailcatArgs));
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, Json.write(map), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.error("Could not write " + file, e);
        }
    }
}
