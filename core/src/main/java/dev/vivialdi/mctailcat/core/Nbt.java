package dev.vivialdi.mctailcat.core;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A generic NBT reader/writer.
 *
 * <p>Deliberately generic rather than typed: the mod reads {@code servers.dat},
 * changes the one entry it owns, and writes everything else back byte-for-byte
 * equivalent. Minecraft has added fields to server entries over the years, and
 * a structure-preserving round trip is what keeps a single jar safe across
 * versions.
 */
public final class Nbt {

    public static final int TAG_END = 0;
    public static final int TAG_BYTE = 1;
    public static final int TAG_SHORT = 2;
    public static final int TAG_INT = 3;
    public static final int TAG_LONG = 4;
    public static final int TAG_FLOAT = 5;
    public static final int TAG_DOUBLE = 6;
    public static final int TAG_BYTE_ARRAY = 7;
    public static final int TAG_STRING = 8;
    public static final int TAG_LIST = 9;
    public static final int TAG_COMPOUND = 10;
    public static final int TAG_INT_ARRAY = 11;
    public static final int TAG_LONG_ARRAY = 12;

    /** Nesting cap, mirroring vanilla's, so a malformed file cannot blow the stack. */
    private static final int MAX_DEPTH = 512;

    private Nbt() {
    }

    /** A named-value map. Insertion order is preserved so rewrites stay stable. */
    public static final class Compound {
        private final Map<String, Object> values = new LinkedHashMap<>();
        private final Map<String, Integer> types = new LinkedHashMap<>();

        public boolean contains(String key) {
            return values.containsKey(key);
        }

        public Object get(String key) {
            return values.get(key);
        }

        public int typeOf(String key) {
            return types.getOrDefault(key, TAG_END);
        }

        public String getString(String key, String fallback) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : fallback;
        }

        public void put(String key, int type, Object value) {
            values.put(key, value);
            types.put(key, type);
        }

        public void putString(String key, String value) {
            put(key, TAG_STRING, value);
        }

        public void putByte(String key, byte value) {
            put(key, TAG_BYTE, value);
        }

        public void remove(String key) {
            values.remove(key);
            types.remove(key);
        }

        public Iterable<String> keys() {
            return new ArrayList<>(values.keySet());
        }

        public int size() {
            return values.size();
        }
    }

    /** A homogeneous list of payloads. */
    public static final class TagList {
        private int elementType;
        private final List<Object> items = new ArrayList<>();

        public TagList(int elementType) {
            this.elementType = elementType;
        }

        public int elementType() {
            return elementType;
        }

        public List<Object> items() {
            return items;
        }

        public void add(int type, Object value) {
            if (items.isEmpty()) {
                elementType = type;
            } else if (elementType != type) {
                throw new IllegalArgumentException(
                        "cannot add tag type " + type + " to a list of type " + elementType);
            }
            items.add(value);
        }
    }

    // ---------------------------------------------------------------- reading

    /** Reads a root compound, including its (conventionally empty) root name. */
    public static Compound readRoot(DataInput in) throws IOException {
        int type = in.readUnsignedByte();
        if (type == TAG_END) {
            return new Compound();
        }
        if (type != TAG_COMPOUND) {
            throw new IOException("expected a root compound tag, found type " + type);
        }
        in.readUTF(); // root name, discarded -- vanilla writes ""
        return readCompound(in, 0);
    }

    private static Compound readCompound(DataInput in, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT nesting too deep");
        }
        Compound compound = new Compound();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == TAG_END) {
                return compound;
            }
            String name = in.readUTF();
            compound.put(name, type, readPayload(in, type, depth + 1));
        }
    }

    private static Object readPayload(DataInput in, int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT nesting too deep");
        }
        switch (type) {
            case TAG_BYTE:
                return in.readByte();
            case TAG_SHORT:
                return in.readShort();
            case TAG_INT:
                return in.readInt();
            case TAG_LONG:
                return in.readLong();
            case TAG_FLOAT:
                return in.readFloat();
            case TAG_DOUBLE:
                return in.readDouble();
            case TAG_BYTE_ARRAY: {
                byte[] bytes = new byte[readLength(in)];
                in.readFully(bytes);
                return bytes;
            }
            case TAG_STRING:
                return in.readUTF();
            case TAG_LIST: {
                int elementType = in.readUnsignedByte();
                int length = readLength(in);
                TagList list = new TagList(elementType);
                for (int i = 0; i < length; i++) {
                    list.items().add(readPayload(in, elementType, depth + 1));
                }
                return list;
            }
            case TAG_COMPOUND:
                return readCompound(in, depth + 1);
            case TAG_INT_ARRAY: {
                int[] ints = new int[readLength(in)];
                for (int i = 0; i < ints.length; i++) {
                    ints[i] = in.readInt();
                }
                return ints;
            }
            case TAG_LONG_ARRAY: {
                long[] longs = new long[readLength(in)];
                for (int i = 0; i < longs.length; i++) {
                    longs[i] = in.readLong();
                }
                return longs;
            }
            default:
                throw new IOException("unknown NBT tag type " + type);
        }
    }

    private static int readLength(DataInput in) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("negative NBT length " + length);
        }
        return length;
    }

    // ---------------------------------------------------------------- writing

    public static void writeRoot(DataOutput out, Compound root) throws IOException {
        out.writeByte(TAG_COMPOUND);
        out.writeUTF("");
        writeCompound(out, root);
    }

    private static void writeCompound(DataOutput out, Compound compound) throws IOException {
        for (String key : compound.keys()) {
            int type = compound.typeOf(key);
            out.writeByte(type);
            out.writeUTF(key);
            writePayload(out, type, compound.get(key));
        }
        out.writeByte(TAG_END);
    }

    private static void writePayload(DataOutput out, int type, Object value) throws IOException {
        switch (type) {
            case TAG_BYTE:
                out.writeByte(((Number) value).byteValue());
                break;
            case TAG_SHORT:
                out.writeShort(((Number) value).shortValue());
                break;
            case TAG_INT:
                out.writeInt(((Number) value).intValue());
                break;
            case TAG_LONG:
                out.writeLong(((Number) value).longValue());
                break;
            case TAG_FLOAT:
                out.writeFloat(((Number) value).floatValue());
                break;
            case TAG_DOUBLE:
                out.writeDouble(((Number) value).doubleValue());
                break;
            case TAG_BYTE_ARRAY: {
                byte[] bytes = (byte[]) value;
                out.writeInt(bytes.length);
                out.write(bytes);
                break;
            }
            case TAG_STRING:
                out.writeUTF((String) value);
                break;
            case TAG_LIST: {
                TagList list = (TagList) value;
                // An empty list is written as element type END, matching vanilla.
                int elementType = list.items().isEmpty() ? TAG_END : list.elementType();
                out.writeByte(elementType);
                out.writeInt(list.items().size());
                for (Object item : list.items()) {
                    writePayload(out, elementType, item);
                }
                break;
            }
            case TAG_COMPOUND:
                writeCompound(out, (Compound) value);
                break;
            case TAG_INT_ARRAY: {
                int[] ints = (int[]) value;
                out.writeInt(ints.length);
                for (int i : ints) {
                    out.writeInt(i);
                }
                break;
            }
            case TAG_LONG_ARRAY: {
                long[] longs = (long[]) value;
                out.writeInt(longs.length);
                for (long l : longs) {
                    out.writeLong(l);
                }
                break;
            }
            default:
                throw new IOException("cannot write NBT tag type " + type);
        }
    }

    /** Convenience wrappers so callers do not need to build the stream plumbing. */
    public static Compound readRoot(java.io.InputStream in) throws IOException {
        return readRoot((DataInput) new DataInputStream(in));
    }

    public static void writeRoot(java.io.OutputStream out, Compound root) throws IOException {
        DataOutputStream data = new DataOutputStream(out);
        writeRoot((DataOutput) data, root);
        data.flush();
    }
}
