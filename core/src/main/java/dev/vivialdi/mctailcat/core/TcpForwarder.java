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
 */
public final class TcpForwarder implements AutoCloseable {

    private final Path executable;
    private final Path stateDir;
    private final String address;
    private final int remotePort;
    private final int localPort;

    private final ExecutorService connections = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "tailcat-forwarder");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger activeConnections = new AtomicInteger();

    private ServerSocket listener;
    private Thread acceptLoop;
    private volatile boolean running;

    public TcpForwarder(Path executable, Path stateDir, String address, int remotePort, int localPort) {
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
        listener = new ServerSocket(localPort, 16, InetAddress.getLoopbackAddress());
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

            List<String> command = ProcessRunner.command(executable, address, String.valueOf(remotePort));
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

    private static void drainToLog(InputStream stderr) throws IOException {
        byte[] buffer = new byte[4096];
        int read;
        while ((read = stderr.read(buffer)) != -1) {
            String text = new String(buffer, 0, read, StandardCharsets.UTF_8).strip();
            if (!text.isEmpty()) {
                Log.info("[tailcat] " + text);
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
        return "127.0.0.1:" + localPort;
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
