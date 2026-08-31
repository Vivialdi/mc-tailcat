package dev.vivialdi.mctailcat.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Client side settings, stored at {@code config/tailcat-client.json}. */
public final class ClientConfig {

    /** One server the player should be able to reach over tailcat. */
    public static final class Entry {
        public String name = "Tailcat Server";
        public String address = "";
        public int port = ServerProperties.DEFAULT_PORT;
        public boolean enabled = true;

        public Entry() {
        }

        public Entry(String name, String address, int port) {
            this.name = name;
            this.address = address;
            this.port = port;
        }

        public static Entry fromMap(Map<String, Object> map) {
            Entry entry = new Entry();
            entry.name = Json.string(map, "name", entry.name);
            entry.address = Json.string(map, "address", "").trim();
            entry.port = Json.integer(map, "port", entry.port);
            entry.enabled = Json.bool(map, "enabled", true);
            return entry;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("address", address);
            map.put("port", port);
            map.put("enabled", enabled);
            return map;
        }

        public boolean isUsable() {
            return enabled && NetworkDescriptor.isValidAddress(address)
                    && port > 0 && port <= 65535;
        }
    }

    public boolean enabled = true;

    public String tailcatPath = "";

    public boolean downloadTailcat = true;

    public boolean isolateState = true;

    /** Extra flags passed to every tailcat invocation, before positional arguments. */
    public List<String> tailcatArgs = new ArrayList<>();

    /** Add or refresh an entry in the multiplayer server list for each server. */
    public boolean addToServerList = true;

    /**
     * Suffix appended to each server's name in the multiplayer list, so a
     * Tailcat entry is recognisable next to a direct one. May be empty.
     */
    public String serverListSuffix = " (Tailcat)";

    /**
     * Places to pick server details up from automatically, checked on every
     * launch. Each may be a path to a {@code tailcat-network.json} written by
     * the server -- a shared folder, or the server directory itself when both
     * run on one machine -- or an {@code https://} URL.
     */
    public List<String> importFrom = new ArrayList<>();

    /** Servers to connect to, whether typed in by hand or imported. */
    public List<Entry> servers = new ArrayList<>();

    public static ClientConfig load(Path file) {
        ClientConfig config = new ClientConfig();
        if (Files.isRegularFile(file)) {
            try {
                Map<String, Object> map = Json.parseObject(
                        Files.readString(file, StandardCharsets.UTF_8));
                config.enabled = Json.bool(map, "enabled", config.enabled);
                config.tailcatPath = Json.string(map, "tailcatPath", config.tailcatPath);
                config.downloadTailcat = Json.bool(map, "downloadTailcat", config.downloadTailcat);
                config.isolateState = Json.bool(map, "isolateState", config.isolateState);
                config.tailcatArgs = new ArrayList<>();
                for (Object item : Json.array(map, "tailcatArgs")) {
                    if (item instanceof String && !((String) item).isBlank()) {
                        config.tailcatArgs.add(((String) item).trim());
                    }
                }
                config.addToServerList = Json.bool(map, "addToServerList", config.addToServerList);
                config.serverListSuffix = Json.string(map, "serverListSuffix", config.serverListSuffix);

                config.importFrom = new ArrayList<>();
                for (Object item : Json.array(map, "importFrom")) {
                    if (item instanceof String && !((String) item).isBlank()) {
                        config.importFrom.add(((String) item).trim());
                    }
                }

                config.servers = new ArrayList<>();
                for (Object item : Json.array(map, "servers")) {
                    config.servers.add(Entry.fromMap(Json.object(item)));
                }
            } catch (IOException | RuntimeException e) {
                Log.error("Could not read " + file + "; using defaults", e);
            }
        }
        config.save(file);
        return config;
    }

    /**
     * Adds or refreshes the entry for an imported descriptor.
     *
     * <p>Matching is by tailcat address: a server that republishes with a new
     * name should update the existing entry rather than accumulate duplicates.
     * Returns true if the config changed.
     */
    public boolean merge(NetworkDescriptor descriptor) {
        if (!descriptor.isUsable()) {
            return false;
        }
        for (Entry entry : servers) {
            if (descriptor.address().equals(entry.address)) {
                boolean changed = entry.port != descriptor.port();
                entry.port = descriptor.port();
                return changed;
            }
        }
        servers.add(new Entry(descriptor.name(), descriptor.address(), descriptor.port()));
        return true;
    }

    public void save(Path file) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", enabled);
        map.put("tailcatPath", tailcatPath);
        map.put("downloadTailcat", downloadTailcat);
        map.put("isolateState", isolateState);
        map.put("tailcatArgs", new ArrayList<Object>(tailcatArgs));
        map.put("addToServerList", addToServerList);
        map.put("serverListSuffix", serverListSuffix);
        map.put("importFrom", new ArrayList<Object>(importFrom));

        List<Object> encoded = new ArrayList<>();
        for (Entry entry : servers) {
            encoded.add(entry.toMap());
        }
        map.put("servers", encoded);

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
