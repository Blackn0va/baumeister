package com.blackn0va.baumeister.minimap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

class OverviewStoreTest {

    private static int[] chunk(int base, int height) {
        int[] s = new int[OverviewStore.PER_CHUNK];
        for (int i = 0; i < s.length; i++) {
            s[i] = OverviewStore.pack(base, height + i);
        }
        return s;
    }

    @Test
    void packKeepsColourHeightAndRoadFlagIncludingNegativeHeights() {
        int v = OverviewStore.pack(12 | MinimapImage.ROAD, -60);
        assertTrue(OverviewStore.present(v));
        assertEquals(12 | MinimapImage.ROAD, OverviewStore.base(v));
        assertEquals(-60, OverviewStore.height(v));
        assertEquals(2000, OverviewStore.height(OverviewStore.pack(1, 2000)), "Erdwelt reicht hoch");
        assertFalse(OverviewStore.present(0));
    }

    @Test
    void samplesComeFromTheirFourByFourField() {
        OverviewStore s = new OverviewStore(10, x -> { });
        s.putChunk("w", -1, 3, chunk(5, 70));
        // Chunk −1/3: Blöcke x −16..−1, z 48..63; Feld (sx 1, sz 2) = Stichprobe 9 → Höhe 70 + 9.
        int v = s.sample("w", -16 + 5, 48 + 9);
        assertEquals(79, OverviewStore.height(v));
        assertEquals(0, s.sample("w", 0, 0), "anderer Chunk unbekannt");
        assertEquals(0, s.sample("v", -11, 57), "andere Welt");
    }

    @Test
    void evictionHandsDirtyRegionsBackForSaving() {
        List<OverviewStore.Saved> saved = new ArrayList<>();
        OverviewStore s = new OverviewStore(2, saved::add);
        s.putChunk("w", 0, 0, chunk(1, 64));      // Region 0/0
        s.putChunk("w", 32, 0, chunk(1, 64));     // Region 1/0
        s.putChunk("w", 64, 0, chunk(1, 64));     // Region 2/0 → Region 0/0 fliegt, ist geändert
        assertEquals(2, s.regions());
        assertEquals(1, saved.size());
        assertEquals(OverviewStore.regionKey(0, 0), saved.getFirst().regionKey());
        assertTrue(s.takeDirty().size() == 2, "die beiden übrigen sind noch zu speichern");
        assertTrue(s.takeDirty().isEmpty(), "danach gilt alles als gespeichert");
    }

    @Test
    void diskRoundTripAndForeignFilesAreRejected() throws IOException {
        int[] data = new int[OverviewStore.REGION * OverviewStore.REGION * OverviewStore.PER_CHUNK];
        data[0] = OverviewStore.pack(3, 64);
        data[data.length - 1] = OverviewStore.pack(7 | MinimapImage.ROAD, -10);
        byte[] file = OverviewStore.encode(data);
        assertTrue(file.length < 2000, "gzip: fast leer ist fast nichts (" + file.length + " Byte)");
        assertArrayEquals(data, OverviewStore.decode(file));
        assertThrows(IOException.class, () -> OverviewStore.decode(new byte[] {1, 2, 3}));
        assertThrows(IOException.class, () -> OverviewStore.decode(OverviewStore.encode(new int[5])));
    }

    @Test
    void theDiskOnlyFillsGapsAndNeverOverwritesNewerSamples() {
        OverviewStore s = new OverviewStore(10, x -> { });
        s.putChunk("w", 0, 0, chunk(9, 100));     // seit dem Start gelesen: neu
        int[] disk = new int[OverviewStore.REGION * OverviewStore.REGION * OverviewStore.PER_CHUNK];
        disk[0] = OverviewStore.pack(2, 50);      // alter Stand desselben Felds
        disk[OverviewStore.PER_CHUNK] = OverviewStore.pack(4, 60);  // Chunk 1/0: nur auf der Platte
        s.putRegion("w", OverviewStore.regionKey(0, 0), disk);
        assertEquals(9, OverviewStore.base(s.sample("w", 2, 2)));
        assertEquals(4, OverviewStore.base(s.sample("w", 18, 2)));
    }

    @Test
    void imageFallsBackToTheOverviewWhereNoTileIsLoaded() {
        OverviewStore s = new OverviewStore(10, x -> { });
        s.putChunk("w", 6, 6, chunk(11, 64));
        byte[] img = MinimapImage.compose((cx, cz) -> null, (x, z) -> s.sample("w", x, z),
                MinimapImage.View.northUp(104, 104, 1), (byte) 0, new HashSet<>());
        assertEquals(11, (img[64 * MinimapImage.SIZE + 64] & 0xFF) / 4, "Mitte 104/104 liegt in Chunk 6/6");
        assertEquals(0, img[0], "Ecke außerhalb: unbekannt");
    }
}
