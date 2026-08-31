package dev.vivialdi.mctailcat.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TailcatBinaryTest {

    @TempDir
    Path tempDir;

    private static Object asset(String name) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("name", name);
        map.put("browser_download_url", "https://example.invalid/" + name);
        return map;
    }

    /** The real v0.4.0 asset list, plus the archives for platforms it does not print. */
    private static List<Object> releaseAssets() {
        List<Object> assets = new ArrayList<>();
        assets.add(asset("checksums.txt"));
        for (String arch : new String[] {"amd64", "arm64", "armv7"}) {
            assets.add(asset("tailcat_0.4.0_linux_" + arch + ".deb"));
            assets.add(asset("tailcat_0.4.0_linux_" + arch + ".rpm"));
            assets.add(asset("tailcat_0.4.0_linux_" + arch + ".tar.gz"));
        }
        assets.add(asset("tailcat_0.4.0_darwin_all.tar.gz"));
        assets.add(asset("tailcat_0.4.0_windows_amd64.zip"));
        return assets;
    }

    @Test
    void picksAnArchiveMatchingThisHost() {
        var selected = TailcatBinary.selectArchive(releaseAssets());
        assertNotNull(selected, "no asset matched " + Platform.os().assetName() + "/" + Platform.arch());

        String name = selected.name.toLowerCase(java.util.Locale.ROOT);
        assertTrue(name.contains("_" + Platform.os().assetName() + "_"), name);
        // Never a package -- those need a package manager and root.
        assertTrue(name.endsWith(".tar.gz") || name.endsWith(".zip"), name);
    }

    @Test
    void prefersTheExactArchitectureOverAUniversalBuild() {
        List<Object> assets = new ArrayList<>();
        assets.add(asset("tailcat_0.4.0_" + Platform.os().assetName() + "_all.tar.gz"));
        assets.add(asset("tailcat_0.4.0_" + Platform.os().assetName() + "_" + Platform.arch() + ".tar.gz"));

        assertEquals("tailcat_0.4.0_" + Platform.os().assetName() + "_" + Platform.arch() + ".tar.gz",
                TailcatBinary.selectArchive(assets).name);
    }

    @Test
    void fallsBackToAUniversalBuild() {
        List<Object> assets = new ArrayList<>();
        assets.add(asset("tailcat_0.4.0_" + Platform.os().assetName() + "_all.tar.gz"));
        assertEquals("tailcat_0.4.0_" + Platform.os().assetName() + "_all.tar.gz",
                TailcatBinary.selectArchive(assets).name);
    }

    @Test
    void ignoresPackagesAndOtherPlatforms() {
        List<Object> assets = new ArrayList<>();
        assets.add(asset("checksums.txt"));
        assets.add(asset("tailcat_0.4.0_" + Platform.os().assetName() + "_" + Platform.arch() + ".deb"));
        assets.add(asset("tailcat_0.4.0_" + Platform.os().assetName() + "_" + Platform.arch() + ".rpm"));
        assets.add(asset("tailcat_0.4.0_plan9_" + Platform.arch() + ".tar.gz"));

        assertNull(TailcatBinary.selectArchive(assets));
    }

    @Test
    void prefersAnExplicitlyConfiguredExecutable() throws IOException {
        Path explicit = tempDir.resolve("my-tailcat");
        Files.writeString(explicit, "#!/bin/sh\n");
        Archives.makeExecutable(explicit);

        // Skip where the filesystem cannot mark files executable (e.g. Windows).
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(explicit));

        assertEquals(explicit,
                TailcatBinary.resolve(tempDir.resolve("install"), explicit.toString(), false));
    }

    @Test
    void reportsAClearErrorForABadConfiguredPath() {
        IOException error = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> TailcatBinary.resolve(tempDir, tempDir.resolve("nope").toString(), false));
        assertTrue(error.getMessage().contains("tailcatPath"));
    }

    @Test
    void hashesFilesForChecksumVerification() throws IOException {
        Path file = tempDir.resolve("payload");
        Files.writeString(file, "abc");
        // Known SHA-256 of "abc".
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                TailcatBinary.sha256(file));
    }
}
