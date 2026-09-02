package dev.vivialdi.mctailcat.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bridges a loopback port to a Minecraft server reachable over tailcat.
 *
 * <p>This is what lets the mod work without touching a single Minecraft class.
 * Rather than reaching into the game's Netty pipeline -- which would mean
 * mixins, and mixins mean per-version maintenance -- the mod listens on
 * {@code 127.0.0.1} and hands each connection to {@code tailcat <address>
 * <port>}, whose stdin and stdout are the far end of the tunnel. Minecraft
 * connects to a plain local address and never knows the difference, on any
 * version.
 *
 * <p>The port is bound before the tailcat binary is known, and a connection
 * that arrives in between waits for it. That matters exactly once, on the
 * launch where the mod has to download tailcat first: the multiplayer entry is
 * already written, so a player who reaches the server list during the download
 * would otherwise find a dead port and have to back out and try again. Waiting
 * a moment is a better answer than being refused.
 */
public final class TcpForwarder implements AutoCloseable {

    /**
     * How long a connection waits for a tailcat binary that is still being
     * downloaded. Generous because the alternative is a failed join, and
     * bounded because a wait nothing will ever satisfy is worse than an error.
     * A download that fails releases every waiter at once rather than timing
     * them out.
     */
    private static final long BINARY_WAIT_SECONDS = 60;

    private final CompletableFuture<Path> executable;
    private final Path stateDir;
    private final String address;
    private final int remotePort;
    private final int localPort;
    private final List<String> extraArgs;

    private final ExecutorService connections = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "tailcat-forwarder");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger activeConnections = new AtomicInteger();

    // Shared across every connection this forwarder makes, so a server that is
    // refusing all of them explains itself once rather than per attempt.
    private final TailcatDiagnostics diagnostics = new TailcatDiagnostics();

    private ServerSocket listener;
    private Thread acceptLoop;
    private volatile boolean running;

    public TcpForwarder(Path executable, Path stateDir, String address, int remotePort, int localPort) {
        this(executable, stateDir, address, remotePort, localPort, List.of());
    }

    public TcpForwarder(Path executable, Path stateDir, String address, int remotePort, int localPort,
            List<String> extraArgs) {
        this(CompletableFuture.completedFuture(executable), stateDir, address, remotePort, localPort,
                extraArgs);
    }

    /**
     * A forwarder for a tailcat binary that is not available yet.
     *
     * <p>Complete {@code executable} once it has been resolved, or complete it
     * exceptionally if it never will be -- which releases anyone waiting on it
     * instead of leaving them to time out.
     */
    public TcpForwarder(CompletableFuture<Path> executable, Path stateDir, String address,
            int remotePort, int localPort, List<String> extraArgs) {
        this.extraArgs = extraArgs == null ? List.of() : List.copyOf(extraArgs);
        this.executable = executable;
        this.stateDir = stateDir;
        this.address = address;
        this.remotePort = remotePort;
        this.localPort = localPort;
    }

    /** Binds the loopback port and begins accepting. */
    public void start() throws IOException {
        if (running) {
            return;
        }
        // Explicitly 127.0.0.1, never getLoopbackAddress(): on a JVM that
        // prefers IPv6 that is ::1, and the multiplayer entry says 127.0.0.1.
        listener = new ServerSocket(localPort, 16, PortAllocator.LOOPBACK);
        running = true;

        acceptLoop = new Thread(this::acceptForever, "tailcat-accept-" + localPort);
        acceptLoop.setDaemon(true);
        acceptLoop.start();

        Log.info("Forwarding 127.0.0.1:" + localPort + " to " + address + " port " + remotePort);
    }

    private void acceptForever() {
        while (running) {
            Socket client;
            try {
                client = listener.accept();
            } catch (IOException e) {
                if (running) {
                    Log.error("Stopped accepting connections on port " + localPort, e);
                }
                return;
            }
            connections.execute(() -> bridge(client));
        }
    }

    private void bridge(Socket client) {
        int active = activeConnections.incrementAndGet();
        Process tunnel = null;
        try {
            client.setTcpNoDelay(true);
            client.setKeepAlive(true);

            Path binary = awaitExecutable();
            if (binary == null) {
                return;
            }

            List<String> command =
                    ProcessRunner.command(binary, extraArgs, address, String.valueOf(remotePort));
            tunnel = ProcessRunner.startPiped(stateDir, command);
            Log.info("Opened a Tailcat tunnel for a local connection (" + active + " active)");

            Process process = tunnel;
            // tailcat writes diagnostics to stderr; keep it off the data path
            // but still visible in the log.
            Thread diagnostics = pump("tailcat-stderr", () -> drainToLog(process.getErrorStream()));
            Thread upstream = pump("tailcat-upstream",
                    () -> copy(client.getInputStream(), process.getOutputStream()));
            Thread downstream = pump("tailcat-downstream",
                    () -> copy(process.getInputStream(), client.getOutputStream()));

            // Either direction closing means the session is over.
            while (upstream.isAlive() && downstream.isAlive() && process.isAlive()) {
                Thread.sleep(50);
            }
            upstream.interrupt();
            downstream.interrupt();
            diagnostics.interrupt();
        } catch (IOException e) {
            Log.error("Tailcat tunnel failed for " + address + ":" + remotePort, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | Error e) {
            // Anything else would vanish into the thread pool: the connection
            // closes, the game shows "can't connect", and nothing says why.
            // A silent failure is the one kind that cannot be diagnosed from
            // a player's log, so name it.
            Log.error("Tailcat tunnel failed unexpectedly for " + address + ":" + remotePort, e);
        } finally {
            closeQuietly(client);
            if (tunnel != null) {
                tunnel.destroy();
                try {
                    if (!tunnel.waitFor(5, TimeUnit.SECONDS)) {
                        tunnel.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    tunnel.destroyForcibly();
                }
            }
            activeConnections.decrementAndGet();
        }
    }

    /**
     * The tailcat binary, waiting for it if it is still being downloaded.
     * Returns null if it is not going to arrive, leaving the caller to drop the
     * connection.
     */
    private Path awaitExecutable() {
        if (executable.isDone() && !executable.isCompletedExceptionally()) {
            return executable.getNow(null);
        }
        try {
            Log.info("A connection arrived before tailcat was ready; holding it open while it"
                    + " is fetched");
            return executable.get(BINARY_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception unavailable) {
            Log.warn("Dropping a connection to " + address + ": tailcat is not available");
            return null;
        }
    }

    private interface IoTask {
        void run() throws IOException;
    }

    private Thread pump(String name, IoTask task) {
        Thread thread = new Thread(() -> {
            try {
                task.run();
            } catch (IOException closed) {
                // Normal at the end of a connection.
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            // Minecraft is latency sensitive and its packets are small; never
            // let data sit in a buffer waiting for more.
            out.flush();
        }
        out.close();
    }

    private void drainToLog(InputStream stderr) throws IOException {
        byte[] buffer = new byte[4096];
        int read;
        while ((read = stderr.read(buffer)) != -1) {
            String text = new String(buffer, 0, read, StandardCharsets.UTF_8).strip();
            if (!text.isEmpty()) {
                Log.info("[tailcat] " + text);
                diagnostics.inspect(text);
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing useful to do.
        }
    }

    public int localPort() {
        return localPort;
    }

    public String localAddress() {
        return PortAllocator.localAddress(localPort);
    }

    public String remoteAddress() {
        return address;
    }

    @Override
    public void close() {
        running = false;
        if (listener != null) {
            try {
                listener.close();
            } catch (IOException ignored) {
                // Closing is what breaks accept() out of its block.
            }
        }
        if (acceptLoop != null) {
            acceptLoop.interrupt();
        }
        connections.shutdownNow();
    }
}
