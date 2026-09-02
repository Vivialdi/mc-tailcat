package dev.vivialdi.mctailcat.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The first-launch case: the mod has to download tailcat before it can carry
 * any traffic, but the multiplayer entry exists from the moment the game
 * starts. A player who reaches the server list during that download must not
 * find a dead port.
 *
 * <p>Needs no tailcat binary and no POSIX shell, so unlike the tests that
 * exercise the data path this one runs everywhere.
 */
class PendingBinaryTest {

    private static final String ADDRESS =
            "tcEXAMPLEaddressForDocsAndTestsOnly_NotARealServer00000000";

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort();
        }
    }

    private static TcpForwarder forwarderFor(CompletableFuture<Path> binary, int port) {
        return new TcpForwarder(binary, null, ADDRESS, 25565, port, List.of());
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000);
        return socket;
    }

    @Test
    @Timeout(30)
    void acceptsConnectionsWhileTheBinaryIsStillDownloading() throws Exception {
        int port = freePort();
        CompletableFuture<Path> stillDownloading = new CompletableFuture<>();

        try (TcpForwarder forwarder = forwarderFor(stillDownloading, port)) {
            forwarder.start();

            // This is the connection that used to be refused.
            try (Socket player = connect(port)) {
                assertTrue(player.isConnected());
            }
        }
    }

    @Test
    @Timeout(30)
    void dropsWaitingConnectionsWhenTheDownloadFails() throws Exception {
        int port = freePort();
        CompletableFuture<Path> binary = new CompletableFuture<>();

        try (TcpForwarder forwarder = forwarderFor(binary, port)) {
            forwarder.start();

            try (Socket player = connect(port)) {
                binary.completeExceptionally(new IOException("no tailcat for you"));

                // The waiter is released rather than left to time out: the read
                // ends, so the game sees a closed connection and not a hang.
                player.setSoTimeout(15_000);
                InputStream in = player.getInputStream();
                assertEquals(-1, in.read(), "a connection that cannot be carried should be closed");
            }
        }
    }

    @Test
    @Timeout(30)
    void aBinaryThatIsAlreadyThereIsNotWaitedFor() throws Exception {
        int port = freePort();
        // Any path will do: nothing is spawned until bytes actually flow.
        CompletableFuture<Path> ready =
                CompletableFuture.completedFuture(Paths.get("tailcat"));

        try (TcpForwarder forwarder = forwarderFor(ready, port)) {
            forwarder.start();
            try (Socket player = connect(port)) {
                assertTrue(player.isConnected());
            }
        }
    }

    @Test
    @Timeout(30)
    void theListenerIsGoneOnceTheForwarderIsClosed() throws Exception {
        int port = freePort();
        TcpForwarder forwarder = forwarderFor(new CompletableFuture<>(), port);
        forwarder.start();
        connect(port).close();
        forwarder.close();

        // Closing is what turns "still starting" into "this server is down",
        // which is the honest signal when tailcat could not be had at all.
        assertThrows(ConnectException.class, () -> connect(port));
    }

    @Test
    void everyServerGetsItsOwnStableLocalPort() {
        int one = PortAllocator.preferredPort(ADDRESS, 25565);
        int two = PortAllocator.preferredPort(ADDRESS, 25566);

        assertEquals(one, PortAllocator.preferredPort(ADDRESS, 25565));
        assertNotEquals(one, two);
    }
}
