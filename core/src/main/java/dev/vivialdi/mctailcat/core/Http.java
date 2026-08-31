package dev.vivialdi.mctailcat.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Small HTTP helper used only for fetching tailcat releases and shared network files. */
public final class Http {

    private static final String USER_AGENT = "mc-tailcat";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int MAX_REDIRECTS = 5;

    private Http() {
    }

    private static HttpURLConnection open(String url, String accept) throws IOException {
        String current = url;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            URL parsed = URI.create(current).toURL();
            String protocol = parsed.getProtocol();
            if (!"https".equalsIgnoreCase(protocol) && !"http".equalsIgnoreCase(protocol)) {
                throw new IOException("refusing to fetch non-HTTP URL: " + current);
            }
            HttpURLConnection connection = (HttpURLConnection) parsed.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            if (accept != null) {
                connection.setRequestProperty("Accept", accept);
            }

            int status = connection.getResponseCode();
            // Redirects are followed by hand so that the http -> https hop the
            // JDK refuses to take on its own still works.
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.isBlank()) {
                    throw new IOException("redirect without a Location header from " + current);
                }
                current = URI.create(current).resolve(location).toString();
                continue;
            }
            if (status / 100 != 2) {
                connection.disconnect();
                throw new IOException("HTTP " + status + " from " + current);
            }
            return connection;
        }
        throw new IOException("too many redirects fetching " + url);
    }

    public static String getString(String url, String accept) throws IOException {
        HttpURLConnection connection = open(url, accept);
        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    /** Downloads to {@code destination}, returning the number of bytes written. */
    public static long download(String url, Path destination) throws IOException {
        HttpURLConnection connection = open(url, "application/octet-stream");
        Path parent = destination.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        long total = 0;
        try (InputStream in = connection.getInputStream();
             OutputStream out = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                total += read;
            }
        } finally {
            connection.disconnect();
        }
        return total;
    }
}
