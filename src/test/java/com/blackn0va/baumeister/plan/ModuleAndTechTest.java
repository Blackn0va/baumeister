package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.CompiledBuild.Action;
import com.blackn0va.baumeister.plan.CompiledBuild.Change;
import com.blackn0va.baumeister.plan.Orientation.Cardinal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class ModuleAndTechTest {

    /** Bauteil 2 breit, 1 tief: links Kolben nach vorn, rechts Hebel an der Wand. */
    private static final ModuleLibrary LIB = new ModuleLibrary() {
        final Module door = new Module("tuer", "Testteil", new Pos(2, 1, 1), List.of(
                new Change(new Pos(0, 0, 0), BlockSpec.of("piston").with("facing", "front")),
                new Change(new Pos(1, 0, 0), BlockSpec.of("lever").with("face", "wall").with("facing", "right"))));

        @Override
        public Optional<Module> find(String name) {
            return name.equals("tuer") ? Optional.of(door) : Optional.empty();
        }

        @Override
        public List<Module> all() {
            return List.of(door);
        }
    };

    private static final BlockCatalog ALL = id -> true;

    private static Map<Pos, BlockSpec> build(String steps) throws Exception {
        PlanCompiler c = new PlanCompiler(ALL, "stone", new PlanCompiler.Limits(6000, 48), LIB);
        Map<Pos, BlockSpec> m = new HashMap<>();
        for (Action a : c.compile(PlanParser.parse("{\"steps\":[" + steps + "]}")).actions()) {
            for (Change ch : a.changes()) {
                m.put(ch.pos(), ch.block());
            }
        }
        return m;
    }

    @Test
    void moduleIsPlacedAtPosUnrotated() throws Exception {
        Map<Pos, BlockSpec> m = build("{\"op\":\"module\",\"name\":\"tuer\",\"pos\":[5,1,2]}");
        assertEquals("front", m.get(new Pos(5, 1, 2)).props().get("facing"));
        assertEquals("lever", m.get(new Pos(6, 1, 2)).id());
    }

    @Test
    void moduleTurnedLeftRotatesPositionsAndFacings() throws Exception {
        Map<Pos, BlockSpec> m = build("{\"op\":\"module\",\"name\":\"tuer\",\"pos\":[0,0,0],\"facing\":\"left\"}");
        // Eine Vierteldrehung: vorne→links, rechts→vorne; aus 2×1 wird 1×2, wieder ab (0,0,0).
        assertEquals(2, m.size());
        BlockSpec piston = m.values().stream().filter(b -> b.id().equals("piston")).findFirst().orElseThrow();
        BlockSpec lever = m.values().stream().filter(b -> b.id().equals("lever")).findFirst().orElseThrow();
        assertEquals("left", piston.props().get("facing"));
        assertEquals("front", lever.props().get("facing"));
        assertTrue(m.keySet().stream().allMatch(p -> p.x() == 0 && p.z() >= 0 && p.z() <= 1));
    }

    @Test
    void unknownModuleIsSkippedWithWarning() throws Exception {
        PlanCompiler c = new PlanCompiler(ALL, "stone", new PlanCompiler.Limits(6000, 48), LIB);
        CompiledBuild b = c.compile(PlanParser.parse("{\"steps\":[{\"op\":\"set\",\"block\":\"stone\","
                + "\"pos\":[0,0,0]},{\"op\":\"module\",\"name\":\"gibtsnicht\",\"pos\":[0,0,0]}]}"));
        assertEquals(1, b.warnings().size());
    }

    @Test
    void facingUpAndDownPassThroughRotation() throws Exception {
        Map<Pos, BlockSpec> m = build("{\"op\":\"set\",\"block\":\"piston\",\"pos\":[0,0,0],\"facing\":\"up\"}");
        assertEquals("up", m.get(new Pos(0, 0, 0)).props().get("facing"));
        Orientation o = new Orientation(Cardinal.EAST);
        assertEquals("down", o.toWorld(BlockSpec.of("hopper").with("facing", "down")).props().get("facing"));
    }

    @Test
    void attachedBlocksComeAfterTheirSupportInTheSameStep() throws Exception {
        PlanCompiler c = new PlanCompiler(ALL, "stone", new PlanCompiler.Limits(6000, 48), LIB);
        // Schritt 0: Block 3×3×2; Schritt 1 ersetzt eine Stelle auf y=1 durch eine Fackel. Die Fackel
        // zählt damit zu Schritt 0 (erster Schritt an der Stelle). Nach reiner Schichtfolge käme sie
        // vor den Blöcken auf y=2 – mit der Regel erst, wenn alles Feste der Gruppe steht.
        CompiledBuild b = c.compile(PlanParser.parse("{\"steps\":["
                + "{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,-1],\"to\":[2,2,0]},"
                + "{\"op\":\"set\",\"block\":\"wall_torch[facing=front]\",\"pos\":[1,1,-1]}]}"));
        List<Action> as = b.actions();
        assertEquals("wall_torch", as.getLast().changes().getFirst().block().id(), "Fackel nach der Wand");
        assertTrue(PlanCompiler.needsSupport("redstone_wall_torch"));
        assertTrue(PlanCompiler.needsSupport("stone_button"));
        assertTrue(!PlanCompiler.needsSupport("iron_trapdoor"));
    }

    @Test
    void worldToLocalIsInverseOfLocalToWorld() throws Exception {
        for (Cardinal f : Cardinal.values()) {
            Orientation o = new Orientation(f);
            Pos local = new Pos(3, 2, -5);
            assertEquals(local, o.toLocalOffset(o.toWorldOffset(local)), f.name());
            BlockSpec spec = BlockSpec.parse("repeater[facing=left,delay=2]");
            assertEquals(spec, o.toLocal(o.toWorld(spec)), f.name());
            BlockSpec rail = BlockSpec.parse("rail[shape=front_right]");
            String world = o.toWorld(rail).props().get("shape");
            assertTrue(world.matches("(north|south)_(east|west)"), "kanonisch: " + world);
        }
    }

    /**
     * Seit 2026-11-08 (Plan B3) behält toLocal Himmelsrichtungs-Schlüssel und dreht sie in den Nordrahmen – vorher fielen
     * sie weg, ein gespeichertes Bauteil kam mit neu gerechneten statt den gespeicherten Verbindungen zurück. Blickt
     * der Spieler nach Osten, ist Weltosten sein „hinten“, im Nordrahmen also north.
     */
    @Test
    void localConversionKeepsCompassKeyedConnectionsInTheNorthFrame() throws Exception {
        Orientation east = new Orientation(Cardinal.EAST);
        BlockSpec world = BlockSpec.parse("redstone_wire[north=side,east=up,south=none,west=none,power=3]");
        BlockSpec local = east.toLocal(world);
        assertEquals(Map.of("west", "side", "north", "up", "east", "none", "south", "none", "power", "3"),
                local.props());
        assertEquals(world, east.toWorld(local), "hin und zurück");
    }
}
