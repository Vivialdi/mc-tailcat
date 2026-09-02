package dev.vivialdi.mctailcat.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Adding a server without relaunching, which is what a paste box in the
 * multiplayer screen needs underneath it.
 */
class AddServerTest {

    private static final String ADDRESS =
            "tcEXAMPLEaddressForDocsAndTestsOnly_NotARealServer00000000";
    private static final String OTHER =
            "tcEXAMPLEsecondServerForTestsOnly_NotARealServer0000000000";

    @TempDir
    Path gameDir;

    private TailcatClientRuntime runtime;

    private TailcatClientRuntime started() throws IOException {
        Path configDir = Files.createDirectories(gameDir.resolve("config"));
        // No downloads: the tunnel cannot come up here, but everything the
        // player sees -- config, ports, the multiplayer list -- still must.
        ClientConfig config = ClientConfig.load(configDir.resolve("tailcat-client.json"));
        config.downloadTailcat = false;
        config.save(configDir.resolve("tailcat-client.json"));

        runtime = new TailcatClientRuntime(gameDir, configDir);
        runtime.start();
        return runtime;
    }

    @AfterEach
    void stop() {
        if (runtime != null) {
            runtime.stop();
        }
    }

    @Test
    void isReachableWhileTheGameIsRunning() throws IOException {
        assertNull(TailcatClientRuntime.current(), "nothing running yet");
        TailcatClientRuntime started = started();
        assertSame(started, TailcatClientRuntime.current());

        started.stop();
        assertNull(TailcatClientRuntime.current(), "and gone once it stops");
        runtime = null;
    }

    @Test
    void addsAServerAndWritesTheEntryImmediately() throws IOException {
        TailcatClientRuntime started = started();

        assertEquals("", started.addServer(ADDRESS, "Dave's SMP", 25565));

        ServerListFile list = ServerListFile.load(gameDir.resolve("servers.dat"));
        assertEquals(1, list.count());
        assertEquals("Dave's SMP (Tailcat)", list.names().get(0));
    }

    @Test
    void keepsWhatThePlayerTypedForNextLaunch() throws IOException {
        TailcatClientRuntime started = started();
        started.addServer(ADDRESS, "Dave's SMP", 25565);

        // Discovered servers are deliberately not persisted; a typed one is,
        // because the player meant it.
        ClientConfig reloaded = ClientConfig.load(gameDir.resolve("config").resolve("tailcat-client.json"));
        assertEquals(1, reloaded.servers.size());
        assertEquals(ADDRESS, reloaded.servers.get(0).address);
        assertEquals("Dave's SMP", reloaded.servers.get(0).name);
    }

    @Test
    void acceptsAWholePastedLineNotJustABareAddress() throws IOException {
        TailcatClientRuntime started = started();

        // What an operator actually sends someone.
        assertEquals("", started.addServer(" Address: " + ADDRESS + "  ", "Server", 25565));

        ClientConfig reloaded = ClientConfig.load(gameDir.resolve("config").resolve("tailcat-client.json"));
        assertEquals(ADDRESS, reloaded.servers.get(0).address,
                "the address should be picked out of the line");
    }

    @Test
    void refusesThingsThatAreNotAddresses() throws IOException {
        TailcatClientRuntime started = started();

        assertNotEquals("", started.addServer("mc.hypixel.net", "Nope", 25565));
        assertNotEquals("", started.addServer("", "Nope", 25565));
        assertNotEquals("", started.addServer(null, "Nope", 25565));
        assertFalse(Files.exists(gameDir.resolve("servers.dat")),
                "nothing should have been written for a rejected address");
    }

    @Test
    void addingTheSameServerTwiceDoesNotOpenASecondTunnel() throws IOException {
        TailcatClientRuntime started = started();
        assertEquals("", started.addServer(ADDRESS, "First", 25565));

        // Adding it again succeeds rather than erroring, but must not
        // duplicate the entry or stand up a second tunnel for one server.
        assertEquals("", started.addServer(ADDRESS, "Second", 25565));
        assertEquals(1, ServerListFile.load(gameDir.resolve("servers.dat")).count());
    }

    @Test
    void putsTheEntryBackWhenThePlayerDeletedIt() throws IOException {
        TailcatClientRuntime started = started();
        started.addServer(ADDRESS, "Dave's SMP", 25565);

        // The player deletes it in the multiplayer screen, then changes their
        // mind and pastes the address again. Refusing would leave them with no
        // way back short of relaunching the game.
        ServerListFile list = ServerListFile.load(gameDir.resolve("servers.dat"));
        assertTrue(list.remove("Dave's SMP (Tailcat)"));
        list.save();
        assertEquals(0, ServerListFile.load(gameDir.resolve("servers.dat")).count());

        assertEquals("", started.addServer(ADDRESS, "Dave's SMP", 25565));

        ServerListFile after = ServerListFile.load(gameDir.resolve("servers.dat"));
        assertEquals(1, after.count(), "the entry should be back");
        assertEquals("Dave's SMP (Tailcat)", after.names().get(0));
    }

    @Test
    void forgetsAServerTheyAddedByMistake() throws IOException {
        TailcatClientRuntime started = started();
        started.addServer(ADDRESS, "Typo", 25565);
        assertEquals(1, started.typedServers().size());
        assertEquals(1, ServerListFile.load(gameDir.resolve("servers.dat")).count());

        assertTrue(started.removeServer(ADDRESS));

        assertTrue(started.typedServers().isEmpty(), "gone from the config");
        assertEquals(0, ServerListFile.load(gameDir.resolve("servers.dat")).count(),
                "and out of the multiplayer list");
    }

    @Test
    void aForgottenServerStaysForgottenAcrossLaunches() throws IOException {
        TailcatClientRuntime started = started();
        started.addServer(ADDRESS, "Typo", 25565);
        started.removeServer(ADDRESS);
        started.stop();

        // The whole point: a mistyped address must not come back next launch.
        Path configDir = gameDir.resolve("config");
        runtime = new TailcatClientRuntime(gameDir, configDir);
        runtime.start();

        assertTrue(runtime.typedServers().isEmpty());
        assertEquals(0, ServerListFile.load(gameDir.resolve("servers.dat")).count());
    }

    @Test
    void addingOneServerDoesNotResurrectAnotherThePlayerDeleted() throws IOException {
        TailcatClientRuntime started = started();
        started.addServer(ADDRESS, "First", 25565);
        assertEquals(1, ServerListFile.load(gameDir.resolve("servers.dat")).count());

        // The player deletes it in the multiplayer screen.
        ServerListFile list = ServerListFile.load(gameDir.resolve("servers.dat"));
        list.remove("First (Tailcat)");
        list.save();

        // Then adds a completely different server. Only that one should appear:
        // rewriting every known entry would quietly bring back the one they
        // had just got rid of.
        assertEquals("", started.addServer(OTHER, "Second", 25565));

        ServerListFile after = ServerListFile.load(gameDir.resolve("servers.dat"));
        assertEquals(1, after.count(), "only the server just added");
        assertEquals("Second (Tailcat)", after.names().get(0));
    }

    @Test
    void forgettingSomethingNotThereIsHarmless() throws IOException {
        TailcatClientRuntime started = started();
        assertFalse(started.removeServer(ADDRESS));
        assertFalse(started.removeServer(null));
    }

    @Test
    void fallsBackToTheVanillaPortWhenGivenNonsense() throws IOException {
        TailcatClientRuntime started = started();
        assertEquals("", started.addServer(ADDRESS, "Server", 0));

        ClientConfig reloaded = ClientConfig.load(gameDir.resolve("config").resolve("tailcat-client.json"));
        assertEquals(ServerProperties.DEFAULT_PORT, reloaded.servers.get(0).port);
    }
}
