package com.blackn0va.baumeister.structure;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Kleiner NBT-Leser (Java Edition, Big Endian) für Bauwerk-Dateien – ohne WorldEdit und ohne Bukkit.
 *
 * Warum selbst: Bisher las nur SchematicLibrary Schematics, über WorldEdit auf dem laufenden Server. Lehrset-Werkzeuge
 * und Tests haben weder Server noch WorldEdit; .litematic und die Strukturdateien des Spiels kann WorldEdit ohnehin
 * nicht. NBT ist ein fester, öffentlich beschriebener Aufbau aus 13 Tag-Arten – das Lesen sind wenige Zeilen.
 *
 * Ergebnis in Java-Typen: Byte, Short, Integer, Long, Float, Double, byte[], String, List, Map (Reihenfolge wie in der
 * Datei), int[], long[]. Der Name des Wurzel-Tags wird verworfen.
 *
 * Grenzen gegen kaputte oder böswillige Dateien (ASVS V5.2): höchstens MAX_DEPTH Ebenen und MAX_ELEMENTS Einträge je
 * Liste oder Feld; eine Länge darüber oder ein negatives Feld bricht mit IOException ab, statt Speicher anzufordern.
 * Gzip wird an der Kennung 1f 8b erkannt, sonst roh gelesen.
 *
 * Threads: zustandslos. Fehler: IOException mit deutschem Grund.
 */
public final class Nbt {

    static final int MAX_DEPTH = 64;
    /** 16 Mio. Einträge: ein 256³-Würfel als Blockindex, mehr hat keine Bauwerk-Datei des Baumeisters. */
    static final int MAX_ELEMENTS = 16 * 1024 * 1024;

    private Nbt() {
    }

    /** Wurzel-Compound einer Datei (gzip oder roh). */
    public static Map<String, Object> read(InputStream raw) throws IOException {
        BufferedInputStream in = new BufferedInputStream(raw);
        in.mark(2);
        int a = in.read();
        int b = in.read();
        in.reset();
        InputStream body = a == 0x1f && b == 0x8b ? new GZIPInputStream(in) : in;
        DataInputStream data = new DataInputStream(body);
        int type = data.readUnsignedByte();
        if (type != 10) {
            throw new IOException("Keine NBT-Datei: Wurzel ist Tag " + type + " statt Compound");
        }
        data.readUTF();
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) readPayload(data, 10, 0);
        return root;
    }

    private static Object readPayload(DataInputStream in, int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT zu tief verschachtelt (mehr als " + MAX_DEPTH + " Ebenen)");
        }
        return switch (type) {
            case 1 -> in.readByte();
            case 2 -> in.readShort();
            case 3 -> in.readInt();
            case 4 -> in.readLong();
            case 5 -> in.readFloat();
            case 6 -> in.readDouble();
            case 7 -> {
                byte[] v = new byte[length(in)];
                in.readFully(v);
                yield v;
            }
            case 8 -> readString(in);
            case 9 -> {
                int elem = in.readUnsignedByte();
                int n = length(in);
                List<Object> list = new ArrayList<>(Math.min(n, 4096));
                for (int i = 0; i < n; i++) {
                    list.add(readPayload(in, elem, depth + 1));
                }
                yield list;
            }
            case 10 -> {
                Map<String, Object> map = new LinkedHashMap<>();
                while (true) {
                    int t = in.readUnsignedByte();
                    if (t == 0) {
                        break;
                    }
                    String name = readString(in);
                    map.put(name, readPayload(in, t, depth + 1));
                }
                yield map;
            }
            case 11 -> {
                int[] v = new int[length(in)];
                for (int i = 0; i < v.length; i++) {
                    v[i] = in.readInt();
                }
                yield v;
            }
            case 12 -> {
                long[] v = new long[length(in)];
                for (int i = 0; i < v.length; i++) {
                    v[i] = in.readLong();
                }
                yield v;
            }
            default -> throw new IOException("Unbekannter NBT-Tag " + type);
        };
    }

    private static int length(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > MAX_ELEMENTS) {
            throw new IOException("NBT-Länge außerhalb der Grenze: " + n);
        }
        return n;
    }

    /** Modified UTF-8 wie readUTF, aber ohne dessen 64-KiB-Annahme beim Puffer. */
    private static String readString(DataInputStream in) throws IOException {
        int n = in.readUnsignedShort();
        byte[] b = new byte[n];
        in.readFully(b);
        // Bauwerk-Dateien enthalten in Blocknamen nur ASCII; Modified UTF-8 weicht nur bei U+0000 und Ersatzpaaren ab.
        return new String(b, StandardCharsets.UTF_8);
    }
}
