package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Rückgängig auf der Platte: Zustandstexte je Zustand einmal, schneller Leser, kleine Teile. */
class UndoSpoolTest {

    /**
     * BlockData ohne Server: vergleicht und hasht über den Zustandstext wie CraftBlockData über den Serverzustand,
     * zählt getAsString.
     */
    private static BlockData fakeData(String state, AtomicInteger asStringCalls) {
        return (BlockData) Proxy.newProxyInstance(UndoSpoolTest.class.getClassLoader(), new Class<?>[] {BlockData.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "getAsString" -> {
                        asStringCalls.incrementAndGet();
                        yield state;
                    }
                    case "hashCode" -> state.hashCode();
                    case "equals" -> args[0] instanceof BlockData o && o.toString().equals(state);
                    case "toString" -> state;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    @Test
    void stateTextIsBuiltOncePerState(@TempDir Path tmp) throws Exception {
        // JFR 2026-10-29: getAsString je gesetztem Block war ein Siebtel des Stadtbaus im Server-Thread.
        AtomicInteger calls = new AtomicInteger();
        String[] states = {"minecraft:air", "minecraft:grass_block[snowy=false]",
            "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]"};
        UndoSpool s = new UndoSpool(tmp.resolve("bau"));
        int n = 30_000;
        for (int i = 0; i < n; i++) {
            // Jedes Mal ein neues Objekt – wie CraftBlock.getBlockData, das je Aufruf eine neue Hülle liefert.
            s.record(i, -61, -i, fakeData(states[i % 3], calls));
        }
        s.finish();
        assertEquals(3, calls.get(), "getAsString je Zustand einmal statt je Block");
        UndoSpool.Part p = UndoSpool.read(s.partsNewestFirst().getFirst());
        assertEquals(n, p.size());
        for (int i = 0; i < n; i++) {
            assertEquals(states[i % 3], p.state()[i]);
        }
    }

    @Test
    void readReturnsWhatWasWrittenIncludingUmlautStates(@TempDir Path tmp) throws Exception {
        UndoSpool s = new UndoSpool(tmp.resolve("bau"));
        List<String> written = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            String st = i % 7 == 0 ? "minecraft:schild_ä_" + (i % 5) : "minecraft:stone_" + (i % 11);
            written.add(st);
            s.record(i * 3, i % 400 - 64, -i * 5, st);
        }
        s.finish();
        UndoSpool.Part p = UndoSpool.read(s.partsNewestFirst().getFirst());
        assertEquals(1000, p.size());
        assertArrayEquals(written.toArray(new String[0]), java.util.Arrays.copyOf(p.state(), p.size()));
        assertEquals(999 * 3, p.x()[999]);
        assertEquals(999 % 400 - 64, p.y()[999]);
        assertEquals(-999 * 5, p.z()[999]);
    }

    @Test
    void corruptPartIsAnIoErrorNotACrash(@TempDir Path tmp) throws Exception {
        // BuildService.restoreSpool fängt IOException und meldet „ein Teil ist nicht lesbar“. Vorher warf ein
        // unbekannter Zustand eine IndexOutOfBoundsException aus dem Tick.
        Path f = tmp.resolve("teil-00001.bin.gz");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(f)))) {
            out.writeInt(1);
            out.writeInt(2);
            out.writeInt(3);
            out.writeInt(7);  // Zustand 7 – nie angelegt
        }
        IOException e = assertThrows(IOException.class, () -> UndoSpool.read(f));
        assertTrue(e.getMessage().contains("kaputt"), e.getMessage());
    }

    @Test
    void truncatedStateIsAnIoErrorNotACrash(@TempDir Path tmp) throws Exception {
        // Prüfbefund 2026-10-29: Nach der Zustandsmarke fehlt die Länge – vorher IndexOutOfBoundsException aus dem
        // Tick, die restoreSpool (fängt IOException) nicht abfängt.
        Path f = tmp.resolve("teil-00002.bin.gz");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(f)))) {
            out.writeInt(Integer.MIN_VALUE);  // NEW_STATE, danach nichts
        }
        IOException e = assertThrows(IOException.class, () -> UndoSpool.read(f));
        assertTrue(e.getMessage().contains("abgeschnitten"), e.getMessage());
        // Länge da, Text zu kurz: ebenfalls IOException statt eines Zustands aus fremden Bytes.
        Path g = tmp.resolve("teil-00003.bin.gz");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(g)))) {
            out.writeInt(Integer.MIN_VALUE);
            out.writeShort(40);
            out.write(new byte[] {'m', 'i', 'n'});
        }
        assertThrows(IOException.class, () -> UndoSpool.read(g));
    }

    @Test
    void partStaysSmallEnoughForOneTick() {
        // Ein Teil wird in EINEM Tick gelesen. 200 000 Einträge kosteten 21–43 ms (alter Leser), 50 000 mit dem neuen
        // 2–4 ms (UndoSpoolProbe 2026-10-29). Größere Teile verlängern die Spitze beim Rückgängig.
        assertTrue(UndoSpool.PART <= 50_000, "PART=" + UndoSpool.PART);
    }
}
