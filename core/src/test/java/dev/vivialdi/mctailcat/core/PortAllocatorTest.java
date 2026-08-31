package dev.vivialdi.mctailcat.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

class PortAllocatorTest {

    private static final String ADDRESS_A =
            "tcomFwWCCcjS5nKNqAod034nWoJZW0LZqDhhC8U_dKdnDRYQ8uNGFpGQEu";
    private static final String ADDRESS_B =
            "tcZZZwWCCcjS5nKNqAod034nWoJZW0LZqDhhC8U_dKdnDRYQ8uNGFpGQEu";

    @Test
    void isStableAcrossCalls() {
        // The whole point: servers.dat holds this port between launches.
        int first = PortAllocator.preferredPort(ADDRESS_A, 25565);
        for (int i = 0; i < 10; i++) {
            assertEquals(first, PortAllocator.preferredPort(ADDRESS_A, 25565));
        }
    }

    @Test
    void staysInsideTheEphemeralWindow() {
        for (int port = 1; port < 400; port++) {
            int local = PortAllocator.preferredPort(ADDRESS_A, port);
            assertTrue(local >= 30_000 && local < 40_000, "out of range: " + local);
        }
    }

    @Test
    void separatesDifferentServers() {
        assertNotEquals(PortAllocator.preferredPort(ADDRESS_A, 25565),
                PortAllocator.preferredPort(ADDRESS_B, 25565));
        assertNotEquals(PortAllocator.preferredPort(ADDRESS_A, 25565),
                PortAllocator.preferredPort(ADDRESS_A, 25566));
    }

    @Test
    void stepsPastAPortThatIsAlreadyTaken() throws Exception {
        int preferred = PortAllocator.preferredPort(ADDRESS_A, 25565);
        try (ServerSocket blocker =
                     new ServerSocket(preferred, 1, InetAddress.getLoopbackAddress())) {
            assertTrue(blocker.isBound());
            int allocated = PortAllocator.allocate(ADDRESS_A, 25565);
            assertNotEquals(preferred, allocated);
            assertTrue(allocated >= 30_000 && allocated < 40_000);
        }
    }

    @Test
    void reportsAvailabilityHonestly() throws Exception {
        int preferred = PortAllocator.preferredPort(ADDRESS_B, 25565);
        try (ServerSocket blocker =
                     new ServerSocket(preferred, 1, InetAddress.getLoopbackAddress())) {
            assertTrue(blocker.isBound());
            org.junit.jupiter.api.Assertions.assertFalse(PortAllocator.isAvailable(preferred));
        }
        assertTrue(PortAllocator.isAvailable(preferred));
    }
}
