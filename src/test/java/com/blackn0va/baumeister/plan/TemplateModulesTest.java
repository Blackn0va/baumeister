package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.Orientation.Cardinal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class TemplateModulesTest {

    /** Kasten aus Brettern; Gold markiert die Seite der Straße (Vorderseite), dort sitzt auch der Verbund-Block. */
    static List<TemplateModules.RawCell> house(int w, int h, int d, String jigsaw, int jx, int jy, int jz) {
        List<TemplateModules.RawCell> out = new ArrayList<>();
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                for (int z = 0; z < d; z++) {
                    out.add(new TemplateModules.RawCell(x, y, z, "minecraft:oak_planks"));
                }
            }
        }
        if (jigsaw != null) {
            out.removeIf(c -> c.x() == jx && c.y() == jy && c.z() == jz);
            out.add(new TemplateModules.RawCell(jx, jy, jz, "minecraft:jigsaw[orientation=" + jigsaw + "]"));
            out.removeIf(c -> c.x() == jx && c.y() == jy + 1 && c.z() == jz);
            out.add(new TemplateModules.RawCell(jx, jy + 1, jz, "minecraft:gold_block"));
        }
        return out;
    }

    private static Pos gold(ModuleLibrary.Module m) {
        return m.cells().stream().filter(c -> c.block().id().equals("gold_block")).findFirst().orElseThrow().pos();
    }

    @Test
    void streetJigsawMarksFrontAndGround() {
        // Wie plains_small_house_1 (gemessen): jigsaw west_up am Rand x = 0 → Vorderseite West.
        TemplateModules.Template t = TemplateModules.build("h", "", house(7, 6, 5, "west_up", 0, 0, 2));
        assertEquals(Cardinal.WEST, t.front());
        assertEquals(0, t.ground());
        assertEquals(0, gold(t.module()).z(), "Vorderseite muss bei z = 0 liegen");
        // Nach der Drehung liegt die Tiefe 7 (Weltachse x) entlang z.
        assertEquals(new Pos(5, 6, 7), t.module().size());
    }

    @Test
    void jigsawBecomesNeighbourBlockNotHole() {
        TemplateModules.Template t = TemplateModules.build("h", "", house(5, 3, 5, "west_up", 0, 0, 2));
        assertTrue(t.module().cells().stream().noneMatch(c -> c.block().id().contains("jigsaw")));
        assertEquals(5 * 3 * 5, t.module().cells().size(), "Verbund-Block ersetzt, nicht weggelassen");
    }

    @Test
    void groundLayerFromJigsawHeight() {
        // Wie desert_small_house_1: Anschluss in Schicht 1, darunter Fundament.
        List<TemplateModules.RawCell> raw = house(5, 4, 5, "south_up", 2, 1, 4);
        raw.add(new TemplateModules.RawCell(9, 0, 9, "minecraft:air"));
        raw.add(new TemplateModules.RawCell(9, 3, 9, "minecraft:air"));
        TemplateModules.Template t = TemplateModules.build("h", "", raw);
        assertEquals(1, t.ground());
        assertEquals(Cardinal.SOUTH, t.front());
        long air = t.module().cells().stream().filter(c -> c.block().isAir()).count();
        assertEquals(1, air, "Luft unter/auf der Bodenschicht grübe Löcher in die Wiese");
    }

    @Test
    void doorGivesFrontWithoutJigsaw() {
        List<TemplateModules.RawCell> raw = house(5, 4, 5, null, 0, 0, 0);
        raw.removeIf(c -> c.x() == 0 && c.y() == 1 && c.z() == 2);
        raw.add(new TemplateModules.RawCell(0, 1, 2, "minecraft:oak_door[facing=east,half=lower,hinge=left,"
                + "open=false,powered=false]"));
        TemplateModules.Template t = TemplateModules.build("h", "", raw);
        assertEquals(Cardinal.WEST, t.front(), "Tür facing=east zeigt ins Haus (gemessen) → Vorderseite West");
        assertEquals(0, t.ground());
    }

    @Test
    void structureVoidIsSkipped() {
        List<TemplateModules.RawCell> raw = house(3, 3, 3, null, 0, 0, 0);
        raw.add(new TemplateModules.RawCell(5, 1, 5, "minecraft:structure_void"));
        assertFalse(TemplateModules.build("h", "", raw).module().cells().stream()
                .anyMatch(c -> c.block().id().contains("structure")));
    }

    @Test
    void facingLeftPutsFrontAtStreetSide() throws PlanException {
        // Der Dorfplan stellt Häuser rechts der Straße mit facing left auf: Ihre Vorderseite muss bei der kleinsten x
        // liegen, also zur Straße. Prüft die Annahme über PlanCompiler.placeModule.
        ModuleLibrary.Module m = TemplateModules.build("h", "", house(7, 6, 5, "west_up", 0, 0, 2)).module();
        ModuleLibrary lib = new ModuleLibrary() {
            @Override
            public Optional<Module> find(String name) {
                return Optional.of(m);
            }

            @Override
            public List<Module> all() {
                return List.of();
            }
        };
        PlanCompiler compiler = new PlanCompiler(
                id -> org.bukkit.Material.getMaterial(id.toUpperCase(Locale.ROOT)) != null, "stone",
                new PlanCompiler.Limits(150_000, 192), lib);
        CompiledBuild b = compiler.compile(PlanParser.parse(
                "{\"steps\":[{\"op\":\"module\",\"name\":\"h\",\"pos\":[3,0,1],\"facing\":\"left\"}]}"));
        int minX = Integer.MAX_VALUE;
        int goldX = 0;
        for (CompiledBuild.Action a : b.actions()) {
            for (CompiledBuild.Change c : a.changes()) {
                minX = Math.min(minX, c.pos().x());
                if (c.block().id().equals("gold_block")) {
                    goldX = c.pos().x();
                }
            }
        }
        assertEquals(3, minX);
        assertEquals(3, goldX, "Vorderseite zur Straße (kleinstes x)");
    }
}
