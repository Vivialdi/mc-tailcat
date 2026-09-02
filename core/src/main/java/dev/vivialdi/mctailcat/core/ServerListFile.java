package dev.vivialdi.mctailcat.core;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
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
     * <p>The address is the mod's to manage; the label is the player's. So an
     * entry found <em>by address</em> is left named however the player named
     * it — someone who renames a server in the multiplayer screen should not
     * find it renamed back on the next launch. An entry found only by name is
     * one whose local port has moved, and its address is corrected in place.
     *
     * <p>The cost of that is an operator renaming their server does not
     * propagate to players who already have the entry, only to new ones. That
     * is the better way round: a stale label is a cosmetic annoyance, while
     * silently undoing what a player did is the kind of thing that makes a mod
     * feel broken.
     *
     * @return true if anything actually changed
     */
    public boolean upsert(String name, String address) {
        Nbt.TagList servers = servers();
        Nbt.Compound byAddress = null;
        Nbt.Compound byName = null;

        for (Object item : servers.items()) {
            if (!(item instanceof Nbt.Compound)) {
                continue;
            }
            Nbt.Compound entry = (Nbt.Compound) item;
            if (byAddress == null && address.equals(entry.getString("ip", null))) {
                byAddress = entry;
            } else if (byName == null && name.equals(entry.getString("name", null))) {
                byName = entry;
            }
        }

        // Already pointing where it should. Whatever it is called is the
        // player's business.
        if (byAddress != null) {
            return false;
        }

        // Same server, different local port: correct the address, keep the name.
        if (byName != null) {
            byName.putString("ip", address);
            return true;
        }

        Nbt.Compound entry = new Nbt.Compound();
        entry.putString("name", name);
        entry.putString("ip", address);
        entry.putByte("hidden", (byte) 0);
        servers.add(Nbt.TAG_COMPOUND, entry);
        return true;
    }

    /** The display names currently in the list, in order. */
    public List<String> names() {
        List<String> names = new ArrayList<>();
        for (Object item : servers().items()) {
            if (item instanceof Nbt.Compound) {
                names.add(((Nbt.Compound) item).getString("name", ""));
            }
        }
        return names;
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
