package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.Orientation.Cardinal;
import com.blackn0va.baumeister.plan.PlanLinter.Issue;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Plot-Modus (Wunsch 2026-10-31).
 * PlotSquared selbst ist hier nicht dabei – geprüft wird die Rechnung: Ausrichtung, Passen, Zentrieren, Gestaltung.
 */
class PlotLayoutTest {

    private static final Machines LIB = new Machines();
    private static final PlanCompiler COMPILER = new PlanCompiler(
            id -> org.bukkit.Material.getMaterial(id.toUpperCase(Locale.ROOT)) != null, "stone",
            new PlanCompiler.Limits(150_000, 192), LIB);
    /** Standard-Plot von PlotSquared: 42 × 42. Weltrechteck irgendwo im Negativen. */
    private static final PlotLayout.Rect PLOT = new PlotLayout.Rect(-100, 50, -59, 91);

    @Test
    void plotWordsAreRecognisedAndCutFromTheOrder() {
        for (String p : List.of("bau das world trade center auf dem plot", "bau mir auf meinem Plot eine Burg",
                "bebau den ganzen Plot", "bau hier auf dem Grundstück ein Haus",
                "bau das World Trade Center auf dem plot, auf dem ich stehe")) {
            assertTrue(PlotLayout.mentioned(p), p);
        }
        assertFalse(PlotLayout.mentioned("bau ein Haus"));
        assertFalse(PlotLayout.mentioned("bau einen Plotter"));
        assertEquals("bau das world trade center", PlotLayout.strip("bau das world trade center auf dem plot"));
        assertEquals("bau das World Trade Center",
                PlotLayout.strip("bau das World Trade Center auf dem plot, auf dem ich stehe"));
        assertEquals("bau mir eine Burg", PlotLayout.strip("bau mir auf meinem Plot eine Burg"));
        // „über“: ohne (?U) blieb das Wort im Auftrag hängen („bau eine Burg über“), \b vor ü war keine Grenze.
        assertEquals("bau eine Burg", PlotLayout.strip("bau eine Burg über das ganze Grundstück"));
    }

    /** Jede Blickrichtung: Plotkoordinaten (0..w-1, 0..d-1) treffen genau die Zellen des Plots, keine daneben. */
    @Test
    void frameCoversExactlyThePlotInEveryDirection() {
        for (Cardinal c : Cardinal.values()) {
            Orientation o = new Orientation(c);
            PlotLayout.Frame f = PlotLayout.frame(PLOT, o);
            assertEquals(42, f.width(), c.name());
            assertEquals(42, f.depth(), c.name());
            Set<Long> seen = new HashSet<>();
            for (int x = 0; x < f.width(); x++) {
                for (int z = 0; z < f.depth(); z++) {
                    Pos w = o.toWorldOffset(new Pos(x, 0, z));
                    int wx = f.cornerX() + w.x();
                    int wz = f.cornerZ() + w.z();
                    assertTrue(wx >= PLOT.minX() && wx <= PLOT.maxX() && wz >= PLOT.minZ() && wz <= PLOT.maxZ(),
                            c + ": " + x + "/" + z + " → " + wx + "/" + wz);
                    seen.add(((long) wx << 32) ^ (wz & 0xffffffffL));
                }
            }
            assertEquals(42 * 42, seen.size(), c.name());
        }
        // Gemergte Plots sind länglich: Breite und Tiefe tauschen mit der Blickrichtung.
        PlotLayout.Rect wide = new PlotLayout.Rect(0, 0, 90, 41);
        assertEquals(91, PlotLayout.frame(wide, new Orientation(Cardinal.NORTH)).width());
        assertEquals(91, PlotLayout.frame(wide, new Orientation(Cardinal.EAST)).depth());
    }

    @Test
    void buildingIsCentredAndTheGardenFitsAround() throws Exception {
        BuildPlan tower = PlanParser.parse("{\"design\":\"\",\"reply\":\"\",\"steps\":[{\"op\":\"skyscraper\","
                + "\"block\":\"light_gray_concrete\",\"from\":[-8,0,0],\"to\":[7,0,15],\"floors\":30}]}");
        CompiledBuild b = COMPILER.compile(tower);
        assertTrue(PlotLayout.problem(b, 42, 42).isEmpty());
        int[] d = PlotLayout.centerShift(b, 42, 42);
        assertArrayEquals(new int[] {21, 13}, d, "16 breit mittig auf 42: x 13..28, z 13..28");
        String combined = PlotLayout.combine(BuildServiceShim.translate(tower, d), PlotLayout.landscape(42, 42,
                b.min().x() + d[0], b.min().z() + d[1], b.max().x() + d[0], b.max().z() + d[1]));
        BuildPlan all = PlanParser.parse(combined, PlotLayout.MAX_STEPS);
        CompiledBuild c = COMPILER.compile(all);
        assertTrue(c.min().x() >= 0 && c.max().x() <= 41 && c.min().z() >= 0 && c.max().z() <= 41,
                "alles auf dem Plot: " + c.min() + " … " + c.max());
        List<Issue> issues = PlanLinter.lint(all, c, LIB, true);
        assertTrue(issues.stream().noneMatch(PlanReview::severe), "Befunde: " + issues);
        assertTrue(c.warnings().isEmpty(), "Warnungen: " + c.warnings());
        // Hecke rundum mit Eingang vorn, Weg zur Front, das Hochhaus hat die Zellen, die beide setzen.
        assertTrue(has(c, new Pos(0, 0, 20), "oak_leaves"));
        assertTrue(has(c, new Pos(20, 0, 0), "air"), "Eingang in der Hecke");
        assertTrue(has(c, new Pos(20, -1, 5), "dirt_path"));
        assertTrue(has(c, new Pos(13, 2, 13), "light_gray_concrete") || has(c, new Pos(13, 2, 13), "glass"));
    }

    @Test
    void tooBigForThePlotGoesBackWithTheSize() throws Exception {
        CompiledBuild knox = COMPILER.compile(PlanParser.parse(QuickOrders.match("bau mir fort knox", LIB, 192)
                .orElseThrow()));
        String why = PlotLayout.problem(knox, 42, 42).orElseThrow();
        assertTrue(why.contains("42 x 42") && why.contains("36 x 36"), why);
        assertTrue(PlotLayout.hint(42, 42).contains("36 x 36"));
    }

    @Test
    void reviewSendsAnOversizedPlanBackOnAPlot() {
        String wide = HouseSite.plan("{\"op\":\"hollow\",\"block\":\"stone_bricks\",\"from\":[0,0,0],"
                + "\"to\":[49,5,9]}");
        PlanReview.Context ctx = new PlanReview.Context("bau ein langes Haus auf meinem Plot",
                RequestHints.parse("bau ein langes Haus", Set.of()), HouseSite.free(), false, Integer.MAX_VALUE, 1,
                PlanReview.LintMode.STRICT, 0, 42, 42);
        PlanReview.Verdict v = new PlanReview(HouseSite.COMPILER, ModuleLibrary.EMPTY, ctx).judge(wide, 0);
        PlanReview.Repair r = org.junit.jupiter.api.Assertions.assertInstanceOf(PlanReview.Repair.class, v);
        assertTrue(r.reason().contains("plot is only 42 x 42"), r.reason());
    }

    private static boolean has(CompiledBuild b, Pos p, String id) {
        return b.actions().stream().flatMap(a -> a.changes().stream())
                .anyMatch(ch -> ch.pos().equals(p) && ch.block().id().equals(id));
    }

    /** translate liegt in BuildService (Paket baumeister); für den Test dieselbe Verschiebung auf JSON-Ebene. */
    private static final class BuildServiceShim {
        static String translate(BuildPlan plan, int[] d) {
            StringBuilder sb = new StringBuilder("{\"design\":\"\",\"reply\":\"\",\"steps\":[");
            for (int i = 0; i < plan.steps().size(); i++) {
                BuildPlan.Step s = plan.steps().get(i);
                sb.append(i == 0 ? "" : ",").append("{\"op\":\"skyscraper\",\"block\":\"").append(s.block())
                        .append("\",\"from\":[").append(s.from().x() + d[0]).append(',').append(s.from().y())
                        .append(',').append(s.from().z() + d[1]).append("],\"to\":[").append(s.to().x() + d[0])
                        .append(',').append(s.to().y()).append(',').append(s.to().z() + d[1])
                        .append("],\"floors\":").append(s.floors()).append('}');
            }
            return sb.append("]}").toString();
        }
    }
}
