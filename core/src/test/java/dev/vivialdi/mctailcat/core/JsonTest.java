package dev.vivialdi.mctailcat.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void parsesObjectsAndScalars() {
        Map<String, Object> map = Json.parseObject(
                "{\"name\":\"Survival\",\"port\":25565,\"ratio\":1.5,\"on\":true,\"off\":null}");

        assertEquals("Survival", map.get("name"));
        assertEquals(25565L, map.get("port"));
        assertEquals(1.5, map.get("ratio"));
        assertEquals(Boolean.TRUE, map.get("on"));
        assertNull(map.get("off"));
    }

    @Test
    void keepsWholeNumbersIntegral() {
        // A port that round-trips as 25565.0 would be written back wrong.
        Map<String, Object> map = Json.parseObject("{\"port\":25565}");
        assertEquals(25565, Json.integer(map, "port", 0));
        assertTrue(Json.write(map).contains("25565"));
        assertTrue(!Json.write(map).contains("25565.0"));
    }

    @Test
    void parsesNestedArrays() {
        Map<String, Object> map = Json.parseObject(
                "{\"servers\":[{\"name\":\"a\"},{\"name\":\"b\"}]}");
        List<Object> servers = Json.array(map, "servers");
        assertEquals(2, servers.size());
        assertEquals("b", Json.object(servers.get(1)).get("name"));
    }

    @Test
    void handlesEscapesInBothDirections() {
        String awkward = "quote\" backslash\\ newline\n tab\t unicodeé";
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("text", awkward);

        Map<String, Object> reparsed = Json.parseObject(Json.write(original));
        assertEquals(awkward, reparsed.get("text"));
    }

    @Test
    void readsUnicodeEscapes() {
        Map<String, Object> map = Json.parseObject("{\"text\":\"caf\\u00e9\"}");
        assertEquals("café", map.get("text"));
    }

    @Test
    void toleratesCommentsAndTrailingCommas() {
        // Config files get hand-edited; these should not be fatal.
        Map<String, Object> map = Json.parseObject(
                "{\n  // a comment\n  \"a\": 1,\n  /* another */ \"b\": [1, 2,],\n}");
        assertEquals(1, Json.integer(map, "a", 0));
        assertEquals(2, Json.array(map, "b").size());
    }

    @Test
    void emptyContainersRoundTrip() {
        Map<String, Object> map = Json.parseObject(Json.write(Json.parseObject("{\"a\":{},\"b\":[]}")));
        assertEquals(0, Json.object(map.get("a")).size());
        assertEquals(0, Json.array(map, "b").size());
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":1"));
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":1} trailing"));
        assertThrows(Json.JsonException.class, () -> Json.parseObject("[1,2]"));
    }

    @Test
    void accessorsFallBackOnWrongTypes() {
        Map<String, Object> map = Json.parseObject("{\"port\":\"25565\",\"flag\":\"true\",\"n\":{}}");
        assertEquals(25565, Json.integer(map, "port", 0));
        assertTrue(Json.bool(map, "flag", false));
        assertEquals(7, Json.integer(map, "n", 7));
        assertEquals("fallback", Json.string(map, "missing", "fallback"));
    }
}
