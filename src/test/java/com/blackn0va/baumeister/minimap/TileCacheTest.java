package com.blackn0va.baumeister.minimap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TileCacheTest {

    private static MinimapImage.Tile tile() {
        return new MinimapImage.Tile(new byte[256], new short[256]);
    }

    private static long key(int x, int z) {
        return MinimapImage.chunkKey(x, z);
    }

    @Test
    void readsOnlyWhatIsMissingStaleOrChanged() {
        TileCache c = new TileCache(10);
        assertTrue(c.needsRead("w", key(0, 0), 1000, 15_000), "fehlt");
        c.put("w", key(0, 0), tile(), 1000);
        assertFalse(c.needsRead("w", key(0, 0), 2000, 15_000), "frisch");
        assertTrue(c.needsRead("w", key(0, 0), 16_000, 15_000), "veraltet");
        c.markDirty("w", 0, 0);
        assertTrue(c.needsRead("w", key(0, 0), 2000, 15_000), "geändert");
        c.put("w", key(0, 0), tile(), 2000);
        assertFalse(c.needsRead("w", key(0, 0), 3000, 15_000), "neu gelesen = sauber");
    }

    @Test
    void changesOutsideTheCacheCreateNothing() {
        TileCache c = new TileCache(10);
        c.markDirty("w", 5, 5);
        assertEquals(0, c.size());
        assertNull(c.get("w", 5, 5));
    }

    @Test
    void evictsTheLeastRecentlyUsedTileAtTheLimit() {
        TileCache c = new TileCache(3);
        c.put("w", key(1, 0), tile(), 0);
        c.put("w", key(2, 0), tile(), 0);
        c.put("w", key(3, 0), tile(), 0);
        assertNotNull(c.get("w", 1, 0));  // 1 frisch benutzt → 2 ist am ältesten
        c.put("w", key(4, 0), tile(), 0);
        assertEquals(3, c.size());
        assertNull(c.get("w", 2, 0));
        assertNotNull(c.get("w", 1, 0));
        assertNotNull(c.get("w", 4, 0));
    }

    @Test
    void evictionHitsTheBiggestWorldFirst() {
        TileCache c = new TileCache(4);
        c.put("klein", key(0, 0), tile(), 0);
        c.put("gross", key(0, 0), tile(), 0);
        c.put("gross", key(1, 0), tile(), 0);
        c.put("gross", key(2, 0), tile(), 0);
        c.put("gross", key(3, 0), tile(), 0);
        assertEquals(4, c.size());
        assertNotNull(c.get("klein", 0, 0), "die Welt mit einer Kachel bleibt");
        assertNull(c.get("gross", 0, 0));
    }

    @Test
    void evictionClearsOnlyTheDirtyMarkOfItsOwnWorld() {
        TileCache c = new TileCache(3);
        c.put("a", key(0, 0), tile(), 0);
        c.put("b", key(0, 0), tile(), 0);
        c.put("b", key(1, 0), tile(), 0);
        c.markDirty("a", 0, 0);
        c.put("b", key(2, 0), tile(), 0);  // verdrängt b/0,0 – die Marke von a/0,0 muss bleiben
        assertTrue(c.needsRead("a", key(0, 0), 1, 15_000));
    }

    @Test
    void forgettingAWorldFreesItsTiles() {
        TileCache c = new TileCache(10);
        c.put("w", key(0, 0), tile(), 0);
        c.put("v", key(0, 0), tile(), 0);
        c.forgetWorld("w");
        assertEquals(1, c.size());
        assertNull(c.get("w", 0, 0));
    }
}
