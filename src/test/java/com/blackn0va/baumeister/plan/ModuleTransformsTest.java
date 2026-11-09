package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** Gemerkte Bauwerke gespiegelt und doppelt so groß nachbauen. */
class ModuleTransformsTest {

    static BlockSpec b(String id, String... kv) {
        Map<String, String> props = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            props.put(kv[i], kv[i + 1]);
        }
        return new BlockSpec(id, props);
    }

    /** 3 breit, 2 hoch, 1 tief: links Stein, rechts Treppe nach links, Tür in der Mitte. */
    static final ModuleLibrary.Module HOUSE = new ModuleLibrary.Module("haus", "test", new Pos(3, 2, 1), List.of(
            new CompiledBuild.Change(new Pos(0, 0, 0), b("stone")),
            new CompiledBuild.Change(new Pos(2, 0, 0), b("oak_stairs", "facing", "left", "shape", "inner_left")),
            new CompiledBuild.Change(new Pos(1, 0, 0), b("oak_door", "facing", "front", "half", "lower", "hinge",
                    "left")),
            new CompiledBuild.Change(new Pos(1, 1, 0), b("oak_door", "facing", "front", "half", "upper", "hinge",
                    "left")),
            new CompiledBuild.Change(new Pos(0, 1, 0), b("red_bed", "facing", "front", "part", "foot"))));

    static final ModuleLibrary BASE = new ModuleLibrary() {
        @Override
        public Optional<Module> find(String name) {
            return name.equals("haus") ? Optional.of(HOUSE) : Optional.empty();
        }

        @Override
        public List<Module> all() {
            return List.of(HOUSE);
        }
    };

    static BlockSpec at(ModuleLibrary.Module m, int x, int y, int z) {
        return m.cells().stream().filter(c -> c.pos().equals(new Pos(x, y, z))).map(CompiledBuild.Change::block)
                .findFirst().orElse(null);
    }

    /**
     * Gespiegelt werden die Positionen hier, die Zustände erst am Server über BlockData.mirror (Vanilla-Regeln). Der reine
     * Zwilling (Orientation.toWorld) zeigt, was dabei herauskommt – auch die Treppenregel aus StairBlock.mirror, nach der
     * eine Innenecke mit Blick entlang x beim Spiegeln eine Innenecke derselben Hand bleibt. Vorher tauschte ein
     * Wortersatz hier inner_left gegen inner_right.
     */
    @Test
    void mirrorMovesCellsAndLeavesStatesToTheServerMirror() {
        ModuleLibrary.Module m = new ModuleTransforms(BASE).find("haus_gespiegelt").orElseThrow();
        assertEquals("stone", at(m, 2, 0, 0).id(), "links ↔ rechts");
        BlockSpec stairs = at(m, 0, 0, 0);
        assertEquals(new BlockSpec.Pending(true, 0), stairs.pending());
        assertEquals("left", stairs.props().get("facing"), "Zustand selbst unverändert – gespiegelt wird beim Setzen");
        Orientation north = new Orientation(Orientation.Cardinal.NORTH);
        BlockSpec world = north.toWorld(stairs);
        assertEquals("east", world.props().get("facing"));
        assertEquals("inner_left", world.props().get("shape"));
        BlockSpec door = north.toWorld(at(m, 1, 0, 0));
        assertEquals("south", door.props().get("facing"), "vorne bleibt vorne");
        assertEquals("right", door.props().get("hinge"));
        assertEquals(HOUSE.cells().size(), m.cells().size());
    }

    @Test
    void mirroringTwiceIsNoMirrorAndPlaceholdersMirrorWithTheCells() throws Exception {
        BlockSpec cmd = BlockSpec.parseTrusted("command_block[facing=left]{Command:\"setblock {2,0,0} stone\"}");
        ModuleLibrary.Module m = new ModuleLibrary.Module("knopf", "", new Pos(3, 1, 1),
                List.of(new CompiledBuild.Change(new Pos(0, 0, 0), cmd)));
        ModuleLibrary.Module once = ModuleTransforms.mirror(m);
        BlockSpec mirrored = at(once, 2, 0, 0);
        assertEquals("setblock {0,0,0} stone", ((Nbt.Str) mirrored.nbt().get("Command")).value());
        BlockSpec twice = ModuleTransforms.mirror(once).cells().getFirst().block();
        assertEquals(cmd, twice, "zweimal gespiegelt = Original, auch der Befehl");
    }

    /** Plan B6: Verdoppeln ist kein Vervielfältiger – Inhalte und Befehle kommen nie mit, auch nicht bei Doppeltruhen. */
    @Test
    void doublingNeverCopiesContentsOrCommands() throws Exception {
        String items = "{Items:[{Slot:0b,id:\"minecraft:diamond\",count:64}]}";
        ModuleLibrary.Module m = new ModuleLibrary.Module("lager", "", new Pos(2, 2, 1), List.of(
                new CompiledBuild.Change(new Pos(0, 0, 0), BlockSpec.parseTrusted("chest[type=left,facing=front]"
                        + items)),
                new CompiledBuild.Change(new Pos(1, 0, 0), BlockSpec.parseTrusted("chest[type=right,facing=front]"
                        + items)),
                new CompiledBuild.Change(new Pos(0, 1, 0), BlockSpec.parseTrusted(
                        "command_block{Command:\"give @p diamond 64\"}"))));
        ModuleLibrary.Module big = ModuleTransforms.doubled(m).orElseThrow();
        assertEquals(24, big.cells().size());
        assertTrue(big.cells().stream().allMatch(c -> c.block().nbt() == null), "keine Daten im verdoppelten Bau");
    }

    @Test
    void doubledKeepsDoorsWholeAndDropsBeds() {
        ModuleLibrary.Module m = new ModuleTransforms(BASE).find("haus_doppelt").orElseThrow();
        assertEquals(new Pos(6, 4, 2), m.size());
        // Stein 8×, Treppe 8×, Tür 2 (unten + oben direkt darüber), Bett entfällt.
        assertEquals(8 + 8 + 2, m.cells().size());
        assertEquals("lower", at(m, 2, 0, 0).props().get("half"));
        assertEquals("upper", at(m, 2, 1, 0).props().get("half"));
        assertTrue(m.cells().stream().noneMatch(c -> c.block().id().endsWith("_bed")));
        assertEquals("stone", at(m, 1, 1, 1).id());
    }

    @Test
    void bothAtOnceAndUnknownNamesStayUnknown() {
        ModuleTransforms t = new ModuleTransforms(BASE);
        ModuleLibrary.Module m = t.find("haus_gespiegelt_doppelt").orElseThrow();
        assertEquals("stone", at(m, 5, 0, 0).id());
        assertEquals("haus_gespiegelt_doppelt", m.name());
        assertTrue(t.find("schloss_gespiegelt").isEmpty());
        assertTrue(t.find("haus_doppelt_gespiegelt").isEmpty(), "feste Reihenfolge");
        assertEquals(List.of(HOUSE), t.all(), "abgeleitete Namen stehen nicht in der Liste");
    }
}
