package com.blackn0va.baumeister.logic;

import com.blackn0va.baumeister.logic.LogicMinimizer.Term;
import com.blackn0va.baumeister.plan.BlockSpec;
import com.blackn0va.baumeister.plan.CompiledBuild;
import com.blackn0va.baumeister.plan.ModuleLibrary;
import com.blackn0va.baumeister.plan.PlanException;
import com.blackn0va.baumeister.plan.Pos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 7-Segment-Anzeige 0–F: vier Hebel (Binärzahl), Decoder aus dem Logik-Compiler, Ziffer aus 27 Lampen flach im Boden.
 *
 * Wunsch 2026-11-06. Das LLM entwirft so etwas nie funktionsfähig; der Compiler konnte
 * es nicht (höchstens 4 Ausgänge, Lampen in einer Reihe). Hier:
 * - Decoder: LogicCircuit.grid mit 7 Ausgängen aus der Wahrheitstabelle HEX (Minimierung wie bei Formeln). Die
 *   Reihenfolge der Ausgänge von links nach rechts ist a, f, e, d, g, c, b – genau die, in der die Leitungen ohne
 *   Kreuzung an ihre Segmente kommen.
 * - Leitungen: Am Ende jeder ODER-Zeile biegt der Draht nach hinten, ein Verstärker frischt auf (die ODER-Zeile endet
 *   mit Stärke ≥ 1). Dann rückt jede Leitung in eigener Tiefe nach links zusammen (Reihenfolge bleibt, also kreuzt
 *   nichts), Abstand immer 2, Verstärker, wo die Stärke nicht mehr bis zum Segment reicht.
 * - Ziffer (Breite 7, Tiefe 9, oben = vom Spieler weg): Lampen auf Höhe 2, Staub obendrauf (Staub speist den Block unter
 *   sich, die Lampe leuchtet). Ecken frei, Segmente berühren sich nur schräg. g in der Mitte ist von allen anderen
 *   umschlossen – seine Leitung läuft im Tunnel (Höhe 1) unter d hindurch und steigt im Innern hoch.
 *
 * Lokal: x nach rechts, z vom Spieler weg, y hoch; Hebel A (1), B (2), C (4), D (8) bei x=0 von vorn nach hinten.
 * Gemessen mit allen 16 Werten auf dem Testserver: probes/redstone/DisplayProbe (siehe dort).
 *
 * Threads: zustandslos. Fehler: IllegalStateException, wenn die Leitungen nicht passen (Programmfehler, nicht Eingabe).
 */
public final class SevenSegment {

    /** Segmente je Wert 0–F, Bit 0 = a, 1 = b, 2 = c, 3 = d, 4 = e, 5 = f, 6 = g (übliche Hex-Schreibweise). */
    static final int[] HEX = {0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F, 0x77, 0x7C, 0x39, 0x5E, 0x79,
        0x71};
    /** Ausgänge von links nach rechts: a, f, e, d, g, c, b (Segment-Bit) und ihr Einlauf relativ zur Ziffer. */
    private static final int[] ORDER = {0, 5, 4, 3, 6, 2, 1};
    private static final int[] ENTRY = {-6, -4, -2, 2, 4, 8, 10};
    static final String NAMES = "abcdefg";

    private static final String WIRE = "redstone_wire";
    private static final String LAMP = "redstone_lamp";

    /** Bauteil, Hebel (A–D) und je Segment seine Lampen – für die Probe und die Ansage. */
    /** taps: erste Leitungszelle hinter jedem Decoder-Ausgang (Reihenfolge a, f, e, d, g, c, b) – für die Probe. */
    public record Display(ModuleLibrary.Module module, List<Pos> levers, Map<Character, List<Pos>> segments,
            int terms, List<Pos> taps) {
    }

    private SevenSegment() {
    }

    /** Welche Segmente bei Wert v leuchten (Testerwartung). */
    public static boolean lit(int v, char segment) {
        return (HEX[v] >> NAMES.indexOf(segment) & 1) == 1;
    }

    public static Display compile() {
        List<List<Term>> terms = new ArrayList<>();
        int total = 0;
        for (int seg : ORDER) {
            List<Integer> on = new ArrayList<>();
            for (int v = 0; v < 16; v++) {
                if ((HEX[v] >> seg & 1) == 1) {
                    on.add(v);
                }
            }
            List<Term> t = LogicMinimizer.minimize(on, 4);
            terms.add(t);
            total += t.size();
        }
        LogicCircuit.Grid g = LogicCircuit.grid(4, terms);
        Map<Pos, String> cells = new LinkedHashMap<>(g.cells());
        int orZ = g.lamps().getFirst().z();
        int[] src = new int[ORDER.length];
        List<Pos> taps = new ArrayList<>();
        for (int j = 0; j < ORDER.length; j++) {
            Pos lamp = g.lamps().get(j);
            cells.remove(lamp);
            src[j] = lamp.x() - 1;
            taps.add(new Pos(src[j], 3, orZ + 1));
        }
        int x0 = src[0] - ENTRY[0];
        int zd = orZ + 4 + 2 * ORDER.length + 6;
        Map<Character, List<Pos>> segments = digit(cells, x0, zd);
        for (int j = 0; j < ORDER.length; j++) {
            route(cells, j, src[j], x0 + ENTRY[j], orZ, x0, zd, NAMES.charAt(ORDER[j]));
        }
        return new Display(module(cells), g.pins(), segments, total, List.copyOf(taps));
    }

    /** Ziffer: Lampen auf y=2, Staub y=3. Rückgabe: Lampen je Segment. */
    private static Map<Character, List<Pos>> digit(Map<Pos, String> cells, int x0, int zd) {
        Map<Character, List<Pos>> seg = new LinkedHashMap<>();
        for (char c : NAMES.toCharArray()) {
            seg.put(c, new ArrayList<>());
        }
        for (int x = 1; x <= 5; x++) {
            lamp(cells, seg, 'd', x0 + x, zd);
            lamp(cells, seg, 'g', x0 + x, zd + 4);
            lamp(cells, seg, 'a', x0 + x, zd + 8);
        }
        for (int z = 1; z <= 3; z++) {
            lamp(cells, seg, 'e', x0, zd + z);
            lamp(cells, seg, 'c', x0 + 6, zd + z);
            lamp(cells, seg, 'f', x0, zd + 4 + z);
            lamp(cells, seg, 'b', x0 + 6, zd + 4 + z);
        }
        return seg;
    }

    private static void lamp(Map<Pos, String> cells, Map<Character, List<Pos>> seg, char s, int x, int z) {
        Pos p = new Pos(x, 2, z);
        cells.put(p, LAMP);
        cells.put(p.up(), WIRE);
        seg.get(s).add(p);
    }

    /**
     * Leitung j: vom Ende der ODER-Zeile (src, 3, orZ) nach hinten, in Tiefe orZ+4+2j nach links auf die Spalte tgt,
     * dann nach hinten an das Segment. Alle Zellen auf y=3 mit Stein darunter; g durch den Tunnel.
     */
    private static void route(Map<Pos, String> cells, int j, int src, int tgt, int orZ, int x0, int zd, char seg) {
        List<int[]> path = new ArrayList<>();
        // {x, z}: Weg in Flussrichtung auf y=3.
        path.add(new int[] {src, orZ + 1});
        int jog = j == 0 ? orZ + 2 : orZ + 4 + 2 * j;
        straight(path, src, jog);
        straight(path, tgt, jog);
        switch (seg) {
            case 'a' -> {
                straight(path, tgt, zd + 10);
                straight(path, x0 + 3, zd + 10);
                straight(path, x0 + 3, zd + 9);
            }
            case 'f' -> {
                straight(path, tgt, zd + 6);
                straight(path, x0 - 1, zd + 6);
            }
            case 'e' -> {
                straight(path, tgt, zd + 2);
                straight(path, x0 - 1, zd + 2);
            }
            case 'd' -> straight(path, tgt, zd - 1);
            case 'g' -> straight(path, tgt, zd - 3);
            case 'c' -> {
                straight(path, tgt, zd + 2);
                straight(path, x0 + 7, zd + 2);
            }
            case 'b' -> {
                straight(path, tgt, zd + 6);
                straight(path, x0 + 7, zd + 6);
            }
            default -> throw new IllegalStateException("Segment " + seg);
        }
        // Rest hinter dem Weg bis zum fernsten Lampenende: g hat noch 7 Tunnelzellen plus 3 im Segment.
        int tail = switch (seg) {
            case 'a', 'd' -> 3;
            case 'g' -> 10;
            default -> 2;
        };
        int power = 0;
        for (int i = 0; i < path.size(); i++) {
            int[] c = path.get(i);
            Pos p = new Pos(c[0], 3, c[1]);
            int remaining = path.size() - 1 - i + tail;
            boolean straightHere = i > 0 && i < path.size() - 1 && (path.get(i - 1)[0] == path.get(i + 1)[0]
                    || path.get(i - 1)[1] == path.get(i + 1)[1]);
            // Erster Verstärker gleich hinter der Biegung (i == 1): Die ODER-Zeile kommt mit Stärke ≥ 1 an. Danach nur,
            // wenn die Stärke fast aufgebraucht ist oder das Ende (höchstens 15 entfernt) sonst nicht mehr erreicht wird.
            // Vorher „Stärke minus GANZER Rest < 2“: Auf langen Leitungen stand so auf fast jedem Block ein Verstärker,
            // je 2 Ticks Verzögerung – gemessen 2026-11-07 (probes/redstone/DisplayProbe): Segment b zeigte nach
            // 200 Ticks noch den vorigen Wert. Wer das zurückdreht, baut eine Anzeige, die hinterherhinkt.
            boolean repeat = i == 1 || straightHere && i < path.size() - 2
                    && (power <= 2 || remaining <= 14 && power - remaining < 2);
            if (repeat) {
                int[] prev = path.get(i - 1);
                cells.put(p, "repeater[delay=1,facing=" + inputSide(prev, c) + "]");
                power = 16;
            } else {
                if (cells.containsKey(p)) {
                    throw new IllegalStateException("Anzeige: Leitung " + seg + " trifft belegte Zelle " + p);
                }
                cells.put(p, WIRE);
            }
            power--;
            cells.putIfAbsent(p.down(), "stone");
            if (power < 1 && i > 1) {
                throw new IllegalStateException("Anzeige: Leitung " + seg + " ohne Signal bei " + p);
            }
        }
        if (seg == 'g') {
            int x = x0 + 4;
            cells.put(new Pos(x, 1, zd - 2), "stone");
            cells.put(new Pos(x, 2, zd - 2), WIRE);
            cells.put(new Pos(x, 1, zd - 1), WIRE);
            cells.put(new Pos(x, 1, zd), WIRE);
            cells.put(new Pos(x, 1, zd + 1), WIRE);
            cells.put(new Pos(x, 1, zd + 2), "stone");
            cells.put(new Pos(x, 2, zd + 2), WIRE);
            cells.put(new Pos(x, 2, zd + 3), "stone");
            cells.put(new Pos(x, 3, zd + 3), WIRE);
        }
    }

    /** Zellen von der letzten Wegzelle geradeaus bis (x, z), ohne die Startzelle. */
    private static void straight(List<int[]> path, int x, int z) {
        int[] last = path.getLast();
        int dx = Integer.signum(x - last[0]);
        int dz = Integer.signum(z - last[1]);
        if (dx != 0 && dz != 0) {
            throw new IllegalStateException("Anzeige: schräger Weg");
        }
        int cx = last[0];
        int cz = last[1];
        while (cx != x || cz != z) {
            cx += dx;
            cz += dz;
            path.add(new int[] {cx, cz});
        }
    }

    /** Eingangsseite eines Verstärkers in Planrichtung: dort, wo das Signal herkommt (front = -z, left = -x). */
    private static String inputSide(int[] from, int[] at) {
        if (from[0] < at[0]) {
            return "left";
        }
        if (from[0] > at[0]) {
            return "right";
        }
        return from[1] < at[1] ? "front" : "back";
    }

    /** Boden y=0 unter allem, dann die Zellen (wie LogicCircuit.module). */
    private static ModuleLibrary.Module module(Map<Pos, String> cells) {
        int maxX = 0;
        int maxY = 0;
        int maxZ = 0;
        for (Pos p : cells.keySet()) {
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
            maxZ = Math.max(maxZ, p.z());
        }
        List<CompiledBuild.Change> list = new ArrayList<>();
        try {
            for (int x = 0; x <= maxX; x++) {
                for (int z = 0; z <= maxZ; z++) {
                    Pos floor = new Pos(x, 0, z);
                    if (!cells.containsKey(floor)) {
                        list.add(new CompiledBuild.Change(floor, BlockSpec.of("smooth_stone")));
                    }
                }
            }
            for (Map.Entry<Pos, String> e : cells.entrySet()) {
                list.add(new CompiledBuild.Change(e.getKey(), BlockSpec.parse(e.getValue())));
            }
        } catch (PlanException e) {
            throw new IllegalStateException("Anzeige: ungültiger Block " + e.getMessage(), e);
        }
        return new ModuleLibrary.Module("anzeige", "7-Segment-Anzeige 0–F: Hebel A (1), B (2), C (4), D (8) links von "
                + "vorn nach hinten; die Ziffer liegt hinten flach im Boden (oben = vom Hebel weg)",
                new Pos(maxX + 1, maxY + 1, maxZ + 1), List.copyOf(list));
    }
}
