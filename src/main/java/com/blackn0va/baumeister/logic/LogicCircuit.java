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
import java.util.TreeSet;

/**
 * Logik-Compiler: Formel(n) → Redstone-Schaltung als Bauteil (ModuleLibrary.Module), kreuzungsfrei im festen Raster.
 *
 * Warum ein Raster statt freier Verdrahtung: Ein Redstone-Draht versorgt jeden Draht und jedes Bauteil neben sich;
 * frei verlegte Leitungen sprechen über. Das Raster hält jede Leitung einen Block von fremden Leitungen weg und
 * kreuzt nur über Höhenwechsel. Grundlage ist das geprüfte UND-Gatter (und_gatter, 80/80): Eingang → Block mit
 * Fackel obendrauf (= NICHT Eingang), Fackeln auf einen gemeinsamen Draht (= ODER der Negationen), Wandfackel am Ende
 * (= NICHT davon = UND der Eingänge).
 *
 * Aufbau (lokal: x nach rechts, z nach hinten, y hoch), n Eingänge, Terme in Spalten nebeneinander:
 *   Eingang i: Hebel (0,1,8i). Leitung i  = Draht y=1 bei z=8i,   x=1..; daneben bei x=0 ein Umkehrer
 *              (Draht 8i+1..8i+2 → Stein 8i+3 → Wandfackel 8i+4), der die Leitung ¬i bei z=8i+4 speist.
 *   Term (Spalte xs, Sammler xc = xs+1): für jedes Literal ein Abgriff von der passenden Leitung
 *              (positives Literal → Leitung i, negiertes → Leitung ¬i): Draht (xs,1,z+1) in Stein (xs,1,z+2),
 *              Fackel (xs,2,z+2), Stein (xs,3,z+2). Der obere Stein ist von der Fackel stark gespeist und gibt ihr
 *              Signal an den Sammeldraht (xc,3,·) daneben – eine Ebene über den Leitungen, also ohne Kreuzung.
 *              Sammler läuft bis z=8n in Stein (xc,3,8n+1), Wandfackel (xc,3,8n+2) = Term.
 *              Alle 8 Blöcke ein Verstärker im Sammler (z=8i+7): bei 5 Eingängen wäre er 40 lang.
 *   Ausgang j: ODER-Draht y=3 bei z=8n+3 über die eigenen Termspalten (ab der ersten xs bis eine hinter dem letzten
 *              Sammler), Lampe direkt dahinter. Ausgänge liegen als Gruppen von links nach rechts in der Reihenfolge
 *              der Formeln, zwischen Lampe und nächster Gruppe eine leere Spalte – sonst zeigte das Drahtende der
 *              nächsten ODER-Leitung in die Lampe davor.
 * Bei einem Ausgang ist das genau die erste Fassung (Spalte xs = 2+3k, Lampe bei 3K+2) – LogicProbe 44/44.
 *
 * Signalstärke: Draht verliert 1 je Block. Ein Abgriff braucht an seiner Spalte noch ≥ 2 auf der Leitung (der
 * Abgriffsdraht selbst ≥ 1), der letzte ODER-Draht vor der Lampe ≥ 1. Bis 5 Terme in einer Zeile reicht das ohne
 * Hilfe (Leitung bis Spalte 14 → 2). Darüber setzt der Compiler Verstärker in Lücken-Spalten der Leitungen bzw. der
 * ODER-Zeile – so spät wie möglich ({@link #boosters}), damit kleine Schaltungen unverändert bleiben. Verstärker
 * wechseln nie die Höhe und stehen nur dort, wo über und neben ihnen nichts ist.
 *
 * Grenzen: 5 Eingänge ({@link LogicExpr#MAX_INPUTS}: 8 Blöcke Tiefe je Eingang, 32 Zeilen Wahrheitstabelle für die
 * Probe), je Stockwerk 4 Ausgänge und {@link #MAX_TERMS} Terme.
 *
 * Stockwerke (seit 2026-11-07, Wunsch):
 * Passt eine Schaltung nicht in eine Ebene (mehr als 4 Ausgänge oder 16 Terme) – oder steht „3d“ davor –, stapelt
 * {@link #stack} bis {@link #MAX_FLOORS} Ebenen im Abstand {@link #FLOOR} übereinander, jede mit eigenen Leitungen,
 * Umkehrern, Termen und Lampen. Die Eingänge steigen in Fackeltürmen bei x=0 hoch:
 *   Hebel (2,1,8i) → Draht (1,1,8i) → Stein (0,1,8i) → Fackel (0,2) → Stein (0,3) → Fackel (0,4) → Stein (0,5) …
 *   Zwei Umkehrungen je 4 Blöcke: Der Stein bei y = 4f+1 führt in jedem Stockwerk f wieder das Eingangssignal.
 *   Ein Verstärker (1,4f+1,8i) speist von dort den Einspeise-Stein (2,4f+1,8i) – der steht, wo unten der Hebel steht,
 *   und versorgt Leitung und Umkehrer mit voller Stärke 15 wie ein Hebel.
 * Warum der Turm über Draht neben dem Hebel beginnt und nicht am Trägerblock eines Deckenhebels: Ein per setblock
 * (Probe) oder Plugin gesetzter Hebel weckt die Nachbarn seines Trägerblocks nicht (gemessen 2026-11-06, LabProbe) –
 * der Turm stünde still. Den Draht daneben weckt er immer.
 * Die Ebenen trennen sich elektrisch: Draht speist den Block über sich nicht, Fackeln speisen nur den Stein direkt
 * über sich (Teil des Rasters), Wandfackeln am Sammlerende speisen den Boden des Stockwerks darüber – dort liegt
 * nichts, was davon schalten könnte (geprüft auf dem Spigot-Testserver, LogicProbe „3d“).
 *
 * Threads: zustandslos. Fehler: IllegalArgumentException mit deutschem Satz.
 */
public final class LogicCircuit {

    /**
     * Terme der ganzen Schaltung. Vorher 5 – die Grenze kam aus der Signalstärke der Leitungen (Spalte 14 → 2), nicht
     * aus dem Bedarf; ein 2-Bit-Addierer braucht für das obere Summenbit allein 6. Jetzt 16: Das ist die größte
     * Minimalform, die 5 Eingänge überhaupt haben können (Parität), jede einzelne Formel passt also. Breite dann
     * 1 + 3·16 + 2·Ausgänge ≤ 57 Blöcke bei 44 Tiefe – kleiner als ein Bauplatz (bau.max-ausdehnung 192).
     */
    public static final int MAX_TERMS = 16;
    /** Höchstens so viele Stockwerke (Turmhöhe 4 je Stockwerk, Laufzeit der Probe). */
    public static final int MAX_FLOORS = 4;
    /** Höhe eines Stockwerks: Boden, Leitungen, Fackeln, Sammler – das nächste beginnt direkt darüber. */
    public static final int FLOOR = 4;
    /** Ausgänge je Stockwerk (eine Ebene des Rasters). */
    static final int OUTPUTS_PER_FLOOR = 4;
    /** Vor der Formel: jeder Ausgang auf ein eigenes Stockwerk. */
    private static final java.util.regex.Pattern STACKED = java.util.regex.Pattern.compile(
            "(?iuU)^\\s*(3d|stockwerke|übereinander|gestapelt|vertikal)\\s*[:,]?\\s+");

    /** Ein Ausgang: Name, Formel, ihre Terme (nach Minimierung) und wo seine Lampe steht. */
    public record Output(String name, LogicExpr expr, List<Term> terms, Pos lamp) {
    }

    /** Gebaute Schaltung samt dem, was die Probe braucht. Hebel in Reihenfolge von {@link #inputs()}. */
    public record Circuit(ModuleLibrary.Module module, List<String> inputs, List<Output> outputs,
            List<Pos> inputPins) {

        /** Formel des ersten Ausgangs – bei einer Formel die einzige. */
        public LogicExpr expr() {
            return outputs.getFirst().expr();
        }

        /** Lampe des ersten Ausgangs. */
        public Pos output() {
            return outputs.getFirst().lamp();
        }

        /** Alle Terme, Ausgang für Ausgang. */
        public List<Term> terms() {
            return outputs.stream().flatMap(o -> o.terms().stream()).toList();
        }
    }

    /**
     * Zellen samt Netzzuordnung jedes Drahts, Verstärkers und jeder Fackel, die einen Draht speist („leitung0“,
     * „umgekehrt1“, „sammler5“, „oder0“). Paketintern für den Abstandstest – die Netze stehen nirgends sonst.
     */
    record Layout(Circuit circuit, Map<Pos, String> cells, Map<Pos, String> nets) {
    }

    private static final String LEVER = "lever[face=floor,facing=back]";
    private static final String WIRE = "redstone_wire";
    /** Signal von links (−x), Ausgang nach rechts – Leitungen und ODER-Zeilen laufen nach +x. */
    private static final String BOOSTER = "repeater[facing=left,delay=1]";

    private LogicCircuit() {
    }

    public static Circuit compile(String formula) {
        return layout(formula).circuit();
    }

    static Layout layout(String formula) {
        java.util.regex.Matcher stacked = STACKED.matcher(formula == null ? "" : formula);
        boolean forced = stacked.find();
        List<LogicExpr> exprs = LogicExpr.parseAll(forced ? formula.substring(stacked.end()) : formula);
        boolean single = exprs.size() == 1;
        List<String> inputs = exprs.getFirst().inputs();
        int n = inputs.size();
        List<List<Term>> termsPerOutput = new ArrayList<>();
        int total = 0;
        for (LogicExpr e : exprs) {
            List<Integer> minterms = e.minterms();
            if (n == 0 || minterms.isEmpty() || minterms.size() == 1 << n) {
                throw new IllegalArgumentException((single ? "Die Formel" : "Ausgang „" + e.output() + "“") + " ist immer "
                        + (minterms.isEmpty() ? "falsch" : "wahr") + " – dafür braucht es keine Schaltung.");
            }
            List<Term> terms = LogicMinimizer.minimize(minterms, n);
            termsPerOutput.add(terms);
            total += terms.size();
        }
        for (int j = 0; j < termsPerOutput.size(); j++) {
            if (termsPerOutput.get(j).size() > MAX_TERMS) {
                throw new IllegalArgumentException((single ? "Die Formel braucht " : "Ausgang „" + exprs.get(j).output()
                        + "“ braucht ") + termsPerOutput.get(j).size() + " Terme, ein Stockwerk trägt " + MAX_TERMS
                        + ". Teile sie auf oder nimm weniger Eingänge.");
            }
        }
        List<List<Integer>> floors = floors(termsPerOutput, forced);
        if (floors.size() > MAX_FLOORS) {
            throw new IllegalArgumentException("Die Formeln brauchen " + floors.size() + " Stockwerke (je bis "
                    + OUTPUTS_PER_FLOOR + " Ausgänge und " + MAX_TERMS + " Terme), höchstens " + MAX_FLOORS
                    + " gehen. Teile sie auf.");
        }
        Grid g = floors.size() == 1 ? grid(n, termsPerOutput) : stack(n, termsPerOutput, floors);
        List<Output> outputs = new ArrayList<>();
        for (int j = 0; j < exprs.size(); j++) {
            outputs.add(new Output(exprs.get(j).output(), exprs.get(j), termsPerOutput.get(j), g.lamps().get(j)));
        }
        Circuit c = new Circuit(module(exprs, g.cells(), floors.size()), inputs, List.copyOf(outputs), g.pins());
        return new Layout(c, g.cells(), g.nets());
    }

    /**
     * Ausgänge auf Stockwerke verteilen, in Reihenfolge: ein neues Stockwerk, sobald 4 Ausgänge oder 16 Terme voll
     * wären. forced: jeder Ausgang ein eigenes Stockwerk (bis MAX_FLOORS, danach wieder gefüllt).
     */
    static List<List<Integer>> floors(List<List<Term>> termsPerOutput, boolean forced) {
        List<List<Integer>> out = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        int terms = 0;
        int perFloor = forced ? Math.max(1, (int) Math.ceil(termsPerOutput.size() / (double) MAX_FLOORS))
                : OUTPUTS_PER_FLOOR;
        for (int j = 0; j < termsPerOutput.size(); j++) {
            int k = termsPerOutput.get(j).size();
            if (!current.isEmpty() && (current.size() >= perFloor || terms + k > MAX_TERMS)) {
                out.add(current);
                current = new ArrayList<>();
                terms = 0;
            }
            current.add(j);
            terms += k;
        }
        out.add(current);
        return out;
    }

    /**
     * Stockwerke übereinander (Kopfkommentar „Stockwerke“): je Stockwerk das Raster aus {@link #grid}, um 2 nach +x
     * verschoben (Platz für Turm bei x=0 und Draht/Verstärker bei x=1) und um FLOOR·f nach oben. Im untersten Stockwerk
     * bleibt der Hebel, darüber steht an seiner Stelle der Einspeise-Stein.
     */
    static Grid stack(int n, List<List<Term>> termsPerOutput, List<List<Integer>> floors) {
        Map<Pos, String> cells = new LinkedHashMap<>();
        Map<Pos, String> nets = new LinkedHashMap<>();
        Pos[] lamps = new Pos[termsPerOutput.size()];
        List<Pos> pins = new ArrayList<>();
        int top = floors.size() - 1;
        for (int f = 0; f < floors.size(); f++) {
            List<Integer> outs = floors.get(f);
            List<List<Term>> mine = new ArrayList<>();
            for (int j : outs) {
                mine.add(termsPerOutput.get(j));
            }
            Grid g = grid(n, mine);
            int dy = FLOOR * f;
            for (Map.Entry<Pos, String> e : g.cells().entrySet()) {
                Pos p = e.getKey();
                Pos q = new Pos(p.x() + 2, p.y() + dy, p.z());
                String block = e.getValue();
                if (f > 0 && block.equals(LEVER)) {
                    block = "stone";
                }
                cells.put(q, block);
                String net = g.nets().get(p);
                if (net != null) {
                    nets.put(q, "s" + f + "_" + net);
                }
            }
            for (int k = 0; k < outs.size(); k++) {
                Pos l = g.lamps().get(k);
                lamps[outs.get(k)] = new Pos(l.x() + 2, l.y() + dy, l.z());
            }
            if (f == 0) {
                for (Pos pin : g.pins()) {
                    pins.add(new Pos(pin.x() + 2, pin.y(), pin.z()));
                }
            }
        }
        // Türme: je Eingang bei z = 8i. Unten Draht neben dem Hebel in den Turm-Stein, dann Fackel/Stein im Wechsel bis
        // zum Einspeise-Stein des obersten Stockwerks; je Stockwerk ein Verstärker vom Turm in den Einspeise-Stein.
        for (int i = 0; i < n; i++) {
            int z = 8 * i;
            put(cells, nets, new Pos(1, 1, z), WIRE, "turm" + i);
            cells.put(new Pos(0, 1, z), "stone");
            int height = FLOOR * top + 1;
            for (int y = 2; y <= height; y++) {
                if (y % 2 == 0) {
                    put(cells, nets, new Pos(0, y, z), "redstone_torch", "turm" + i);
                } else {
                    cells.put(new Pos(0, y, z), "stone");
                }
            }
            for (int f = 1; f <= top; f++) {
                put(cells, nets, new Pos(1, FLOOR * f + 1, z), BOOSTER, "turm" + i);
            }
        }
        return new Grid(cells, nets, List.of(lamps), List.copyOf(pins));
    }

    /**
     * Das Raster aus fertigen Termen: n Eingänge, je Ausgang eine Gruppe von links nach rechts. Herausgelöst
     * 2026-11-06 für die 7-Segment-Anzeige (SevenSegment), die ihre Terme aus einer Wahrheitstabelle statt aus
     * Formeltext bekommt. Block für Block dasselbe wie vorher (Fingerabdruck-Test in LogicCompilerTest).
     */
    record Grid(Map<Pos, String> cells, Map<Pos, String> nets, List<Pos> lamps, List<Pos> pins) {
    }

    static Grid grid(int n, List<List<Term>> termsPerOutput) {
        // Spalten: je Ausgang eine Gruppe, je Term xs (Abgriffe) und xc = xs+1 (Sammler).
        List<int[]> tapColumns = new ArrayList<>();      // je Ausgang die xs seiner Terme
        int[] bases = new int[termsPerOutput.size()];
        int[] lamps = new int[termsPerOutput.size()];
        int x = 2;
        for (int j = 0; j < termsPerOutput.size(); j++) {
            int k = termsPerOutput.get(j).size();
            bases[j] = x;
            int[] cols = new int[k];
            for (int t = 0; t < k; t++) {
                cols[t] = x + 3 * t;
            }
            tapColumns.add(cols);
            lamps[j] = x + 3 * k;
            x = lamps[j] + 2;
        }
        TreeSet<Integer> taps = new TreeSet<>();
        TreeSet<Integer> collectors = new TreeSet<>();
        for (int[] cols : tapColumns) {
            for (int xs : cols) {
                taps.add(xs);
                collectors.add(xs + 1);
            }
        }
        int lastTap = taps.last();
        // Leitungs-Verstärker nur in Spalten ohne Abgriff und ohne Sammler darüber.
        List<Integer> lineSlots = new ArrayList<>();
        for (int c = 2; c < lastTap; c++) {
            if (!taps.contains(c) && !collectors.contains(c)) {
                lineSlots.add(c);
            }
        }
        List<int[]> tapNeeds = taps.stream().map(t -> new int[] {t, 2}).toList();
        TreeSet<Integer> lineBoosters = new TreeSet<>(boosters(1, tapNeeds, lineSlots));

        Map<Pos, String> cells = new LinkedHashMap<>();
        Map<Pos, String> nets = new LinkedHashMap<>();
        List<Pos> pins = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int zb = 8 * i;
            int zi = zb + 4;
            Pos pin = new Pos(0, 1, zb);
            pins.add(pin);
            cells.put(pin, LEVER);
            // Umkehrer bei x=0: Draht vom Hebel nach hinten in einen Stein, Wandfackel dahinter speist Leitung ¬i.
            put(cells, nets, new Pos(0, 1, zb + 1), WIRE, "eingang" + i);
            put(cells, nets, new Pos(0, 1, zb + 2), WIRE, "eingang" + i);
            cells.put(new Pos(0, 1, zb + 3), "stone");
            put(cells, nets, new Pos(0, 1, zi), "redstone_wall_torch[facing=back]", "umgekehrt" + i);
            for (int c = 1; c <= lastTap; c++) {
                String part = lineBoosters.contains(c) ? BOOSTER : WIRE;
                put(cells, nets, new Pos(c, 1, zb), part, "leitung" + i);
                put(cells, nets, new Pos(c, 1, zi), part, "umgekehrt" + i);
            }
        }
        int top = 8 * n;
        for (int j = 0; j < termsPerOutput.size(); j++) {
            List<Term> terms = termsPerOutput.get(j);
            for (int t = 0; t < terms.size(); t++) {
                Term term = terms.get(t);
                int xs = tapColumns.get(j)[t];
                int xc = xs + 1;
                int zmin = Integer.MAX_VALUE;
                for (int i = 0; i < n; i++) {
                    int lit = term.literal(i);
                    if (lit < 0) {
                        continue;
                    }
                    // Positives Literal: die Fackel muss NICHT Eingang liefern → von Leitung i speisen.
                    // Negiertes Literal: NICHT(¬Eingang) = Eingang → von Leitung ¬i speisen.
                    int z = lit == 1 ? 8 * i : 8 * i + 4;
                    put(cells, nets, new Pos(xs, 1, z + 1), WIRE, (lit == 1 ? "leitung" : "umgekehrt") + i);
                    cells.put(new Pos(xs, 1, z + 2), "stone");
                    cells.put(new Pos(xs, 2, z + 2), "redstone_torch");
                    cells.put(new Pos(xs, 3, z + 2), "stone");
                    zmin = Math.min(zmin, z + 2);
                }
                for (int z = zmin; z <= top; z++) {
                    cells.put(new Pos(xc, 2, z), "stone");
                    put(cells, nets, new Pos(xc, 3, z), z % 8 == 7 ? "repeater[facing=front,delay=1]" : WIRE,
                            "sammler" + xc);
                }
                cells.put(new Pos(xc, 3, top + 1), "stone");
                put(cells, nets, new Pos(xc, 3, top + 2), "redstone_wall_torch[facing=back]", "oder" + j);
            }
        }
        int orZ = top + 3;
        List<Pos> lampList = new ArrayList<>();
        for (int j = 0; j < termsPerOutput.size(); j++) {
            int[] cols = tapColumns.get(j);
            int lastWire = lamps[j] - 1;
            // Verstärker nur zwischen zwei Termspalten (Lücke hinter einem Sammler); schlechtester Fall ist die erste
            // Fackel der Gruppe bzw. der Ausgang des letzten Verstärkers.
            List<Integer> orSlots = new ArrayList<>();
            for (int t = 0; t + 1 < cols.length; t++) {
                orSlots.add(cols[t] + 2);
            }
            TreeSet<Integer> orBoosters = new TreeSet<>(boosters(cols[0] + 1, List.of(new int[] {lastWire, 1}),
                    orSlots));
            // Ein Draht MEHR hinter der letzten Term-Fackel: Ein Draht neben einer Fackel biegt optisch zu ihr ab und
            // zeigt dann nicht mehr in die Lampe – gemessen 2026-10-28, erste Fassung: End-Fackel an, Lampe in jeder
            // Belegung aus (11/11 Formeln FEHLER). Der zusätzliche Draht hat keine Fackel neben sich, läuft gerade und
            // zeigt in die Lampe.
            for (int c = bases[j]; c <= lastWire; c++) {
                cells.put(new Pos(c, 2, orZ), "stone");
                put(cells, nets, new Pos(c, 3, orZ), orBoosters.contains(c) ? BOOSTER : WIRE, "oder" + j);
            }
            Pos lamp = new Pos(lamps[j], 3, orZ);
            cells.put(lamp, "redstone_lamp");
            lampList.add(lamp);
        }
        return new Grid(cells, nets, List.copyOf(lampList), List.copyOf(pins));
    }

    private static void put(Map<Pos, String> cells, Map<Pos, String> nets, Pos p, String block, String net) {
        cells.put(p, block);
        nets.put(p, net);
    }

    /**
     * Verstärker-Spalten für eine Leitung, die nach +x läuft und bei {@code source} mit Stärke 15 beginnt.
     * needs: {Spalte, Mindeststärke dort}; slots: aufsteigend, wo ein Verstärker stehen darf.
     *
     * So spät wie möglich: Ein Platz bleibt frei, solange bis zum nächsten Platz jede Forderung erfüllt ist und der
     * nächste Platz selbst noch Signal bekommt (Eingang ≥ 1). Warum nicht regelmäßig alle n Spalten: Jeder Verstärker
     * ändert die Schaltung, die auf dem Testserver mit 44/44 bestanden hat; so bleibt jede Schaltung bis 5 Terme
     * Block für Block die alte. Hinter einem Verstärker gilt die Leitung wie neu gespeist (Stärke 15).
     */
    static List<Integer> boosters(int source, List<int[]> needs, List<Integer> slots) {
        List<Integer> out = new ArrayList<>();
        int src = source;
        int firstSlot = slots.isEmpty() ? Integer.MAX_VALUE : slots.getFirst();
        for (int[] need : needs) {
            if (need[0] < firstSlot && 15 - (need[0] - src) < need[1]) {
                throw new IllegalStateException("Logik-Compiler: Signal reicht nicht bis Spalte " + need[0]);
            }
        }
        for (int i = 0; i < slots.size(); i++) {
            int g = slots.get(i);
            int next = i + 1 < slots.size() ? slots.get(i + 1) : Integer.MAX_VALUE;
            boolean enough = next == Integer.MAX_VALUE || 15 - (next - 1 - src) >= 1;
            for (int[] need : needs) {
                if (need[0] > g && need[0] < next && 15 - (need[0] - src) < need[1]) {
                    enough = false;
                }
            }
            if (!enough) {
                if (15 - (g - 1 - src) < 1) {
                    throw new IllegalStateException("Logik-Compiler: kein Signal mehr vor dem Verstärker bei " + g);
                }
                out.add(g);
                src = g + 1;
            }
        }
        return out;
    }

    /**
     * Boden y=0 unter allem (Draht, Fackeln und Hebel brauchen Halt), dann die Zellen. Bei Stockwerken je Stockwerk
     * ein Boden bei y = FLOOR·f ab x=1 (unter den Verstärkern), nie in der Turmspalte x=0.
     */
    private static ModuleLibrary.Module module(List<LogicExpr> exprs, Map<Pos, String> cells, int floorCount) {
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
            for (int f = 0; f < floorCount; f++) {
                int y = FLOOR * f;
                for (int x = f == 0 ? 0 : 1; x <= maxX; x++) {
                    for (int z = 0; z <= maxZ; z++) {
                        Pos floor = new Pos(x, y, z);
                        if (!cells.containsKey(floor)) {
                            list.add(new CompiledBuild.Change(floor, BlockSpec.of("smooth_stone")));
                        }
                    }
                }
            }
            for (Map.Entry<Pos, String> e : cells.entrySet()) {
                list.add(new CompiledBuild.Change(e.getKey(), BlockSpec.parse(e.getValue())));
            }
        } catch (PlanException e) {
            throw new IllegalStateException("Logik-Compiler: ungültiger Block " + e.getMessage(), e);
        }
        LogicExpr first = exprs.getFirst();
        String floorsText = floorCount > 1 ? "; " + floorCount + " Stockwerke übereinander, Eingänge im Turm links"
                : "";
        String desc = exprs.size() == 1
                ? first.output() + " = Formel über " + String.join(", ", first.inputs()) + "; Hebel links vorn "
                        + "nach hinten, Lampe hinten rechts"
                : String.join(", ", names(exprs)) + " = Formeln über " + String.join(", ", first.inputs())
                        + "; Hebel links vorn nach hinten, Lampen hinten von links: " + String.join(", ", names(exprs));
        return new ModuleLibrary.Module("logik", desc + floorsText, new Pos(maxX + 1, maxY + 1, maxZ + 1),
                List.copyOf(list));
    }

    private static List<String> names(List<LogicExpr> exprs) {
        return exprs.stream().map(LogicExpr::output).toList();
    }

    /**
     * Ansage an den Spieler vor dem Bau. Bei einer Formel wörtlich wie vor dem Ausbau; bei mehreren nennt sie die
     * Lampen von links nach rechts – ohne Schilder ist die Reihenfolge der einzige Hinweis, welche Lampe was ist.
     */
    public static String announce(Circuit c, String formula) {
        int terms = c.terms().size();
        String levers = String.join(", ", c.inputs()) + ", Hebel links von vorn nach hinten";
        if (c.outputs().size() == 1) {
            LogicExpr expr = c.expr();
            return String.format("Baue %s = %s: %d Eingänge (%s), %d Terme, Lampe hinten rechts.", expr.output(),
                    formula.contains("=") ? formula.substring(formula.indexOf('=') + 1).trim() : formula,
                    expr.inputs().size(), levers, terms);
        }
        List<String> names = c.outputs().stream().map(Output::name).toList();
        java.util.SortedMap<Integer, List<String>> byFloor = lampsByFloor(c);
        if (byFloor.size() > 1) {
            // Stockwerke: je Stockwerk die Lampen von links nach rechts – sonst sucht man „P, U“ in einer Reihe.
            List<String> parts = new ArrayList<>();
            byFloor.forEach((f, n) -> parts.add("Stockwerk " + (f + 1) + ": " + String.join(", ", n)));
            return String.format("Baue %d Ausgänge aus %d Eingängen (%s), %d Terme, %d Stockwerke übereinander "
                    + "(Eingänge im Turm links); Lampen hinten von links nach rechts – %s.", names.size(),
                    c.inputs().size(), levers, terms, byFloor.size(), String.join("; ", parts));
        }
        return String.format("Baue %d Ausgänge aus %d Eingängen (%s), %d Terme; Lampen hinten von links nach rechts: "
                + "%s.", names.size(), c.inputs().size(), levers, terms, String.join(", ", names));
    }

    /**
     * Lampennamen je Stockwerk (0 = unten), je Stockwerk von links nach rechts. Aus der Lampenhöhe: Das flache Raster
     * stellt Lampen unter {@link #FLOOR}, jedes Stockwerk liegt um FLOOR höher.
     */
    public static java.util.SortedMap<Integer, List<String>> lampsByFloor(Circuit c) {
        java.util.TreeMap<Integer, List<String>> byFloor = new java.util.TreeMap<>();
        for (Output o : c.outputs()) {
            byFloor.computeIfAbsent(o.lamp().y() / FLOOR, k -> new ArrayList<>()).add(o.name());
        }
        return byFloor;
    }

    /** Erwarteter Ausgang je Belegung im Schlüssel-Format der Funktionsprobe („A=1,B=0“) – erster Ausgang. */
    public static Map<String, Boolean> truthTable(Circuit c) {
        return truthTables(c).get(c.outputs().getFirst().name());
    }

    /** Wie {@link #truthTable}, für jeden Ausgang (Name → Tabelle), in Reihenfolge der Formeln. */
    public static Map<String, Map<String, Boolean>> truthTables(Circuit c) {
        Map<String, Map<String, Boolean>> all = new LinkedHashMap<>();
        List<String> in = c.inputs();
        for (Output o : c.outputs()) {
            Map<String, Boolean> out = new LinkedHashMap<>();
            for (int b = 0; b < 1 << in.size(); b++) {
                List<String> key = new ArrayList<>();
                for (int i = 0; i < in.size(); i++) {
                    key.add(in.get(i) + "=" + (b >> i & 1));
                }
                out.put(String.join(",", key), o.expr().eval(b));
            }
            all.put(o.name(), out);
        }
        return all;
    }
}
