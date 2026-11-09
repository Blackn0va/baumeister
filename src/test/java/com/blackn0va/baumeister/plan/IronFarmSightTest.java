package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.CompiledBuild.Change;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Sichtlinien der Eisenfarm am gebauten Raster, ohne Server (2026-11-08, Eisenfarm rund um die Uhr).
 *
 * Minecraft prüft „sieht“ mit einem Strahl von Auge zu Auge gegen die KOLLISIONSFORMEN der Blöcke
 * (LivingEntity.hasLineOfSight, ClipContext.Block.COLLIDER, Wasser zählt nicht). Hier wird jeder Strahl exakt gegen
 * jeden Kollisionsquader geschnitten – nicht nur an Blockkanten abgefragt: So war am 2026-11-08 ein Nachtentwurf
 * durchgegangen, dessen Strahl innerhalb des Blocks über die Stufe stieg. Augenhöhen: Dorfbewohner 1,62, liegend 0,2
 * über der Liegehöhe (gemessen sinkt der Schlafende von +0,6875 auf die Bettoberkante +0,5625; beide geprüft),
 * Zombie 1,74 (EntityTypes in Paper 26.2, javap). Stehende Bewohner stehen überall auf den drei Betten, Zombies irgendwo
 * in ihrem Feld (Breite 0,6 → Mitte ±0,2). „Frei“ heißt: frei auch gegen um 0,005 vergrößerte Quader; „versperrt“:
 * versperrt auch gegen verkleinerte – Ränder zählen nicht als Treffer.
 */
class IronFarmSightTest {

    private static final double EPS = 0.005;
    private static final int BED_Y = 10;
    private static final double VILLAGER_EYE = 1.62;
    private static final double ZOMBIE_EYE = 1.74;
    private static final double ZOMBIE_REACH = Math.sqrt(2.04) - 0.6;   // Mob.DEFAULT_ATTACK_REACH

    private record Box(double x0, double y0, double z0, double x1, double y1, double z1) {

        Box grow(double d) {
            return new Box(x0 - d, y0 - d, z0 - d, x1 + d, y1 + d, z1 + d);
        }

        boolean overlaps(Box o) {
            return x0 < o.x1 && o.x0 < x1 && y0 < o.y1 && o.y0 < y1 && z0 < o.z1 && o.z0 < z1;
        }
    }

    /** Kollisionsform eines Blocks in Zellkoordinaten 0..1 (lokale Planrichtungen, wie Machines sie schreibt). */
    static List<double[]> shape(BlockSpec b) {
        String id = b.id();
        Map<String, String> p = b.props();
        if (b.isAir() || id.equals("water")) {
            return List.of();
        }
        if (id.endsWith("_slab")) {
            return switch (p.getOrDefault("type", "bottom")) {
                case "top" -> List.of(new double[] {0, 0.5, 0, 1, 1, 1});
                case "double" -> List.of(new double[] {0, 0, 0, 1, 1, 1});
                default -> List.of(new double[] {0, 0, 0, 1, 0.5, 1});
            };
        }
        if (id.endsWith("_bed")) {
            return List.of(new double[] {0, 0, 0, 1, 0.5625, 1});
        }
        if (id.endsWith("_trapdoor")) {
            double t = 3 / 16.0;
            if (!"true".equals(p.get("open"))) {
                return "top".equals(p.get("half")) ? List.of(new double[] {0, 1 - t, 0, 1, 1, 1})
                        : List.of(new double[] {0, 0, 0, 1, t, 1});
            }
            // Offen: Platte an der Seite gegenüber facing (TrapDoorBlock: NORTH → z 13..16).
            return switch (p.getOrDefault("facing", "front")) {
                case "back" -> List.of(new double[] {0, 0, 0, 1, 1, t});
                case "left" -> List.of(new double[] {1 - t, 0, 0, 1, 1, 1});
                case "right" -> List.of(new double[] {0, 0, 0, t, 1, 1});
                default -> List.of(new double[] {0, 0, 1 - t, 1, 1, 1});
            };
        }
        if (id.equals("daylight_detector")) {
            return List.of(new double[] {0, 0, 0, 1, 6 / 16.0, 1});
        }
        if (id.endsWith("campfire")) {
            return List.of(new double[] {0, 0, 0, 1, 7 / 16.0, 1});
        }
        if (id.equals("chest")) {
            return List.of(new double[] {1 / 16.0, 0, 1 / 16.0, 15 / 16.0, 14 / 16.0, 15 / 16.0});
        }
        if (id.endsWith("_carpet")) {
            return List.of(new double[] {0, 0, 0, 1, 1 / 16.0, 1});
        }
        // Stein, Glas, getöntes Glas, Trichter (Kollision fast voll): ganzer Block. Unbekanntes als voll zu nehmen
        // macht „frei“ strenger, nie lockerer.
        return List.of(new double[] {0, 0, 0, 1, 1, 1});
    }

    private static final class World {
        final Map<Pos, BlockSpec> cells = new HashMap<>();
        final List<Box> boxes = new ArrayList<>();

        World(Map<Pos, BlockSpec> source) {
            cells.putAll(source);
            cells.forEach((pos, b) -> {
                for (double[] s : shape(b)) {
                    boxes.add(new Box(pos.x() + s[0], pos.y() + s[1], pos.z() + s[2], pos.x() + s[3], pos.y() + s[4],
                            pos.z() + s[5]));
                }
            });
        }

        /** Trifft die Strecke a → b einen (um grow vergrößerten) Quader? */
        boolean blocked(double[] a, double[] b, double grow) {
            for (Box box : boxes) {
                if (segmentHits(a, b, grow == 0 ? box : box.grow(grow))) {
                    return true;
                }
            }
            return false;
        }

        /** Oberkante der Kollision in der Zelle (für Fußhöhen); 0, wenn leer. */
        double top(Pos p) {
            BlockSpec b = cells.get(p);
            double t = 0;
            if (b != null) {
                for (double[] s : shape(b)) {
                    t = Math.max(t, s[4]);
                }
            }
            return p.y() + t;
        }
    }

    /** Strecke gegen achsparallelen Quader (Slab-Verfahren). */
    static boolean segmentHits(double[] a, double[] b, Box box) {
        double t0 = 0;
        double t1 = 1;
        double[] lo = {box.x0(), box.y0(), box.z0()};
        double[] hi = {box.x1(), box.y1(), box.z1()};
        for (int i = 0; i < 3; i++) {
            double d = b[i] - a[i];
            if (Math.abs(d) < 1e-12) {
                if (a[i] <= lo[i] || a[i] >= hi[i]) {
                    return false;
                }
            } else {
                double u = (lo[i] - a[i]) / d;
                double v = (hi[i] - a[i]) / d;
                t0 = Math.max(t0, Math.min(u, v));
                t1 = Math.min(t1, Math.max(u, v));
                if (t0 >= t1) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Map<Pos, BlockSpec> farm(boolean night, boolean headBack) {
        Map<Pos, BlockSpec> out = new HashMap<>();
        for (Change c : new Machines().machine("eisenfarm").orElseThrow().module().cells()) {
            BlockSpec b = c.block();
            if (night && b.id().equals("iron_trapdoor")) {
                // Nachts geben die umgekehrten Sensoren darüber Strom: Die Eisenfalltür klappt auf.
                b = b.with("open", "true");
            }
            out.put(c.pos(), b);
        }
        // Betten, wie der Spieler (bzw. die Probe) sie setzt.
        for (int x = 1; x <= 3; x++) {
            out.put(new Pos(x, BED_Y, 11), bed(headBack ? "foot" : "head"));
            out.put(new Pos(x, BED_Y, 12), bed(headBack ? "head" : "foot"));
        }
        return out;
    }

    private static BlockSpec bed(String part) {
        try {
            return BlockSpec.parse("red_bed[part=" + part + "]");
        } catch (PlanException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Augen stehender Bewohner: Mitte überall auf den 3 × 2 Bettfeldern (Wände halten sie 0,3 vom Rand). */
    private static List<double[]> standingEyes() {
        List<double[]> eyes = new ArrayList<>();
        for (int i = 0; i <= 24; i++) {
            for (int k = 0; k <= 14; k++) {
                eyes.add(new double[] {1.3 + i * 0.1, BED_Y + 0.5625 + VILLAGER_EYE, 11.3 + k * 0.1});
            }
        }
        return eyes;
    }

    /** Augen liegender Bewohner am Kopfende (Mitte des Kopfteils), vor und nach dem Absinken auf die Bettoberkante. */
    private static List<double[]> lyingEyes(boolean headBack) {
        List<double[]> eyes = new ArrayList<>();
        double z = headBack ? 12.5 : 11.5;
        for (int x = 1; x <= 3; x++) {
            eyes.add(new double[] {x + 0.5, BED_Y + 0.5625 + 0.2, z});
            eyes.add(new double[] {x + 0.5, BED_Y + 0.6875 + 0.2, z});
        }
        return eyes;
    }

    /** Zombie-Augen im Feld (x, z): Füße auf der Kollisionsoberkante darunter, Mitte ±0,2. */
    private static List<double[]> zombieEyes(World w, Pos cell) {
        double feet = w.top(cell.add(0, -1, 0));
        List<double[]> eyes = new ArrayList<>();
        for (int i = 0; i <= 4; i++) {
            for (int k = 0; k <= 4; k++) {
                eyes.add(new double[] {cell.x() + 0.3 + i * 0.1, feet + ZOMBIE_EYE, cell.z() + 0.3 + k * 0.1});
            }
        }
        return eyes;
    }

    private static final Pos NIGHT_ZOMBIE = new Pos(3, 9, 14);
    private static final Pos DAY_ZOMBIE = new Pos(2, 11, 14);

    private static int clear(World w, List<double[]> from, List<double[]> to) {
        int n = 0;
        for (double[] a : from) {
            for (double[] b : to) {
                if (!w.blocked(a, b, EPS)) {
                    n++;
                }
            }
        }
        return n;
    }

    private static int seen(World w, List<double[]> from, List<double[]> to) {
        int n = 0;
        for (double[] a : from) {
            for (double[] b : to) {
                if (!w.blocked(a, b, -EPS)) {
                    n++;
                }
            }
        }
        return n;
    }

    @Test
    void zombieCellsArePlacedWhereTheProbeSummonsThem() {
        Map<Pos, BlockSpec> c = farm(false, true);
        World w = new World(c);
        // Nachtzombie: Füße 9,0, Auge 10,74 mitten im Schlitz 10,5..11,0; Tagzombie: Füße 10,5, Auge 12,24.
        assertEquals(9.0, w.top(NIGHT_ZOMBIE.add(0, -1, 0)), 1e-9);
        assertEquals(10.5, w.top(DAY_ZOMBIE.add(0, -1, 0)), 1e-9);
        for (Pos p : List.of(NIGHT_ZOMBIE, NIGHT_ZOMBIE.add(0, 1, 0), DAY_ZOMBIE, DAY_ZOMBIE.add(0, 1, 0))) {
            assertTrue(c.get(p) != null && c.get(p).isAir(), "Zombiefeld frei " + p);
        }
        List<String> summons = new Machines().machine("eisenfarm").orElseThrow().sequence().stream()
                .flatMap(s -> s.commands().stream()).filter(s -> s.startsWith("summon minecraft:zombie")).toList();
        assertEquals(List.of("summon minecraft:zombie {3,9,14} {PersistenceRequired:1b,IsBaby:0b}",
                "summon minecraft:zombie {2,11,14} {PersistenceRequired:1b,IsBaby:0b}"), summons);
    }

    @Test
    void lyingVillagersSeeTheNightZombieInBothBedDirections() {
        for (boolean night : new boolean[] {false, true}) {
            for (boolean headBack : new boolean[] {true, false}) {
                World w = new World(farm(night, headBack));
                List<double[]> from = lyingEyes(headBack);
                List<double[]> to = zombieEyes(w, NIGHT_ZOMBIE);
                assertEquals(from.size() * to.size(), clear(w, from, to),
                        "liegend → Nachtzombie, Kopf " + (headBack ? "hinten" : "vorn") + (night ? ", Nacht" : ", Tag"));
            }
        }
    }

    @Test
    void standingVillagersNeverSeeTheNightZombie() {
        // Sonst blieben sie nachts in Panik und schliefen nie wieder ein (gemessen 2026-11-08 an der alten Farm).
        for (boolean night : new boolean[] {false, true}) {
            World w = new World(farm(night, true));
            assertEquals(0, seen(w, standingEyes(), zombieEyes(w, NIGHT_ZOMBIE)), night ? "Nacht" : "Tag");
        }
    }

    @Test
    void byDayStandingVillagersSeeTheDayZombieFromEveryBedField() {
        World w = new World(farm(false, true));
        List<double[]> from = standingEyes();
        List<double[]> to = zombieEyes(w, DAY_ZOMBIE);
        assertEquals(from.size() * to.size(), clear(w, from, to));
    }

    @Test
    void atNightTheOpenTrapdoorsHideTheDayZombieFromEveryone() {
        for (boolean headBack : new boolean[] {true, false}) {
            World w = new World(farm(true, headBack));
            List<double[]> to = zombieEyes(w, DAY_ZOMBIE);
            assertEquals(0, seen(w, standingEyes(), to), "stehend");
            assertEquals(0, seen(w, lyingEyes(headBack), to), "liegend");
        }
    }

    @Test
    void everyTrapdoorHasAnInvertedDaylightSensorDirectlyAbove() {
        Map<Pos, BlockSpec> c = farm(false, true);
        int trapdoors = 0;
        for (Map.Entry<Pos, BlockSpec> e : c.entrySet()) {
            if (e.getValue().id().equals("iron_trapdoor")) {
                trapdoors++;
                BlockSpec above = c.get(e.getKey().add(0, 1, 0));
                assertEquals("daylight_detector", above.id(), "Sensor über " + e.getKey());
                assertEquals("true", above.props().get("inverted"), "umgekehrt (Strom bei Nacht) " + e.getKey());
                assertEquals("top", e.getValue().props().get("half"), "zu liegt sie oben " + e.getKey());
                assertTrue(Set.of("front", "back").contains(e.getValue().props().get("facing")),
                        "offen quer zur Blickrichtung " + e.getKey());
            }
        }
        assertEquals(3, trapdoors);
    }

    @Test
    void zombiesStayOutOfReachOfStandingAndSleepingVillagers() {
        World w = new World(farm(false, true));
        for (Pos z : List.of(NIGHT_ZOMBIE, DAY_ZOMBIE)) {
            double feet = w.top(z.add(0, -1, 0));
            for (double[] eye : zombieEyes(w, z)) {
                double r = 0.3 + ZOMBIE_REACH;
                Box attack = new Box(eye[0] - r, feet, eye[2] - r, eye[0] + r, feet + 1.95, eye[2] + r);
                for (double[] v : standingEyes()) {
                    Box villager = new Box(v[0] - 0.3, BED_Y + 0.5625, v[2] - 0.3, v[0] + 0.3, BED_Y + 0.5625 + 1.95,
                            v[2] + 0.3);
                    assertFalse(attack.overlaps(villager), "Schlag von " + z + " auf Stehenden bei " + v[0] + "," + v[2]);
                }
                for (boolean headBack : new boolean[] {true, false}) {
                    for (double[] v : lyingEyes(headBack)) {
                        Box sleeper = new Box(v[0] - 0.1, BED_Y + 0.5625, v[2] - 0.1, v[0] + 0.1, BED_Y + 0.8875,
                                v[2] + 0.1);
                        assertFalse(attack.overlaps(sleeper), "Schlag von " + z + " auf Schlafenden");
                    }
                }
            }
        }
    }

    @Test
    void sightRowIsTooLowForBabyZombiesAndOutOfJumpReach() {
        // Zwischen Kammer (z ≤ 12) und Käfigen (z ≥ 14) liegt die Sichtreihe z = 13. Jede freie Lücke darin ist
        // niedriger als ein Baby-Zombie (0,975), die Seitenpfosten sind voll, und die Zombies kommen mit einem Sprung
        // (1,25) nicht an die Unterkante ihres Schlitzes.
        World w = new World(farm(false, true));
        for (int x = 0; x <= 4; x++) {
            List<double[]> solid = new ArrayList<>();
            for (int y = 9; y <= 14; y++) {
                BlockSpec b = w.cells.get(new Pos(x, y, 13));
                if (b == null && y == 14) {
                    continue;                       // über dem Dach der Seitenpfosten: außerhalb der Käfige
                }
                assertTrue(b != null, "Sichtreihe lückenlos gebaut bei " + x + "," + y);
                for (double[] s : shape(b)) {
                    solid.add(new double[] {y + s[1], y + s[4]});
                }
            }
            solid.sort((a, b) -> Double.compare(a[0], b[0]));
            double reach = 9;
            for (double[] s : solid) {
                assertTrue(s[0] - reach < 0.975, "Lücke " + reach + ".." + s[0] + " in Spalte " + x);
                reach = Math.max(reach, s[1]);
            }
        }
        assertTrue(10.5 - w.top(NIGHT_ZOMBIE.add(0, -1, 0)) > 1.25, "Nachtzombie springt nicht in seinen Schlitz");
        assertTrue(12.0 - w.top(DAY_ZOMBIE.add(0, -1, 0)) > 1.25, "Tagzombie springt nicht in seinen Schlitz");
        // Nachts steht die offene Falltür quer über ihrer ganzen Zelle.
        for (Map.Entry<Pos, BlockSpec> e : farm(true, true).entrySet()) {
            if (e.getValue().id().equals("iron_trapdoor")) {
                double[] s = shape(e.getValue()).getFirst();
                assertTrue(s[0] == 0 && s[3] == 1 && s[1] == 0 && s[4] == 1, "Platte über die ganze Zelle " + e.getKey());
            }
        }
    }

    /**
     * Golems laichen nur auf der Wasserfläche. Regeln wie SpawnUtil.Strategy.LEGACY_IRON_GOLEM und
     * IronGolem.checkSpawnObstruction (Paper 26.2, javap): darunter „fest“ (Kollision ganzer Block oder Umfang im Mittel
     * ≥ 0,7292, BlockStateBase.calculateSolid) und kein Glas/getöntes Glas, Oberseite voll; das Feld selbst Luft oder
     * Wasser; die zwei Felder darüber ohne volle Kollision, ohne Flüssigkeit und keine Stromquelle (Tageslichtsensor!).
     * Gesucht wird bis 8 Felder seitlich und 6 hoch oder tief um jeden Bewohner (auf den Betten, Blockhöhe 10).
     */
    @Test
    void golemsSpawnOnlyOnTheWater() {
        for (boolean night : new boolean[] {false, true}) {
            Map<Pos, BlockSpec> c = farm(night, true);
            List<Pos> bad = new ArrayList<>();
            for (int x = 1 - 8; x <= 3 + 8; x++) {
                for (int z = 11 - 8; z <= 12 + 8; z++) {
                    for (int y = BED_Y - 7; y <= BED_Y + 7; y++) {
                        Pos p = new Pos(x, y, z);
                        if (golemCanSpawn(c, p)) {
                            boolean water = x >= 1 && x <= 3 && y == 9 && z >= 1 && z <= 7;
                            if (!water) {
                                bad.add(p);
                            }
                        }
                    }
                }
            }
            assertTrue(bad.isEmpty(), (night ? "Nacht" : "Tag") + ": Golemplätze außerhalb der Wasserfläche " + bad);
        }
    }

    private static boolean golemCanSpawn(Map<Pos, BlockSpec> c, Pos p) {
        BlockSpec below = c.get(p.add(0, -1, 0));
        if (below == null || below.isAir() || below.id().equals("water")) {
            return false;
        }
        if (Set.of("glass", "tinted_glass", "glass_pane", "ice", "frosted_ice", "glowstone", "sea_lantern", "beacon",
                "tnt", "conduit", "cactus", "cobweb").contains(below.id()) || below.id().endsWith("_stained_glass")) {
            return false;
        }
        List<double[]> s = shape(below);
        double[] bounds = bounds(s);
        double size = ((bounds[3] - bounds[0]) + (bounds[4] - bounds[1]) + (bounds[5] - bounds[2])) / 3.0;
        boolean solid = !s.isEmpty() && (size >= 0.7291666666666666 || bounds[4] - bounds[1] >= 1.0);
        boolean topFull = s.stream().anyMatch(q -> q[4] == 1.0 && q[0] == 0 && q[2] == 0 && q[3] == 1 && q[5] == 1);
        BlockSpec here = c.get(p);
        boolean hereFree = here == null || here.isAir() || here.id().equals("water");
        if (!solid || !topFull || !hereFree) {
            return false;
        }
        for (int dy = 1; dy <= 2; dy++) {
            BlockSpec up = c.get(p.add(0, dy, 0));
            if (up == null || up.isAir()) {
                continue;
            }
            if (up.id().equals("water") || up.id().equals("daylight_detector") || fullBlock(shape(up))) {
                return false;
            }
        }
        return true;
    }

    private static double[] bounds(List<double[]> s) {
        double[] b = {1, 1, 1, 0, 0, 0};
        for (double[] q : s) {
            for (int i = 0; i < 3; i++) {
                b[i] = Math.min(b[i], q[i]);
                b[i + 3] = Math.max(b[i + 3], q[i + 3]);
            }
        }
        return b;
    }

    private static boolean fullBlock(List<double[]> s) {
        return s.stream().anyMatch(q -> q[0] == 0 && q[1] == 0 && q[2] == 0 && q[3] == 1 && q[4] == 1 && q[5] == 1);
    }

    /**
     * Golemfläche (2026-11-08): Fließendes Wasser schiebt jeden Golem in die Grube; auf dem früheren Teich aus Quellen
     * stand einer über 80 s still (Spigot, Ost). Die Quellen liegen eine Lage über der ersten Reihe – der Plan baut von
     * unten, Boden und Wände der Fläche stehen also, bevor das Wasser fließt (sonst trockene Bahnen, 26.2-Kopie).
     */
    @Test
    void golemStreamFlowsFromSourcesAboveTheFirstRowIntoThePit() {
        Map<Pos, BlockSpec> c = farm(false, true);
        List<Pos> water = c.entrySet().stream().filter(e -> e.getValue().id().equals("water")).map(Map.Entry::getKey)
                .sorted(java.util.Comparator.comparingInt(Pos::x)).toList();
        assertEquals(List.of(new Pos(1, 10, 1), new Pos(2, 10, 1), new Pos(3, 10, 1)), water, "nur die drei Quellen");
        for (int x = 1; x <= 3; x++) {
            for (int z = 1; z <= 7; z++) {
                BlockSpec lane = c.get(new Pos(x, 9, z));
                assertTrue(lane == null || lane.isAir(), "Bahn frei, das Wasser kommt von oben " + x + "," + z);
                assertEquals("stone", c.get(new Pos(x, 8, z)).id(), "Boden unter der Bahn");
            }
            assertEquals("glass_pane", c.get(new Pos(0, 9, x + 2)).id(), "Wand links in einer tieferen Baulage");
            assertEquals("glass_pane", c.get(new Pos(4, 9, x + 2)).id(), "Wand rechts in einer tieferen Baulage");
            BlockSpec pit = c.get(new Pos(x, 9, 8));
            BlockSpec below = c.get(new Pos(x, 8, 8));
            assertTrue((pit == null || pit.isAir()) && (below == null || below.isAir()), "hinten fällt es in die Grube");
        }
    }

    /**
     * Golems (1,4 breit) erscheinen mitten im Feld. In den äußeren Bahnen ragten sie mit vollen Glaswänden 0,2 in die
     * Wand und blieben am nächsten Wandblock hängen (Spigot Ost 2026-11-08, über 80 s). Glasscheiben sind nur in der
     * Mitte ihres Felds fest (7/16..9/16): An der ganzen Golemstrecke – Fläche und Grube – bleibt Luft dazwischen.
     */
    @Test
    void golemsInTheOuterLanesDoNotTouchTheSideWalls() {
        Map<Pos, BlockSpec> c = farm(false, true);
        for (int z = 1; z <= 9; z++) {
            for (int y = z <= 7 ? 9 : 2; y <= 10; y++) {
                for (int wall : new int[] {0, 4}) {
                    BlockSpec b = c.get(new Pos(wall, y, z));
                    assertEquals("glass_pane", b.id(), "dünne Wand bei " + wall + "," + y + "," + z);
                }
            }
        }
        double golemHalf = 0.7;
        assertTrue(1 + 0.5 - golemHalf > 0 + 9 / 16.0, "linke Bahn frei von der Scheibe");
        assertTrue(3 + 0.5 + golemHalf < 4 + 7 / 16.0, "rechte Bahn frei von der Scheibe");
    }

    @Test
    void probeWaitsForRealSleepAndNeverFakesIt() {
        // Vorher gab die Probe last_slept in der Zukunft vor – damit blieb verborgen, dass die alte Farm ihre Bewohner
        // nie wieder schlafen ließ. Jetzt: Betten belegt sehen, bevor Zombies kommen, und nach einem Tag in Panik noch
        // einmal (die Gegenprobe ohne Sensoren scheitert genau dort).
        List<String> all = new Machines().machine("eisenfarm").orElseThrow().sequence().stream()
                .flatMap(s -> s.commands().stream()).toList();
        assertFalse(all.stream().anyMatch(s -> s.contains("last_slept")), "kein vorgegebener Schlaf");
        assertEquals(2, all.stream().filter(s -> s.startsWith("#schlaf ")).count());
        int firstSleep = all.indexOf(all.stream().filter(s -> s.startsWith("#schlaf ")).findFirst().orElseThrow());
        int firstZombie = all.indexOf(all.stream().filter(s -> s.startsWith("summon minecraft:zombie")).findFirst()
                .orElseThrow());
        assertTrue(firstSleep < firstZombie, "erst schlafen, dann Zombies");
    }
}
