package dev.vivialdi.mctailcat.core;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Reads and writes {@code servers.dat}, the multiplayer server list.
 *
 * <p>Vanilla writes this file uncompressed, but it is read back through the
 * same NBT helpers that accept gzip, so the format is sniffed on load and
 * preserved on save. Entries the mod does not own are left untouched.
 */
public final class ServerListFile {

    private static final String SERVERS_KEY = "servers";

    private final Path path;
    private final Nbt.Compound root;
    private final boolean gzipped;

    private ServerListFile(Path path, Nbt.Compound root, boolean gzipped) {
        this.path = path;
        this.root = root;
        this.gzipped = gzipped;
    }

    /** Loads the file, or returns an empty list if it is absent or unreadable. */
    public static ServerListFile load(Path path) {
        if (!Files.isRegularFile(path)) {
            return new ServerListFile(path, emptyRoot(), false);
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            boolean gzipped = isGzip(bytes);
            try (InputStream in = gzipped
                    ? new GZIPInputStream(new ByteArrayInputStream(bytes))
                    : new BufferedInputStream(new ByteArrayInputStream(bytes))) {
                Nbt.Compound root = Nbt.readRoot(in);
                if (!root.contains(SERVERS_KEY)) {
                    root.put(SERVERS_KEY, Nbt.TAG_LIST, new Nbt.TagList(Nbt.TAG_COMPOUND));
                }
                return new ServerListFile(path, root, gzipped);
            }
        } catch (Exception e) {
            // A corrupt or unexpected server list is not worth failing startup
            // over, but silently replacing the player's list would be worse.
            Log.error("Could not read " + path + "; leaving it alone this launch", e);
            return null;
        }
    }

    private static Nbt.Compound emptyRoot() {
        Nbt.Compound root = new Nbt.Compound();
        root.put(SERVERS_KEY, Nbt.TAG_LIST, new Nbt.TagList(Nbt.TAG_COMPOUND));
        return root;
    }

    private static boolean isGzip(byte[] bytes) {
        return bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B;
    }

    private Nbt.TagList servers() {
        Object value = root.get(SERVERS_KEY);
        if (value instanceof Nbt.TagList) {
            return (Nbt.TagList) value;
        }
        Nbt.TagList list = new Nbt.TagList(Nbt.TAG_COMPOUND);
        root.put(SERVERS_KEY, Nbt.TAG_LIST, list);
        return list;
    }

    /**
     * Inserts or updates the entry for a Tailcat server.
     *
     * <p>An existing entry is matched by display name first, then by address,
     * so renaming an entry in game or changing the local port does not leave a
     * duplicate behind. Returns {@code true} if anything actually changed.
     */
    public boolean upsert(String name, String address) {
        Nbt.TagList servers = servers();
        Nbt.Compound match = null;

        for (Object item : servers.items()) {
            if (!(item instanceof Nbt.Compound)) {
                continue;
            }
            Nbt.Compound entry = (Nbt.Compound) item;
            if (name.equals(entry.getString("name", null))
                    || address.equals(entry.getString("ip", null))) {
                match = entry;
                break;
            }
        }

        if (match != null) {
            if (name.equals(match.getString("name", null))
                    && address.equals(match.getString("ip", null))) {
                return false;
            }
            match.putString("name", name);
            match.putString("ip", address);
            return true;
        }

        Nbt.Compound entry = new Nbt.Compound();
        entry.putString("name", name);
        entry.putString("ip", address);
        entry.putByte("hidden", (byte) 0);
        servers.add(Nbt.TAG_COMPOUND, entry);
        return true;
    }

    /** Removes any entry with this display name. Returns true if one was removed. */
    public boolean remove(String name) {
        return servers().items().removeIf(item -> item instanceof Nbt.Compound
                && name.equals(((Nbt.Compound) item).getString("name", null)));
    }

    public int count() {
        return servers().items().size();
    }

    /** Writes the file atomically so an interrupted save cannot truncate the list. */
    public void save() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (gzipped) {
            try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
                Nbt.writeRoot(gzip, root);
            }
        } else {
            Nbt.writeRoot(buffer, root);
        }

        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = path.resolveSibling(path.getFileName() + ".tailcat-tmp");
        Files.write(temp, buffer.toByteArray());
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
