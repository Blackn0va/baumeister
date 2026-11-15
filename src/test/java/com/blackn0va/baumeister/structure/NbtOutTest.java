package com.blackn0va.baumeister.structure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** NBT-Schreiber gegen den Leser (2026-11-15): jeder Tag-Typ übersteht den Weg hin und zurück. */
class NbtOutTest {

    private static Map<String, Object> roundTrip(Map<String, Object> root) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtOut.writeGzip(root, bytes);
        return Nbt.read(new ByteArrayInputStream(bytes.toByteArray()));
    }

    @Test
    void everyTagTypeSurvivesWriteAndRead() throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("b", (byte) -3);
        root.put("s", (short) 300);
        root.put("i", 70_000);
        root.put("l", 1L << 40);
        root.put("f", 1.5f);
        root.put("d", -2.25);
        root.put("t", "Wüstentempel");
        root.put("leer", List.of());
        root.put("pos", List.of(1, 2, 3));
        root.put("tief", Map.of("innen", List.of(Map.of("x", (byte) 1))));
        Map<String, Object> back = roundTrip(root);
        assertEquals(List.copyOf(root.keySet()), List.copyOf(back.keySet()), "Reihenfolge wie geschrieben");
        root.forEach((k, v) -> assertEquals(v, back.get(k), k));
        Map<String, Object> arrays = new LinkedHashMap<>();
        arrays.put("ba", new byte[] {1, -1});
        arrays.put("ia", new int[] {7, -7});
        arrays.put("la", new long[] {Long.MIN_VALUE});
        Map<String, Object> a = roundTrip(arrays);
        assertArrayEquals(new byte[] {1, -1}, (byte[]) a.get("ba"));
        assertArrayEquals(new int[] {7, -7}, (int[]) a.get("ia"));
        assertArrayEquals(new long[] {Long.MIN_VALUE}, (long[]) a.get("la"));
    }

    @Test
    void mixedListIsRefusedInsteadOfWrittenWrong() {
        Map<String, Object> root = Map.of("pos", List.of(1, 2L));
        assertThrows(IOException.class, () -> NbtOut.writeGzip(root, new ByteArrayOutputStream()));
    }
}
