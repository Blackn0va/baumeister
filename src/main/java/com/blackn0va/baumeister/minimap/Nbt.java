package com.blackn0va.baumeister.minimap;

import java.io.DataInput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Schlanker NBT-Leser für Chunk-Daten aus Regionsdateien (Vorab-Scan der Minikarte). Kein Fremdcode im Plugin: Ein
 * NBT-Paket wäre die einzige neue Abhängigkeit, und gebraucht werden nur Lesen und Überspringen.
 *
 * Überspringt alle Schlüssel aus skip in jeder Tiefe, ohne Objekte anzulegen – Biome, Licht, Entities und Blockeinträge
 * machen den Großteil eines Chunks aus und sind für die Karte wertlos.
 *
 * Ergebnis: Map (Compound), List, String, Byte, Short, Integer, Long, Float, Double, byte[], int[], long[].
 * Threads: rein, beliebig. Fehler: IOException bei unbekanntem Tag oder abgeschnittenen Daten.
 */
public final class Nbt {

    static final int END = 0;
    static final int BYTE = 1;
    static final int SHORT = 2;
    static final int INT = 3;
    static final int LONG = 4;
    static final int FLOAT = 5;
    static final int DOUBLE = 6;
    static final int BYTE_ARRAY = 7;
    static final int STRING = 8;
    static final int LIST = 9;
    static final int COMPOUND = 10;
    static final int INT_ARRAY = 11;
    static final int LONG_ARRAY = 12;
    /** Gegen kaputte Längenangaben: mehr hat kein Chunk (eine Sektion hat höchstens 4 096 Einträge). */
    private static final int MAX_LENGTH = 1 << 24;
    private static final int MAX_DEPTH = 64;

    private Nbt() {
    }

    /** Liest das Wurzel-Compound (Typ 10 mit Namen). */
    public static Map<String, Object> readRoot(DataInput in, Set<String> skip) throws IOException {
        int type = in.readUnsignedByte();
        if (type != COMPOUND) {
            throw new IOException("NBT: Wurzel ist kein Compound (Typ " + type + ")");
        }
        in.skipBytes(in.readUnsignedShort());  // Name der Wurzel
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) payload(in, COMPOUND, skip, 0);
        return root;
    }

    private static Object payload(DataInput in, int type, Set<String> skip, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT: zu tief verschachtelt");
        }
        switch (type) {
            case BYTE:
                return in.readByte();
            case SHORT:
                return in.readShort();
            case INT:
                return in.readInt();
            case LONG:
                return in.readLong();
            case FLOAT:
                return in.readFloat();
            case DOUBLE:
                return in.readDouble();
            case BYTE_ARRAY: {
                byte[] a = new byte[length(in.readInt())];
                in.readFully(a);
                return a;
            }
            case STRING:
                return readString(in);
            case LIST: {
                int elem = in.readUnsignedByte();
                int n = length(in.readInt());
                List<Object> list = new ArrayList<>(Math.min(n, 1024));
                for (int i = 0; i < n; i++) {
                    list.add(payload(in, elem, skip, depth + 1));
                }
                return list;
            }
            case COMPOUND: {
                Map<String, Object> m = new HashMap<>();
                while (true) {
                    int t = in.readUnsignedByte();
                    if (t == END) {
                        return m;
                    }
                    String name = readString(in);
                    if (skip.contains(name)) {
                        skipPayload(in, t, depth + 1);
                    } else {
                        m.put(name, payload(in, t, skip, depth + 1));
                    }
                }
            }
            case INT_ARRAY: {
                int[] a = new int[length(in.readInt())];
                for (int i = 0; i < a.length; i++) {
                    a[i] = in.readInt();
                }
                return a;
            }
            case LONG_ARRAY: {
                long[] a = new long[length(in.readInt())];
                for (int i = 0; i < a.length; i++) {
                    a[i] = in.readLong();
                }
                return a;
            }
            default:
                throw new IOException("NBT: unbekannter Tag " + type);
        }
    }

    private static void skipPayload(DataInput in, int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT: zu tief verschachtelt");
        }
        switch (type) {
            case BYTE -> in.skipBytes(1);
            case SHORT -> in.skipBytes(2);
            case INT, FLOAT -> in.skipBytes(4);
            case LONG, DOUBLE -> in.skipBytes(8);
            case BYTE_ARRAY -> skipFully(in, length(in.readInt()));
            case STRING -> skipFully(in, in.readUnsignedShort());
            case LIST -> {
                int elem = in.readUnsignedByte();
                int n = length(in.readInt());
                for (int i = 0; i < n; i++) {
                    skipPayload(in, elem, depth + 1);
                }
            }
            case COMPOUND -> {
                while (true) {
                    int t = in.readUnsignedByte();
                    if (t == END) {
                        return;
                    }
                    skipFully(in, in.readUnsignedShort());
                    skipPayload(in, t, depth + 1);
                }
            }
            case INT_ARRAY -> skipFully(in, 4L * length(in.readInt()));
            case LONG_ARRAY -> skipFully(in, 8L * length(in.readInt()));
            default -> throw new IOException("NBT: unbekannter Tag " + type);
        }
    }

    private static void skipFully(DataInput in, long n) throws IOException {
        while (n > 0) {
            int step = (int) Math.min(n, Integer.MAX_VALUE);
            int done = in.skipBytes(step);
            if (done <= 0) {
                throw new IOException("NBT: abgeschnitten");
            }
            n -= done;
        }
    }

    private static int length(int n) throws IOException {
        if (n < 0 || n > MAX_LENGTH) {
            throw new IOException("NBT: unplausible Länge " + n);
        }
        return n;
    }

    /** Modifiziertes UTF-8 wie DataInput.readUTF; für Blocknamen und Schlüssel reicht das. */
    private static String readString(DataInput in) throws IOException {
        int n = in.readUnsignedShort();
        byte[] b = new byte[n];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }
}
