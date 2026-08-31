package dev.vivialdi.mctailcat.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class NbtTest {

    @Test
    void roundTripsEveryTagType() throws IOException {
        Nbt.Compound root = new Nbt.Compound();
        root.put("byte", Nbt.TAG_BYTE, (byte) -7);
        root.put("short", Nbt.TAG_SHORT, (short) 300);
        root.put("int", Nbt.TAG_INT, 70000);
        root.put("long", Nbt.TAG_LONG, 1234567890123L);
        root.put("float", Nbt.TAG_FLOAT, 1.5f);
        root.put("double", Nbt.TAG_DOUBLE, 2.25d);
        root.put("bytes", Nbt.TAG_BYTE_ARRAY, new byte[] {1, 2, 3});
        root.put("string", Nbt.TAG_STRING, "héllo");
        root.put("ints", Nbt.TAG_INT_ARRAY, new int[] {4, 5});
        root.put("longs", Nbt.TAG_LONG_ARRAY, new long[] {6L, 7L});

        Nbt.TagList list = new Nbt.TagList(Nbt.TAG_COMPOUND);
        Nbt.Compound element = new Nbt.Compound();
        element.putString("name", "entry");
        list.add(Nbt.TAG_COMPOUND, element);
        root.put("list", Nbt.TAG_LIST, list);

        Nbt.Compound decoded = reencode(root);

        assertEquals((byte) -7, decoded.get("byte"));
        assertEquals((short) 300, decoded.get("short"));
        assertEquals(70000, decoded.get("int"));
        assertEquals(1234567890123L, decoded.get("long"));
        assertEquals(1.5f, decoded.get("float"));
        assertEquals(2.25d, decoded.get("double"));
        assertArrayEquals(new byte[] {1, 2, 3}, (byte[]) decoded.get("bytes"));
        assertEquals("héllo", decoded.get("string"));
        assertArrayEquals(new int[] {4, 5}, (int[]) decoded.get("ints"));
        assertArrayEquals(new long[] {6L, 7L}, (long[]) decoded.get("longs"));

        Nbt.TagList decodedList = (Nbt.TagList) decoded.get("list");
        assertEquals(1, decodedList.items().size());
        assertEquals("entry", ((Nbt.Compound) decodedList.items().get(0)).getString("name", null));
    }

    @Test
    void preservesKeyOrder() throws IOException {
        Nbt.Compound root = new Nbt.Compound();
        root.putString("z", "1");
        root.putString("a", "2");
        root.putString("m", "3");

        StringBuilder order = new StringBuilder();
        for (String key : reencode(root).keys()) {
            order.append(key);
        }
        assertEquals("zam", order.toString());
    }

    @Test
    void writesEmptyListsWithEndElementType() throws IOException {
        Nbt.Compound root = new Nbt.Compound();
        root.put("empty", Nbt.TAG_LIST, new Nbt.TagList(Nbt.TAG_COMPOUND));

        Nbt.TagList decoded = (Nbt.TagList) reencode(root).get("empty");
        assertEquals(0, decoded.items().size());
        assertEquals(Nbt.TAG_END, decoded.elementType());
    }

    @Test
    void rejectsMixedListTypes() {
        Nbt.TagList list = new Nbt.TagList(Nbt.TAG_STRING);
        list.add(Nbt.TAG_STRING, "a");
        assertThrows(IllegalArgumentException.class, () -> list.add(Nbt.TAG_INT, 1));
    }

    @Test
    void rejectsUnknownTagTypes() {
        byte[] malformed = {Nbt.TAG_COMPOUND, 0, 0, 99, 0, 1, 'x'};
        assertThrows(IOException.class,
                () -> Nbt.readRoot(new ByteArrayInputStream(malformed)));
    }

    private static Nbt.Compound reencode(Nbt.Compound root) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Nbt.writeRoot(out, root);
        return Nbt.readRoot(new ByteArrayInputStream(out.toByteArray()));
    }
}
