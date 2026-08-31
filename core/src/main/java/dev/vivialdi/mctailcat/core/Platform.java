package dev.vivialdi.mctailcat.core;

import java.util.Locale;

/** Host operating system and CPU architecture, in the spelling tailcat's release assets use. */
public final class Platform {

    public enum Os {
        LINUX("linux"),
        MACOS("darwin"),
        WINDOWS("windows");

        private final String assetName;

        Os(String assetName) {
            this.assetName = assetName;
        }

        /** The token that appears in a release asset filename, e.g. {@code linux}. */
        public String assetName() {
            return assetName;
        }
    }

    private Platform() {
    }

    public static Os os() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return Os.WINDOWS;
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return Os.MACOS;
        }
        return Os.LINUX;
    }

    public static boolean isWindows() {
        return os() == Os.WINDOWS;
    }

    /**
     * Architecture token used by tailcat's release assets: {@code amd64},
     * {@code arm64} or {@code armv7}.
     */
    public static String arch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            return "arm64";
        }
        if (arch.startsWith("arm")) {
            return "armv7";
        }
        return "amd64";
    }

    /** The executable filename for tailcat on this host. */
    public static String executableName() {
        return isWindows() ? "tailcat.exe" : "tailcat";
    }
}
