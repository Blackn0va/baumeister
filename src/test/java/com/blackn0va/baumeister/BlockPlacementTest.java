package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.BlockSpec;
import com.blackn0va.baumeister.plan.BlockTiers;
import com.blackn0va.baumeister.plan.CompiledBuild;
import com.blackn0va.baumeister.plan.Nbt;
import com.blackn0va.baumeister.plan.PlanCommands;
import com.blackn0va.baumeister.plan.PlanCompiler;
import com.blackn0va.baumeister.plan.PlanParser;
import com.blackn0va.baumeister.plan.Pos;
import com.blackn0va.baumeister.plan.Snbt;
import com.blackn0va.baumeister.plan.Tier;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Umrechnen eines Bauplans für den Bau (Plan B2–B6), soweit ohne Server prüfbar: kein stilles Verwerfen von Zuständen,
 * zurückgehaltene Befehle aus Plantext, Daten-Erwartungen, Direktbau mit Rechteprüfung, Reparatur mit Stufe.
 */
class BlockPlacementTest {

    /** Wie createBlockData für oak_log: kennt nur axis mit x/y/z. */
    private static Map<String, String> logLike(Map<String, String> props) {
        for (Map.Entry<String, String> e : props.entrySet()) {
            if (!e.getKey().equals("axis") || !e.getValue().matches("[xyz]")) {
                return null;
            }
        }
        return props;
    }

    /**
     * Vorher: Ein ungültiger Zusatz (decorate hängt facing an jeden Block) kostete alle Zustände – aus oak_log[axis=x]
     * wurde oak_log[axis=y]. Jetzt fällt nur der ergänzte Zustand weg, ein ausdrücklich genannter nie still.
     */
    @Test
    void addedStatesGoFirstAndExplicitStatesNeverSilently() {
        Map<String, String> props = new java.util.LinkedHashMap<>(Map.of("axis", "x"));
        props.put("facing", "left");
        assertEquals(Map.of("axis", "x"), StateFallback.create(props, Set.of("axis"), () -> Set.of("axis"),
                BlockPlacementTest::logLike));
        assertNull(StateFallback.create(Map.of("axis", "w", "facing", "left"), Set.of("axis"),
                () -> Set.of("axis"), BlockPlacementTest::logLike),
                "falscher ausdrücklicher Zustand → ungültig, nicht nackter Block");
        assertEquals(Map.of(), StateFallback.create(Map.of("facing", "up"), Set.of(), () -> Set.of("facing"),
                p -> p.isEmpty() ? p : null), "ergänzter Zustand mit falschem Wert fällt weg");
    }

    @Test
    void commandsFromPlanTextAreHeldBackButModuleCommandsAreNot() throws Exception {
        assertTrue(PlanCommands.needsConfirmation(BlockSpec.parse("command_block{Command:\"op Gast\",auto:1b}")));
        assertTrue(PlanCommands.needsConfirmation(BlockSpec.parse(
                "oak_sign{front_text:{messages:[{text:\"x\",click_event:{action:\"run_command\",command:\"/op Gast\"}},"
                        + "\"\",\"\",\"\"]}}")));
        assertFalse(PlanCommands.needsConfirmation(BlockSpec.parse("oak_sign{front_text:{messages:[\"Hallo\",\"\","
                + "\"\",\"\"]}}")), "reiner Text braucht keine Bestätigung");
        assertFalse(PlanCommands.needsConfirmation(BlockSpec.parse("command_block[facing=up]")), "leer");
        assertFalse(PlanCommands.needsConfirmation(BlockSpec.parseTrusted("command_block{Command:\"say hi\"}")),
                "aus Bauteil oder Code");
        PlanCompiler c = new PlanCompiler(id -> true, "stone", new PlanCompiler.Limits(6000, 48));
        CompiledBuild b = c.compile(PlanParser.parse("{\"steps\":[{\"op\":\"set\",\"block\":"
                + "\"command_block{Command:\\\"setblock {0,1,0} stone\\\"}\",\"pos\":[0,0,0]},"
                + "{\"op\":\"set\",\"block\":\"stone\",\"pos\":[1,0,0]}]}"));
        List<PlanCommands.Found> found = PlanCommands.find(b);
        assertEquals(1, found.size());
        assertEquals(new Pos(0, 0, 0), found.getFirst().pos());
        assertEquals("setblock {0,1,0} stone", found.getFirst().command());
    }

    @Test
    void expectationsAreReadFromTheRewrittenData() throws Exception {
        Nbt.Compound cmd = BlockSpec.parse("command_block{Command:\"say hi\"}").nbt();
        assertEquals("say hi", BlockDataWriter.Expectation.of("command_block", cmd).command());
        Nbt.Compound sign = BlockSpec.parse("oak_sign{front_text:{messages:[\"a\",\"b\",\"\",\"\"]}}").nbt();
        assertEquals(List.of("a", "b", "", ""), BlockDataWriter.Expectation.of("oak_sign", sign).signLines());
        Nbt.Compound fancy = BlockSpec.parse("oak_sign{front_text:{messages:[{text:\"a\"},\"\",\"\",\"\"]}}").nbt();
        assertNull(BlockDataWriter.Expectation.of("oak_sign", fancy).signLines(), "Komponenten nicht über Zeilen");
        Nbt.Compound chest = BlockSpec.parse("chest{Items:[{Slot:0b,id:\"minecraft:stone\",count:1},{Slot:1b,"
                + "id:\"minecraft:dirt\",count:2}]}").nbt();
        assertEquals(2, BlockDataWriter.Expectation.of("chest", chest).itemStacks());
        assertEquals(-1, BlockDataWriter.Expectation.of("chest", null).itemStacks());
    }

    /** Plan B6: Direktbau (Bauteil, Logik, Veras „bau … hier“) prüft je Block im Namen des Spielers. */
    @Test
    void directBuildsOfPlayersAreCheckedLikeChatOrders() {
        assertTrue(BuildService.directCheck(true, true), "Spieler: je Block wie ein Chat-Auftrag");
        assertFalse(BuildService.directCheck(false, true), "Konsole/RCON ohne Spieler");
        assertFalse(BuildService.directCheck(true, false), "bau.rechte-prüfen aus");
    }

    @Test
    void repairPlacementCarriesTheTierOfTheStoredState() {
        BlockTiers.WorldFacts nether = BlockTiers.WorldFacts.NETHER;
        assertEquals(Tier.DANGEROUS, BuildService.repairPlacement("minecraft:spawner", nether).tier());
        assertEquals(Tier.EVERYONE, BuildService.repairPlacement("minecraft:oak_stairs[facing=north]", nether).tier());
        assertEquals(Tier.DANGEROUS, BuildService.repairPlacement("minecraft:red_bed[part=foot]", nether).tier());
        assertNull(BuildService.repairPlacement("minecraft:chest", nether).nbt(), "nie Inhalte beim Reparieren");
        assertEquals(Tier.COMMANDS, BuildService.repairPlacement("minecraft:no such[", nether).tier());
    }

    @Test
    void movingAPlanMovesThePlaceholdersInItsCommands() throws Exception {
        String plan = "{\"steps\":[{\"op\":\"set\",\"block\":\"command_block{Command:\\\"setblock {2,0,0} stone\\\"}\","
                + "\"pos\":[0,0,0]},{\"op\":\"set\",\"block\":\"stone\",\"pos\":[2,0,0]}]}";
        var out = JsonParser.parseString(BuildService.translate(plan, -3, 5)).getAsJsonObject();
        var first = out.getAsJsonArray("steps").get(0).getAsJsonObject();
        BlockSpec moved = BlockSpec.parse(first.get("block").getAsString());
        assertEquals("setblock {-1,0,5} stone", ((Nbt.Str) moved.nbt().get("Command")).value());
        assertEquals("[-3,0,5]", first.get("pos").toString());
        assertEquals("{Command:\"setblock {-1,0,5} stone\"}", Snbt.write(moved.nbt()));
    }

    /**
     * Was BuildJob für eine Zelle bekommt: Stufe, ausdrücklich genannte Zustände in Welt-Namen (die Zaunverbindung
     * north im Nordrahmen ist bei Blick nach Osten der Weltschlüssel east) und die neu geschriebenen Daten.
     */
    @Test
    void placementCarriesTierWorldExplicitKeysAndRewrittenData() throws Exception {
        com.blackn0va.baumeister.plan.Orientation east = new com.blackn0va.baumeister.plan.Orientation(
                com.blackn0va.baumeister.plan.Orientation.Cardinal.EAST);
        BlockSpec sign = BlockSpec.parse("oak_sign[rotation=0]{ front_text : { messages : ['Hallo','','',''] } }");
        BuildJob.Placement p = BuildService.placement(sign, 0, BlockTiers.WorldFacts.OVERWORLD, east, q -> null);
        assertEquals(Tier.EVERYONE, p.tier());
        assertEquals("{front_text:{messages:[\"Hallo\",\"\",\"\",\"\"]}}", p.nbt(), "neu geschrieben, nicht roh");
        assertEquals(List.of("Hallo", "", "", ""), p.expect().signLines());
        BuildJob.Placement fence = BuildService.placement(BlockSpec.parse("oak_fence[north=true]"), 3,
                BlockTiers.WorldFacts.OVERWORLD, east, q -> null);
        assertEquals(Set.of("east"), fence.explicit());
        assertEquals(3, fence.group());
        assertNull(fence.nbt());
        assertEquals(Tier.DANGEROUS, BuildService.placement(BlockSpec.parse("lava"), 0,
                BlockTiers.WorldFacts.OVERWORLD, east, q -> null).tier());
    }

    @Test
    void dropGuardBoxCoversTheSiteWithItsEdge() {
        java.util.UUID w = java.util.UUID.randomUUID();
        BuildDropGuard.Box box = new BuildDropGuard.Box(w, -1, 63, -1, 5, 70, 5);
        assertTrue(box.contains(w, 0, 64, 0));
        assertTrue(box.contains(w, 5, 70, -1));
        assertFalse(box.contains(w, 6, 64, 0));
        assertFalse(box.contains(java.util.UUID.randomUUID(), 0, 64, 0), "andere Welt");
    }

    /**
     * Sicherheitsprüfung 2026-11-08, Befund 8: Nur was neben einer gerade gesetzten Zelle fällt, wird unterdrückt –
     * nicht mehr alles im ganzen Kasten für die ganze Bauzeit (fremde Farmen, Abbau anderer Spieler).
     */
    @Test
    void dropGuardOnlyCoversCellsTheBuildJustTouched() {
        java.util.UUID w = java.util.UUID.randomUUID();
        long[] now = {1_000};
        BuildDropGuard guard = new BuildDropGuard(() -> now[0]);
        BuildDropGuard.Box box = new BuildDropGuard.Box(w, -50, 0, -50, 50, 100, 50);
        guard.open(box);
        assertFalse(guard.covers(w, 10, 64, 10), "im Kasten, aber nichts gesetzt (fremde Farm)");
        guard.touched(w, 0, 64, 0);
        assertTrue(guard.covers(w, 0, 64, 0));
        assertTrue(guard.covers(w, 1, 65, -1), "Nachbar (Fackel am gesetzten Block)");
        assertFalse(guard.covers(w, 2, 64, 0), "zwei Blöcke weiter");
        assertFalse(guard.covers(java.util.UUID.randomUUID(), 0, 64, 0), "andere Welt");
        now[0] += BuildDropGuard.WINDOW_MS + 1;
        assertFalse(guard.covers(w, 0, 64, 0), "nach dem Zeitfenster");
        guard.touched(w, 60, 64, 60);
        assertFalse(guard.covers(w, 60, 64, 60), "außerhalb des Kastens nie");
        guard.close(box);
        guard.touched(w, 0, 64, 0);
        assertFalse(guard.covers(w, 0, 64, 0), "ohne laufende Baustelle nie");
    }
}
