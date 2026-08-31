package dev.vivialdi.mctailcat.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs short-lived tailcat commands and captures their output. */
public final class ProcessRunner {

    public static final class Result {
        public final int exitCode;
        public final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        public boolean succeeded() {
            return exitCode == 0;
        }
    }

    private ProcessRunner() {
    }

    /**
     * Runs a command to completion, merging stdout and stderr.
     *
     * <p>tailcat prints its banner to one or the other depending on the
     * subcommand, and callers only ever want to scan the combined text.
     */
    public static Result run(Path stateDir, List<String> command, long timeoutSeconds)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        applyStateDir(builder, stateDir);

        Process process = builder.start();
        // Drain on a separate thread: reading to EOF on this one would outlast
        // the timeout whenever the child hangs with its output still open.
        StringBuilder collected = new StringBuilder();
        Thread drain = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    synchronized (collected) {
                        collected.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException ignored) {
                // The stream closing under us just ends the capture.
            }
        }, "tailcat-output");
        drain.setDaemon(true);
        drain.start();

        boolean exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            drain.join(1000);
            throw new IOException("`" + String.join(" ", command) + "` did not finish within "
                    + timeoutSeconds + "s");
        }
        drain.join(2000);
        synchronized (collected) {
            return new Result(process.exitValue(), collected.toString());
        }
    }

    /**
     * Points a child process's config/state lookups at {@code stateDir}.
     *
     * <p>tailcat saves named keys under the user's config directory. Redirecting
     * that into the game directory keeps a server's identity with the server
     * install -- so its address survives restarts and moves with a backup --
     * and lets it work at all under service accounts with no writable home.
     * The variables cover the conventions used on each platform; the ones that
     * do not apply are simply ignored.
     */
    private static void applyStateDir(ProcessBuilder builder, Path stateDir) throws IOException {
        if (stateDir == null) {
            return;
        }
        Files.createDirectories(stateDir);
        String absolute = stateDir.toAbsolutePath().toString();
        Map<String, String> environment = builder.environment();
        environment.put("HOME", absolute);
        environment.put("USERPROFILE", absolute);
        environment.put("XDG_CONFIG_HOME", absolute);
        environment.put("XDG_STATE_HOME", absolute);
        environment.put("XDG_DATA_HOME", absolute);
        environment.put("XDG_CACHE_HOME", absolute);
        environment.put("APPDATA", absolute);
        environment.put("LOCALAPPDATA", absolute);
    }

    /** Starts a long-running process with merged output, for the caller to supervise. */
    public static Process start(Path stateDir, List<String> command) throws IOException {
        return start(stateDir, command, Map.of());
    }

    /**
     * Starts a long-running process with merged output and extra environment
     * variables.
     *
     * <p>Merged because tailcat prints its startup banner -- the address blob
     * included -- to stderr, not stdout.
     */
    public static Process start(Path stateDir, List<String> command, Map<String, String> extraEnv)
            throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        applyStateDir(builder, stateDir);
        builder.environment().putAll(extraEnv);
        return builder.start();
    }

    /**
     * Starts a process whose stdout carries data rather than log lines.
     *
     * <p>stderr is kept separate here: merging it would splice tailcat's own
     * chatter into the byte stream the caller is proxying, corrupting it.
     */
    public static Process startPiped(Path stateDir, List<String> command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);
        applyStateDir(builder, stateDir);
        return builder.start();
    }

    public static List<String> command(Path executable, String... arguments) {
        return command(executable, List.of(), arguments);
    }

    /**
     * Builds a command line, splicing {@code extraFlags} in after the
     * subcommand but before any positional arguments.
     *
     * <p>tailcat requires flags to precede its port and service arguments, so
     * appending them would silently change their meaning.
     */
    public static List<String> command(Path executable, List<String> extraFlags,
            String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(executable.toAbsolutePath().toString());

        List<String> parts = new ArrayList<>();
        for (String argument : arguments) {
            if (argument != null && !argument.isBlank()) {
                parts.add(argument);
            }
        }

        // A leading subcommand ("serve", "genkey") has to stay first; a bare
        // client invocation takes its address as the first positional instead.
        int insertAt = !parts.isEmpty() && isSubcommand(parts.get(0)) ? 1 : 0;
        command.addAll(parts.subList(0, insertAt));
        for (String flag : extraFlags) {
            if (flag != null && !flag.isBlank()) {
                command.add(flag.trim());
            }
        }
        command.addAll(parts.subList(insertAt, parts.size()));
        return command;
    }

    private static boolean isSubcommand(String argument) {
        switch (argument) {
            case "serve":
            case "genkey":
            case "ping":
            case "parse":
            case "resolve":
            case "socks":
            case "ssh":
            case "cp":
            case "ls":
            case "recv":
                return true;
            default:
                return false;
        }
    }
}
