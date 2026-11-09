package com.blackn0va.baumeister.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.BlockSpec;
import com.blackn0va.baumeister.plan.CompiledBuild;
import com.blackn0va.baumeister.plan.Orientation;
import com.blackn0va.baumeister.plan.Orientation.Cardinal;
import com.blackn0va.baumeister.plan.PlanCompiler;
import com.blackn0va.baumeister.plan.PlanParser;
import com.blackn0va.baumeister.plan.Pos;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Rundweg mit eigenen Plänen: Plan → Compiler → Blöcke (in Weltachsen gedreht, als Datei geschrieben und gelesen) →
 * Rückübersetzer → Compiler → derselbe Bau. Und die Gegenprobe, dass verify einen falschen Plan auch erkennt.
 */
class PlanDecompilerTest {

    private static final PlanCompiler COMPILER = new PlanCompiler(
            id -> org.bukkit.Material.getMaterial(id.toUpperCase(Locale.ROOT)) != null, "stone",
            new PlanCompiler.Limits(150_000, 192));

    /** Das 7×7-Haus aus dem System-Prompt plus Turm-Zylinder: Wände, Stämme, Tür, Fenster, Dach, Laterne, Rohr. */
    static final String HOUSE = "{\"design\":\"x\",\"reply\":\"x\",\"steps\":["
            + "{\"op\":\"fill\",\"block\":\"cobblestone\",\"from\":[0,0,0],\"to\":[6,0,6]},"
            + "{\"op\":\"walls\",\"block\":\"oak_planks\",\"from\":[0,1,0],\"to\":[6,3,6]},"
            + "{\"op\":\"fill\",\"block\":\"oak_log\",\"from\":[0,1,0],\"to\":[0,3,0]},"
            + "{\"op\":\"fill\",\"block\":\"oak_log\",\"from\":[6,1,0],\"to\":[6,3,0]},"
            + "{\"op\":\"fill\",\"block\":\"oak_log\",\"from\":[0,1,6],\"to\":[0,3,6]},"
            + "{\"op\":\"fill\",\"block\":\"oak_log\",\"from\":[6,1,6],\"to\":[6,3,6]},"
            + "{\"op\":\"door\",\"block\":\"oak_door\",\"pos\":[3,1,0],\"facing\":\"front\"},"
            + "{\"op\":\"set\",\"block\":\"glass_pane\",\"pos\":[1,2,0]},"
            + "{\"op\":\"set\",\"block\":\"glass_pane\",\"pos\":[5,2,0]},"
            + "{\"op\":\"roof\",\"block\":\"oak_stairs\",\"from\":[0,4,0],\"to\":[6,4,6],\"shape\":\"gable\","
            + "\"overhang\":1,\"gable_block\":\"oak_planks\"},"
            + "{\"op\":\"set\",\"block\":\"lantern\",\"pos\":[3,1,4]},"
            + "{\"op\":\"cylinder\",\"block\":\"stone_bricks\",\"pos\":[14,0,4],\"radius\":4,\"height\":10,"
            + "\"hollow\":true}]}";

    static Map<Pos, BlockSpec> build(String json) throws Exception {
        CompiledBuild b = COMPILER.compile(PlanParser.parse(json));
        Map<Pos, BlockSpec> cells = new HashMap<>();
        for (CompiledBuild.Action a : b.actions()) {
            for (CompiledBuild.Change c : a.changes()) {
                cells.put(c.pos(), c.block());
            }
        }
        cells.values().removeIf(BlockSpec::isAir);
        return cells;
    }

    /** Lokale Blöcke → Welt (Vorderseite front) → .schem v3 → lesen → lokal. */
    static Map<Pos, BlockSpec> viaFile(Map<Pos, BlockSpec> local, Cardinal front) throws Exception {
        Orientation o = new Orientation(front.opposite());
        Map<Pos, BlockSpec> world = new HashMap<>();
        int mx = Integer.MAX_VALUE;
        int my = Integer.MAX_VALUE;
        int mz = Integer.MAX_VALUE;
        for (Pos p : local.keySet()) {
            Pos w = o.toWorldOffset(p);
            mx = Math.min(mx, w.x());
            my = Math.min(my, w.y());
            mz = Math.min(mz, w.z());
        }
        for (Map.Entry<Pos, BlockSpec> e : local.entrySet()) {
            world.put(o.toWorldOffset(e.getKey()).add(-mx, -my, -mz), o.toWorld(e.getValue()));
        }
        byte[] file = NbtWriter.sponge(world, 3);
        return StructureFile.toLocal(StructureFile.read(new ByteArrayInputStream(file)), front);
    }

    @Test
    void ownHouseRoundTripsExactlyFromEverySide() throws Exception {
        Map<Pos, BlockSpec> original = build(HOUSE);
        for (Cardinal front : Cardinal.values()) {
            Map<Pos, BlockSpec> local = viaFile(original, front);
            PlanDecompiler.Result r = PlanDecompiler.decompile(local, "haus", "Ein Haus!");
            PlanDecompiler.Verify v = PlanDecompiler.verify(r.json(), local, COMPILER, 60);
            assertTrue(v.exact(), () -> front + ": " + v + "\n" + r.json());
            // Form statt Einzelblöcke: Das Original hat 12 Schritte (das Dach rechnet der Compiler aus Treppen);
            // zurück kommen die Dachtreppen als Reihen, der Rest als fill/walls/cylinder.
            assertTrue(r.steps() <= 45, () -> r.steps() + " Schritte, " + r.ops());
            assertTrue(r.ops().getOrDefault("cylinder", 0) >= 1, () -> "Rohr nicht erkannt: " + r.ops());
            assertEquals(1, r.ops().get("door"), () -> "Tür: " + r.ops());
        }
    }

    @Test
    void neverWritesIntoAirOfTheOriginal() throws Exception {
        Map<Pos, BlockSpec> local = viaFile(build(HOUSE), Cardinal.SOUTH);
        PlanDecompiler.Result r = PlanDecompiler.decompile(local, "x", "x");
        assertEquals(0, PlanDecompiler.verify(r.json(), local, COMPILER, 60).extra());
    }

    @Test
    void forbiddenBlocksAreDroppedAndCounted() throws Exception {
        Map<Pos, BlockSpec> local = new HashMap<>(viaFile(build(HOUSE), Cardinal.SOUTH));
        local.put(new Pos(30, 0, 0), BlockSpec.parse("red_bed[part=foot]"));
        local.put(new Pos(31, 0, 0), BlockSpec.of("tnt"));
        PlanDecompiler.Result r = PlanDecompiler.decompile(local, "x", "x");
        assertEquals(2, r.dropped());
        assertFalse(r.json().contains("tnt") || r.json().contains("_bed"), r.json());
        assertTrue(PlanDecompiler.verify(r.json(), local, COMPILER, 60).exact());
    }

    /** Gegenprobe: verify erkennt einen veränderten Plan – sonst wäre „exakt“ im Lehrset nichts wert. */
    @Test
    void verifyNoticesAWrongPlan() throws Exception {
        Map<Pos, BlockSpec> local = viaFile(build(HOUSE), Cardinal.SOUTH);
        PlanDecompiler.Result r = PlanDecompiler.decompile(local, "x", "x");
        String wrong = r.json().replaceFirst("\"block\":\"cobblestone\"", "\"block\":\"dirt\"");
        assertFalse(wrong.equals(r.json()), "Testplan enthält kein cobblestone");
        PlanDecompiler.Verify v = PlanDecompiler.verify(wrong, local, COMPILER, 60);
        assertFalse(v.exact(), v.toString());
        assertTrue(v.matched() < v.cells());
    }
}
