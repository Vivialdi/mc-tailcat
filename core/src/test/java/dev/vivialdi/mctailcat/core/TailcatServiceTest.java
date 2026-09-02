package dev.vivialdi.mctailcat.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the service against a stand-in for tailcat that records how it was
 * invoked.
 *
 * <p>Both behaviours pinned here were established by running the real tailcat
 * v0.4.0: the address blob encodes the DERP region and is only stable when the
 * key bakes one in, and {@code TAILCAT_ADDR_FILE} is the documented way to
 * receive the blob.
 */
class TailcatServiceTest {

    /**
     * Shaped like a real address — the {@code tc} prefix and base64url payload
     * the parser insists on — but obviously invented, so nothing here reads as
     * an invitation to somebody's actual server.
     */
    private static final String ADDRESS =
            "tcEXAMPLEaddressForTestsOnly_NotARealServer_0000000000000000000000"
                    + "0000000000000000000000000000000000000EXAMPLE";

    @TempDir
    Path tempDir;

    private Path fakeTailcat;
    private Path invocationLog;

    @BeforeEach
    void createFakeTailcat() throws IOException {
        assumeTrue(!Platform.isWindows(), "needs a POSIX shell");
        invocationLog = tempDir.resolve("invocations.txt");
        fakeTailcat = tempDir.resolve("tailcat");
    }

    /** Writes the stand-in. {@code writeAddrFile} and {@code printBanner} pick its behaviour. */
    private void installFake(boolean writeAddrFile, boolean printBanner) throws IOException {
        String script = "#!/bin/sh\n"
                + "echo \"$@\" >> " + invocationLog.toAbsolutePath() + "\n"
                + "case \"$1\" in\n"
                + "  genkey) exit 0 ;;\n"
                + "  serve)\n"
                + (writeAddrFile
                        ? "    [ -n \"$TAILCAT_ADDR_FILE\" ] && printf '%s' '" + ADDRESS
                                + "' > \"$TAILCAT_ADDR_FILE\"\n"
                        : "")
                // The real tailcat prints its banner to stderr, not stdout.
                + (printBanner
                        ? "    echo '# \\xf0\\x9f\\x90\\x88 Server listening with saved key \"mc\": "
                                + ADDRESS + "' >&2\n"
                        : "")
                + "    sleep 30 ;;\n"
                + "esac\n";
        Files.writeString(fakeTailcat, script);
        Archives.makeExecutable(fakeTailcat);
        assumeTrue(Files.isExecutable(fakeTailcat));
    }

    private TailcatService newService(boolean fixedRegion) {
        return newService(fixedRegion, java.util.List.of());
    }

    private TailcatService newService(boolean fixedRegion, java.util.List<String> extraArgs) {
        return new TailcatService(fakeTailcat, tempDir.resolve("state"),
                tempDir.resolve("address.txt"), "mc", 25565, false, fixedRegion, extraArgs);
    }

    private String invocations() throws IOException {
        return Files.isRegularFile(invocationLog) ? Files.readString(invocationLog) : "";
    }

    @Test
    @Timeout(60)
    void generatesTheKeyWithAFixedRegion() throws Exception {
        installFake(true, false);
        try (TailcatService service = newService(true)) {
            assertTrue(service.start(), "the service should report an address");
        }

        String log = invocations();
        assertTrue(log.contains("genkey --list"), "should check for an existing key first: " + log);
        assertTrue(log.contains("genkey --key=mc --fixed-region"),
                "the saved key must bake in a region or the published address moves: " + log);
    }

    @Test
    @Timeout(60)
    void omitsFixedRegionWhenTheOperatorTurnsItOff() throws Exception {
        installFake(true, false);
        try (TailcatService service = newService(false)) {
            assertTrue(service.start());
        }
        assertTrue(invocations().contains("genkey --key=mc\n"),
                "expected a bare genkey: " + invocations());
    }

    @Test
    @Timeout(60)
    void readsTheAddressFromTheAddressFileAlone() throws Exception {
        // No banner at all: the address can only have come from TAILCAT_ADDR_FILE.
        installFake(true, false);
        try (TailcatService service = newService(true)) {
            assertTrue(service.start());
            assertEquals(ADDRESS, service.address());
        }
    }

    @Test
    @Timeout(60)
    void fallsBackToScrapingTheBannerWhenNoAddressFileIsWritten() throws Exception {
        installFake(false, true);
        try (TailcatService service = newService(true)) {
            assertTrue(service.start(), "should still find the address in the output");
            assertEquals(ADDRESS, service.address());
        }
    }

    @Test
    @Timeout(60)
    void passesTheServePortAndKeyWithFlagsFirst() throws Exception {
        installFake(true, false);
        try (TailcatService service = newService(true)) {
            assertTrue(service.start());
        }
        // The real tailcat requires flags before the port arguments.
        assertTrue(invocations().contains("serve --key=mc 25565"),
                "expected flags before the port: " + invocations());
    }

    @Test
    @Timeout(60)
    void placesExtraFlagsAfterTheSubcommandButBeforeThePort() throws Exception {
        installFake(true, false);
        try (TailcatService service =
                     newService(true, java.util.List.of("--derpmap-url=http://relay.test/map.json"))) {
            assertTrue(service.start());
        }
        // tailcat rejects flags that come after its port arguments.
        assertTrue(invocations().contains(
                        "serve --derpmap-url=http://relay.test/map.json --key=mc 25565"),
                "extra flags must precede the port: " + invocations());
        assertTrue(invocations().contains(
                        "genkey --derpmap-url=http://relay.test/map.json --key=mc --fixed-region"),
                "extra flags should reach genkey too: " + invocations());
    }

    @Test
    @Timeout(60)
    void ignoresAnAddressLeftOverFromAPreviousRun() throws Exception {
        Path addressFile = tempDir.resolve("address.txt");
        Files.writeString(addressFile, "tcSTALEaddressFromAnEarlierRunXXXXXXXXXXXX");

        // This fake writes no address file, so a stale read would surface the old value.
        installFake(false, true);
        try (TailcatService service = newService(true)) {
            assertTrue(service.start());
            assertEquals(ADDRESS, service.address(), "must not report the stale address");
        }
    }
}
