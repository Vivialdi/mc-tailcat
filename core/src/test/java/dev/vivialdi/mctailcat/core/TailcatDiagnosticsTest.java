package dev.vivialdi.mctailcat.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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
}
