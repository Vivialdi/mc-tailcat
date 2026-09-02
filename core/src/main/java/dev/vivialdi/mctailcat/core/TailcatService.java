package dev.vivialdi.mctailcat.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Owns the long-lived {@code tailcat serve} process that exposes the Minecraft
 * port to players.
 *
 * <p>The process is supervised: if tailcat exits while the server is still
 * running, it is restarted with backoff. The saved key means the address that
 * players already have keeps working across those restarts.
 */
public final class TailcatService implements AutoCloseable {

    private static final long ADDRESS_TIMEOUT_SECONDS = 45;
    private static final long RESTART_DELAY_MS = 5_000;
    private static final long MAX_RESTART_DELAY_MS = 120_000;

    private final Path executable;
    private final Path stateDir;
    private final Path addressFile;
    private final String keyName;
    private final int port;
    private final boolean fullAddress;
    private final boolean fixedRegion;
    private final List<String> extraArgs;

    private final AtomicReference<String> address = new AtomicReference<>();
    private final CountDownLatch addressReady = new CountDownLatch(1);
    private final TailcatDiagnostics diagnostics = new TailcatDiagnostics();

    private volatile boolean running;
    private volatile Process process;
    private volatile Consumer<String> addressListener;
    private Thread supervisor;

    public TailcatService(Path executable, Path stateDir, Path addressFile, String keyName, int port,
            boolean fullAddress, boolean fixedRegion, List<String> extraArgs) {
        this.executable = executable;
        this.stateDir = stateDir;
        this.addressFile = addressFile;
        this.keyName = keyName;
        this.port = port;
        this.fullAddress = fullAddress;
        this.fixedRegion = fixedRegion;
        this.extraArgs = extraArgs == null ? List.of() : List.copyOf(extraArgs);
    }

    /**
     * Registers a callback fired whenever tailcat reports an address.
     *
     * <p>Driven by the callback rather than by {@link #start}'s return value so
     * that an address arriving after the startup wait -- a slow first run, or a
     * restart hours later -- still gets published.
     */
    public void setAddressListener(Consumer<String> listener) {
        this.addressListener = listener;
    }

    /**
     * Makes sure a saved key exists so the server's address is stable.
     *
     * <p>Best effort by design: if tailcat's key subcommands change shape, an
     * ephemeral address still works for the current session, and that beats
     * refusing to start.
     */
    private void ensureSavedKey() {
        try {
            ProcessRunner.Result listed = ProcessRunner.run(stateDir,
                    ProcessRunner.command(executable, extraArgs, "genkey", "--list"), 20);
            if (listed.succeeded() && listed.output.contains(keyName)) {
                Log.info("Reusing the saved tailcat key '" + keyName + "'");
                return;
            }

            Log.info("Creating a saved tailcat key named '" + keyName + "'");
            // --fixed-region is what actually makes the published address
            // stable. The address blob encodes the DERP relay region, and
            // without a fixed one tailcat re-picks the region by latency at
            // every startup, changing the address and stranding players who
            // already saved it. A saved key alone is not enough.
            ProcessRunner.Result created = ProcessRunner.run(stateDir,
                    ProcessRunner.command(executable, extraArgs, "genkey", "--key=" + keyName,
                            fixedRegion ? "--fixed-region" : null), 60);
            if (!created.succeeded()) {
                Log.warn("`tailcat genkey` exited with " + created.exitCode
                        + "; the server will fall back to an ephemeral address that changes on"
                        + " restart. Output: " + created.output.strip());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Log.warn("Could not prepare a saved tailcat key: " + e.getMessage());
        }
    }

    /** Starts tailcat and blocks until it reports an address, or the wait times out. */
    public boolean start() {
        if (running) {
            return address.get() != null;
        }
        running = true;
        ensureSavedKey();

        supervisor = new Thread(this::supervise, "tailcat-supervisor");
        supervisor.setDaemon(true);
        supervisor.start();

        try {
            if (!addressReady.await(ADDRESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.error("tailcat did not report an address within " + ADDRESS_TIMEOUT_SECONDS
                        + "s. The server will keep running without Tailcat connectivity.");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return address.get() != null;
    }

    private void supervise() {
        long delay = RESTART_DELAY_MS;
        while (running) {
            try {
                int exitCode = runOnce();
                if (!running) {
                    return;
                }
                Log.warn("tailcat exited with code " + exitCode + "; restarting in "
                        + (delay / 1000) + "s");
            } catch (IOException e) {
                if (!running) {
                    return;
                }
                Log.error("Could not run tailcat; retrying in " + (delay / 1000) + "s", e);
            }

            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            // Back off so a persistently broken install does not spin.
            delay = Math.min(delay * 2, MAX_RESTART_DELAY_MS);
        }
    }

    private int runOnce() throws IOException {
        List<String> command = ProcessRunner.command(executable, extraArgs,
                "serve",
                "--key=" + keyName,
                fullAddress ? "--full-address" : null,
                String.valueOf(port));
        Log.info("Starting: " + String.join(" ", command));

        // tailcat writes the bare address blob to TAILCAT_ADDR_FILE. That is a
        // documented interface, unlike the human-readable banner, so it is the
        // primary way the address is picked up; scanning the output stays as a
        // fallback in case the variable ever stops being honoured.
        Map<String, String> environment = Map.of();
        if (addressFile != null) {
            deleteStaleAddressFile();
            environment = Map.of("TAILCAT_ADDR_FILE", addressFile.toAbsolutePath().toString());
        }

        Process started = ProcessRunner.start(stateDir, command, environment);
        process = started;
        Thread addressWatcher = watchAddressFile(started);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                consume(line);
            }
        } finally {
            if (addressWatcher != null) {
                addressWatcher.interrupt();
            }
        }

        try {
            return started.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            started.destroyForcibly();
            return -1;
        }
    }

    private void consume(String line) {
        String trimmed = line.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        Log.info("[tailcat] " + trimmed);
        diagnostics.inspect(trimmed);

        String found = NetworkDescriptor.findAddress(trimmed);
        if (found != null) {
            acceptAddress(found);
        }
    }

    /** Clears an address left by a previous run so a stale one is never read back. */
    private void deleteStaleAddressFile() {
        try {
            Files.deleteIfExists(addressFile);
        } catch (IOException e) {
            Log.warn("Could not clear " + addressFile + ": " + e.getMessage());
        }
    }

    /**
     * Polls for the address file tailcat writes on startup.
     *
     * <p>Polling rather than watching: the file appears once, within a second
     * or two of launch, and a directory watch service would be far more
     * machinery for the same answer.
     */
    private Thread watchAddressFile(Process forProcess) {
        if (addressFile == null) {
            return null;
        }
        Thread watcher = new Thread(() -> {
            long deadline = System.currentTimeMillis()
                    + TimeUnit.SECONDS.toMillis(ADDRESS_TIMEOUT_SECONDS);
            while (System.currentTimeMillis() < deadline && forProcess.isAlive()) {
                try {
                    if (Files.isRegularFile(addressFile)) {
                        String found = NetworkDescriptor.findAddress(
                                Files.readString(addressFile, StandardCharsets.UTF_8));
                        if (found != null) {
                            acceptAddress(found);
                            return;
                        }
                    }
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (IOException e) {
                    // A partially written file just means try again.
                }
            }
        }, "tailcat-address-watch");
        watcher.setDaemon(true);
        watcher.start();
        return watcher;
    }

    /** Records a newly reported address and notifies the listener, exactly once per change. */
    private synchronized void acceptAddress(String found) {
        String previous = address.getAndSet(found);
        if (previous == null) {
            Log.info("Tailcat address for this server: " + found);
            addressReady.countDown();
        } else if (previous.equals(found)) {
            return;
        } else {
            // With a fixed-region key this should not happen; if it does, the
            // key was regenerated or predates the fixed-region default.
            Log.warn("The Tailcat address changed from " + previous + " to " + found
                    + ". Players will need the new address.");
        }

        Consumer<String> listener = addressListener;
        if (listener != null) {
            try {
                listener.accept(found);
            } catch (RuntimeException e) {
                Log.error("Failed to publish the Tailcat address", e);
            }
        }
    }

    public String address() {
        return address.get();
    }

    public int port() {
        return port;
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public void close() {
        running = false;
        Process current = process;
        if (current != null && current.isAlive()) {
            Log.info("Stopping tailcat");
            current.destroy();
            try {
                if (!current.waitFor(10, TimeUnit.SECONDS)) {
                    current.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                current.destroyForcibly();
            }
        }
        if (supervisor != null) {
            supervisor.interrupt();
        }
    }
}
