package dev.vivialdi.mctailcat.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerListFileTest {

    @TempDir
    Path tempDir;

    @Test
    void createsTheFileWhenAbsent() throws IOException {
        Path file = tempDir.resolve("servers.dat");
        ServerListFile list = ServerListFile.load(file);

        assertTrue(list.upsert("Survival (Tailcat)", "127.0.0.1:31234"));
        list.save();

        assertTrue(Files.isRegularFile(file));
        assertEquals(1, ServerListFile.load(file).count());
    }

    @Test
    void updatesTheAddressOfAnExistingEntry() throws IOException {
        Path file = tempDir.resolve("servers.dat");
        ServerListFile first = ServerListFile.load(file);
        first.upsert("Survival (Tailcat)", "127.0.0.1:31234");
        first.save();

        ServerListFile second = ServerListFile.load(file);
        assertTrue(second.upsert("Survival (Tailcat)", "127.0.0.1:39999"));
        second.save();

        // Updated in place rather than duplicated.
        assertEquals(1, ServerListFile.load(file).count());
    }

    @Test
    void matchesAnEntryRenamedByThePlayer() throws IOException {
        Path file = tempDir.resolve("servers.dat");
        ServerListFile first = ServerListFile.load(file);
        first.upsert("My Renamed Server", "127.0.0.1:31234");
        first.save();

        ServerListFile second = ServerListFile.load(file);
        second.upsert("Survival (Tailcat)", "127.0.0.1:31234");
        second.save();

        assertEquals(1, ServerListFile.load(file).count());
    }

    @Test
    void reportsNoChangeWhenTheEntryAlreadyMatches() throws IOException {
        Path file = tempDir.resolve("servers.dat");
        ServerListFile first = ServerListFile.load(file);
        first.upsert("Survival (Tailcat)", "127.0.0.1:31234");
        first.save();

        // The common relaunch: nothing moved, so the player's file is untouched.
        assertFalse(ServerListFile.load(file).upsert("Survival (Tailcat)", "127.0.0.1:31234"));
    }

    @Test
    void leavesOtherServersAndTheirUnknownFieldsAlone() throws IOException {
        Path file = tempDir.resolve("servers.dat");

        // A vanilla-looking entry, including a field this mod knows nothing
        // about -- newer Minecraft versions add these.
        Nbt.Compound root = new Nbt.Compound();
        Nbt.TagList servers = new Nbt.TagList(Nbt.TAG_COMPOUND);
        Nbt.Compound existing = new Nbt.Compound();
        existing.putString("name", "Hypixel");
        existing.putString("ip", "mc.hypixel.net");
        existing.putString("icon", "iVBORw0KGgo=");
        existing.putByte("acceptTextures", (byte) 1);
        existing.put("someFutureField", Nbt.TAG_INT, 42);
        servers.add(Nbt.TAG_COMPOUND, existing);
        root.put("servers", Nbt.TAG_LIST, servers);
        try (var out = Files.newOutputStream(file)) {
            Nbt.writeRoot(out, root);
        }

        ServerListFile list = ServerListFile.load(file);
        list.upsert("Survival (Tailcat)", "127.0.0.1:31234");
        list.save();

        Nbt.Compound reloaded;
        try (var in = Files.newInputStream(file)) {
            reloaded = Nbt.readRoot(in);
        }
        Nbt.TagList reloadedServers = (Nbt.TagList) reloaded.get("servers");
        assertEquals(2, reloadedServers.items().size());

        Nbt.Compound untouched = (Nbt.Compound) reloadedServers.items().get(0);
        assertEquals("mc.hypixel.net", untouched.getString("ip", null));
        assertEquals("iVBORw0KGgo=", untouched.getString("icon", null));
        assertEquals(42, untouched.get("someFutureField"));
    }

    @Test
    void readsAndPreservesGzippedFiles() throws IOException {
        Path file = tempDir.resolve("servers.dat");

        Nbt.Compound root = new Nbt.Compound();
        root.put("servers", Nbt.TAG_LIST, new Nbt.TagList(Nbt.TAG_COMPOUND));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            Nbt.writeRoot(gzip, root);
        }
        Files.write(file, buffer.toByteArray());

        ServerListFile list = ServerListFile.load(file);
        list.upsert("Survival (Tailcat)", "127.0.0.1:31234");
        list.save();

        byte[] saved = Files.readAllBytes(file);
        assertEquals(0x1F, saved[0] & 0xFF);
        assertEquals(0x8B, saved[1] & 0xFF);
        assertEquals(1, ServerListFile.load(file).count());
    }

    @Test
    void removesEntriesByName() throws IOException {
        Path file = tempDir.resolve("servers.dat");
        ServerListFile list = ServerListFile.load(file);
        list.upsert("Survival (Tailcat)", "127.0.0.1:31234");
        list.save();

        ServerListFile reloaded = ServerListFile.load(file);
        assertTrue(reloaded.remove("Survival (Tailcat)"));
        reloaded.save();

        assertEquals(0, ServerListFile.load(file).count());
    }

    @Test
    void refusesToClobberAnUnreadableFile() throws IOException {
        Path file = tempDir.resolve("servers.dat");
        Files.write(file, "this is not NBT at all".getBytes());

        // Returning null tells the caller to leave the player's file alone.
        org.junit.jupiter.api.Assertions.assertNull(ServerListFile.load(file));
    }
}
