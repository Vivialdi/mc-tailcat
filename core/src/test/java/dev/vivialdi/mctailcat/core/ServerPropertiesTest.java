package dev.vivialdi.mctailcat.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerPropertiesTest {

    @TempDir
    Path tempDir;

    private Path write(String contents) throws IOException {
        Path file = tempDir.resolve("server.properties");
        Files.writeString(file, contents, StandardCharsets.ISO_8859_1);
        return file;
    }

    @Test
    void readsThePortAndBoundAddress() throws IOException {
        ServerProperties properties = ServerProperties.load(write(
                "#Minecraft server properties\nserver-port=25570\nserver-ip=\nmotd=A Server\n"));

        assertEquals(25570, properties.port());
        assertTrue(properties.boundAddress().isEmpty());
        assertEquals("A Server", properties.motd());
    }

    @Test
    void fallsBackToTheVanillaPort() throws IOException {
        assertEquals(25565, ServerProperties.load(write("motd=hi\n")).port());
        assertEquals(25565, ServerProperties.load(write("server-port=\n")).port());
        assertEquals(25565, ServerProperties.load(write("server-port=abc\n")).port());
        assertEquals(25565, ServerProperties.load(write("server-port=70000\n")).port());
        assertEquals(25565, ServerProperties.load(tempDir.resolve("absent.properties")).port());
    }

    @Test
    void readsAnExplicitBindAddress() throws IOException {
        ServerProperties properties = ServerProperties.load(write("server-ip=203.0.113.7\n"));
        assertEquals("203.0.113.7", properties.boundAddress());
    }

    @Test
    void decodesEscapedMotdText() throws IOException {
        // The game writes non-ASCII MOTDs as \\uXXXX escapes.
        ServerProperties properties = ServerProperties.load(write("motd=Caf\\u00e9 Server\n"));
        assertEquals("Café Server", properties.motd());
    }
}
