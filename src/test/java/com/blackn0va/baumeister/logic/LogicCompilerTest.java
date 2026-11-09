package com.blackn0va.baumeister.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.logic.LogicMinimizer.Term;
import com.blackn0va.baumeister.plan.CompiledBuild;
import com.blackn0va.baumeister.plan.Pos;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Logik-Compiler ohne Server: Formeln lesen, minimieren, Raster einhalten. Ob die Schaltung schaltet, prüft LogicProbe. */
class LogicCompilerTest {

    @Test
    void parsesGermanEnglishAndSymbolsWithPrecedence() {
        LogicExpr e = LogicExpr.parse("Licht = A und (B oder nicht C)");
        assertEquals("Licht", e.output());
        assertEquals(List.of("A", "B", "C"), e.inputs());
        // A=1,B=0,C=0 → 1; A=1,B=0,C=1 → 0; A=0 → 0
        assertTrue(e.eval(0b001));
        assertFalse(e.eval(0b101));
        assertFalse(e.eval(0b110));
        LogicExpr s = LogicExpr.parse("a & b | !a & !b");  // UND bindet stärker als ODER → Gleichheit
        assertTrue(s.eval(0b00));
        assertTrue(s.eval(0b11));
        assertFalse(s.eval(0b01));
        LogicExpr x = LogicExpr.parse("A xor B xor C");
        for (int b = 0; b < 8; b++) {
            assertEquals(Integer.bitCount(b) % 2 == 1, x.eval(b), "Parität " + b);
        }
    }

    @Test
    void chatPrefixesTakeTheRestAsFormula() {
        var m = com.blackn0va.baumeister.BaumeisterListenerAccess.logicChat("logik: Q = A und B");
        assertEquals("Q = A und B", m);
        assertEquals("A oder nicht B", com.blackn0va.baumeister.BaumeisterListenerAccess.logicChat("Schaltung A oder nicht B"));
        assertEquals(null, com.blackn0va.baumeister.BaumeisterListenerAccess.logicChat("bau mir eine Logik-Burg"));
    }

    @Test
    void rejectsNonsenseWithGermanReason() {
        assertThrows(IllegalArgumentException.class, () -> LogicExpr.parse("A und"));
        assertThrows(IllegalArgumentException.class, () -> LogicExpr.parse("(A oder B"));
        assertThrows(IllegalArgumentException.class, () -> LogicExpr.parse("A $ B"));
        assertThrows(IllegalArgumentException.class, () -> LogicExpr.parse("A & B & C & D & E & F"));
        assertThrows(IllegalArgumentException.class, () -> LogicCircuit.compile("A oder nicht A"));
    }

    @Test
    void minimizedTermsCoverExactlyTheTruthTableForRandomFunctions() {
        Random r = new Random(2026);
        for (int round = 0; round < 400; round++) {
            int n = 1 + r.nextInt(5);
            List<Integer> on = new ArrayList<>();
            for (int b = 0; b < 1 << n; b++) {
                if (r.nextBoolean()) {
                    on.add(b);
                }
            }
            List<Term> terms = LogicMinimizer.minimize(on, n);
            for (int b = 0; b < 1 << n; b++) {
                int bb = b;
                boolean covered = terms.stream().anyMatch(t -> (bb & t.mask()) == t.value());
                assertEquals(on.contains(b), covered, "n=" + n + " Zeile " + b + " Terme " + terms);
            }
        }
    }

    @Test
    void classicFunctionsMinimizeToTheTextbookSize() {
        assertEquals(1, LogicMinimizer.minimize(LogicExpr.parse("A & B | A & !B").minterms(), 2).size());
        assertEquals(2, LogicMinimizer.minimize(LogicExpr.parse("A xor B").minterms(), 2).size());
        // Mehrheit aus drei: AB + AC + BC
        assertEquals(3, LogicMinimizer.minimize(LogicExpr.parse("A&B | A&C | B&C").minterms(), 3).size());
    }

    @Test
    void circuitStaysOnTheGridAndEveryWireStandsOnSomething() {
        for (String f : List.of("A und B", "A oder B", "nicht A", "A xor B", "A&B | A&C | B&C",
                "S = A xor B xor C", "Q = (A oder B) und nicht (C und D)", "A & !B & C & !D & E")) {
            LogicCircuit.Circuit c = LogicCircuit.compile(f);
            Map<Pos, String> cells = new HashMap<>();
            Set<Pos> seen = new HashSet<>();
            for (CompiledBuild.Change ch : c.module().cells()) {
                assertTrue(seen.add(ch.pos()), f + ": doppelt " + ch.pos());
                cells.put(ch.pos(), ch.block().id());
                Pos s = c.module().size();
                Pos p = ch.pos();
                assertTrue(p.x() >= 0 && p.y() >= 0 && p.z() >= 0 && p.x() < s.x() && p.y() < s.y() && p.z() < s.z(),
                        f + ": außerhalb " + p);
            }
            cells.forEach((p, id) -> {
                if (id.equals("redstone_wire") || id.equals("repeater") || id.equals("redstone_torch")
                        || id.equals("lever")) {
                    String below = cells.get(p.add(0, -1, 0));
                    assertTrue(below != null && !below.equals("redstone_wire") && !below.equals("redstone_torch")
                            && !below.equals("repeater"), f + ": " + id + " bei " + p + " steht auf " + below);
                }
            });
            assertEquals("redstone_lamp", cells.get(c.output()), f);
            for (Pos pin : c.inputPins()) {
                assertEquals("lever", cells.get(pin), f);
            }
            assertEquals(1 << c.expr().inputs().size(), LogicCircuit.truthTable(c).size(), f);
        }
    }

    /**
     * Abstandsregel des Rasters: Ein Draht versorgt jeden Draht neben sich. Zwei Drähte verschiedener Netze dürfen
     * sich nicht waagerecht berühren – das prüft dieser Test für alle Drähte auf derselben Höhe über eine Netzzuordnung
     * aus dem Aufbau (Leitung/Sammler/ODER), sonst wäre das Übersprechen erst im Spiel zu sehen.
     */
    @Test
    void wiresOfDifferentNetsNeverTouch() {
        LogicCircuit.Circuit c = LogicCircuit.compile("Q = (A oder B) und nicht (C und D) oder A und nicht B");
        int orZ = c.output().z();
        Set<Pos> wires = new HashSet<>();
        for (CompiledBuild.Change ch : c.module().cells()) {
            if (ch.block().id().equals("redstone_wire")) {
                wires.add(ch.pos());
            }
        }
        for (Pos w : wires) {
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                Pos o = w.add(d[0], 0, d[1]);
                if (wires.contains(o)) {
                    assertEquals(net(w, orZ), net(o, orZ), "Drähte " + w + " und " + o + " gehören zu verschiedenen Netzen");
                }
            }
        }
    }

    /** Netz eines Drahts aus seiner Lage im Raster (siehe LogicCircuit-Kopf). */
    private static String net(Pos p, int orZ) {
        if (p.y() == 3) {
            return p.z() == orZ ? "oder" : "sammler" + p.x();
        }
        if (p.x() == 0) {
            return "eingang" + p.z() / 8;
        }
        int r = p.z() % 8;
        if (r == 0 || r == 1) {
            return "leitung" + p.z() / 8;
        }
        return "umgekehrt" + p.z() / 8;
    }

    // ------------------------------------------------------------------ mehrere Ausgänge (2026-10-29)

    /** Volladdierer, 2-Bit-Addierer, Vergleicher, 2-zu-4-Dekoder und eine große Einzelformel (8 Terme). */
    static final List<String> MULTI = List.of(
            "S = A xor B xor Ci; Co = A&B | Ci&(A xor B)",
            "S0 = A0 ^ B0; S1 = A1 ^ B1 ^ (A0 & B0); C = A1&B1 | (A1|B1)&A0&B0",
            "Größer = A & !B; Gleich = !(A ^ B); Kleiner = !A & B",
            "D0 = !A&!B; D1 = A&!B; D2 = !A&B; D3 = A&B",
            "P = A xor B xor C xor D");

    /** Die Formeln der Live-Probe – bis 5 Terme, ein Ausgang; Grundlage der Regression. */
    private static final List<String> PROBE = com.blackn0va.baumeister.LogicProbe.DEFAULT;

    @Test
    void severalFormulasShareTheirInputsAndKeepTheirOrder() {
        List<LogicExpr> all = LogicExpr.parseAll("s = a xor b xor c; u = a&b | c&(a^b)");
        assertEquals(List.of("s", "u"), all.stream().map(LogicExpr::output).toList());
        for (LogicExpr e : all) {
            assertEquals(List.of("A", "B", "C"), e.inputs());
        }
        for (int b = 0; b < 8; b++) {
            int sum = Integer.bitCount(b);
            assertEquals(sum % 2 == 1, all.get(0).eval(b), "Summe " + b);
            assertEquals(sum >= 2, all.get(1).eval(b), "Übertrag " + b);
        }
        // Ohne Namen: Q1, Q2 nach der Stelle; Zeilenumbruch trennt wie „;“, ein „;“ am Ende stört nicht.
        assertEquals(List.of("Q1", "Q2"), LogicExpr.parseAll("A & B\nA | B;").stream().map(LogicExpr::output).toList());
        // Eine Formel heißt weiter Q – wie parse().
        assertEquals("Q", LogicExpr.parseAll("A und B").getFirst().output());
        // Eingang nur in der zweiten Formel: kommt trotzdem in die gemeinsame Liste.
        assertEquals(List.of("A", "B", "C"), LogicExpr.parseAll("X = A & B; Y = C").getFirst().inputs());
    }

    @Test
    void outputNamesMayContainSharpS() {
        List<LogicExpr> cmp = LogicExpr.parseAll("Größer = A & !B; Gleich = !(A ^ B); Kleiner = !A & B");
        assertEquals(List.of("Größer", "Gleich", "Kleiner"), cmp.stream().map(LogicExpr::output).toList());
        assertEquals("Heiß", LogicExpr.parse("Heiß = A und B").output());
    }

    /**
     * Vorgabenamen dürfen einen Eingang nicht verdrängen: „P und Q“ (Lehrbuch-Schreibweise) baute auf b8ec700, die
     * Rückführungsprüfung lehnte es mit „„Q“ ist Ausgang und zugleich Eingang“ ab, obwohl niemand einen Ausgang Q
     * genannt hatte. Jetzt weicht der Vorgabename aus (Q → Y → Z), ausdrücklich genannte Namen prüft die Regel weiter.
     */
    @Test
    void unnamedFormulaMayUseInputQ() {
        LogicExpr pq = LogicExpr.parseAll("P und Q").getFirst();
        assertEquals(List.of("P", "Q"), pq.inputs());
        assertEquals("Y", pq.output());
        assertEquals("Y", LogicExpr.parse("P und Q").output());
        assertEquals(List.of("P", "Q"), LogicCircuit.compile("P xor Q").inputs());
        List<LogicExpr> two = LogicExpr.parseAll("A & Q1; B");
        assertEquals(List.of("A", "Q1", "B"), two.getFirst().inputs());
        assertEquals(List.of("Y1", "Q2"), two.stream().map(LogicExpr::output).toList());
        assertEquals(List.of("Q2", "Y2"), LogicExpr.parseAll("Q2 = A; B").stream().map(LogicExpr::output).toList());
        // Auch wenn Q, Y und Z alle Eingänge sind, findet sich ein freier Name.
        LogicExpr all = LogicExpr.parseAll("Q & Y & Z & X").getFirst();
        assertFalse(all.inputs().contains(all.output().toUpperCase(java.util.Locale.ROOT)), all.output());
        // Ein selbst gewählter Name als Eingang bleibt verboten (keine Rückführung).
        assertThrows(IllegalArgumentException.class, () -> LogicExpr.parseAll("Q = P und Q"));
    }

    @Test
    void rejectsMultiOutputNonsenseWithGermanReason() {
        String tooMany = assertThrows(IllegalArgumentException.class,
                () -> LogicExpr.parseAll("A; B; A&B; A|B; A^B; !A; !B; A&!B; !A&B; A|!B; !A|B; !(A&B); !(A|B); "
                        + "A^!B; A&B&C; A|B|C; A^B^C")).getMessage();
        assertTrue(tooMany.contains("Höchstens 16 Ausgänge"), tooMany);
        String twice = assertThrows(IllegalArgumentException.class,
                () -> LogicExpr.parseAll("S = A & B; s = A | B")).getMessage();
        assertTrue(twice.contains("zweimal"), twice);
        String loop = assertThrows(IllegalArgumentException.class,
                () -> LogicExpr.parseAll("C = A & B; S = C ^ D")).getMessage();
        assertTrue(loop.contains("Ausgang und zugleich Eingang"), loop);
        String constant = assertThrows(IllegalArgumentException.class,
                () -> LogicCircuit.compile("S = A ^ B; T = A | !A")).getMessage();
        assertTrue(constant.startsWith("Ausgang „T“ ist immer wahr"), constant);
        // 5-Bit-Parität braucht allein 16 Terme – passt in ein Stockwerk; zusammen 17 Terme gehen seit 2026-11-07 auf
        // zwei Stockwerke (vorher abgelehnt).
        assertEquals(16, LogicCircuit.compile("A ^ B ^ C ^ D ^ E").terms().size());
        assertEquals(17, LogicCircuit.compile("P = A ^ B ^ C ^ D ^ E; U = A & B").terms().size());
        // Fünf Paritäten à 16 Terme bräuchten 5 Stockwerke – höchstens 4.
        String floors = assertThrows(IllegalArgumentException.class, () -> LogicCircuit.compile(
                "P1 = A^B^C^D^E; P2 = !(A^B^C^D^E); P3 = A^B^C^D^!E; P4 = A^B^C^!D^E; P5 = A^B^!C^D^E"))
                .getMessage();
        assertTrue(floors.contains("Stockwerke"), floors);
        assertThrows(IllegalArgumentException.class, () -> LogicExpr.parseAll(" ; ; "));
    }

    @Test
    void largeLogicGoesUpInFloorsWithInputTowers() {
        // 3-zu-8-Dekoder: 8 Ausgänge → 2 Stockwerke à 4.
        LogicCircuit.Layout l = LogicCircuit.layout("D0 = !A&!B&!C; D1 = A&!B&!C; D2 = !A&B&!C; D3 = A&B&!C; "
                + "D4 = !A&!B&C; D5 = A&!B&C; D6 = !A&B&C; D7 = A&B&C");
        Map<Pos, String> cells = l.cells();
        List<Pos> lamps = l.circuit().outputs().stream().map(LogicCircuit.Output::lamp).toList();
        assertEquals(List.of(3, 3, 3, 3, 7, 7, 7, 7), lamps.stream().map(Pos::y).toList(), "Lampen zweier Stockwerke");
        // Hebel unten, um 2 nach rechts verschoben (Turm bei x=0, Draht bei x=1).
        assertEquals(List.of(new Pos(2, 1, 0), new Pos(2, 1, 8), new Pos(2, 1, 16)), l.circuit().inputPins());
        for (int i = 0; i < 3; i++) {
            int z = 8 * i;
            assertEquals("redstone_wire", cells.get(new Pos(1, 1, z)), "Draht neben dem Hebel in den Turm");
            assertEquals("stone", cells.get(new Pos(0, 1, z)));
            assertEquals("redstone_torch", cells.get(new Pos(0, 2, z)));
            assertEquals("stone", cells.get(new Pos(0, 3, z)));
            assertEquals("redstone_torch", cells.get(new Pos(0, 4, z)));
            assertEquals("stone", cells.get(new Pos(0, 5, z)), "zwei Umkehrungen: führt wieder den Eingang");
            assertTrue(cells.get(new Pos(1, 5, z)).startsWith("repeater"), "Verstärker in den Einspeise-Stein");
            assertEquals("stone", cells.get(new Pos(2, 5, z)), "oben statt Hebel ein Einspeise-Stein");
        }
        assertFalse(cells.containsValue("lever[face=floor,facing=back]") && cells.entrySet().stream()
                .anyMatch(e -> e.getKey().y() > 1 && e.getValue().startsWith("lever")), "Hebel nur unten");
    }

    @Test
    void threeDPrefixPutsEveryOutputOnItsOwnFloorAndSmallStaysFlat() {
        LogicCircuit.Circuit c = LogicCircuit.compile("3d S = A xor B; C = A & B");
        assertEquals(List.of(3, 7), c.outputs().stream().map(o -> o.lamp().y()).toList());
        assertTrue(c.module().description().contains("2 Stockwerke"));
        // Ohne „3d“ und klein: eine Ebene, Block für Block wie vorher (Fingerabdruck-Test oben).
        LogicCircuit.Circuit flat = LogicCircuit.compile("S = A xor B; C = A & B");
        assertEquals(List.of(3, 3), flat.outputs().stream().map(o -> o.lamp().y()).toList());
        assertEquals(new Pos(0, 1, 0), flat.inputPins().getFirst());
    }

    @Test
    void floorsAreFilledInOrderUpToFourOutputsAndSixteenTerms() {
        List<List<LogicMinimizer.Term>> outs = new java.util.ArrayList<>();
        for (int k : new int[] {10, 6, 1, 16, 2}) {
            outs.add(java.util.Collections.nCopies(k, null));
        }
        assertEquals(List.of(List.of(0, 1), List.of(2), List.of(3), List.of(4)), LogicCircuit.floors(outs, false),
                "10+6 = 16 voll, 1+16 zu viel, 16+2 zu viel");
        assertEquals(List.of(List.of(0, 1, 2, 3), List.of(4)), LogicCircuit.floors(
                java.util.Collections.nCopies(5, List.of()), false), "4 Ausgänge je Stockwerk");
        assertEquals(List.of(List.of(0), List.of(1), List.of(2)), LogicCircuit.floors(
                java.util.Collections.nCopies(3, List.of()), true), "3d: jeder für sich");
    }

    /**
     * Liest die Funktion aus den gebauten Zellen zurück – unabhängig von der Buchführung des Compilers: Lampe →
     * ODER-Zeile nach links → Wandfackeln davor = Sammler → Abgriffe in der Spalte daneben → Leitung des Abgriffs
     * (z = 8i positiv, 8i+4 negiert). Vertauschte Gruppen, ein Sammler an der falschen ODER-Zeile oder ein Abgriff
     * an der falschen Leitung fallen hier auf.
     */
    @Test
    void builtCellsComputeExactlyEveryOutputsTruthTable() {
        List<String> all = new ArrayList<>(MULTI);
        all.addAll(PROBE);
        for (String f : all) {
            LogicCircuit.Layout l = LogicCircuit.layout(f);
            LogicCircuit.Circuit c = l.circuit();
            Map<Pos, String> cells = l.cells();
            int n = c.inputs().size();
            int top = 8 * n;
            int orZ = top + 3;
            Map<String, Map<String, Boolean>> want = LogicCircuit.truthTables(c);
            for (LogicCircuit.Output o : c.outputs()) {
                assertEquals("redstone_lamp", cells.get(o.lamp()), f);
                List<List<int[]>> terms = new ArrayList<>();
                for (int x = o.lamp().x() - 1; isWireOrRepeater(cells.get(new Pos(x, 3, orZ))); x--) {
                    String torch = cells.get(new Pos(x, 3, orZ - 1));
                    if (torch == null || !torch.startsWith("redstone_wall_torch")) {
                        continue;
                    }
                    List<int[]> lits = new ArrayList<>();
                    int xs = x - 1;
                    int zmin = Integer.MAX_VALUE;
                    for (int z = 0; z <= top; z++) {
                        if ("redstone_torch".equals(cells.get(new Pos(xs, 2, z)))) {
                            assertEquals("redstone_wire", cells.get(new Pos(xs, 1, z - 1)), f + " Abgriff " + xs);
                            int line = z - 2;
                            assertTrue(line % 8 == 0 || line % 8 == 4, f + " Abgriff zwischen den Leitungen " + z);
                            lits.add(new int[] {line / 8, line % 8 == 0 ? 1 : 0});
                            zmin = Math.min(zmin, z);
                        }
                    }
                    assertFalse(lits.isEmpty(), f + ": Sammler " + x + " ohne Abgriff");
                    for (int z = zmin; z <= top; z++) {
                        assertTrue(isWireOrRepeater(cells.get(new Pos(x, 3, z))), f + ": Sammler " + x + " bricht bei "
                                + z);
                    }
                    terms.add(lits);
                }
                assertEquals(o.terms().size(), terms.size(), f + " " + o.name());
                for (int b = 0; b < 1 << n; b++) {
                    int bb = b;
                    boolean on = terms.stream().anyMatch(t -> t.stream().allMatch(lit -> (bb >> lit[0] & 1) == lit[1]));
                    List<String> key = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        key.add(c.inputs().get(i) + "=" + (b >> i & 1));
                    }
                    assertEquals(want.get(o.name()).get(String.join(",", key)), on, f + " " + o.name() + " " + key);
                }
            }
        }
    }

    /**
     * Signalstärke nachgerechnet: Draht −1 je Block, Verstärker macht wieder 15. Jede Leitung muss an jedem Abgriff
     * noch ≥ 2 haben (der Abgriffsdraht ≥ 1), jede einzelne Term-Fackel muss ihre Lampe mit ≥ 1 erreichen. Ohne die
     * Verstärker endet die 5-Bit-Parität (Leitung 47 lang) bei Spalte 15 im Nichts.
     */
    @Test
    void signalReachesEveryTapAndEveryLamp() {
        List<String> all = new ArrayList<>(MULTI);
        all.add("A ^ B ^ C ^ D ^ E");
        all.addAll(PROBE);
        for (String f : all) {
            LogicCircuit.Layout l = LogicCircuit.layout(f);
            Map<Pos, String> cells = l.cells();
            int n = l.circuit().inputs().size();
            for (int i = 0; i < n; i++) {
                for (int z : new int[] {8 * i, 8 * i + 4}) {
                    int s = 16;
                    for (int x = 1; isWireOrRepeater(cells.get(new Pos(x, 1, z))); x++) {
                        if (cells.get(new Pos(x, 1, z)).startsWith("repeater")) {
                            assertTrue(s >= 1, f + ": Verstärker ohne Signal bei " + x + "/" + z);
                            s = 16;
                            continue;
                        }
                        s--;
                        boolean tap = "redstone_wire".equals(cells.get(new Pos(x, 1, z + 1)))
                                && "stone".equals(cells.get(new Pos(x, 1, z + 2)));
                        assertTrue(!tap || s >= 2, f + ": Abgriff bei x=" + x + " z=" + z + " bekommt nur " + s);
                    }
                }
            }
            int orZ = 8 * n + 3;
            for (LogicCircuit.Output o : l.circuit().outputs()) {
                for (int x = o.lamp().x() - 1; isWireOrRepeater(cells.get(new Pos(x, 3, orZ))); x--) {
                    String torch = cells.get(new Pos(x, 3, orZ - 1));
                    if (torch == null || !torch.startsWith("redstone_wall_torch")) {
                        continue;
                    }
                    int s = 15;
                    for (int xx = x + 1; xx < o.lamp().x(); xx++) {
                        if (cells.get(new Pos(xx, 3, orZ)).startsWith("repeater")) {
                            assertTrue(s >= 1, f + ": ODER-Verstärker ohne Signal bei " + xx);
                            s = 16;
                        } else {
                            s--;
                        }
                    }
                    assertTrue(s >= 1, f + " " + o.name() + ": Fackel bei x=" + x + " erreicht die Lampe nicht (" + s
                            + ")");
                }
            }
        }
    }

    /**
     * Abstandsregel für alle Formen: waagerecht benachbarte Drähte, Fackeln und Lampen gehören zum selben Netz. Fängt
     * eine ODER-Zeile, die in die Lampe des Nachbarausgangs zeigt (Gruppen ohne Lücke), und Übersprechen zwischen
     * Leitungen.
     */
    @Test
    void neighboursOfDifferentNetsNeverTouchInAnyCircuit() {
        List<String> all = new ArrayList<>(MULTI);
        all.add("A ^ B ^ C ^ D ^ E");
        all.addAll(PROBE);
        // Stockwerke (2026-11-07): auch über Türme und gestapelte Ebenen hinweg.
        all.add("3d S = A xor B; C = A & B");
        all.add("P = A ^ B ^ C ^ D ^ E; U = A & B");
        all.add("D0 = !A&!B&!C; D1 = A&!B&!C; D2 = !A&B&!C; D3 = A&B&!C; D4 = !A&!B&C; D5 = A&!B&C; D6 = !A&B&C; "
                + "D7 = A&B&C");
        for (String f : all) {
            LogicCircuit.Layout l = LogicCircuit.layout(f);
            Map<Pos, String> nets = new HashMap<>(l.nets());
            List<LogicCircuit.Output> outs = l.circuit().outputs();
            for (int j = 0; j < outs.size(); j++) {
                // Lampe gehört zum Netz des Drahts direkt davor (in Stockwerken heißen die Netze „s1_oder0“).
                Pos lamp = outs.get(j).lamp();
                nets.put(lamp, nets.getOrDefault(lamp.add(-1, 0, 0), "oder" + j));
            }
            for (Map.Entry<Pos, String> e : nets.entrySet()) {
                String block = l.cells().get(e.getKey());
                if (block.startsWith("repeater")) {
                    continue;    // Verstärker nehmen nur von hinten und geben nur nach vorn
                }
                for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    Pos o = e.getKey().add(d[0], 0, d[1]);
                    String other = nets.get(o);
                    if (other != null && !l.cells().get(o).startsWith("repeater")) {
                        assertEquals(e.getValue(), other, f + ": " + block + " bei " + e.getKey() + " berührt "
                                + l.cells().get(o) + " bei " + o);
                    }
                }
            }
        }
    }

    /**
     * Fingerabdruck der Zellen je Probeformel aus dem Stand b8ec700 (2026-10-28 mit 44/44 auf dem Testserver geprüft):
     * Zellenzahl und die ersten 16 Hexzeichen von SHA-256 über die sortierten Zeilen „x,y,z=block“. Erzeugt 2026-10-29
     * mit den aus b8ec700 übersetzten Klassen, der neue Stand ergab Zeile für Zeile dasselbe. Vorher prüfte dieser Test
     * nur Lampenort und Zusatzverstärker – ein Umbau, der andere Blöcke verschiebt, bliebe grün. Wer hier einen Wert
     * ändert, hat das Raster geändert und muss LogicProbe in allen vier Richtungen neu laufen lassen.
     */
    private static final Map<String, String> TESTED_LAYOUT = Map.ofEntries(
            Map.entry("Q = A und B", "185 28ff489d7369aa32"),
            Map.entry("Q = A oder B", "279 3e2c50dbe2d15017"),
            Map.entry("Q = nicht A", "100 c6cee3b2c72aaa9b"),
            Map.entry("Q = A xor B", "295 36da8254cc9e1e63"),
            Map.entry("Q = A und nicht B", "185 27c993e5b7d4cae0"),
            Map.entry("Mehrheit = A&B | A&C | B&C", "570 9c493e61efade175"),
            Map.entry("Licht = A und (B oder nicht C)", "422 11290d1c290272c6"),
            Map.entry("S = A xor B xor C", "750 8150d3d5946cf5ea"),
            Map.entry("Q = (A oder B) und nicht (C und D)", "929 5ffb2c6d1848b0ae"),
            Map.entry("Q = A & !B & C & !D & E", "416 b83703e2d04acea1"),
            Map.entry("Q = (A xor B) | (C & D & E)", "880 41c0862126efdfe2"));

    /** Bis 5 Terme mit einem Ausgang: kein einziger Zusatzverstärker – die auf dem Server geprüfte Fassung bleibt. */
    @Test
    void smallSingleCircuitsKeepTheTestedLayout() throws Exception {
        assertEquals(TESTED_LAYOUT.keySet(), Set.copyOf(PROBE));
        for (String f : PROBE) {
            List<String> lines = new ArrayList<>();
            for (CompiledBuild.Change ch : LogicCircuit.compile(f).module().cells()) {
                lines.add(ch.pos().x() + "," + ch.pos().y() + "," + ch.pos().z() + "=" + ch.block().asString());
            }
            lines.sort(null);
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
            assertEquals(TESTED_LAYOUT.get(f), lines.size() + " " + HexFormat.of().formatHex(hash).substring(0, 16), f);
            LogicCircuit.Circuit c = LogicCircuit.compile(f);
            int k = c.terms().size();
            int n = c.inputs().size();
            assertEquals(new Pos(3 * k + 2, 3, 8 * n + 3), c.output(), f);
            long boosters = c.module().cells().stream()
                    .filter(ch -> ch.block().id().equals("repeater") && "left".equals(ch.block().props().get("facing")))
                    .count();
            assertEquals(0, boosters, f);
        }
    }

    @Test
    void announcementNamesTheLampsFromLeftToRight() {
        String single = "Q = A und B";
        assertEquals("Baue Q = A und B: 2 Eingänge (A, B, Hebel links von vorn nach hinten), 1 Terme, Lampe hinten "
                + "rechts.", LogicCircuit.announce(LogicCircuit.compile(single), single));
        String adder = "S = A xor B xor Ci; Co = A&B | Ci&(A xor B)";
        LogicCircuit.Circuit c = LogicCircuit.compile(adder);
        assertEquals("Baue 2 Ausgänge aus 3 Eingängen (A, B, CI, Hebel links von vorn nach hinten), 7 Terme; Lampen "
                + "hinten von links nach rechts: S, Co.", LogicCircuit.announce(c, adder));
        assertTrue(c.outputs().get(0).lamp().x() < c.outputs().get(1).lamp().x());
        assertTrue(c.module().description().endsWith("Lampen hinten von links: S, Co"), c.module().description());
    }

    @Test
    void announcementNamesTheLampsFloorByFloor() {
        // Parität aus 5 Eingängen = 16 Terme, ein Stockwerk voll – U muss eins höher.
        String parity = "P = A ^ B ^ C ^ D ^ E; U = A & B";
        String a = LogicCircuit.announce(LogicCircuit.compile(parity), parity);
        assertTrue(a.contains("17 Terme, 2 Stockwerke übereinander (Eingänge im Turm links)"), a);
        assertTrue(a.endsWith("Lampen hinten von links nach rechts – Stockwerk 1: P; Stockwerk 2: U."), a);
        LogicCircuit.Circuit decoder = LogicCircuit.compile("D0 = !A&!B&!C; D1 = A&!B&!C; D2 = !A&B&!C; "
                + "D3 = A&B&!C; D4 = !A&!B&C; D5 = A&!B&C; D6 = !A&B&C; D7 = A&B&C");
        assertEquals(Map.of(0, List.of("D0", "D1", "D2", "D3"), 1, List.of("D4", "D5", "D6", "D7")),
                LogicCircuit.lampsByFloor(decoder));
        assertEquals(Map.of(0, List.of("S", "C")), LogicCircuit.lampsByFloor(
                LogicCircuit.compile("S = A xor B; C = A & B")), "flach: ein Stockwerk");
    }

    private static boolean isWireOrRepeater(String block) {
        return block != null && (block.equals("redstone_wire") || block.startsWith("repeater"));
    }
}
