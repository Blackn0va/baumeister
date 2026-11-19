package com.blackn0va.baumeister.bilder;

import com.blackn0va.baumeister.plan.BlockSpec;
import com.blackn0va.baumeister.plan.CompiledBuild;
import com.blackn0va.baumeister.plan.Pos;
import com.blackn0va.baumeister.plan.TunnelOrder;
import com.blackn0va.baumeister.plan.TunnelShape;
import com.blackn0va.baumeister.plan.TunnelStyle;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;

/**
 * Szenen für das README-Bild „Tunnel, Treppen und Brücken“ (2026-11-19, Wunsch: Repo mit Bildern verständlicher):
 * echte Pläne aus TunnelShape.plan – derselbe Weg wie im Spiel, nur auf einem gedachten Gelände statt der Welt. Der
 * Fels drumherum ist Kulisse und bei Tunnel und Treppen auf der Ostseite aufgeschnitten (x ≤ 0), sonst sähe man nur
 * Stein. Gezählt werden nur die Blöcke, die der Baumeister selbst setzt.
 *
 * Threads: einer. Fehler: Ausnahme, wenn ein Plan leer bleibt (dann stimmt das Gelände nicht).
 */
final class TunnelBilder {

    /** Eine fertige Szene: was ins Bild kommt und wie viele Blöcke davon der Baumeister gesetzt hat. */
    record Scene(String title, String sub, Map<Pos, BlockSpec> cells, int placed, int slices) {
    }

    /**
     * Gelände wie TunnelTestSite (Testpaket plan, dort paketprivat): Stein bis top(z), darüber Luft, Wasser je
     * Stelle.
     */
    private static final class Terrain implements TunnelShape.Site {

        final Set<Pos> water = new HashSet<>();
        IntUnaryOperator top = z -> 40;

        @Override
        public TunnelShape.Ground at(Pos p) {
            if (water.contains(p)) {
                return TunnelShape.Ground.FLUID;
            }
            return sky(p) ? TunnelShape.Ground.OPEN : TunnelShape.Ground.SOLID;
        }

        @Override
        public boolean sky(Pos p) {
            return p.y() > top.applyAsInt(p.z());
        }

        @Override
        public String id(Pos p) {
            return switch (at(p)) {
                case SOLID -> "stone";
                case FLUID -> "water";
                default -> "air";
            };
        }

        Terrain water(int x0, int x1, int y0, int y1, int z0, int z1) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        water.add(new Pos(x, y, z));
                    }
                }
            }
            return this;
        }
    }

    private TunnelBilder() {
    }

    static List<Scene> scenes() {
        return List.of(rock(), tube(), stairsUp(), stairsDown(), bridge(), deep());
    }

    /** Was der Plan setzt (ohne Luft) und welche Stellen er ausräumt. */
    private record Built(Map<Pos, BlockSpec> blocks, Set<Pos> dug, int slices) {
    }

    private static Built built(TunnelShape.Result r) {
        if (r.empty()) {
            throw new IllegalStateException("Plan leer: " + r.stop());
        }
        Map<Pos, BlockSpec> blocks = new HashMap<>();
        Set<Pos> dug = new HashSet<>();
        for (CompiledBuild.Action a : r.build().actions()) {
            for (CompiledBuild.Change c : a.changes()) {
                if (air(c.block().id())) {
                    blocks.remove(c.pos());
                    dug.add(c.pos());
                } else {
                    blocks.put(c.pos(), c.block());
                    dug.remove(c.pos());
                }
            }
        }
        return new Built(blocks, dug, r.slices());
    }

    private static boolean air(String id) {
        String n = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        return n.equals("air") || n.equals("cave_air");
    }

    /**
     * Kulisse: Fels in einem Quader, ohne was der Plan ausgeräumt oder selbst gesetzt hat; oben Gras, wo Himmel
     * darüber ist. keep schneidet auf (Ostseite weg).
     */
    private static Map<Pos, BlockSpec> scenery(Terrain t, Built b, int[] box, String rock, Predicate<Pos> keep) {
        Map<Pos, BlockSpec> out = new HashMap<>();
        for (int x = box[0]; x <= box[1]; x++) {
            for (int y = box[2]; y <= box[3]; y++) {
                for (int z = box[4]; z <= box[5]; z++) {
                    Pos p = new Pos(x, y, z);
                    if (!keep.test(p) || b.dug().contains(p) || b.blocks().containsKey(p)) {
                        continue;
                    }
                    TunnelShape.Ground g = t.at(p);
                    Pos above = new Pos(x, y + 1, z);
                    if (g == TunnelShape.Ground.SOLID) {
                        String id = t.water.contains(above) ? "sand" : t.sky(above) ? "grass_block"
                                : y >= t.top.applyAsInt(z) - 2 ? "dirt" : rock;
                        out.put(p, BlockSpec.of(id));
                    } else if (g == TunnelShape.Ground.FLUID && !t.water.contains(above)) {
                        out.put(p, BlockSpec.of("water"));  // nur die Wasseroberfläche – darunter sähe man nichts
                    }
                }
            }
        }
        return out;
    }

    private static Map<Pos, BlockSpec> merge(Map<Pos, BlockSpec> scenery, Built b, Predicate<Pos> keep) {
        Map<Pos, BlockSpec> cells = new HashMap<>(scenery);
        b.blocks().forEach((p, s) -> {
            if (keep.test(p)) {
                cells.put(p, s);
            }
        });
        return cells;
    }

    private static final Predicate<Pos> WEST_HALF = p -> p.x() <= 0;

    /**
     * Tunnel offen von oben: westliche Hälfte, und über dem Gang (ab Deckenhöhe) nur Fels hinter der Westwand. Mit
     * Decke verdeckte sie Boden, Sockel und Gurtbögen fast ganz (erster Entwurf, 2026-11-19).
     */
    private static Predicate<Pos> openTop(TunnelOrder.Size size) {
        return p -> p.x() <= 0 && (p.y() < size.height || p.x() <= -(size.half + 1));
    }

    private static TunnelStyle style(int y, TunnelOrder.Kind kind, TunnelOrder.Size size) {
        return TunnelStyle.forPlace(TunnelStyle.Realm.OVERWORLD, y, null, kind, size);
    }

    /**
     * Tunnel im Fels, Normalgröße, 14 Abschnitte: Sockel, Mittelstreifen, Gurtbögen, Laternen alle 6. Kurz gehalten –
     * mit 24 Abschnitten wurden die Blöcke im Bild zu klein für Sockel und Streifen (erster Entwurf).
     */
    private static Scene rock() {
        Terrain t = new Terrain();
        Built b = built(TunnelShape.plan(TunnelOrder.Kind.TUNNEL, TunnelOrder.Size.NORMAL, 14, 192,
                style(30, TunnelOrder.Kind.TUNNEL, TunnelOrder.Size.NORMAL), t));
        Predicate<Pos> cut = openTop(TunnelOrder.Size.NORMAL);
        Map<Pos, BlockSpec> sc = scenery(t, b, new int[] {-5, 0, -3, 5, 0, 14}, "stone", cut);
        return new Scene("Tunnel im Fels", "„grab einen Tunnel 14“ · aufgeschnitten", merge(sc, b, cut),
                b.blocks().size(), b.slices());
    }

    /**
     * Über einen See läuft der Tunnel auf derselben Linie und Höhe als Röhre weiter – Fenster, Pfeiler bis zum
     * Grund.
     */
    private static Scene tube() {
        Terrain t = new Terrain();
        t.top = z -> z < 8 || z > 43 ? 30 : -14;
        t.water(-12, 12, -13, -5, 8, 43);
        Built b = built(TunnelShape.plan(TunnelOrder.Kind.TUNNEL, TunnelOrder.Size.NORMAL, 52, 192,
                style(30, TunnelOrder.Kind.TUNNEL, TunnelOrder.Size.NORMAL), t));
        Map<Pos, BlockSpec> sc = scenery(t, b, new int[] {-9, 9, -14, 7, 0, 51}, "stone", p -> true);
        return new Scene("Röhre über den See", "„grab den Tunnel weiter“ – gleiche Linie, gleiche Höhe",
                merge(sc, b, p -> true), b.blocks().size(), b.slices());
    }

    /** Treppe nach oben bis ans Tageslicht, aufgeschnitten. */
    private static Scene stairsUp() {
        Terrain t = new Terrain();
        t.top = z -> 14;
        Built b = built(TunnelShape.plan(TunnelOrder.Kind.STAIRS_UP, TunnelOrder.Size.NORMAL, 0, 192,
                style(30, TunnelOrder.Kind.STAIRS_UP, TunnelOrder.Size.NORMAL), t));
        int end = b.blocks().keySet().stream().mapToInt(Pos::z).max().orElse(20) + 2;
        Map<Pos, BlockSpec> sc = scenery(t, b, new int[] {-6, 0, -3, 14, -2, end}, "stone", WEST_HALF);
        return new Scene("Treppe nach oben", "„bau mir eine Treppe nach oben“ · bis ans Tageslicht",
                merge(sc, b, WEST_HALF), b.blocks().size(), b.slices());
    }

    /** Treppe nach unten, 12 Stufen von der Wiese aus, aufgeschnitten. */
    private static Scene stairsDown() {
        Terrain t = new Terrain();
        t.top = z -> -1;
        Built b = built(TunnelShape.plan(TunnelOrder.Kind.STAIRS_DOWN, TunnelOrder.Size.NORMAL, 12, 192,
                style(30, TunnelOrder.Kind.STAIRS_DOWN, TunnelOrder.Size.NORMAL), t));
        int end = b.blocks().keySet().stream().mapToInt(Pos::z).max().orElse(14) + 2;
        Map<Pos, BlockSpec> sc = scenery(t, b, new int[] {-6, 0, -18, -1, -2, end}, "stone", WEST_HALF);
        return new Scene("Treppe nach unten", "„/bm treppe runter 12“ · Stufe für Stufe tiefer",
                merge(sc, b, WEST_HALF), b.blocks().size(), b.slices());
    }

    /** Brücke über einen Fluss: Balustrade mit Pfosten alle 3, Laternen, Pfeiler mit Kapitell bis zum Grund. */
    private static Scene bridge() {
        Terrain t = new Terrain();
        t.top = z -> z < 4 || z > 40 ? -1 : -17;
        t.water(-12, 12, -16, -6, 4, 40);
        Built b = built(TunnelShape.plan(TunnelOrder.Kind.BRIDGE, TunnelOrder.Size.NORMAL, 44, 192,
                style(30, TunnelOrder.Kind.BRIDGE, TunnelOrder.Size.NORMAL), t));
        Map<Pos, BlockSpec> sc = scenery(t, b, new int[] {-8, 8, -17, -1, -3, 46}, "stone", p -> true);
        return new Scene("Brücke übers Wasser", "„bau eine Brücke übers Meer“ · Pfeiler bis zum Grund",
                merge(sc, b, p -> true), b.blocks().size(), b.slices());
    }

    /** Großer Tunnel tief unten: dort Tiefenschiefer statt Steinziegel – die Palette folgt der Gegend. */
    private static Scene deep() {
        Terrain t = new Terrain();
        Built b = built(TunnelShape.plan(TunnelOrder.Kind.TUNNEL, TunnelOrder.Size.LARGE, 14, 192,
                style(-40, TunnelOrder.Kind.TUNNEL, TunnelOrder.Size.LARGE), t));
        Predicate<Pos> cut = openTop(TunnelOrder.Size.LARGE);
        Map<Pos, BlockSpec> sc = scenery(t, b, new int[] {-6, 0, -3, 6, 0, 14}, "deepslate", cut);
        return new Scene("Großer Tunnel, Tiefenschiefer", "„grab einen großen Tunnel“ in der Tiefe · aufgeschnitten",
                merge(sc, b, cut), b.blocks().size(), b.slices());
    }
}
