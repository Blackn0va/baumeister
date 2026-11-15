package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.PlanLinter.Issue;
import com.blackn0va.baumeister.portal.PortalFrame;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Lobby und Portalhalle ohne LLM (Wunsch 2026-11-15: „ich will auch einen frischen server einrichten können mit dem
 * baumeister, mit diesen portal hallen burgen, riesen große lobys“).
 */
class LobbyTest {

    private static final Machines LIB = new Machines();
    private static final PlanCompiler COMPILER = new PlanCompiler(
            id -> org.bukkit.Material.getMaterial(id.toUpperCase(Locale.ROOT)) != null, "stone",
            new PlanCompiler.Limits(150_000, 192), LIB);

    /** Jede Größe beider Formen, dazu die Ränder: kleinste und größte Maße, kein und das größte Portal-Angebot. */
    private static final List<String> VARIANTS = List.of("bau eine kleine Lobby", "bau eine Lobby",
            "bau eine große Lobby", "riesen große lobys", "bau eine Lobby 25x81", "bau eine Lobby 81 mal 25",
            "bau eine Lobby ohne Portale", "bau eine riesige Lobby mit 11 Portalen", "bau eine kleine Portalhalle",
            "bau eine Portalhalle", "bau eine große Portalhalle", "bau eine riesige Portalhalle",
            "bau eine Halle mit 13 Portalen", "bau eine Portalhalle 31x96", "bau eine Portalhalle mit 1 Portal",
            "bau eine Portalhalle 11x21");

    private static final Pattern PORTALS = Pattern.compile("(\\d+) unlit obsidian portal frame");

    private static BuildPlan plan(String prompt) throws PlanException {
        return PlanParser.parse(QuickOrders.match(prompt, LIB, 192).orElseThrow(() -> new AssertionError(
                "kein Schnellauftrag: " + prompt)));
    }

    private static Map<Pos, String> cells(CompiledBuild b) {
        Map<Pos, String> out = new HashMap<>();
        b.actions().forEach(a -> a.changes().forEach(c -> out.put(c.pos(), c.block().id())));
        return out;
    }

    private static int portalsOf(BuildPlan p) {
        Matcher m = PORTALS.matcher(p.design());
        assertTrue(m.find(), p.design());
        return Integer.parseInt(m.group(1));
    }

    @Test
    void theWishAndItsVariantsAreRecognised() throws Exception {
        assertTrue(plan("riesen große lobys").design().startsWith("Spawn lobby 81x81"));
        assertTrue(plan("bau mir eine Spawn-Lobby").design().startsWith("Spawn lobby 49x49"));
        assertTrue(plan("bau eine kleine Lobby für meinen Server").design().startsWith("Spawn lobby 33x33"));
        assertTrue(plan("bau eine Lobby 60x60").design().startsWith("Spawn lobby 61x61"), "ungerade für die Mitte");
        assertTrue(plan("bau eine Lobby 60 mal 40").design().startsWith("Spawn lobby 61x41"));
        assertEquals(0, portalsOf(plan("bau eine Lobby ohne Portale")));
        assertTrue(plan("bau mir eine riesige Portalhalle").design().startsWith("Portal hall 23x"));
        assertEquals(6, portalsOf(plan("bau eine Halle mit 6 Portalen")));
        assertEquals(7, portalsOf(plan("bau eine Portalhalle mit sieben Portalen")));
        assertTrue(plan("bau mir eine Lobby mit Portalen zu allen Welten").design().startsWith("Spawn lobby"));
    }

    @Test
    void anythingElseStaysWithTheLlm() {
        for (String prompt : List.of("bau eine Lobby mit Kino", "bau eine Halle", "bau ein Portal",
                "bau eine große kleine Lobby", "bau eine Lobby mit 3 Etagen", "bau 2 Lobbys mit 4 Portalen",
                "bau eine Lobby ohne", "bau eine Turnhalle", "bau eine Halle mit 40 Portalen")) {
            assertTrue(QuickOrders.lobby(prompt, 192).isEmpty(), prompt);
        }
    }

    @Test
    void tooManyPortalsAreCappedAndSaid() throws Exception {
        BuildPlan p = plan("bau eine kleine Lobby mit 11 Portalen");
        assertEquals(5, portalsOf(p), "klein: 2 je Seite und die Rückwand");
        assertTrue(p.reply().contains("mehr passen nicht"), p.reply());
        assertTrue(p.reply().contains("/bm portal"), "sagt, wie die Rahmen entzündet werden: " + p.reply());
        assertTrue(p.reply().length() <= 200, p.reply());
        assertTrue(!plan("bau eine Lobby ohne Portale").reply().contains("/bm portal"), "ohne Rahmen kein Hinweis");
    }

    @Test
    void everyVariantPassesTheBuildCheckWithoutRepair() throws Exception {
        for (String prompt : VARIANTS) {
            BuildPlan p = plan(prompt);
            assertTrue(p.steps().size() <= PlanParser.MAX_STEPS, prompt + ": " + p.steps().size() + " Schritte");
            CompiledBuild b = COMPILER.compile(p);
            List<Issue> issues = PlanLinter.lint(p, b, LIB, true);
            assertTrue(issues.stream().noneMatch(PlanReview::severe), prompt + " – Befunde: " + issues);
            assertTrue(b.warnings().isEmpty(), prompt + " – Warnungen: " + b.warnings());
            assertTrue(p.reply().length() <= 200, prompt + ": " + p.reply());
        }
    }

    @Test
    void everyFrameIsAWalkInOpeningAndNothingIsLit() throws Exception {
        for (String prompt : VARIANTS) {
            BuildPlan p = plan(prompt);
            Map<Pos, String> c = cells(COMPILER.compile(p));
            for (String id : c.values()) {
                assertTrue(!BlockTiers.DANGEROUS_BLOCKS.contains(id) && !BlockTiers.COMMAND_BLOCKS.contains(id),
                        prompt + ": " + id + " braucht ein Admin-Recht");
            }
            // Öffnung = Reihe aus Luft auf y 0 über Obsidian auf Bodenhöhe; je Reihe ein Portal.
            Set<Pos> bottoms = new HashSet<>();
            c.forEach((pos, id) -> {
                if (pos.y() == 0 && id.equals("air") && "obsidian".equals(c.get(pos.add(0, -1, 0)))) {
                    bottoms.add(pos);
                }
            });
            List<List<Pos>> runs = runs(bottoms);
            assertEquals(portalsOf(p), runs.size(), prompt);
            for (List<Pos> run : runs) {
                int width = run.size();
                assertTrue(width == 3 || width == 5, prompt + ": Öffnung " + width + " breit bei " + run.getFirst());
                int height = width == 3 ? 4 : 6;
                for (Pos b : run) {
                    for (int y = 0; y < height; y++) {
                        assertEquals("air", c.get(b.add(0, y, 0)), prompt + ": Öffnung zu bei " + b.add(0, y, 0));
                    }
                    assertEquals("obsidian", c.get(b.add(0, height, 0)), prompt + ": Sturz fehlt über " + b);
                }
            }
        }
    }

    /**
     * Jeder Rahmen erfüllt die Regeln von Minecraft (PortalFrame wie PortalShape) – sonst ließe /bm portal ihn nicht
     * entzünden, oder das Portal zerfiele beim ersten Update daneben.
     */
    @Test
    void everyFrameIsAValidNetherPortalShape() throws Exception {
        for (String prompt : VARIANTS) {
            BuildPlan p = plan(prompt);
            Map<Pos, String> c = cells(COMPILER.compile(p));
            PortalFrame.Blocks view = new PortalFrame.Blocks() {
                @Override
                public boolean frame(int x, int y, int z) {
                    return "obsidian".equals(c.get(new Pos(x, y, z)));
                }

                @Override
                public boolean open(int x, int y, int z) {
                    return "air".equals(c.get(new Pos(x, y, z)));
                }
            };
            Set<PortalFrame.Opening> openings = new HashSet<>();
            c.forEach((pos, id) -> {
                if (pos.y() == 0 && id.equals("air") && "obsidian".equals(c.get(pos.add(0, -1, 0)))) {
                    PortalFrame.Result r = PortalFrame.at(view, pos.x(), pos.y(), pos.z());
                    assertTrue(r.found(), prompt + " bei " + pos + ": " + r.reason());
                    openings.add(r.opening());
                }
            });
            assertEquals(portalsOf(p), openings.size(), prompt);
        }
    }

    /** Zusammenhängende Reihen in x oder z. */
    private static List<List<Pos>> runs(Set<Pos> bottoms) {
        List<List<Pos>> out = new ArrayList<>();
        Set<Pos> seen = new HashSet<>();
        for (Pos start : bottoms) {
            if (!seen.add(start)) {
                continue;
            }
            List<Pos> run = new ArrayList<>();
            ArrayDeque<Pos> todo = new ArrayDeque<>(List.of(start));
            while (!todo.isEmpty()) {
                Pos q = todo.poll();
                run.add(q);
                for (Pos n : List.of(q.add(1, 0, 0), q.add(-1, 0, 0), q.add(0, 0, 1), q.add(0, 0, -1))) {
                    if (bottoms.contains(n) && seen.add(n)) {
                        todo.add(n);
                    }
                }
            }
            out.add(run);
        }
        return out;
    }

    @Test
    void theRoofIsSpawnProof() throws Exception {
        for (String prompt : VARIANTS) {
            Map<Pos, String> c = cells(COMPILER.compile(plan(prompt)));
            int roof = c.entrySet().stream().filter(e -> e.getValue().equals("smooth_stone_slab"))
                    .mapToInt(e -> e.getKey().y()).max().orElseThrow();
            c.forEach((pos, id) -> {
                if (pos.y() >= roof) {
                    assertTrue(Set.of("smooth_stone_slab", "glass", "air").contains(id),
                            prompt + ": " + id + " auf dem Dach bei " + pos);
                }
            });
        }
    }

    /**
     * Monster spawnen seit 1.18 nur bei Blocklicht 0 auf einer vollen Oberseite mit zwei Luftblöcken darüber.
     * Gerechnet wird das Licht der Seelaternen (15, je Schritt durch Luft, Glas oder Teppich eins weniger); alles,
     * was nicht zum Plan gehört, zählt als undurchsichtig – das Gelände ringsum hilft also nie mit.
     */
    @Test
    void nothingCanSpawnInsideAtNight() throws Exception {
        for (String prompt : VARIANTS) {
            Map<Pos, String> c = cells(COMPILER.compile(plan(prompt)));
            Map<Pos, Integer> light = new HashMap<>();
            ArrayDeque<Pos> todo = new ArrayDeque<>();
            c.forEach((pos, id) -> {
                if (id.equals("sea_lantern")) {
                    light.put(pos, 15);
                    todo.add(pos);
                }
            });
            while (!todo.isEmpty()) {
                Pos q = todo.poll();
                int next = light.get(q) - 1;
                if (next <= 0) {
                    continue;
                }
                for (Pos n : List.of(q.add(1, 0, 0), q.add(-1, 0, 0), q.add(0, 1, 0), q.add(0, -1, 0),
                        q.add(0, 0, 1), q.add(0, 0, -1))) {
                    if (passesLight(c.get(n)) && light.getOrDefault(n, 0) < next) {
                        light.put(n, next);
                        todo.add(n);
                    }
                }
            }
            int spawnable = 0;
            for (Map.Entry<Pos, String> e : c.entrySet()) {
                Pos pos = e.getKey();
                if (!e.getValue().equals("air") || !"air".equals(c.get(pos.add(0, 1, 0)))
                        || !fullTop(c.get(pos.add(0, -1, 0)))) {
                    continue;
                }
                spawnable++;
                assertTrue(light.getOrDefault(pos, 0) >= 1, prompt + ": dunkel bei " + pos);
            }
            assertTrue(spawnable > 100, prompt + ": Prüfung sieht keinen Boden (" + spawnable + ")");
        }
    }

    private static boolean passesLight(String id) {
        return id != null && (id.equals("air") || id.equals("glass") || id.endsWith("_carpet"));
    }

    /** Volle Oberseite: kein Glas, keine Platte, kein Teppich, keine Luft (und nichts außerhalb des Plans). */
    private static boolean fullTop(String id) {
        return id != null && !id.equals("air") && !id.equals("glass") && !id.endsWith("_slab")
                && !id.endsWith("_carpet");
    }

    @Test
    void theEntranceIsOpenAtGroundLevel() throws Exception {
        for (String prompt : VARIANTS) {
            Map<Pos, String> c = cells(COMPILER.compile(plan(prompt)));
            for (int x = -2; x <= 2; x++) {
                for (int y = 0; y <= 3; y++) {
                    assertEquals("air", c.get(new Pos(x, y, 0)), prompt + ": Eingang zu bei " + x + "," + y);
                }
                assertTrue(fullTop(c.get(new Pos(x, -1, 0))), prompt + ": Schwelle fehlt bei x " + x);
            }
        }
    }
}
