package dev.vivialdi.mctailcat.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the forwarder against a stand-in for tailcat that echoes its stdin
 * back to stdout. That is exactly the contract the real tool provides -- stdin
 * and stdout are the two ends of the tunnel -- so this covers the accept loop,
 * the process launch, and both copy directions.
 */
class TcpForwarderTest {

    @TempDir
    Path tempDir;

    private Path fakeTailcat;

    @BeforeEach
    void createFakeTailcat() throws IOException {
        assumeTrue(!Platform.isWindows(), "needs a POSIX shell");

        fakeTailcat = tempDir.resolve("tailcat");
        // Ignores its arguments, which is what this test needs, and pipes both ways.
        Files.writeString(fakeTailcat, "#!/bin/sh\nexec cat\n");
        Archives.makeExecutable(fakeTailcat);
        assumeTrue(Files.isExecutable(fakeTailcat));
    }

    @Test
    @Timeout(30)
    void carriesBytesInBothDirections() throws Exception {
        int localPort = freePort();
        try (TcpForwarder forwarder = new TcpForwarder(
                fakeTailcat, tempDir.resolve("state"), "tcTEST", 25565, localPort)) {
            forwarder.start();
            assertEquals("127.0.0.1:" + localPort, forwarder.localAddress());

            byte[] payload = "handshake packet".getBytes(StandardCharsets.UTF_8);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), localPort), 10_000);
                socket.setSoTimeout(20_000);

                OutputStream out = socket.getOutputStream();
                out.write(payload);
                out.flush();

                assertArrayEquals(payload, readExactly(socket.getInputStream(), payload.length));
            }
        }
    }

    @Test
    @Timeout(30)
    void handlesSeveralConnectionsInSequence() throws Exception {
        // Minecraft opens one connection to ping a server and another to play.
        int localPort = freePort();
        try (TcpForwarder forwarder = new TcpForwarder(
                fakeTailcat, tempDir.resolve("state"), "tcTEST", 25565, localPort)) {
            forwarder.start();

            for (int attempt = 0; attempt < 3; attempt++) {
                byte[] payload = ("connection-" + attempt).getBytes(StandardCharsets.UTF_8);
                try (Socket socket = new Socket()) {
                    socket.connect(
                            new InetSocketAddress(InetAddress.getLoopbackAddress(), localPort), 10_000);
                    socket.setSoTimeout(20_000);
                    socket.getOutputStream().write(payload);
                    socket.getOutputStream().flush();
                    assertArrayEquals(payload, readExactly(socket.getInputStream(), payload.length));
                }
            }
        }
    }

    @Test
    @Timeout(30)
    void releasesTheLocalPortWhenClosed() throws Exception {
        int localPort = freePort();
        TcpForwarder forwarder = new TcpForwarder(
                fakeTailcat, tempDir.resolve("state"), "tcTEST", 25565, localPort);
        forwarder.start();
        forwarder.close();

        // Waiting rather than asserting immediately: the accept loop unwinds
        // asynchronously once the listener closes.
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && !PortAllocator.isAvailable(localPort)) {
            Thread.sleep(100);
        }
        org.junit.jupiter.api.Assertions.assertTrue(PortAllocator.isAvailable(localPort));
    }

    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read < 0) {
                throw new IOException("stream ended after " + offset + " of " + length + " bytes");
            }
            offset += read;
        }
        return buffer;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
