package dev.vivialdi.mctailcat.core;

/**
 * Logging indirection for the core module.
 *
 * <p>Core stays dependency-free, so it cannot reach for SLF4J the way a normal
 * Fabric mod would. The loader module installs a {@link Sink} that forwards to
 * the game log; until then messages go to stderr.
 */
public final class Log {

    public interface Sink {
        void info(String message);

        void warn(String message);

        void error(String message, Throwable error);
    }

    private static volatile Sink sink = new Sink() {
        @Override
        public void info(String message) {
            System.out.println("[Tailcat] " + message);
        }

        @Override
        public void warn(String message) {
            System.out.println("[Tailcat] WARN: " + message);
        }

        @Override
        public void error(String message, Throwable error) {
            System.err.println("[Tailcat] ERROR: " + message);
            if (error != null) {
                error.printStackTrace();
            }
        }
    };

    private Log() {
    }

    public static void setSink(Sink newSink) {
        if (newSink != null) {
            sink = newSink;
        }
    }

    public static void info(String message) {
        sink.info(message);
    }

    public static void warn(String message) {
        sink.warn(message);
    }

    public static void error(String message, Throwable error) {
        sink.error(message, error);
    }

    public static void error(String message) {
        sink.error(message, null);
    }
}
