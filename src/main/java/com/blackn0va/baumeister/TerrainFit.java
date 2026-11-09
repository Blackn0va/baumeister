package com.blackn0va.baumeister;

import com.blackn0va.baumeister.earth.HeightField;
import com.blackn0va.baumeister.osm.CityModel;
import com.blackn0va.baumeister.osm.CityPlanner;
import com.blackn0va.baumeister.osm.CityPlanner.Cell;
import com.blackn0va.baumeister.osm.CityPlanner.Section;
import com.blackn0va.baumeister.osm.CityPlanner.Sign;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Höhenrechnung des Stadtbaus auf Gelände: Bauabschnitt mit relativem y → Zellen mit absolutem y. Rein, ohne Welt –
 * testbar mit einem erfundenen Höhenfeld.
 *
 * Vorher (bis 2026-10-28) setzte CityJob jede Zelle auf EINE feste Höhe (baseY + y): richtig in der Flachwelt, auf
 * echtem Gelände dagegen hingen Straßen am Hang in der Luft oder verschwanden im Berg, Häuser schwebten talseitig und
 * steckten bergseitig im Fels. Jetzt:
 *   - Boden (Straßen, Wiesen, Wasser, Bäume) folgt dem Gelände Säule für Säule: y = Oberkante(x, z) + y. Ein Baum
 *     bezieht sich auf die Säule seines Stamms (Cell.anchor), sonst zerfiele die Krone am Hang in Stufen.
 *   - Ein Gebäude bekommt EINE Basis = höchste Oberkante unter seinem Grundriss. Folgte es den Säulen, stünden die
 *     Wände versetzt und die Geschossdecken als Treppe. Die Höchste statt der mittleren: So steckt kein Raum im Berg;
 *     talseitig füllt ein Sockel aus Steinziegeln (PLINTH) die Lücke zwischen Gelände und Erdgeschossboden – nur
 *     unter Zellen der Bodenschicht (y = 0), ein Turmhelm mit Unterkante über dem Schiff bekommt keinen Sockel.
 * Säulen ohne Gelände (Integer.MIN_VALUE) fallen weg; ein Gebäude ganz ohne Gelände darunter entfällt.
 *
 * Threads: rein, beliebig. Fehler: keine.
 */
final class TerrainFit {

    /** Materialindex für Sockelzellen – liegt außerhalb jeder Palette, CityJob setzt dort stone_bricks. */
    static final int PLINTH = -1;
    static final String PLINTH_BLOCK = "stone_bricks";

    private TerrainFit() {
    }

    /**
     * Paletten-Indizes für die Küstenmarker des Weltbaus (CityPlanner.SEA_MARK/BEACH_MARK). enabled = false: Marker
     * fallen weg (Küste verworfen, siehe shoreTrusted).
     */
    record Shore(int water, int sand, int air, int stone, boolean enabled) {

        static Shore of(List<String> palette, boolean enabled) {
            return new Shore(palette.indexOf("water"), palette.indexOf("sand"), palette.indexOf("air"),
                    palette.indexOf("stone"), enabled);
        }
    }

    /** Tiefer schüttet der Weltbau keine Grube auf (meerestiefe-max der Erdwelt ist 200). */
    static final int MAX_FILL = 220;

    /**
     * Höchstens so viele Blöcke über dem Meeresspiegel trägt der Weltbau Land ab, um Meer zu machen. Darüber bleibt die
     * Säule stehen: Ein Steilufer oder eine falsch erkannte Landzunge wird nicht zur Grube. Cockle Bay (Sydney) liegt im
     * Höhenmodell 11,7 m hoch – das muss weg; ein 40-m-Kliff nicht.
     */
    static final int MAX_CARVE = 24;
    /** Strand nur, wo das Land höchstens so hoch über dem Meer liegt; tiefer liegendes Land bis so tief wird aufgeschüttet. */
    static final int BEACH_RISE = 3;

    /**
     * Passt die Küste einer Etappe zum Gelände? Gegen eine verdrehte Maske (Land und Meer vertauscht): Dann lägen viele
     * „Meer“-Säulen weit über dem Meer (seaHigh: Stadt auf Land) oder viele „Strand“-Säulen tief darunter (beachDeep:
     * echtes Meer). Eine einzige falsche Laufrichtung flutete sonst eine ganze Hafenstadt. Grenzen geschätzt, nicht an
     * einer verdrehten Karte gemessen: ein Drittel bzw. die Hälfte, ab 50 bzw. 20 Säulen.
     */
    static boolean shoreTrusted(int seaCount, int seaHigh, int beachCount, int beachDeep) {
        if (seaCount >= 50 && seaHigh * 3 > seaCount) {
            return false;
        }
        return !(beachCount >= 20 && beachDeep * 2 > beachCount);
    }

    /** Zählt für shoreTrusted: Säulen der Marker in s, „hoch“ = mehr als 15 über, „tief“ = mehr als 2 unter dem Meer. */
    static void countShore(Section s, HeightField h, int baseX, int baseZ, int[] counts) {
        for (Cell c : s.cells()) {
            boolean sea = CityPlanner.isSeaMark(c.mat());
            if (!sea && c.mat() != CityPlanner.BEACH_MARK) {
                continue;
            }
            int x = baseX + c.x();
            int z = baseZ + c.z();
            int top = h.surfaceY(x, z);
            if (top == Integer.MIN_VALUE) {
                continue;
            }
            int lvl = h.seaLevelY(x, z);
            if (sea) {
                counts[0]++;
                counts[1] += top - lvl > 15 ? 1 : 0;
            } else {
                counts[2]++;
                counts[3] += top - lvl < -2 ? 1 : 0;
            }
        }
    }

    /** Wie {@link #fit(Section, HeightField, int, int)}, dazu Küstenmarker; shore null = Marker fallen weg. */
    static List<Cell> fit(Section s, HeightField h, int baseX, int baseZ, Shore shore) {
        if (s.building()) {
            return fit(s, h, baseX, baseZ);
        }
        List<Cell> out = new ArrayList<>(s.cells().size());
        List<Cell> rest = new ArrayList<>(s.cells().size());
        // Zuerst Gruben am Ufer (CityPlanner.FILL_MARK): aufschütten und die neue Oberkante merken – alle anderen Zellen
        // dieser Säule (Strand, Wiese, Weg) stehen danach darauf statt auf dem Grubenboden.
        Map<Long, Integer> lifted = new HashMap<>();
        for (Cell c : s.cells()) {
            if (c.mat() != CityPlanner.FILL_MARK || shore == null || !shore.enabled()) {
                continue;
            }
            int x = baseX + c.x();
            int z = baseZ + c.z();
            int top = h.surfaceY(x, z);
            int lvl = h.seaLevelY(x, z);
            if (top == Integer.MIN_VALUE || top >= lvl - BEACH_RISE || lvl - top > MAX_FILL) {
                continue;
            }
            for (int y = top + 1; y <= lvl; y++) {
                out.add(new Cell(c.x(), y, c.z(), shore.stone()));
            }
            out.add(new Cell(c.x(), lvl + 1, c.z(), shore.sand()));
            lifted.put(key(x, z), lvl + 1);
        }
        HeightField view = lifted.isEmpty() ? h : new HeightField() {
            @Override
            public int surfaceY(int x, int z) {
                Integer t = lifted.get(key(x, z));
                return t != null ? t : h.surfaceY(x, z);
            }

            @Override
            public int seaLevelY(int x, int z) {
                return h.seaLevelY(x, z);
            }
        };
        for (Cell c : s.cells()) {
            if (c.mat() == CityPlanner.FILL_MARK) {
                continue;
            }
            boolean sea = CityPlanner.isSeaMark(c.mat());
            if (!sea && c.mat() != CityPlanner.BEACH_MARK) {
                rest.add(c);
                continue;
            }
            if (shore == null || !shore.enabled()) {
                continue;
            }
            int x = baseX + c.x();
            int z = baseZ + c.z();
            int top = view.surfaceY(x, z);
            if (top == Integer.MIN_VALUE) {
                continue;
            }
            int lvl = view.seaLevelY(x, z);
            if (sea) {
                if (top - lvl > MAX_CARVE) {
                    continue;
                }
                int bed = lvl - CityPlanner.seaDepth(c.mat());
                if (top < bed) {
                    // Echter Meeresboden liegt schon tiefer (Bathymetrie): nur bis zum Meeresspiegel füllen.
                    for (int y = top + 1; y <= lvl; y++) {
                        out.add(new Cell(c.x(), y, c.z(), shore.water()));
                    }
                    continue;
                }
                out.add(new Cell(c.x(), bed, c.z(), shore.sand()));
                for (int y = bed + 1; y <= lvl; y++) {
                    out.add(new Cell(c.x(), y, c.z(), shore.water()));
                }
                for (int y = lvl + 1; y <= top; y++) {
                    out.add(new Cell(c.x(), y, c.z(), shore.air()));
                }
            } else {
                int rise = top - lvl;
                if (rise > BEACH_RISE || rise < -BEACH_RISE) {
                    continue;
                }
                // Strand: oberste drei Lagen Sand; liegt die Säule unter dem Meer (Höhenmodell), bis 1 über dem Meer
                // aufschütten – die Küstenlinie sagt Land.
                int from = top - 2;
                int to = Math.max(top, lvl + 1);
                for (int y = from; y <= to; y++) {
                    out.add(new Cell(c.x(), y, c.z(), shore.sand()));
                }
            }
        }
        out.addAll(fit(new Section(s.label(), s.cx(), s.cz(), rest, false), view, baseX, baseZ));
        return out;
    }

    /**
     * Zellen des Abschnitts mit absolutem y. baseX/baseZ: Blockkoordinate des Planursprungs in der Welt. Sockelzellen
     * stehen vor den Gebäudezellen und sind nach y sortiert (von unten nach oben wie der Rest).
     */
    static List<Cell> fit(Section s, HeightField h, int baseX, int baseZ) {
        Map<Long, Integer> tops = new HashMap<>();
        if (!s.building()) {
            List<Cell> out = new ArrayList<>(s.cells().size());
            for (Cell c : s.cells()) {
                int top = top(tops, h, baseX + c.x() + c.anchorDx(), baseZ + c.z() + c.anchorDz());
                if (top != Integer.MIN_VALUE) {
                    out.add(new Cell(c.x(), top + c.y(), c.z(), c.mat()));
                }
            }
            return out;
        }
        int base = buildingBase(s, h, baseX, baseZ, tops);
        if (base == Integer.MIN_VALUE) {
            return List.of();
        }
        Set<Long> floor = new LinkedHashSet<>();
        for (Cell c : s.cells()) {
            if (c.y() == 0) {
                floor.add(key(c.x(), c.z()));
            }
        }
        List<Cell> plinth = new ArrayList<>();
        for (long k : floor) {
            int x = (int) (k >> 32);
            int z = (int) k;
            int top = top(tops, h, baseX + x, baseZ + z);
            if (top == Integer.MIN_VALUE) {
                continue;
            }
            for (int y = top + 1; y < base; y++) {
                plinth.add(new Cell(x, y, z, PLINTH));
            }
        }
        plinth.sort(Comparator.comparingInt(Cell::y));
        List<Cell> out = new ArrayList<>(plinth.size() + s.cells().size());
        out.addAll(plinth);
        for (Cell c : s.cells()) {
            out.add(new Cell(c.x(), base + c.y(), c.z(), c.mat()));
        }
        return out;
    }

    /**
     * Schilder mit absolutem y wie ihre Bodenzelle (eigene Säule, ohne Anker) – sonst fände SignWriter in der Erdwelt
     * das Schild nicht an der geplanten Stelle. Schilder über Säulen ohne Gelände fallen weg wie ihre Zellen.
     */
    static List<Sign> fitSigns(List<Sign> signs, HeightField h, int baseX, int baseZ) {
        List<Sign> out = new ArrayList<>(signs.size());
        for (Sign s : signs) {
            int top = h.surfaceY(baseX + s.x(), baseZ + s.z());
            if (top != Integer.MIN_VALUE) {
                out.add(new Sign(s.x(), top + s.y(), s.z(), s.block(), s.lines()));
            }
        }
        return out;
    }

    /** Höchste Geländeoberkante unter dem Grundriss (alle Säulen des Abschnitts), MIN_VALUE ohne Gelände. */
    static int buildingBase(Section s, HeightField h, int baseX, int baseZ) {
        return buildingBase(s, h, baseX, baseZ, new HashMap<>());
    }

    private static int buildingBase(Section s, HeightField h, int baseX, int baseZ, Map<Long, Integer> tops) {
        int base = Integer.MIN_VALUE;
        for (Cell c : s.cells()) {
            base = Math.max(base, top(tops, h, baseX + c.x(), baseZ + c.z()));
        }
        // Gebäudegruppe (Section.basis): Schiff, Turm und Helm stehen auf derselben Basis. Vorher bestimmte jeder Teil
        // sie aus dem eigenen Grundriss – am Hang lag der Turmboden auf anderer Höhe als das Schiff und der Helm
        // (kleinerer Grundriss, niedrigerer Höchstpunkt) steckte im Turm. Ein Turm talseitig neben dem Schiff bekommt
        // so seinen eigenen Sockel bis zur gemeinsamen Basis. Wer die Schleife entfernt, holt den Versatz zurück.
        for (CityModel.P p : s.basis()) {
            base = Math.max(base, top(tops, h, baseX + p.x(), baseZ + p.z()));
        }
        return base;
    }

    /** Oberkante je Säule nur einmal abfragen – ein Haus hat Dutzende Zellen übereinander. */
    private static int top(Map<Long, Integer> tops, HeightField h, int x, int z) {
        return tops.computeIfAbsent(key(x, z), k -> h.surfaceY(x, z));
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }
}
