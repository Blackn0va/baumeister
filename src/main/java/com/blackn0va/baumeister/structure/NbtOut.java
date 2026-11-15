package com.blackn0va.baumeister.structure;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * NBT-Schreiber, Gegenstück zu {@link Nbt#read}: schreibt genau die Java-Typen zurück, die der Leser liefert (Byte,
 * Short, Integer, Long, Float, Double, byte[], String, List, Map, int[], long[]), gzip-gepackt.
 *
 * Wozu (2026-11-15): /bm spielbauwerk speichert ein Bauwerk des Spiels über Structure#fill – samt unberührtem Gestein im
 * Quader. Das muss vor dem Katalog aus der Datei, sonst baut der Baumeister einen Gesteinsklotz um jede Fallgrube.
 * Lesen, Blöcke filtern, mit diesem Schreiber zurück. Die Testhilfe NbtWriter bleibt getrennt, damit Tests nicht
 * denselben Denkfehler teilen.
 *
 * Eine Liste trägt den Tag ihres ersten Elements; gemischte Listen gibt es in NBT nicht und brechen ab, statt eine
 * Datei zu schreiben, die das Spiel anders liest.
 *
 * Threads: zustandslos. Fehler: IOException bei unbekanntem Typ oder gemischter Liste.
 */
public final class NbtOut {

    private NbtOut() {
    }

    /** Wurzel-Compound ohne Namen, gzip-gepackt; schließt out. */
    public static void writeGzip(Map<String, Object> root, OutputStream out) throws IOException {
        try (DataOutputStream data = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(out)))) {
            data.writeByte(10);
            data.writeUTF("");
            compound(data, root, 0);
        }
    }

    @SuppressWarnings("unchecked")
    private static void payload(DataOutputStream out, Object v, int depth) throws IOException {
        if (depth > Nbt.MAX_DEPTH) {
            throw new IOException("NBT zu tief verschachtelt (mehr als " + Nbt.MAX_DEPTH + " Ebenen)");
        }
        switch (v) {
            case Byte b -> out.writeByte(b);
            case Short s -> out.writeShort(s);
            case Integer i -> out.writeInt(i);
            case Long l -> out.writeLong(l);
            case Float f -> out.writeFloat(f);
            case Double d -> out.writeDouble(d);
            case String s -> out.writeUTF(s);
            case byte[] b -> {
                out.writeInt(b.length);
                out.write(b);
            }
            case int[] a -> {
                out.writeInt(a.length);
                for (int x : a) {
                    out.writeInt(x);
                }
            }
            case long[] a -> {
                out.writeInt(a.length);
                for (long x : a) {
                    out.writeLong(x);
                }
            }
            case List<?> list -> {
                int elem = list.isEmpty() ? 0 : type(list.getFirst());
                for (Object o : list) {
                    if (type(o) != elem) {
                        throw new IOException("NBT-Liste mit gemischten Typen (" + elem + " und " + type(o) + ")");
                    }
                }
                out.writeByte(elem);
                out.writeInt(list.size());
                for (Object o : list) {
                    payload(out, o, depth + 1);
                }
            }
            case Map<?, ?> m -> compound(out, (Map<String, Object>) m, depth + 1);
            default -> throw new IOException("NBT-Typ nicht unterstützt: " + v.getClass().getName());
        }
    }

    private static void compound(DataOutputStream out, Map<String, Object> m, int depth) throws IOException {
        for (Map.Entry<String, Object> e : m.entrySet()) {
            out.writeByte(type(e.getValue()));
            out.writeUTF(e.getKey());
            payload(out, e.getValue(), depth);
        }
        out.writeByte(0);
    }

    private static int type(Object v) throws IOException {
        return switch (v) {
            case Byte b -> 1;
            case Short s -> 2;
            case Integer i -> 3;
            case Long l -> 4;
            case Float f -> 5;
            case Double d -> 6;
            case byte[] b -> 7;
            case String s -> 8;
            case List<?> l -> 9;
            case Map<?, ?> m -> 10;
            case int[] a -> 11;
            case long[] a -> 12;
            case null -> throw new IOException("NBT-Wert null");
            default -> throw new IOException("NBT-Typ nicht unterstützt: " + v.getClass().getName());
        };
    }
}
