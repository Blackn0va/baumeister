package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;

/** Aussehen der Tunnel (Wunsch 2026-11-16: schöne Lichter statt Fackeln) und Material aus der Kiste. */
class TunnelStyleTest {

    /** Block-IDs einer Fassung aus dem Datengenerator (src/test/resources/blockstates). */
    private static Set<String> ids(String version) throws IOException {
        Set<String> out = new HashSet<>();
        try (InputStream in = TunnelStyleTest.class.getResourceAsStream("/blockstates/" + version + ".txt.gz");
                BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(in),
                        StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null;) {
                String id = line.startsWith("minecraft:") ? line.substring("minecraft:".length()) : line;
                int cut = id.indexOf('[');
                out.add((cut >= 0 ? id.substring(0, cut) : id).trim());
            }
        }
        return out;
    }

    @Test
    void everyPaletteBlockExistsInBothServerVersions() throws IOException {
        Set<String> old = ids("1.21.11");
        Set<String> now = ids("26.2");
        for (TunnelStyle.Realm realm : TunnelStyle.Realm.values()) {
            for (int y : new int[] {-40, 64}) {
                for (TunnelOrder.Size size : TunnelOrder.Size.values()) {
                    for (String id : TunnelStyle.forPlace(realm, y, null, size).ids()) {
                        assertTrue(old.contains(id) && now.contains(id), realm + " y=" + y + ": " + id);
                    }
                }
            }
        }
        for (String id : TunnelStyle.HANGING_LIGHTS) {
            assertTrue(old.contains(id) && now.contains(id), id);
        }
        for (String id : TunnelStyle.BLOCK_LIGHTS) {
            assertTrue(old.contains(id) && now.contains(id), id);
        }
        for (String id : TunnelStyle.CHAINS) {
            assertTrue(old.contains(id) && now.contains(id), id);
        }
    }

    @Test
    void thePlaceDecidesTheLookAndNeverTorches() {
        assertEquals("stone_bricks", TunnelStyle.forPlace(TunnelStyle.Realm.OVERWORLD, 30, null,
                TunnelOrder.Size.NORMAL).wall().id());
        assertEquals("deepslate_bricks", TunnelStyle.forPlace(TunnelStyle.Realm.OVERWORLD, -30, null,
                TunnelOrder.Size.NORMAL).wall().id());
        assertEquals("soul_lantern", TunnelStyle.forPlace(TunnelStyle.Realm.NETHER, 40, null,
                TunnelOrder.Size.NORMAL).light().id());
        assertNull(TunnelStyle.forPlace(TunnelStyle.Realm.OVERWORLD, 30, null, TunnelOrder.Size.NORMAL).chain());
        assertEquals("iron_chain", TunnelStyle.forPlace(TunnelStyle.Realm.OVERWORLD, 30, null,
                TunnelOrder.Size.LARGE).chain().id());
        TunnelStyle wish = TunnelStyle.forPlace(TunnelStyle.Realm.OVERWORLD, 30, "deepslate_bricks",
                TunnelOrder.Size.NORMAL);
        assertEquals("deepslate_bricks", wish.wall().id());
        assertEquals("deepslate_brick_stairs", wish.arch().id());
        for (TunnelStyle.Realm realm : TunnelStyle.Realm.values()) {
            for (String id : TunnelStyle.forPlace(realm, 10, null, TunnelOrder.Size.LARGE).ids()) {
                assertFalse(id.contains("torch"), realm + ": " + id);
            }
        }
    }

    @Test
    void wallBlocksAreFullStonesAndNothingElse() throws IOException {
        Set<String> accepted = new TreeSet<>();
        for (String id : ids("26.2")) {
            if (TunnelStyle.isWallBlock(id)) {
                accepted.add(id);
            }
        }
        for (String id : accepted) {
            for (String bad : List.of("_stairs", "_slab", "_wall", "_fence", "_door", "_ore", "torch", "lantern",
                    "_button", "_pressure_plate", "_pane", "_carpet", "_sign", "infested", "bookshelf", "_bars")) {
                assertFalse(id.contains(bad), id);
            }
        }
        for (String good : List.of("stone_bricks", "cobblestone", "oak_planks", "deepslate_tiles", "polished_andesite",
                "smooth_stone", "chiseled_stone_bricks", "bricks", "white_concrete", "quartz_block", "tuff_bricks",
                "cobbled_deepslate", "stripped_spruce_log", "mud_bricks", "end_stone_bricks")) {
            assertTrue(accepted.contains(good), good);
        }
        for (String bad : List.of("stone_brick_stairs", "smooth_stone_slab", "torch", "lantern", "diamond_ore",
                "gravel", "sand", "dirt", "glass", "chiseled_bookshelf", "infested_stone", "oak_fence", "iron_bars",
                "sea_lantern", "glowstone", "copper_bulb", "tnt", "chest", "melon_stem", "attached_pumpkin_stem",
                "big_dripleaf_stem")) {
            assertFalse(accepted.contains(bad), bad);
        }
        assertTrue(accepted.size() > 150 && accepted.size() < 400, "angenommen: " + accepted.size());
    }

    @Test
    void theChestDecidesTheLookForEveryoneElse() {
        TunnelStyle.Choice c = TunnelStyle.fromChest(Map.of("stone_bricks", 200, "lantern", 10, "cobblestone", 50),
                TunnelOrder.Kind.TUNNEL, TunnelOrder.Size.NORMAL, null);
        assertNull(c.missing());
        assertEquals("stone_bricks", c.style().wall().id(), "schöner Stein vor Bruchstein");
        assertEquals("stone_bricks", c.style().floor().id(), "zweiter Stein erst ab 16 schön");
        assertEquals("lantern", c.style().light().id());
        assertNull(c.style().arch(), "ohne Treppen in der Kiste kein Bogen");

        TunnelStyle.Choice arch = TunnelStyle.fromChest(Map.of("stone_bricks", 100, "stone_brick_stairs", 20,
                "polished_andesite", 64, "lantern", 4, "stripped_oak_log", 12), TunnelOrder.Kind.TUNNEL,
                TunnelOrder.Size.NORMAL, null);
        assertEquals("stone_brick_stairs", arch.style().arch().id());
        assertEquals("polished_andesite", arch.style().floor().id());
        assertEquals("stripped_oak_log", arch.style().rib().id());

        assertEquals("cobblestone", TunnelStyle.fromChest(Map.of("cobblestone", 64, "soul_lantern", 3),
                TunnelOrder.Kind.TUNNEL, TunnelOrder.Size.NORMAL, null).style().wall().id(), "roh, wenn sonst nichts");
        TunnelStyle sea = TunnelStyle.fromChest(Map.of("stone_bricks", 64, "sea_lantern", 8),
                TunnelOrder.Kind.TUNNEL, TunnelOrder.Size.NORMAL, null).style();
        assertFalse(sea.hangingLight());
        assertEquals("deepslate_bricks", TunnelStyle.fromChest(Map.of("stone_bricks", 100, "deepslate_bricks", 10,
                "lantern", 2), TunnelOrder.Kind.TUNNEL, TunnelOrder.Size.NORMAL, "deepslate_bricks").style().wall()
                .id(), "„aus Tiefenschiefer“ zählt, wenn er in der Kiste liegt");
        assertNotNull(TunnelStyle.fromChest(Map.of("stone_bricks", 100, "lantern", 2, "iron_chain", 3),
                TunnelOrder.Kind.TUNNEL, TunnelOrder.Size.LARGE, null).style().chain());
    }

    @Test
    void whatIsMissingIsSaidAndTorchesNeverCount() {
        String noLight = TunnelStyle.fromChest(Map.of("stone_bricks", 100, "torch", 64), TunnelOrder.Kind.TUNNEL,
                TunnelOrder.Size.NORMAL, null).missing();
        assertNotNull(noLight);
        assertTrue(noLight.contains("Fackeln nehme ich nicht"), noLight);
        assertTrue(TunnelStyle.fromChest(Map.of("lantern", 5), TunnelOrder.Kind.TUNNEL, TunnelOrder.Size.NORMAL,
                null).missing().contains("Bausteine"));
        String noSteps = TunnelStyle.fromChest(Map.of("stone_bricks", 100, "lantern", 5), TunnelOrder.Kind.STAIRS_UP,
                TunnelOrder.Size.NORMAL, null).missing();
        assertTrue(noSteps.contains("Treppenstufen"), noSteps);
        TunnelStyle steps = TunnelStyle.fromChest(Map.of("stone_bricks", 100, "lantern", 5, "cobblestone_stairs", 40),
                TunnelOrder.Kind.STAIRS_UP, TunnelOrder.Size.NORMAL, null).style();
        assertEquals("cobblestone_stairs", steps.step().id(), "irgendeine Treppe, wenn die passende fehlt");
    }
}
