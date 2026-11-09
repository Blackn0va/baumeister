package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.blackn0va.baumeister.plan.CompiledBuild.Action;
import com.blackn0va.baumeister.plan.CompiledBuild.Change;
import com.blackn0va.baumeister.plan.Orientation.Cardinal;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Prüfungen für die Bauformen (Structures), gemeinsam für StructuresTest und QuickFormsTest: Halt, angehängte Blöcke,
 * Begehbarkeit. Bewusst ohne Server – ein einfaches Laufmodell (2 hoch frei, fester Boden, 1 Block springen, bis 3
 * fallen, Leitern klettern) reicht, um ein zugemauertes Tor oder einen Wehrgang ohne Zugang zu finden.
 */
final class FormChecks {

    /** Testkatalog plus das Zubehör, das die Formen selbst wählen. */
    static final Set<String> EXTRA = Set.of("stone_brick_wall", "grass_block", "dirt", "coarse_dirt", "poppy",
            "dandelion", "cornflower", "oxeye_daisy", "allium", "azure_bluet", "oak_fence_gate", "spruce_fence_gate",
            "spruce_leaves", "birch_log", "birch_leaves", "birch_planks", "sandstone", "sandstone_wall", "cherry_log",
            "cherry_leaves", "oak_planks");
    static final BlockCatalog CATALOG = id -> TestCatalog.KNOWN.contains(id) || EXTRA.contains(id);
    /** Config-Vorgaben (bau.max-blöcke 150 000, bau.max-ausdehnung 192). */
    static final PlanCompiler.Limits DEFAULTS = new PlanCompiler.Limits(150_000, 192);
    static final String[] SIDES = {"front", "left", "back", "right"};

    private FormChecks() {
    }

    static CompiledBuild compile(String stepJson) throws PlanException {
        return compiler().compile(PlanParser.parse("{\"steps\":[" + stepJson + "]}"));
    }

    static PlanCompiler compiler() {
        return new PlanCompiler(CATALOG, "stone", DEFAULTS);
    }

    static Map<Pos, BlockSpec> state(CompiledBuild b) {
        Map<Pos, BlockSpec> m = new HashMap<>();
        for (Action a : b.actions()) {
            for (Change c : a.changes()) {
                m.put(c.pos(), c.block());
            }
        }
        return m;
    }

    static Map<Pos, BlockSpec> solidOnly(Map<Pos, BlockSpec> s) {
        Map<Pos, BlockSpec> out = new HashMap<>();
        s.forEach((p, b) -> {
            if (!b.isAir()) {
                out.put(p, b);
            }
        });
        return out;
    }

    static int minY(Map<Pos, BlockSpec> s) {
        return solidOnly(s).keySet().stream().mapToInt(Pos::y).min().orElseThrow();
    }

    static int maxY(Map<Pos, BlockSpec> s) {
        return solidOnly(s).keySet().stream().mapToInt(Pos::y).max().orElseThrow();
    }

    /** Keine schwebenden Blöcke: Jeder Block hängt über Nachbarn (6er) an der untersten Schicht des Baus. */
    static void assertHolds(Map<Pos, BlockSpec> s, String label) {
        Map<Pos, BlockSpec> solid = solidOnly(s);
        int low = minY(s);
        Set<Pos> seen = new HashSet<>();
        ArrayDeque<Pos> queue = new ArrayDeque<>();
        for (Pos p : solid.keySet()) {
            if (p.y() == low) {
                seen.add(p);
                queue.add(p);
            }
        }
        int[][] six = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty()) {
            Pos p = queue.poll();
            for (int[] d : six) {
                Pos n = p.add(d[0], d[1], d[2]);
                if (solid.containsKey(n) && seen.add(n)) {
                    queue.add(n);
                }
            }
        }
        for (Map.Entry<Pos, BlockSpec> e : solid.entrySet()) {
            if (!seen.contains(e.getKey())) {
                fail(label + ": " + e.getValue().id() + " bei " + e.getKey() + " schwebt (keine Verbindung zur "
                        + "untersten Schicht y=" + low + ")");
            }
        }
    }

    /**
     * Angehängtes hat Halt: Leiter am Block hinter ihr (Gegenrichtung von facing), Hängelaterne an der Decke,
     * stehende Laterne und Blumen auf festem Grund. Lokal und nach dem Drehen in alle vier Weltrichtungen – dort mit
     * den Himmelsrichtungen der Orientation, so wie BuildService den Plan setzt.
     */
    static void assertAttached(Map<Pos, BlockSpec> local, String label) {
        checkAttached(local, label + " lokal", FormChecks::localVector);
        for (Cardinal c : Cardinal.values()) {
            Orientation o = new Orientation(c);
            Map<Pos, BlockSpec> world = new HashMap<>();
            local.forEach((p, b) -> world.put(o.toWorldOffset(p), o.toWorld(b)));
            checkAttached(world, label + " Blick " + c, FormChecks::worldVector);
        }
    }

    private static void checkAttached(Map<Pos, BlockSpec> s, String label,
            java.util.function.Function<String, int[]> vector) {
        for (Map.Entry<Pos, BlockSpec> e : s.entrySet()) {
            Pos p = e.getKey();
            BlockSpec b = e.getValue();
            if (b.id().equals("ladder")) {
                int[] v = vector.apply(b.props().get("facing"));
                Pos support = p.add(-v[0], 0, -v[2]);
                assertTrue(isFirm(s.get(support)), label + ": Leiter bei " + p + " (facing "
                        + b.props().get("facing") + ") hängt an " + s.get(support) + " bei " + support);
            } else if (b.id().equals("lantern")) {
                boolean hanging = "true".equals(b.props().get("hanging"));
                Pos support = hanging ? p.up() : p.down();
                assertTrue(s.get(support) != null && !s.get(support).isAir(), label + ": Laterne bei " + p
                        + " ohne Halt (" + (hanging ? "Decke" : "Boden") + ")");
            } else if (FormChecks.isFlower(b.id())) {
                BlockSpec below = s.get(p.down());
                assertTrue(below != null && (below.id().contains("dirt") || below.id().equals("grass_block")),
                        label + ": Blume bei " + p + " auf " + below);
            }
        }
    }

    static boolean isFlower(String id) {
        return Set.of("poppy", "dandelion", "cornflower", "oxeye_daisy", "allium", "azure_bluet").contains(id);
    }

    private static boolean isFirm(BlockSpec b) {
        return b != null && !b.isAir() && !b.id().equals("ladder") && !b.id().equals("lantern") && !isFlower(b.id());
    }

    static int[] localVector(String dir) {
        return switch (dir) {
            case "front" -> new int[] {0, 0, -1};
            case "back" -> new int[] {0, 0, 1};
            case "left" -> new int[] {-1, 0, 0};
            case "right" -> new int[] {1, 0, 0};
            default -> throw new IllegalArgumentException("keine lokale Richtung: " + dir);
        };
    }

    static int[] worldVector(String dir) {
        Cardinal c = Cardinal.parse(dir);
        return new int[] {c.dx, 0, c.dz};
    }

    // ---------------------------------------------------------------- Laufen

    /**
     * Laufmodell: Gelände (nicht gebaut) liegt bei y ≤ ground. Eine Zelle ist begehbar, wenn sie und die darüber frei
     * sind und darunter fester Boden liegt (Zaun und Mauer zählen nicht: 1,5 hoch, darauf springt man nicht) oder sie
     * eine Leiter ist. Schritte: waagrecht, 1 hoch springen, bis 3 fallen, auf Leitern hoch und runter.
     */
    static final class Walker {
        private final Map<Pos, BlockSpec> s;
        private final int ground;
        private final boolean gatesOpen;
        private final Predicate<Pos> allowed;

        Walker(Map<Pos, BlockSpec> s, int ground, boolean gatesOpen, Predicate<Pos> allowed) {
            this.s = s;
            this.ground = ground;
            this.gatesOpen = gatesOpen;
            this.allowed = allowed;
        }

        boolean blocked(Pos p) {
            BlockSpec b = s.get(p);
            if (b == null) {
                return p.y() <= ground;
            }
            if (b.isAir() || b.id().equals("ladder") || b.id().equals("lantern") || isFlower(b.id())) {
                return false;
            }
            return !(gatesOpen && b.id().endsWith("_fence_gate"));
        }

        private boolean firmFloor(Pos p) {
            BlockSpec b = s.get(p);
            if (b == null) {
                return p.y() <= ground;
            }
            String id = b.id();
            return blocked(p) && !id.endsWith("_fence") && !id.endsWith("_wall") && !id.endsWith("_fence_gate");
        }

        boolean ladder(Pos p) {
            BlockSpec b = s.get(p);
            return b != null && b.id().equals("ladder");
        }

        boolean standable(Pos p) {
            return allowed.test(p) && !blocked(p) && !blocked(p.up()) && (ladder(p) || firmFloor(p.down()));
        }

        Set<Pos> reach(Collection<Pos> starts) {
            Set<Pos> seen = new HashSet<>();
            ArrayDeque<Pos> queue = new ArrayDeque<>();
            for (Pos p : starts) {
                if (standable(p) && seen.add(p)) {
                    queue.add(p);
                }
            }
            int[][] four = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            while (!queue.isEmpty()) {
                Pos p = queue.poll();
                for (int[] d : four) {
                    Pos n = p.add(d[0], 0, d[1]);
                    if (standable(n)) {
                        visit(n, seen, queue);
                    } else if (standable(n.up()) && !blocked(p.up().up())) {
                        visit(n.up(), seen, queue);
                    } else if (!blocked(n) && !blocked(n.up())) {
                        for (int k = 1; k <= 3; k++) {
                            Pos f = n.add(0, -k, 0);
                            if (blocked(f)) {
                                break;
                            }
                            if (standable(f)) {
                                visit(f, seen, queue);
                                break;
                            }
                        }
                    }
                }
                if (ladder(p) && !blocked(p.up()) && (ladder(p.up()) || standable(p.up()))) {
                    visit(p.up(), seen, queue);
                }
                if (ladder(p) && (ladder(p.down()) || standable(p.down()))) {
                    visit(p.down(), seen, queue);
                }
            }
            return seen;
        }

        private void visit(Pos p, Set<Pos> seen, ArrayDeque<Pos> queue) {
            if (allowed.test(p) && seen.add(p)) {
                queue.add(p);
            }
        }
    }

    static void assertNoCell(Map<Pos, BlockSpec> s, Predicate<Pos> outside, String label) {
        for (Map.Entry<Pos, BlockSpec> e : s.entrySet()) {
            assertFalse(outside.test(e.getKey()), label + ": " + e.getValue().id() + " bei " + e.getKey()
                    + " liegt außerhalb der Grenzen");
        }
    }
}
