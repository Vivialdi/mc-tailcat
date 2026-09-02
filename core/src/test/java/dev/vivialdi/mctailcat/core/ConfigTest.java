package dev.vivialdi.mctailcat.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {

    private static final String ADDRESS =
            "tcEXAMPLEaddressForDocsAndTestsOnly_NotARealServer00000000";

    @TempDir
    Path tempDir;

    @Test
    void writesDefaultsOnFirstLoad() {
        Path file = tempDir.resolve("tailcat-server.json");
        ServerConfig config = ServerConfig.load(file);

        assertTrue(Files.isRegularFile(file), "the config file should be created for the operator");
        assertTrue(config.enabled);
        assertEquals("minecraft", config.keyName);
        assertEquals(0, config.port);
        // On by default: without it the published address moves between restarts.
        assertTrue(config.fixedRegion);
    }

    @Test
    void serverConfigRoundTrips() {
        Path file = tempDir.resolve("tailcat-server.json");
        ServerConfig config = ServerConfig.load(file);
        config.serverName = "Survival";
        config.keyName = "smp";
        config.port = 25570;
        config.fullAddress = true;
        config.fixedRegion = false;
        config.downloadTailcat = false;
        config.save(file);

        ServerConfig reloaded = ServerConfig.load(file);
        assertEquals("Survival", reloaded.serverName);
        assertEquals("smp", reloaded.keyName);
        assertEquals(25570, reloaded.port);
        assertTrue(reloaded.fullAddress);
        assertFalse(reloaded.fixedRegion);
        assertFalse(reloaded.downloadTailcat);
    }

    @Test
    void serverConfigLocksInWhatPlayersNeed() {
        Path file = tempDir.resolve("tailcat-server.json");
        ServerConfig config = ServerConfig.load(file);
        config.clientTailcatArgs.add("--derpmap-url=https://relay.example/derpmap.json");
        config.clientServerListSuffix = " [SMP]";
        config.save(file);

        ServerConfig reloaded = ServerConfig.load(file);
        NetworkDescriptor.ClientSettings settings = reloaded.clientSettings();

        assertEquals(1, settings.tailcatArgs().size());
        assertEquals("--derpmap-url=https://relay.example/derpmap.json",
                settings.tailcatArgs().get(0));
        assertEquals(" [SMP]", settings.serverListSuffix());
    }

    @Test
    void anOperatorWhoConfiguresNothingLocksNothingIn() {
        ServerConfig config = ServerConfig.load(tempDir.resolve("tailcat-server.json"));

        assertTrue(config.clientSettings().isEmpty(),
                "a default server should publish the same file it always has");
    }

    @Test
    void autoDiscoveryIsOnByDefaultAndRoundTrips() {
        Path file = tempDir.resolve("tailcat-client.json");

        // On by default: it is what lets a modpack ship a server.
        assertTrue(ClientConfig.load(file).autoDiscover);

        ClientConfig config = ClientConfig.load(file);
        config.autoDiscover = false;
        config.save(file);
        assertFalse(ClientConfig.load(file).autoDiscover);
    }

    @Test
    void clientConfigRoundTripsServersAndSources() {
        Path file = tempDir.resolve("tailcat-client.json");
        ClientConfig config = ClientConfig.load(file);
        config.importFrom.add("/srv/minecraft/tailcat-network.json");
        config.importFrom.add("https://example.com/tailcat-network.json");
        config.servers.add(new ClientConfig.Entry("Survival", ADDRESS, 25570));
        config.save(file);

        ClientConfig reloaded = ClientConfig.load(file);
        assertEquals(2, reloaded.importFrom.size());
        assertEquals(1, reloaded.servers.size());
        assertEquals("Survival", reloaded.servers.get(0).name);
        assertEquals(ADDRESS, reloaded.servers.get(0).address);
        assertEquals(25570, reloaded.servers.get(0).port);
        assertTrue(reloaded.servers.get(0).isUsable());
    }

    @Test
    void mergeAddsThenUpdatesInPlace() {
        ClientConfig config = new ClientConfig();

        assertTrue(config.merge(NetworkDescriptor.of("Survival", ADDRESS, 25565, "")));
        assertEquals(1, config.servers.size());

        // Same address, same port: nothing to do.
        assertFalse(config.merge(NetworkDescriptor.of("Survival", ADDRESS, 25565, "")));
        assertEquals(1, config.servers.size());

        // Same address, moved port: update rather than duplicate.
        assertTrue(config.merge(NetworkDescriptor.of("Renamed", ADDRESS, 25570, "")));
        assertEquals(1, config.servers.size());
        assertEquals(25570, config.servers.get(0).port);
        // A player's own name for the server survives a republish.
        assertEquals("Survival", config.servers.get(0).name);
    }

    @Test
    void mergeIgnoresUnusableDescriptors() {
        ClientConfig config = new ClientConfig();
        assertFalse(config.merge(new NetworkDescriptor("n", "not-an-address", 25565, "", "")));
        assertTrue(config.servers.isEmpty());
    }

    @Test
    void survivesACorruptConfigFile() throws IOException {
        Path file = tempDir.resolve("tailcat-client.json");
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);

        // Defaults, and the file is rewritten so the player gets a valid one back.
        ClientConfig config = ClientConfig.load(file);
        assertTrue(config.enabled);
        assertTrue(config.servers.isEmpty());
        assertTrue(Json.parseObject(Files.readString(file)).containsKey("servers"));
    }

    @Test
    void entriesAreUnusableWhenDisabledOrMalformed() {
        assertFalse(new ClientConfig.Entry("n", "", 25565).isUsable());
        assertFalse(new ClientConfig.Entry("n", ADDRESS, 0).isUsable());

        ClientConfig.Entry disabled = new ClientConfig.Entry("n", ADDRESS, 25565);
        disabled.enabled = false;
        assertFalse(disabled.isUsable());
    }

    @Test
    void stripsColourCodesFromAMotdDerivedName() {
        assertEquals("A Minecraft Server",
                TailcatServerRuntime.stripFormatting("§aA §lMinecraft§r Server"));
    }
}
