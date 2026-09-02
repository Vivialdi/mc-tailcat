package dev.vivialdi.mctailcat.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns tailcat's own output into something an operator can act on.
 *
 * <p>tailcat reports its failures accurately but tersely, and the terse version
 * lands in a Minecraft log next to thousands of unrelated lines. The failure
 * that motivated this class is the clearest example: a server whose machine
 * cannot resolve {@code localhost} refuses every connection, and all a player
 * sees is a server that will not respond. The cause is two hundred lines away
 * in the operator's console, phrased as a DNS error.
 *
 * <p>Each distinct diagnosis is reported once. A failing server produces one of
 * these per connection attempt, and the advice does not get truer by repetition.
 */
public final class TailcatDiagnostics {

    private final Set<String> alreadyReported = new HashSet<>();

    /**
     * Logs an explanation if this line of tailcat output is one we recognise.
     *
     * @param line a line tailcat wrote to its output
     * @return true if something was reported
     */
    public synchronized boolean inspect(String line) {
        String explanation = explain(line);
        if (explanation == null || !alreadyReported.add(explanation)) {
            return false;
        }
        for (String part : explanation.split("\n")) {
            Log.warn(part);
        }
        return true;
    }

    /**
     * The actionable explanation for a line of tailcat output, or null if there
     * is nothing useful to add to what tailcat already said.
     */
    public static String explain(String line) {
        if (line == null) {
            return null;
        }
        String lower = line.toLowerCase(Locale.ROOT);

        // tailcat proxies connections to the literal hostname "localhost", and
        // resolves it with Go's built-in resolver rather than the OS. On Windows
        // that combination fails outright: "localhost" is normally answered by
        // the DNS Client service, the hosts file leaves it commented out, and a
        // public resolver has no record for it. Every connection is then refused
        // with nothing in the server log to say why.
        if (lower.contains("lookup localhost")) {
            return "Tailcat cannot resolve 'localhost' on this machine, so it cannot hand"
                    + " connections to the server and players will be refused."
                    + "\n  Fix it by adding this line to your hosts file, then restart the server:"
                    + "\n      127.0.0.1 localhost"
                    + "\n  On Windows that file is at"
                    + " C:\\Windows\\System32\\drivers\\etc\\hosts and editing it needs"
                    + " administrator rights.";
        }

        // The far end completed a tunnel and then dropped the connection, which
        // means tailcat reached the server's machine but could not reach the
        // game on it. Worth saying out loud, because from here it is
        // indistinguishable from the server being down.
        if (lower.contains("connection reset by peer")) {
            return "A Tailcat tunnel was established but the server's end closed it"
                    + " immediately. That usually means tailcat reached the server's machine"
                    + " but could not connect to Minecraft on it -- the server may be bound"
                    + " to a single external address, or its machine may not resolve"
                    + " 'localhost'. The server's own log will say which.";
        }

        return null;
    }

    /**
     * Warns before anyone is turned away, rather than after.
     *
     * <p>{@link #explain} only fires once a player has already failed to
     * connect, which is late: the operator has by then been told their server
     * works. The precondition for that failure is visible at startup, so this
     * looks for it directly -- a Windows machine with no {@code localhost} line
     * in its hosts file is one public DNS server away from refusing everyone.
     *
     * <p>Deliberately not phrased as an error. A resolver that does answer
     * {@code localhost} makes this harmless, and there is no way to tell from
     * here which kind you have; the advice costs a line in a file either way.
     *
     * @return the warning to print, or null if there is nothing to say
     */
    public static String checkLocalhostResolves() {
        return checkHostsFile(windowsHostsFile(), Platform.isWindows());
    }

    /** The hosts file Go's resolver reads on Windows. */
    static Path windowsHostsFile() {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null || systemRoot.isBlank()) {
            systemRoot = "C:\\Windows";
        }
        return Paths.get(systemRoot, "System32", "drivers", "etc", "hosts");
    }

    /** Split out from {@link #checkLocalhostResolves} so it can be tested on any host. */
    static String checkHostsFile(Path hostsFile, boolean windows) {
        // Linux and macOS define localhost in /etc/hosts as a matter of course,
        // so there is nothing to warn about there.
        if (!windows) {
            return null;
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(hostsFile, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            // Better to say nothing than to guess from a file we cannot read.
            return null;
        }

        for (String line : lines) {
            int comment = line.indexOf('#');
            String active = comment >= 0 ? line.substring(0, comment) : line;
            for (String token : active.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
                if (token.equals("localhost")) {
                    return null;
                }
            }
        }

        return "This machine has no 'localhost' entry in " + hostsFile + "."
                + " Tailcat resolves that name with its own resolver rather than asking"
                + " Windows, so if your DNS server does not answer it, every player will be"
                + " refused and this server will still look healthy."
                + "\n  If players cannot connect, add this line to that file and restart:"
                + "\n      127.0.0.1 localhost"
                + "\n  Editing it needs administrator rights.";
    }
}
