package dev.vivialdi.mctailcat.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The modpack handoff: an operator's published file, dropped somewhere
 * standard, is found with nothing configured.
 */
class DiscoveryTest {

    private static final String ADDRESS =
            "tcomFwWCCcjS5nKNqAod034nWoJZW0LZqDhhC8U_dKdnDRYQ8uNGFpGQEu";
    private static final String OTHER =
            "tcQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQ_dKdnDRYQ8u";

    @TempDir
    Path gameDir;

    private Path configDir() throws IOException {
        return Files.createDirectories(gameDir.resolve("config"));
    }

    @Test
    void findsTheFileAModpackShipsInConfig() throws IOException {
        Path config = configDir();
        NetworkDescriptor.of("Survival", ADDRESS, 25565, "")
                .write(config.resolve(DescriptorSource.DEFAULT_FILENAME));

        List<DescriptorSource.Found> found = DescriptorSource.discover(gameDir, config);

        assertEquals(1, found.size());
        assertEquals(ADDRESS, found.get(0).descriptor().address());
        assertEquals("Survival", found.get(0).descriptor().name());
    }

    @Test
    void findsEveryServerInTheDirectoryForm() throws IOException {
        Path config = configDir();
        Path directory = config.resolve(DescriptorSource.DEFAULT_DIRECTORY);
        NetworkDescriptor.of("Survival", ADDRESS, 25565, "").write(directory.resolve("a.json"));
        NetworkDescriptor.of("Creative", OTHER, 25566, "").write(directory.resolve("b.json"));
        // Anything that is not a descriptor is left alone.
        Files.writeString(directory.resolve("notes.txt"), "ignore me", StandardCharsets.UTF_8);

        List<DescriptorSource.Found> found = DescriptorSource.discover(gameDir, config);

        assertEquals(2, found.size());
        assertEquals(ADDRESS, found.get(0).descriptor().address());
        assertEquals(OTHER, found.get(1).descriptor().address());
    }

    @Test
    void findsAServerSharingTheGameDirectory() throws IOException {
        Path config = configDir();
        NetworkDescriptor.of("Survival", ADDRESS, 25565, "")
                .write(gameDir.resolve(DescriptorSource.DEFAULT_FILENAME));

        List<DescriptorSource.Found> found = DescriptorSource.discover(gameDir, config);

        assertEquals(1, found.size());
        assertEquals(ADDRESS, found.get(0).descriptor().address());
    }

    @Test
    void findsNothingWhenNothingWasShipped() throws IOException {
        assertTrue(DescriptorSource.discover(gameDir, configDir()).isEmpty());
        // A game directory with no config directory at all is the first launch.
        assertTrue(DescriptorSource.discover(gameDir, gameDir.resolve("config")).isEmpty());
    }

    @Test
    void aBrokenFileDoesNotStopTheOthersBeingFound() throws IOException {
        Path config = configDir();
        Files.writeString(config.resolve(DescriptorSource.DEFAULT_FILENAME),
                "{ not json at all", StandardCharsets.UTF_8);
        NetworkDescriptor.of("Survival", ADDRESS, 25565, "")
                .write(gameDir.resolve(DescriptorSource.DEFAULT_FILENAME));

        List<DescriptorSource.Found> found = DescriptorSource.discover(gameDir, config);

        assertEquals(1, found.size());
        assertEquals(ADDRESS, found.get(0).descriptor().address());
    }

    @Test
    void namesWhereEachServerCameFromSoLogsAreUseful() throws IOException {
        Path config = configDir();
        Path file = config.resolve(DescriptorSource.DEFAULT_FILENAME);
        NetworkDescriptor.of("Survival", ADDRESS, 25565, "").write(file);

        assertEquals(file.toString(), DescriptorSource.discover(gameDir, config).get(0).origin());
    }
}
