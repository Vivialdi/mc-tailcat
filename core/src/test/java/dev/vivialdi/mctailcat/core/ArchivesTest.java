package dev.vivialdi.mctailcat.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchivesTest {

    private static final byte[] PAYLOAD = "#!/bin/sh\necho tailcat\n".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    @Test
    void extractsFromATarGzWithADirectoryPrefix() throws IOException {
        // goreleaser archives usually nest the binary under a version folder.
        Path archive = tempDir.resolve("tailcat_0.4.0_linux_amd64.tar.gz");
        writeTarGz(archive, new String[] {"tailcat_0.4.0/README.md", "tailcat_0.4.0/tailcat"},
                new byte[][] {"docs".getBytes(StandardCharsets.UTF_8), PAYLOAD});

        Path destination = tempDir.resolve("bin").resolve("tailcat");
        Archives.extractExecutable(archive, "tailcat", destination);

        assertArrayEquals(PAYLOAD, Files.readAllBytes(destination));
    }

    @Test
    void extractsFromAFlatTarGz() throws IOException {
        Path archive = tempDir.resolve("flat.tar.gz");
        writeTarGz(archive, new String[] {"tailcat"}, new byte[][] {PAYLOAD});

        Path destination = tempDir.resolve("tailcat");
        Archives.extractExecutable(archive, "tailcat", destination);
        assertArrayEquals(PAYLOAD, Files.readAllBytes(destination));
    }

    @Test
    void skipsPastLargeLeadingEntries() throws IOException {
        // Exercises the size/padding arithmetic across several blocks.
        byte[] filler = new byte[5000];
        for (int i = 0; i < filler.length; i++) {
            filler[i] = (byte) (i % 251);
        }
        Path archive = tempDir.resolve("padded.tar.gz");
        writeTarGz(archive, new String[] {"LICENSE", "tailcat"}, new byte[][] {filler, PAYLOAD});

        Path destination = tempDir.resolve("tailcat");
        Archives.extractExecutable(archive, "tailcat", destination);
        assertArrayEquals(PAYLOAD, Files.readAllBytes(destination));
    }

    @Test
    void extractsFromAZip() throws IOException {
        Path archive = tempDir.resolve("tailcat_0.4.0_windows_amd64.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("docs/"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("tailcat.exe"));
            zip.write(PAYLOAD);
            zip.closeEntry();
        }

        Path destination = tempDir.resolve("tailcat.exe");
        Archives.extractExecutable(archive, "tailcat.exe", destination);
        assertArrayEquals(PAYLOAD, Files.readAllBytes(destination));
    }

    @Test
    void failsClearlyWhenTheExecutableIsMissing() throws IOException {
        Path archive = tempDir.resolve("wrong.tar.gz");
        writeTarGz(archive, new String[] {"README.md"},
                new byte[][] {"nothing here".getBytes(StandardCharsets.UTF_8)});

        Path destination = tempDir.resolve("tailcat");
        IOException error = assertThrows(IOException.class,
                () -> Archives.extractExecutable(archive, "tailcat", destination));
        assertTrue(error.getMessage().contains("tailcat"));
        // A failed extraction must not leave a partial file behind.
        assertTrue(!Files.exists(destination));
    }

    // -------------------------------------------------------- tar test writer

    private static void writeTarGz(Path archive, String[] names, byte[][] contents)
            throws IOException {
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(archive))) {
            for (int i = 0; i < names.length; i++) {
                out.write(tarHeader(names[i], contents[i].length));
                out.write(contents[i]);
                int padding = (512 - (contents[i].length % 512)) % 512;
                out.write(new byte[padding]);
            }
            out.write(new byte[1024]); // end-of-archive marker
        }
    }

    private static byte[] tarHeader(String name, int size) {
        byte[] header = new byte[512];
        putAscii(header, 0, name, 100);
        putAscii(header, 100, "0000644", 8);
        putAscii(header, 108, "0000000", 8);
        putAscii(header, 116, "0000000", 8);
        putAscii(header, 124, String.format("%011o", size), 12);
        putAscii(header, 136, String.format("%011o", 0), 12);
        header[156] = '0'; // regular file
        putAscii(header, 257, "ustar", 6);
        putAscii(header, 263, "00", 2);

        // Checksum is computed with the field itself read as spaces.
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        int checksum = 0;
        for (byte b : header) {
            checksum += b & 0xFF;
        }
        putAscii(header, 148, String.format("%06o", checksum), 7);
        header[154] = 0;
        header[155] = ' ';
        return header;
    }

    private static void putAscii(byte[] block, int offset, String value, int length) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, block, offset, Math.min(bytes.length, length));
    }
}
