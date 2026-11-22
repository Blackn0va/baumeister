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

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Kugel-Erkennung im Rückübersetzer: Kugeln und Kuppeln werden EIN sphere-Schritt statt eines Scheiben-Zylinders je
 * Schicht, der Rückbau bleibt Block für Block gleich. Die Bauten sind von Hand gesetzt (wie in MiniVorbilder), nicht
 * über den PlanCompiler – sonst bewiese der Test nur, dass der Rückübersetzer seine eigene Nachbildung wiedererkennt.
 * Was nur fast eine Kugel ist, bleibt zeichengleich bei der Zerlegung ohne Kugel.
 */
class PlanDecompilerSphereTest {

    private static final PlanCompiler COMPILER = new PlanCompiler(
            id -> org.bukkit.Material.getMaterial(id.toUpperCase(Locale.ROOT)) != null, "stone",
            new PlanCompiler.Limits(150_000, 192));

    /** Gleichung wie bei einem Spieler mit Kugel-Generator: Abstand zur Mitte unter r + 0,5. */
    private static boolean in(int dx, int dy, int dz, int r) {
        return dx * dx + dy * dy + dz * dz < (r + 0.5) * (r + 0.5);
    }

    /** Kugel um (cx, cy, cz); hollow = nur Zellen mit einem Nachbarn (6 Richtungen) außerhalb; dome = nur dy ≥ 0. */
    private static void ball(Map<Pos, BlockSpec> m, String block, int cx, int cy, int cz, int r, boolean hollow,
            boolean dome) {
        for (int dy = dome ? 0 : -r; dy <= r; dy++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (!in(dx, dy, dz, r)) {
                        continue;
                    }
                    boolean shell = !in(dx + 1, dy, dz, r) || !in(dx - 1, dy, dz, r) || !in(dx, dy + 1, dz, r)
                            || !in(dx, dy - 1, dz, r) || !in(dx, dy, dz + 1, r) || !in(dx, dy, dz - 1, r);
                    if (!hollow || shell) {
                        m.put(new Pos(cx + dx, cy + dy, cz + dz), BlockSpec.of(block));
                    }
                }
            }
        }
    }

    /** Runder Turm (Rohr) aus block, Mitte (cx, cz), Höhe h ab y 0. */
    private static void tower(Map<Pos, BlockSpec> m, String block, int cx, int cz, int r, int h) {
        for (int y = 0; y < h; y++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (in(dx, 0, dz, r) && !(in(dx + 1, 0, dz, r) && in(dx - 1, 0, dz, r) && in(dx, 0, dz + 1, r)
                            && in(dx, 0, dz - 1, r))) {
                        m.put(new Pos(cx + dx, y, cz + dz), BlockSpec.of(block));
                    }
                }
            }
        }
    }

    /** Die Nachbildung im Rückübersetzer ist genau PlanCompiler.sphere: jeder Radius, alle vier Varianten. */
    @Test
    void ballCellsAreExactlyTheCompilersSphere() throws Exception {
        for (int r = 1; r <= 24; r++) {
            for (int variant = 0; variant < 4; variant++) {
                boolean hollow = (variant & 1) != 0;
                boolean dome = (variant & 2) != 0;
                String step = "{\"op\":\"sphere\",\"block\":\"stone\",\"pos\":[30,30,30],\"radius\":" + r
                        + (hollow ? ",\"hollow\":true" : "") + (dome ? ",\"dome\":true" : "") + "}";
                CompiledBuild b = COMPILER.compile(PlanParser.parse("{\"design\":\"x\",\"reply\":\"x\",\"steps\":["
                        + step + "]}"));
                Set<Pos> built = new HashSet<>();
                for (CompiledBuild.Action a : b.actions()) {
                    for (CompiledBuild.Change c : a.changes()) {
                        built.add(c.pos());
                    }
                }
                Set<Pos> ours = new HashSet<>();
                for (int[] d : PlanDecompiler.ballCells(r, hollow, dome)) {
                    ours.add(new Pos(30 + d[0], 30 + d[1], 30 + d[2]));
                }
                assertEquals(built, ours, step);
            }
        }
    }

    @Test
    void fullAndHollowSphereBecomeOneSphereStepFromEverySide() throws Exception {
        for (boolean hollow : new boolean[] {false, true}) {
            Map<Pos, BlockSpec> m = new HashMap<>();
            ball(m, "white_concrete", 6, 6, 6, 6, hollow, false);
            for (Cardinal front : Cardinal.values()) {
                Map<Pos, BlockSpec> local = PlanDecompilerTest.viaFile(m, front);
                PlanDecompiler.Result r = PlanDecompiler.decompile(local, "x", "x");
                String label = (hollow ? "hohle" : "volle") + " Kugel, " + front;
                assertEquals(Map.of("sphere", 1), r.ops(), () -> label + "\n" + r.json());
                JsonObject s = step(r.json(), "sphere");
                assertEquals(6, s.get("radius").getAsInt(), s::toString);
                assertEquals(hollow, s.has("hollow"), s::toString);
                assertFalse(s.has("dome"), s::toString);
                assertExactAndShorter(label, local, r);
            }
        }
    }

    /** Kuppel auf einem runden Turm: aus anderem und aus demselben Block wie der Turm. */
    @Test
    void domeOnATowerBecomesOneDomeStep() throws Exception {
        for (String domeBlock : new String[] {"cut_copper", "stone_bricks"}) {
            for (boolean hollow : new boolean[] {false, true}) {
                Map<Pos, BlockSpec> m = new HashMap<>();
                tower(m, "stone_bricks", 5, 5, 5, 8);
                ball(m, domeBlock, 5, 8, 5, 5, hollow, true);
                Map<Pos, BlockSpec> local = PlanDecompilerTest.viaFile(m, Cardinal.SOUTH);
                PlanDecompiler.Result r = PlanDecompiler.decompile(local, "x", "x");
                String label = "Kuppel " + domeBlock + (hollow ? " hohl" : " voll");
                assertEquals(1, r.ops().getOrDefault("sphere", 0), () -> label + ": " + r.ops() + "\n" + r.json());
                JsonObject s = step(r.json(), "sphere");
                assertTrue(s.has("dome"), s::toString);
                assertEquals(5, s.get("radius").getAsInt(), s::toString);
                assertExactAndShorter(label, local, r);
            }
        }
    }

    /** Kugel direkt an einer Ziegelwand, und eine zweite mit einem Block Abstand zu einer Wand aus demselben Block. */
    @Test
    void sphereNextToAWallIsStillASphere() throws Exception {
        Map<String, Map<Pos, BlockSpec>> builds = new LinkedHashMap<>();
        Map<Pos, BlockSpec> touching = new HashMap<>();
        ball(touching, "white_concrete", 4, 4, 4, 4, false, false);
        wall(touching, "bricks", 9, 8);
        builds.put("berührt Ziegelwand", touching);
        Map<Pos, BlockSpec> gap = new HashMap<>();
        ball(gap, "white_concrete", 4, 4, 4, 4, true, false);
        wall(gap, "white_concrete", 10, 8);
        builds.put("Abstand zur gleichen Wand", gap);
        for (Map.Entry<String, Map<Pos, BlockSpec>> e : builds.entrySet()) {
            Map<Pos, BlockSpec> local = PlanDecompilerTest.viaFile(e.getValue(), Cardinal.SOUTH);
            PlanDecompiler.Result r = PlanDecompiler.decompile(local, "x", "x");
            assertEquals(1, r.ops().getOrDefault("sphere", 0), () -> e.getKey() + ": " + r.ops() + "\n" + r.json());
            assertExactAndShorter(e.getKey(), local, r);
        }
    }

    /**
     * Gegenbeispiele, die fast Kugeln sind: eine Schale nach der Gleichung „Abstand² ≤ r²“ (schmaler als die des
     * Compilers, jede sphere schriebe in Luft) und eine hohle Kugel mit einem Loch in der Schale. Beide dürfen kein
     * sphere werden, der Plan bleibt zeichengleich zu dem ohne Kugel-Erkennung und baut exakt zurück. Eine VOLLE Kugel
     * mit Luft in der Mitte ist dagegen kein Gegenbeispiel: Ihre Außenschale ist wirklich eine hohle Kugel (gemessen
     * 2026-11-22: hollow sphere plus Füllung, Rückbau exakt).
     */
    @Test
    void almostSpheresStayAsBefore() throws Exception {
        Map<String, Map<Pos, BlockSpec>> builds = new LinkedHashMap<>();
        Map<Pos, BlockSpec> shell = new HashMap<>();
        int r = 6;
        for (int dy = -r; dy <= r; dy++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    int d = dx * dx + dy * dy + dz * dz;
                    if (d <= r * r && d > (r - 1) * (r - 1)) {
                        shell.put(new Pos(r + dx, r + dy, r + dz), BlockSpec.of("white_concrete"));
                    }
                }
            }
        }
        builds.put("Schale r²", shell);
        Map<Pos, BlockSpec> holed = new HashMap<>();
        ball(holed, "white_concrete", 6, 6, 6, 6, true, false);
        assertTrue(holed.remove(new Pos(12, 6, 6)) != null, "Schalenblock fehlt");
        builds.put("Schale mit Loch", holed);
        for (Map.Entry<String, Map<Pos, BlockSpec>> e : builds.entrySet()) {
            Map<Pos, BlockSpec> local = PlanDecompilerTest.viaFile(e.getValue(), Cardinal.SOUTH);
            String with = PlanDecompiler.decompile(local, "x", "x").json();
            String without = PlanDecompiler.decompile(local, "x", "x", Integer.MAX_VALUE, true, false).json();
            assertFalse(with.contains("\"op\":\"sphere\""), () -> e.getKey() + " als Kugel:\n" + with);
            assertEquals(without, with, e.getKey());
            assertTrue(PlanDecompiler.verify(with, local, COMPILER, 2000).exact(), e.getKey());
        }
    }

    /** Bauten ohne Kugel (Testhaus mit Turm-Rohr, Häuschen mit Dach) bleiben zeichengleich. */
    @Test
    void buildsWithoutASphereStayUnchanged() throws Exception {
        for (Map.Entry<String, Map<Pos, BlockSpec>> e : Map.of("Testhaus", PlanDecompilerTest.build(
                PlanDecompilerTest.HOUSE), "Häuschen", MiniVorbilder.cottage(), "Pavillon", MiniVorbilder.pavilion())
                .entrySet()) {
            Map<Pos, BlockSpec> local = PlanDecompilerTest.viaFile(e.getValue(), Cardinal.SOUTH);
            for (int budget : new int[] {Integer.MAX_VALUE, 40}) {
                assertEquals(PlanDecompiler.decompile(local, "x", "x", budget, true, false).json(),
                        PlanDecompiler.decompile(local, "x", "x", budget).json(), e.getKey() + " " + budget);
            }
        }
    }

    /** Im Lehrset-Budget (40 Schritte) trifft der Plan mit Kugel mindestens so viele Zellen und schreibt nie in Luft. */
    @Test
    void sphereKeepsTheBudgetedBuildAtLeastAsGood() throws Exception {
        Map<Pos, BlockSpec> m = new HashMap<>();
        tower(m, "stone_bricks", 8, 8, 8, 12);
        ball(m, "cut_copper", 8, 12, 8, 8, true, true);
        ball(m, "white_concrete", 30, 6, 8, 6, false, false);
        Map<Pos, BlockSpec> local = PlanDecompilerTest.viaFile(m, Cardinal.SOUTH);
        PlanDecompiler.Result with = PlanDecompiler.decompile(local, "x", "x", 40);
        PlanDecompiler.Result without = PlanDecompiler.decompile(local, "x", "x", 40, true, false);
        PlanDecompiler.Verify v = PlanDecompiler.verify(with.json(), local, COMPILER, 60);
        PlanDecompiler.Verify w = PlanDecompiler.verify(without.json(), local, COMPILER, 60);
        System.out.printf(Locale.ROOT, "Messung | Turm mit Kuppel + Kugel, 40er-Budget | ohne Kugel-Erkennung: %d "
                + "Schritte %d/%d | mit: %d Schritte %s %d/%d extra %d%n", without.steps(), w.matched(), w.cells(),
                with.steps(), with.ops(), v.matched(), v.cells(), v.extra());
        assertEquals(2, with.ops().getOrDefault("sphere", 0), with::json);
        assertTrue(v.exact(), v::toString);
        assertTrue(v.matched() >= w.matched() && with.steps() < without.steps(), () -> v + " gegen " + w);
    }

    private static void assertExactAndShorter(String label, Map<Pos, BlockSpec> local, PlanDecompiler.Result r) {
        PlanDecompiler.Verify v = PlanDecompiler.verify(r.json(), local, COMPILER, 2000);
        assertTrue(v.exact(), () -> label + ": " + v + "\n" + r.json());
        PlanDecompiler.Result before = PlanDecompiler.decompile(local, "x", "x", Integer.MAX_VALUE, true, false);
        System.out.printf(Locale.ROOT, "Messung | %s | ohne Kugel-Erkennung %d Schritte %s | mit %d Schritte %s%n",
                label, before.steps(), before.ops(), r.steps(), r.ops());
        assertTrue(r.steps() < before.steps(), () -> label + ": " + r.steps() + " gegen " + before.steps());
    }

    /** Wand in der Ebene x = wx, z 0..size, y 0..size. */
    private static void wall(Map<Pos, BlockSpec> m, String block, int wx, int size) {
        for (int y = 0; y <= size; y++) {
            for (int z = 0; z <= size; z++) {
                m.put(new Pos(wx, y, z), BlockSpec.of(block));
            }
        }
    }

    private static JsonObject step(String json, String op) {
        for (JsonElement s : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("steps")) {
            if (s.getAsJsonObject().get("op").getAsString().equals(op)) {
                return s.getAsJsonObject();
            }
        }
        throw new AssertionError("kein " + op + "-Schritt:\n" + json);
    }
}
