package dev.vivialdi.mctailcat.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NetworkDescriptorTest {

    private static final String ADDRESS =
            "tcomFwWCCcjS5nKNqAod034nWoJZW0LZqDhhC8U_dKdnDRYQ8uNGFpGQEu";

    @TempDir
    Path tempDir;

    @Test
    void acceptsRealAddresses() {
        assertTrue(NetworkDescriptor.isValidAddress(ADDRESS));
        assertTrue(NetworkDescriptor.isValidAddress("  " + ADDRESS + "  "));
    }

    @Test
    void rejectsThingsThatAreNotAddresses() {
        assertFalse(NetworkDescriptor.isValidAddress(null));
        assertFalse(NetworkDescriptor.isValidAddress(""));
        assertFalse(NetworkDescriptor.isValidAddress("paste your address here"));
        assertFalse(NetworkDescriptor.isValidAddress("tcshort"));
        assertFalse(NetworkDescriptor.isValidAddress("mc.example.com"));
        // A whole banner line is not itself an address.
        assertFalse(NetworkDescriptor.isValidAddress("address: " + ADDRESS));
    }

    @Test
    void findsTheAddressInTailcatsBanner() {
        // The exact shape tailcat prints on startup.
        String banner = "🐈 Server listening with new address: " + ADDRESS;
        assertEquals(ADDRESS, NetworkDescriptor.findAddress(banner));

        String saved = "🐈 Server listening with saved key \"minecraft\": " + ADDRESS;
        assertEquals(ADDRESS, NetworkDescriptor.findAddress(saved));
    }

    @Test
    void findsNothingInOrdinaryLogLines() {
        assertNull(NetworkDescriptor.findAddress("2026-08-31 starting up, listening on port 25565"));
        assertNull(NetworkDescriptor.findAddress(null));
    }

    @Test
    void roundTripsThroughJson() {
        NetworkDescriptor original = NetworkDescriptor.of("Survival", ADDRESS, 25566, "A nice server");
        NetworkDescriptor parsed = NetworkDescriptor.parse(original.toJson());

        assertEquals("Survival", parsed.name());
        assertEquals(ADDRESS, parsed.address());
        assertEquals(25566, parsed.port());
        assertEquals("A nice server", parsed.motd());
        assertEquals(original.updatedAt(), parsed.updatedAt());
        assertTrue(parsed.isUsable());
    }

    @Test
    void roundTripsThroughAFile() throws IOException {
        Path file = tempDir.resolve("nested").resolve(DescriptorSource.DEFAULT_FILENAME);
        NetworkDescriptor.of("Survival", ADDRESS, 25565, "").write(file);

        NetworkDescriptor read = NetworkDescriptor.read(file);
        assertEquals(ADDRESS, read.address());
        assertEquals(25565, read.port());
    }

    @Test
    void incompleteDescriptorsAreNotUsable() {
        assertFalse(new NetworkDescriptor("n", "", 25565, "", "").isUsable());
        assertFalse(new NetworkDescriptor("n", ADDRESS, 0, "", "").isUsable());
        assertFalse(new NetworkDescriptor("n", ADDRESS, 70000, "", "").isUsable());
    }

    @Test
    void loadsFromAFileSourceAndADirectorySource() throws IOException {
        Path directory = tempDir.resolve("shared");
        NetworkDescriptor.of("Survival", ADDRESS, 25565, "")
                .write(directory.resolve(DescriptorSource.DEFAULT_FILENAME));

        assertEquals(ADDRESS, DescriptorSource.load(directory.toString()).address());
        assertEquals(ADDRESS, DescriptorSource.load(
                directory.resolve(DescriptorSource.DEFAULT_FILENAME).toString()).address());
    }

    @Test
    void missingSourcesReturnNullRatherThanThrowing() {
        assertNull(DescriptorSource.load(tempDir.resolve("nope.json").toString()));
        assertNull(DescriptorSource.load(""));
        assertNull(DescriptorSource.load(null));
    }
}
