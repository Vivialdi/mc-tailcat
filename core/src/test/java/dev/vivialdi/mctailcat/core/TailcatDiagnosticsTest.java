package dev.vivialdi.mctailcat.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TailcatDiagnosticsTest {

    /** Verbatim from a real tailcat 0.4.0 server that was refusing every player. */
    private static final String LOCALHOST_FAILURE =
            "2026/09/01 03:17:38 error proxying to localhost:25565: dial tcp: "
                    + "lookup localhost on 1.1.1.1:53: no such host";

    private static final String RESET =
            "2026/09/01 02:33:49 read tcp [fd7a:115c:a1e0:4b71:917e:a247:bac0:45f3]:57727: "
                    + "connection reset by peer";

    @Test
    void explainsTheFailureThatLooksLikeNothingAtAll() {
        String explanation = TailcatDiagnostics.explain(LOCALHOST_FAILURE);

        assertNotNull(explanation);
        // The fix has to be in the message; a diagnosis the operator has to go
        // research is barely better than the DNS error it replaces.
        assertTrue(explanation.contains("127.0.0.1 localhost"),
                "the explanation should contain the line to add");
        assertTrue(explanation.contains("hosts file"));
    }

    @Test
    void explainsATunnelThatOpensAndImmediatelyCloses() {
        String explanation = TailcatDiagnostics.explain(RESET);

        assertNotNull(explanation);
        assertTrue(explanation.contains("localhost") || explanation.contains("bound"),
                "should point at the two things that actually cause it");
    }

    @Test
    void staysQuietAboutOrdinaryOutput() {
        assertNull(TailcatDiagnostics.explain(null));
        assertNull(TailcatDiagnostics.explain(""));
        assertNull(TailcatDiagnostics.explain("# Selected bootstrap relay region 302, San Francisco"));
        assertNull(TailcatDiagnostics.explain(
                "🐈 Server listening with saved key \"minecraft\": tcoabc"));
        assertNull(TailcatDiagnostics.explain("magicsock: derp-302 connected; connGen=1"));
    }

    @Test
    void reportsEachDiagnosisOnlyOnce() {
        TailcatDiagnostics diagnostics = new TailcatDiagnostics();

        assertTrue(diagnostics.inspect(LOCALHOST_FAILURE));
        // A server in this state produces one of these per connection attempt.
        assertFalse(diagnostics.inspect(LOCALHOST_FAILURE));
        assertFalse(diagnostics.inspect(
                "error proxying to localhost:25570: dial tcp: lookup localhost on 8.8.8.8:53: "
                        + "no such host"),
                "the same diagnosis for a different port is still the same advice");

        // A genuinely different problem still gets said once.
        assertTrue(diagnostics.inspect(RESET));
        assertFalse(diagnostics.inspect(RESET));
    }

    @Test
    void aQuietLineIsNotReported() {
        TailcatDiagnostics diagnostics = new TailcatDiagnostics();
        assertFalse(diagnostics.inspect("magicsock: home is now derp-302 (sfo)"));
    }

    // --- the startup check, which fires before anyone is turned away --------

    @TempDir
    Path tempDir;

    private Path hostsContaining(String body) throws IOException {
        Path file = tempDir.resolve("hosts-" + Math.abs(body.hashCode()));
        Files.writeString(file, body, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void warnsWhenWindowsLeavesLocalhostCommentedOut() throws IOException {
        // This is the Windows default, verbatim in spirit: the name is
        // mentioned only inside comments.
        Path hosts = hostsContaining(
                "# Copyright (c) 1993-2009 Microsoft Corp.\n"
                        + "#\n"
                        + "# localhost name resolution is handled within DNS itself.\n"
                        + "#\t127.0.0.1       localhost\n"
                        + "#\t::1             localhost\n");

        String warning = TailcatDiagnostics.checkHostsFile(hosts, true);

        assertNotNull(warning);
        assertTrue(warning.contains("127.0.0.1 localhost"), "the fix belongs in the warning");
    }

    @Test
    void staysQuietOnceTheEntryIsThere() throws IOException {
        Path hosts = hostsContaining(
                "# some comment\n127.0.0.1 localhost\n::1 localhost\n");

        assertNull(TailcatDiagnostics.checkHostsFile(hosts, true));
    }

    @Test
    void acceptsTheEntryWhateverItsSpacingOrCase() throws IOException {
        assertNull(TailcatDiagnostics.checkHostsFile(
                hostsContaining("127.0.0.1\t\tLOCALHOST\n"), true));
        assertNull(TailcatDiagnostics.checkHostsFile(
                hostsContaining("127.0.0.1 localhost # added for tailcat\n"), true));
        // A trailing comment must not hide a real entry, and an entry for
        // something else must not be mistaken for one.
        assertNotNull(TailcatDiagnostics.checkHostsFile(
                hostsContaining("127.0.0.1 localhostfoo\n192.168.1.5 nas\n"), true));
    }

    @Test
    void saysNothingOnLinuxOrMac() throws IOException {
        Path hosts = hostsContaining("# nothing here at all\n");

        assertNull(TailcatDiagnostics.checkHostsFile(hosts, false),
                "/etc/hosts always defines localhost, so there is nothing to warn about");
    }

    @Test
    void saysNothingWhenTheFileCannotBeRead() {
        assertNull(TailcatDiagnostics.checkHostsFile(tempDir.resolve("absent"), true),
                "guessing from a file we cannot read would be worse than silence");
    }
}
