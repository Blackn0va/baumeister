package com.blackn0va.baumeister.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.BlockSpec;
import com.blackn0va.baumeister.plan.CompiledBuild;
import com.blackn0va.baumeister.plan.Orientation.Cardinal;
import com.blackn0va.baumeister.plan.PlanCompiler;
import com.blackn0va.baumeister.plan.PlanParser;
import com.blackn0va.baumeister.plan.Pos;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Dach-Erkennung im Rückübersetzer: Treppenreihen, die sich gegenüberstehen, werden EIN roof-Schritt statt vieler
 * line-Schritte – und dieser Schritt ergibt beim echten Compiler wieder dieselben Zellen. Was kein Dach ist
 * (Freitreppe, offener First, Bau ohne Treppen), bleibt Schritt für Schritt wie vor der Erkennung.
 */
class PlanDecompilerRoofTest {

    private static final PlanCompiler COMPILER = new PlanCompiler(
            id -> org.bukkit.Material.getMaterial(id.toUpperCase(Locale.ROOT)) != null, "stone",
            new PlanCompiler.Limits(150_000, 192));

    @Test
    void gableRoofOfStairsBecomesOneRoofStepFromEverySide() throws Exception {
        for (Cardinal front : Cardinal.values()) {
            Map<Pos, BlockSpec> local = PlanDecompilerTest.viaFile(MiniVorbilder.cottage(), front);
            PlanDecompiler.Result r = PlanDecompiler.decompile(local, "x", "x");
            assertEquals(1, r.ops().getOrDefault("roof", 0), () -> front + ": " + r.ops() + "\n" + r.json());
            assertEquals(0, stairsRows(r.json()), () -> front + ": Dachtreppen als line/set\n" + r.json());
            JsonObject roof = roofStep(r.json());
            assertEquals("gable", roof.get("shape").getAsString(), roof::toString);
            assertEquals("spruce_planks", roof.get("gable_block").getAsString(), roof::toString);
            assertTrue(PlanDecompiler.verify(r.json(), local, COMPILER, 60).exact(), r::json);
        }
    }

    @Test
    void hipRoofWithCornerStairsBecomesOneRoofStep() throws Exception {
        Map<Pos, BlockSpec> local = PlanDecompilerTest.viaFile(MiniVorbilder.pavilion(), Cardinal.SOUTH);
        PlanDecompiler.Result r = PlanDecompiler.decompile(local, "x", "x");
        assertEquals(1, r.ops().getOrDefault("roof", 0), () -> r.ops() + "\n" + r.json());
        assertEquals("hip", roofStep(r.json()).get("shape").getAsString(), r::json);
        assertTrue(PlanDecompiler.verify(r.json(), local, COMPILER, 60).exact(), r::json);
    }

    /**
     * Der erkannte roof-Schritt allein, mit dem echten Compiler gebaut, gegen das Original: Satteldach ohne eine
     * abweichende Zelle; Walmdach weicht höchstens an den Ecken ab (shape=outer_… setzt erst der Server am Nachbarn,
     * 4 Ecken × 3 Schichten). In Luft schreibt der Schritt nie.
     */
    @Test
    void roofStepCompilesToTheSameCells() throws Exception {
        int[] cottage = roofDeviation(MiniVorbilder.cottage());
        int[] pavilion = roofDeviation(MiniVorbilder.pavilion());
        System.out.println("Messung | Dachzellen | Häuschen gleich=" + cottage[0] + " anders=" + cottage[1]
                + " Luft=" + cottage[2] + " | Pavillon gleich=" + pavilion[0] + " anders=" + pavilion[1] + " Luft="
                + pavilion[2]);
        assertEquals(0, cottage[1], () -> "Satteldach: " + Arrays.toString(cottage));
        assertEquals(0, cottage[2], () -> "Satteldach: " + Arrays.toString(cottage));
        assertTrue(pavilion[1] <= 12, () -> "Walmdach: " + Arrays.toString(pavilion));
        assertEquals(0, pavilion[2], () -> "Walmdach: " + Arrays.toString(pavilion));
    }

    /** Gegenprobe „bleibt wie heute“: Ohne Dach im Bau ist der Plan mit und ohne Erkennung zeichengleich. */
    @Test
    void buildsWithoutARoofStayUnchanged() throws Exception {
        Map<String, Map<Pos, BlockSpec>> builds = Map.of("ohne Dach", MiniVorbilder.shed(), "Freitreppe",
                MiniVorbilder.staircase(), "offener First", MiniVorbilder.openRidge());
        for (Map.Entry<String, Map<Pos, BlockSpec>> e : builds.entrySet()) {
            Map<Pos, BlockSpec> local = PlanDecompilerTest.viaFile(e.getValue(), Cardinal.SOUTH);
            String with = PlanDecompiler.decompile(local, "x", "x").json();
            String without = PlanDecompiler.decompile(local, "x", "x", Integer.MAX_VALUE, false).json();
            assertEquals(without, with, e.getKey());
            assertFalse(with.contains("\"op\":\"roof\""), e.getKey());
        }
    }

    /**
     * Vorbilder: eine Doppelbank wird kein Dach. Zwei Treppenreihen Rücken an Rücken (back/front bzw. right/left)
     * liegen auf einer Höhe – der roof-Schritt hätte dieselben Zellen, aber das Lehrset nennte eine Bank „Dach“. Der
     * Plan bleibt zeichengleich zu dem ohne Erkennung.
     */
    @Test
    void doubleBenchIsNoRoof() throws Exception {
        Map<String, Map<Pos, BlockSpec>> benches = Map.of("x back/front", MiniVorbilder.doubleBench("back", "front",
                true), "x front/back", MiniVorbilder.doubleBench("front", "back", true), "z right/left",
                MiniVorbilder.doubleBench("right", "left", false), "z left/right",
                MiniVorbilder.doubleBench("left", "right", false));
        for (Map.Entry<String, Map<Pos, BlockSpec>> e : benches.entrySet()) {
            Map<Pos, BlockSpec> local = PlanDecompilerTest.viaFile(e.getValue(), Cardinal.SOUTH);
            String with = PlanDecompiler.decompile(local, "x", "x").json();
            assertFalse(with.contains("\"op\":\"roof\""), () -> e.getKey() + ": Bank als Dach\n" + with);
            assertEquals(PlanDecompiler.decompile(local, "x", "x", Integer.MAX_VALUE, false).json(), with,
                    e.getKey());
            assertTrue(PlanDecompiler.verify(with, local, COMPILER, 60).exact(), e.getKey());
        }
    }

    /** Gegenstück zur Bank: Zwei Lagen, die zum First hin steigen, sind das kleinste Dach und bleiben roof. */
    @Test
    void twoRisingLayersStayARoof() throws Exception {
        Map<Pos, BlockSpec> local = PlanDecompilerTest.viaFile(MiniVorbilder.twoLayerRoof(), Cardinal.SOUTH);
        PlanDecompiler.Result r = PlanDecompiler.decompile(local, "x", "x");
        assertEquals(1, r.ops().getOrDefault("roof", 0), () -> r.ops() + "\n" + r.json());
        assertEquals(0, MiniVorbilder.stairsSteps(r.json()), r::json);
        assertTrue(PlanDecompiler.verify(r.json(), local, COMPILER, 60).exact(), r::json);
    }

    /** Mit dem Lehrset-Budget (40 Schritte) bleibt der Rückbau exakt, und es braucht weniger Schritte als vorher. */
    @Test
    void roofSavesStepsAndKeepsTheBuildExact() throws Exception {
        for (Map.Entry<String, Map<Pos, BlockSpec>> e : Map.of("Häuschen", MiniVorbilder.cottage(), "Pavillon",
                MiniVorbilder.pavilion(), "Testhaus", PlanDecompilerTest.build(PlanDecompilerTest.HOUSE)).entrySet()) {
            Map<Pos, BlockSpec> local = PlanDecompilerTest.viaFile(e.getValue(), Cardinal.SOUTH);
            PlanDecompiler.Result with = PlanDecompiler.decompile(local, "x", "x", 40);
            PlanDecompiler.Result without = PlanDecompiler.decompile(local, "x", "x", 40, false);
            PlanDecompiler.Verify v = PlanDecompiler.verify(with.json(), local, COMPILER, 60);
            PlanDecompiler.Verify w = PlanDecompiler.verify(without.json(), local, COMPILER, 60);
            System.out.printf(Locale.ROOT, "Messung | %s, 40er-Budget | ohne Dach-Erkennung: %d Schritte %s %d/%d "
                    + "extra %d | mit: %d Schritte %s %d/%d extra %d%n", e.getKey(), without.steps(), without.ops(),
                    w.matched(), w.cells(), w.extra(), with.steps(), with.ops(), v.matched(), v.cells(), v.extra());
            assertTrue(v.matched() >= w.matched(), () -> e.getKey() + ": " + v + " gegen " + w);
            assertEquals(0, v.extra(), e.getKey());
            assertTrue(with.steps() < without.steps() || v.matched() > w.matched(),
                    () -> e.getKey() + ": " + with.steps() + " gegen " + without.steps());
        }
    }

    /** {gleich, anders, in Luft} für die Zellen des erkannten roof-Schritts, allein kompiliert. */
    private static int[] roofDeviation(Map<Pos, BlockSpec> build) throws Exception {
        Map<Pos, BlockSpec> local = PlanDecompilerTest.viaFile(build, Cardinal.SOUTH);
        JsonObject roof = roofStep(PlanDecompiler.decompile(local, "x", "x").json());
        CompiledBuild b = COMPILER.compile(PlanParser.parse("{\"design\":\"x\",\"reply\":\"x\",\"steps\":[" + roof
                + "]}"));
        Map<Pos, BlockSpec> built = new HashMap<>();
        for (CompiledBuild.Action a : b.actions()) {
            for (CompiledBuild.Change c : a.changes()) {
                built.put(c.pos(), BlockStates.normalize(c.block()));
            }
        }
        int[] out = new int[3];
        for (Map.Entry<Pos, BlockSpec> e : built.entrySet()) {
            BlockSpec want = local.get(e.getKey());
            out[want == null ? 2 : want.equals(e.getValue()) ? 0 : 1]++;
        }
        return out;
    }

    private static JsonObject roofStep(String json) {
        for (JsonElement s : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("steps")) {
            if (s.getAsJsonObject().get("op").getAsString().equals("roof")) {
                return s.getAsJsonObject();
            }
        }
        throw new AssertionError("kein roof-Schritt:\n" + json);
    }

    private static int stairsRows(String json) {
        return MiniVorbilder.stairsSteps(json);
    }
}
