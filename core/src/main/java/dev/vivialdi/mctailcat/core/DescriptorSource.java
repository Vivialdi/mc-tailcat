package dev.vivialdi.mctailcat.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Loads a {@link NetworkDescriptor} from wherever an operator chose to put it.
 *
 * <p>A source is either an {@code http(s)://} URL or a filesystem path. The
 * path case covers the common setups directly: a server and client on one
 * machine, a shared folder, or a synced drive. If the path names a directory,
 * the standard filename inside it is used.
 *
 * <p>{@link #discover} adds the case a modpack needs: the standard locations
 * are checked on every launch with nothing configured at all, so shipping a
 * pack that already knows its server is a matter of dropping the file the
 * server published into {@code config/} and nothing else.
 */
public final class DescriptorSource {

    public static final String DEFAULT_FILENAME = "tailcat-network.json";

    /**
     * A directory of descriptors, for a pack that ships more than one server.
     * Every {@code .json} in it is read.
     */
    public static final String DEFAULT_DIRECTORY = "tailcat-servers";

    /** A source and the descriptor found there, so logs can name where it came from. */
    public static final class Found {
        private final String origin;
        private final NetworkDescriptor descriptor;

        Found(String origin, NetworkDescriptor descriptor) {
            this.origin = origin;
            this.descriptor = descriptor;
        }

        public String origin() {
            return origin;
        }

        public NetworkDescriptor descriptor() {
            return descriptor;
        }
    }

    private DescriptorSource() {
    }

    /** Returns the descriptor at {@code source}, or null if it cannot be read. */
    public static NetworkDescriptor load(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        String trimmed = source.trim();
        try {
            if (isUrl(trimmed)) {
                return NetworkDescriptor.parse(Http.getString(trimmed, "application/json"));
            }

            Path path = Paths.get(trimmed);
            if (Files.isDirectory(path)) {
                path = path.resolve(DEFAULT_FILENAME);
            }
            if (!Files.isRegularFile(path)) {
                Log.warn("Tailcat import source not found: " + path);
                return null;
            }
            return NetworkDescriptor.read(path);
        } catch (Exception e) {
            Log.warn("Could not import Tailcat details from '" + trimmed + "': " + e.getMessage());
            return null;
        }
    }

    /**
     * The standard places a client looks with no configuration at all.
     *
     * <p>{@code config/} comes first because that is the directory a modpack
     * ships, and it is where a player told to "drop this file in" will put it.
     * The game directory is checked too, for the common case of a server and a
     * client sharing one machine.
     *
     * <p>A location that is simply absent is not a problem worth mentioning --
     * most players have none of these -- so unlike {@link #load} this is quiet
     * about what it does not find.
     */
    public static List<Found> discover(Path gameDir, Path configDir) {
        List<Path> locations = new ArrayList<>();
        if (configDir != null) {
            locations.add(configDir.resolve(DEFAULT_FILENAME));
            locations.add(configDir.resolve(DEFAULT_DIRECTORY));
        }
        if (gameDir != null) {
            locations.add(gameDir.resolve(DEFAULT_FILENAME));
        }

        List<Found> found = new ArrayList<>();
        for (Path location : locations) {
            for (Path file : expand(location)) {
                try {
                    found.add(new Found(file.toString(), NetworkDescriptor.read(file)));
                } catch (IOException | RuntimeException e) {
                    Log.warn("Could not read Tailcat details from " + file + ": " + e.getMessage());
                }
            }
        }
        return found;
    }

    /**
     * The descriptor files at a location: the file itself, or every
     * {@code .json} in it when it names a directory. Empty if it does not
     * exist.
     */
    private static List<Path> expand(Path location) {
        if (Files.isRegularFile(location)) {
            return List.of(location);
        }
        if (!Files.isDirectory(location)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(location)) {
            List<Path> files = new ArrayList<>();
            entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(".json"))
                    .sorted()
                    .forEach(files::add);
            return files;
        } catch (IOException e) {
            Log.warn("Could not list " + location + ": " + e.getMessage());
            return List.of();
        }
    }

    private static boolean isUrl(String source) {
        String lower = source.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
