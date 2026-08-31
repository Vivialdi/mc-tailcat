package dev.vivialdi.mctailcat.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader/writer.
 *
 * <p>The mod ships as a single jar with no third-party runtime dependencies so
 * that it stays loader-only and version-agnostic, which rules out Gson even
 * though Minecraft happens to bundle it. Values map to {@link Map},
 * {@link List}, {@link String}, {@link Double}, {@link Long}, {@link Boolean}
 * and {@code null}.
 */
public final class Json {

    private Json() {
    }

    // ---------------------------------------------------------------- parsing

    public static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new JsonException("trailing content at index " + parser.index);
        }
        return value;
    }

    /** Parses {@code text} and requires the result to be a JSON object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new JsonException("expected a JSON object at the top level");
        }
        return (Map<String, Object>) value;
    }

    public static final class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public JsonException(String message) {
            super(message);
        }
    }

    private static final class Parser {
        private final String src;
        private int index;

        Parser(String src) {
            this.src = src;
        }

        boolean atEnd() {
            return index >= src.length();
        }

        void skipWhitespace() {
            while (index < src.length()) {
                char c = src.charAt(index);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    index++;
                } else if (c == '/' && index + 1 < src.length()) {
                    // Line and block comments are not JSON, but hand-edited
                    // config files collect them anyway. Accept both.
                    char next = src.charAt(index + 1);
                    if (next == '/') {
                        while (index < src.length() && src.charAt(index) != '\n') {
                            index++;
                        }
                    } else if (next == '*') {
                        int close = src.indexOf("*/", index + 2);
                        if (close < 0) {
                            throw new JsonException("unterminated block comment");
                        }
                        index = close + 2;
                    } else {
                        return;
                    }
                } else {
                    return;
                }
            }
        }

        Object readValue() {
            if (atEnd()) {
                throw new JsonException("unexpected end of input");
            }
            char c = src.charAt(index);
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return readNumber();
            }
        }

        private void expect(String literal) {
            if (!src.startsWith(literal, index)) {
                throw new JsonException("expected '" + literal + "' at index " + index);
            }
            index += literal.length();
        }

        private Map<String, Object> readObject() {
            Map<String, Object> result = new LinkedHashMap<>();
            index++; // '{'
            skipWhitespace();
            if (!atEnd() && src.charAt(index) == '}') {
                index++;
                return result;
            }
            while (true) {
                skipWhitespace();
                if (atEnd() || src.charAt(index) != '"') {
                    throw new JsonException("expected a string key at index " + index);
                }
                String key = readString();
                skipWhitespace();
                if (atEnd() || src.charAt(index) != ':') {
                    throw new JsonException("expected ':' at index " + index);
                }
                index++;
                skipWhitespace();
                result.put(key, readValue());
                skipWhitespace();
                if (atEnd()) {
                    throw new JsonException("unterminated object");
                }
                char c = src.charAt(index++);
                if (c == '}') {
                    return result;
                }
                if (c != ',') {
                    throw new JsonException("expected ',' or '}' at index " + (index - 1));
                }
                skipWhitespace();
                // Tolerate a trailing comma before the closing brace.
                if (!atEnd() && src.charAt(index) == '}') {
                    index++;
                    return result;
                }
            }
        }

        private List<Object> readArray() {
            List<Object> result = new ArrayList<>();
            index++; // '['
            skipWhitespace();
            if (!atEnd() && src.charAt(index) == ']') {
                index++;
                return result;
            }
            while (true) {
                skipWhitespace();
                result.add(readValue());
                skipWhitespace();
                if (atEnd()) {
                    throw new JsonException("unterminated array");
                }
                char c = src.charAt(index++);
                if (c == ']') {
                    return result;
                }
                if (c != ',') {
                    throw new JsonException("expected ',' or ']' at index " + (index - 1));
                }
                skipWhitespace();
                if (!atEnd() && src.charAt(index) == ']') {
                    index++;
                    return result;
                }
            }
        }

        private String readString() {
            index++; // opening quote
            StringBuilder out = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new JsonException("unterminated string");
                }
                char c = src.charAt(index++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw new JsonException("unterminated escape sequence");
                }
                char esc = src.charAt(index++);
                switch (esc) {
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case '/': out.append('/'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        if (index + 4 > src.length()) {
                            throw new JsonException("truncated \\u escape");
                        }
                        out.append((char) Integer.parseInt(src.substring(index, index + 4), 16));
                        index += 4;
                        break;
                    default:
                        throw new JsonException("invalid escape '\\" + esc + "'");
                }
            }
        }

        private Object readNumber() {
            int start = index;
            if (!atEnd() && (src.charAt(index) == '-' || src.charAt(index) == '+')) {
                index++;
            }
            boolean floating = false;
            while (!atEnd()) {
                char c = src.charAt(index);
                if (c >= '0' && c <= '9') {
                    index++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    floating = true;
                    index++;
                } else {
                    break;
                }
            }
            String literal = src.substring(start, index);
            if (literal.isEmpty()) {
                throw new JsonException("expected a value at index " + start);
            }
            try {
                // Keep whole numbers integral so round-tripping a port does not
                // turn 25565 into "25565.0".
                return floating ? (Object) Double.parseDouble(literal) : (Object) Long.parseLong(literal);
            } catch (NumberFormatException e) {
                throw new JsonException("malformed number '" + literal + "'");
            }
        }
    }

    // ---------------------------------------------------------------- writing

    /** Serialises {@code value} as indented JSON. */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value, 0);
        out.append('\n');
        return out.toString();
    }

    private static void writeValue(StringBuilder out, Object value, int depth) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Map) {
            writeObject(out, (Map<?, ?>) value, depth);
        } else if (value instanceof List) {
            writeArray(out, (List<?>) value, depth);
        } else if (value instanceof String) {
            writeString(out, (String) value);
        } else if (value instanceof Boolean || value instanceof Number) {
            out.append(value);
        } else {
            writeString(out, String.valueOf(value));
        }
    }

    private static void writeObject(StringBuilder out, Map<?, ?> map, int depth) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append("{\n");
        int remaining = map.size();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            indent(out, depth + 1);
            writeString(out, String.valueOf(entry.getKey()));
            out.append(": ");
            writeValue(out, entry.getValue(), depth + 1);
            if (--remaining > 0) {
                out.append(',');
            }
            out.append('\n');
        }
        indent(out, depth);
        out.append('}');
    }

    private static void writeArray(StringBuilder out, List<?> list, int depth) {
        if (list.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            indent(out, depth + 1);
            writeValue(out, list.get(i), depth + 1);
            if (i < list.size() - 1) {
                out.append(',');
            }
            out.append('\n');
        }
        indent(out, depth);
        out.append(']');
    }

    private static void indent(StringBuilder out, int depth) {
        out.append("  ".repeat(depth));
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    // ------------------------------------------------------------- accessors

    public static String string(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    public static int integer(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static boolean bool(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof List ? (List<Object>) value : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }
}
