package dev.vivialdi.mctailcat.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Locates the {@code tailcat} executable, downloading a copy if the host does
 * not already have one.
 *
 * <p>Search order is deliberate: an operator's explicit setting wins, then a
 * previously downloaded private copy, then whatever is on {@code PATH}, and
 * only then the network. That way a machine with tailcat already installed
 * never grows a second copy.
 */
public final class TailcatBinary {

    private static final String RELEASES_API =
            "https://api.github.com/repos/tailscale/tailcat/releases/latest";

    private TailcatBinary() {
    }

    public static Path resolve(Path installDir, String configuredPath, boolean allowDownload)
            throws IOException {
        if (configuredPath != null && !configuredPath.isBlank()) {
            Path explicit = Paths.get(configuredPath.trim());
            if (isRunnable(explicit)) {
                Log.info("Using the configured tailcat executable at " + explicit);
                return explicit;
            }
            throw new IOException("tailcatPath is set to '" + configuredPath
                    + "' but that is not an executable file");
        }

        Path bundled = installDir.resolve("bin").resolve(Platform.executableName());
        if (isRunnable(bundled)) {
            Log.info("Using the downloaded tailcat executable at " + bundled);
            return bundled;
        }

        Path onPath = findOnPath();
        if (onPath != null) {
            Log.info("Using tailcat from PATH at " + onPath);
            return onPath;
        }

        if (!allowDownload) {
            throw new IOException("tailcat is not installed and downloadTailcat is disabled. "
                    + "Install it from https://github.com/tailscale/tailcat/releases, or set "
                    + "tailcatPath in the Tailcat config.");
        }

        Log.info("tailcat was not found on this machine; downloading a private copy");
        download(bundled);
        return bundled;
    }

    private static boolean isRunnable(Path candidate) {
        return candidate != null && Files.isRegularFile(candidate) && Files.isExecutable(candidate);
    }

    /** Scans {@code PATH} for the executable, honouring PATHEXT-less Windows naming. */
    public static Path findOnPath() {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        String executable = Platform.executableName();
        for (String element : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (element.isBlank()) {
                continue;
            }
            try {
                Path candidate = Paths.get(element.trim()).resolve(executable);
                if (isRunnable(candidate)) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // A malformed PATH element should not stop the search.
            }
        }
        return null;
    }

    // -------------------------------------------------------------- download

    private static void download(Path destination) throws IOException {
        Map<String, Object> release = Json.parseObject(
                Http.getString(RELEASES_API, "application/vnd.github+json"));
        String tag = Json.string(release, "tag_name", "(unknown)");
        List<Object> assets = Json.array(release, "assets");

        Asset archive = selectArchive(assets);
        if (archive == null) {
            throw new IOException("tailcat release " + tag + " has no archive for "
                    + Platform.os().assetName() + "/" + Platform.arch()
                    + ". Install tailcat manually and set tailcatPath.");
        }
        Log.info("Downloading " + archive.name + " from tailcat " + tag);

        Path workDir = destination.toAbsolutePath().getParent().resolve("download");
        Files.createDirectories(workDir);
        Path archiveFile = workDir.resolve(archive.name);
        try {
            long bytes = Http.download(archive.url, archiveFile);
            Log.info("Downloaded " + bytes + " bytes");

            verifyChecksum(assets, archive.name, archiveFile);
            Archives.extractExecutable(archiveFile, Platform.executableName(), destination);
            Log.info("Installed tailcat to " + destination);
        } finally {
            deleteQuietly(archiveFile);
            deleteQuietly(workDir);
        }
    }

    /** Package-private so asset selection can be exercised directly by tests. */
    static final class Asset {
        final String name;
        final String url;

        Asset(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    /**
     * Picks the archive matching this host.
     *
     * <p>Matching is by os/arch tokens rather than a hardcoded filename because
     * tailcat is young and its asset naming may still move. An architecture
     * specific build is preferred over a universal one.
     */
    static Asset selectArchive(List<Object> assets) {
        String os = "_" + Platform.os().assetName() + "_";
        String arch = "_" + Platform.arch() + ".";
        Asset universal = null;

        for (Object item : assets) {
            Map<String, Object> asset = Json.object(item);
            String name = Json.string(asset, "name", "");
            String url = Json.string(asset, "browser_download_url", "");
            if (name.isEmpty() || url.isEmpty()) {
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            boolean isArchive = lower.endsWith(".tar.gz") || lower.endsWith(".zip");
            if (!isArchive || !lower.contains(os)) {
                continue;
            }
            if (lower.contains(arch)) {
                return new Asset(name, url);
            }
            // macOS ships a single universal binary named "..._all.tar.gz".
            if (universal == null && (lower.contains("_all.") || lower.contains("_universal."))) {
                universal = new Asset(name, url);
            }
        }
        return universal;
    }

    /**
     * Verifies the download against the release's {@code checksums.txt}.
     *
     * <p>A mismatch aborts. A missing checksum file only warns: the mod should
     * not become unusable because the release layout changed, but it must never
     * run a binary that was published with a checksum and fails it.
     */
    private static void verifyChecksum(List<Object> assets, String assetName, Path file)
            throws IOException {
        String checksumsUrl = null;
        for (Object item : assets) {
            Map<String, Object> asset = Json.object(item);
            if ("checksums.txt".equalsIgnoreCase(Json.string(asset, "name", ""))) {
                checksumsUrl = Json.string(asset, "browser_download_url", "");
                break;
            }
        }
        if (checksumsUrl == null || checksumsUrl.isEmpty()) {
            Log.warn("This tailcat release publishes no checksums.txt; skipping verification");
            return;
        }

        String expected = null;
        for (String line : Http.getString(checksumsUrl, "text/plain").split("\\R")) {
            String[] parts = line.trim().split("\\s+");
            // goreleaser writes "<sha256>  <filename>", sometimes with a
            // leading "*" on the filename for binary mode.
            if (parts.length >= 2 && stripBinaryMarker(parts[parts.length - 1]).equals(assetName)) {
                expected = parts[0].toLowerCase(Locale.ROOT);
                break;
            }
        }
        if (expected == null) {
            Log.warn("checksums.txt has no entry for " + assetName + "; skipping verification");
            return;
        }

        String actual = sha256(file);
        if (!expected.equals(actual)) {
            Files.deleteIfExists(file);
            throw new IOException("checksum mismatch for " + assetName
                    + " (expected " + expected + ", got " + actual + "); refusing to install it");
        }
        Log.info("Verified " + assetName + " against its published SHA-256");
    }

    private static String stripBinaryMarker(String name) {
        return name.startsWith("*") ? name.substring(1) : name;
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable on this JVM", e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Temporary files are not worth reporting.
        }
    }
}
