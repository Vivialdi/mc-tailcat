package dev.vivialdi.mctailcat.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Loads a {@link NetworkDescriptor} from wherever an operator chose to put it.
 *
 * <p>A source is either an {@code http(s)://} URL or a filesystem path. The
 * path case covers the common setups directly: a server and client on one
 * machine, a shared folder, or a synced drive. If the path names a directory,
 * the standard filename inside it is used.
 */
public final class DescriptorSource {

    public static final String DEFAULT_FILENAME = "tailcat-network.json";

    private DescriptorSource() {
    }

    /** Returns the descriptor at {@code source}, or null if it cannot be read. */
    public static NetworkDescriptor load(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        String trimmed = source.trim();
        try {
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
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
}
