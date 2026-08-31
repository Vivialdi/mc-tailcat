package dev.vivialdi.mctailcat.core;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts a single named executable from a release archive.
 *
 * <p>The JDK can read zip but not tar, so the tar reader here is hand-rolled.
 * It only needs to handle what goreleaser produces: a flat ustar archive with
 * regular files.
 */
public final class Archives {

    private static final int TAR_BLOCK = 512;

    private Archives() {
    }

    /**
     * Finds {@code executableName} inside {@code archive} and writes it to
     * {@code destination}, marking it executable.
     *
     * @throws IOException if the archive does not contain the executable
     */
    public static void extractExecutable(Path archive, String executableName, Path destination)
            throws IOException {
        String lower = archive.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        Path parent = destination.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = destination.resolveSibling(destination.getFileName() + ".part");
        Files.deleteIfExists(temp);

        boolean found = lower.endsWith(".zip")
                ? extractFromZip(archive, executableName, temp)
                : extractFromTarGz(archive, executableName, temp);

        if (!found) {
            Files.deleteIfExists(temp);
            throw new IOException("no entry named '" + executableName + "' inside " + archive.getFileName());
        }

        makeExecutable(temp);
        Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
    }

    private static boolean extractFromZip(Path archive, String executableName, Path destination)
            throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && matches(entry.getName(), executableName)) {
                    copyTo(zip, destination, Long.MAX_VALUE);
                    return true;
                }
                zip.closeEntry();
            }
        }
        return false;
    }

    private static boolean extractFromTarGz(Path archive, String executableName, Path destination)
            throws IOException {
        try (InputStream in = new GZIPInputStream(
                new BufferedInputStream(Files.newInputStream(archive)), 64 * 1024)) {
            byte[] header = new byte[TAR_BLOCK];
            while (true) {
                if (!readFully(in, header, TAR_BLOCK)) {
                    return false;
                }
                if (isAllZero(header)) {
                    return false; // end-of-archive marker
                }

                String name = cString(header, 0, 100);
                String prefix = cString(header, 345, 155);
                if (!prefix.isEmpty()) {
                    name = prefix + "/" + name;
                }
                long size = parseOctal(header, 124, 12);
                char typeFlag = (char) (header[156] == 0 ? '0' : header[156]);

                boolean isRegularFile = typeFlag == '0' || typeFlag == 0;
                if (isRegularFile && matches(name, executableName)) {
                    copyTo(in, destination, size);
                    return true;
                }

                long skip = size + padding(size);
                while (skip > 0) {
                    long skipped = in.skip(skip);
                    if (skipped <= 0) {
                        // skip() may legally return 0; fall back to reading.
                        int read = in.read(header, 0, (int) Math.min(TAR_BLOCK, skip));
                        if (read < 0) {
                            return false;
                        }
                        skipped = read;
                    }
                    skip -= skipped;
                }
            }
        }
    }

    /** Matches the bare filename, so {@code tailcat_0.4.0/tailcat} still resolves. */
    private static boolean matches(String entryName, String executableName) {
        String normalised = entryName.replace('\\', '/');
        int slash = normalised.lastIndexOf('/');
        String base = slash < 0 ? normalised : normalised.substring(slash + 1);
        return base.equals(executableName);
    }

    private static void copyTo(InputStream in, Path destination, long limit) throws IOException {
        try (OutputStream out = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            long remaining = limit;
            while (remaining > 0) {
                int want = (int) Math.min(buffer.length, remaining);
                int read = in.read(buffer, 0, want);
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    private static boolean readFully(InputStream in, byte[] buffer, int length) throws IOException {
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read < 0) {
                return false;
            }
            offset += read;
        }
        return true;
    }

    private static boolean isAllZero(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String cString(byte[] block, int offset, int length) {
        int end = offset;
        int limit = offset + length;
        while (end < limit && block[end] != 0) {
            end++;
        }
        return new String(block, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    private static long parseOctal(byte[] block, int offset, int length) throws IOException {
        String text = cString(block, offset, length).trim();
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(text, 8);
        } catch (NumberFormatException e) {
            throw new IOException("malformed tar size field '" + text + "'");
        }
    }

    private static long padding(long size) {
        long remainder = size % TAR_BLOCK;
        return remainder == 0 ? 0 : TAR_BLOCK - remainder;
    }

    /** Best-effort chmod +x; a no-op on filesystems without POSIX permissions. */
    public static void makeExecutable(Path file) {
        if (Platform.isWindows()) {
            return;
        }
        try {
            var permissions = Files.getPosixFilePermissions(file);
            permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
            permissions.add(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
            permissions.add(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException | IOException e) {
            Log.warn("Could not mark " + file + " executable: " + e.getMessage());
        }
    }
}
