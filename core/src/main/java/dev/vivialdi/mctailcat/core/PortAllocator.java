package dev.vivialdi.mctailcat.core;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Chooses the loopback port a Tailcat server is reachable on locally.
 *
 * <p>The port has to be <em>stable</em>, not merely free: it is written into
 * {@code servers.dat} as part of the entry's address, so a port that moved
 * between launches would leave the player with a stale, dead entry in their
 * multiplayer list. Deriving it from the server's address gives the same
 * answer every launch, while different servers land on different ports.
 */
public final class PortAllocator {

    private static final int RANGE_START = 30_000;
    private static final int RANGE_SIZE = 10_000;
    private static final int MAX_PROBES = 64;

    /**
     * The address every listener binds and every multiplayer entry names.
     *
     * <p>Not {@link InetAddress#getLoopbackAddress()}: that returns {@code ::1}
     * on a JVM that prefers IPv6, and NeoForge's launch arguments make it one.
     * The multiplayer list says {@code 127.0.0.1} in so many words, so the
     * listener has to be there in so many words -- on Fabric the two only ever
     * agreed by luck.
     */
    public static final InetAddress LOOPBACK;

    static {
        try {
            LOOPBACK = InetAddress.getByAddress("127.0.0.1", new byte[] {127, 0, 0, 1});
        } catch (java.net.UnknownHostException impossible) {
            throw new IllegalStateException("127.0.0.1 is not a valid address?", impossible);
        }
    }

    /** What goes in the multiplayer list for a local port. */
    public static String localAddress(int port) {
        return "127.0.0.1:" + port;
    }

    private PortAllocator() {
    }

    /** The preferred port for a server, before checking whether it is free. */
    public static int preferredPort(String address, int remotePort) {
        byte[] digest = sha256((address + ":" + remotePort).getBytes(StandardCharsets.UTF_8));
        int value = ((digest[0] & 0xFF) << 16) | ((digest[1] & 0xFF) << 8) | (digest[2] & 0xFF);
        return RANGE_START + Math.floorMod(value, RANGE_SIZE);
    }

    /**
     * Returns the preferred port if it is bindable, otherwise the next free one
     * after it. Returns -1 if nothing in the probe window is available.
     */
    public static int allocate(String address, int remotePort) {
        int preferred = preferredPort(address, remotePort);
        for (int probe = 0; probe < MAX_PROBES; probe++) {
            int candidate = RANGE_START + Math.floorMod(preferred - RANGE_START + probe, RANGE_SIZE);
            if (isAvailable(candidate)) {
                if (probe > 0) {
                    Log.warn("Local port " + preferred + " is in use; using " + candidate
                            + " for this launch");
                }
                return candidate;
            }
        }
        return -1;
    }

    /** Tests whether a loopback port can be bound right now. */
    public static boolean isAvailable(int port) {
        try (ServerSocket socket = new ServerSocket(port, 1, LOOPBACK)) {
            return socket.getLocalPort() == port;
        } catch (IOException taken) {
            return false;
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable on this JVM", e);
        }
    }
}
