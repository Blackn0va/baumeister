package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.CompiledBuild.Action;
import com.blackn0va.baumeister.plan.CompiledBuild.Change;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Aufbau-Regeln der eingebauten Maschinen ohne Server. Ob sie wirklich schalten, prüft MachineProbe auf dem
 * Testserver; hier nur, was sich ohne Welt sagen lässt.
 */
class MachinesTest {

    private static final Machines LIB = new Machines();
    private static final Set<String> NEEDS_FLOOR = Set.of("redstone_wire", "repeater", "comparator", "redstone_torch",
            "lever");

    /**
     * Alle Maschinen der Bibliothek plus jede Fassung des Mehrsorten-Sortierers, die nur über den Namen entsteht
     * (item_sortierer_2 … _8, mit und ohne Sorten): Die allgemeinen Bauregeln unten gelten für jede, nicht nur für die
     * eine in der Bibliothek.
     */
    private static final List<Machines.Machine> WITH_SORTERS = withSorters();

    private static List<Machines.Machine> withSorters() {
        java.util.List<Machines.Machine> out = new java.util.ArrayList<>(LIB.machines());
        for (int n = Machines.MULTI_SORTER_MIN; n <= Machines.MULTI_SORTER_MAX; n++) {
            if (n != Machines.MULTI_SORTER_PROBED) {
                out.add(LIB.machine(Machines.MULTI_SORTER + n).orElseThrow());
            }
        }
        out.add(LIB.machine("item_sortierer_3:cobblestone,sand,oak_planks").orElseThrow());
        out.add(LIB.machine("item_sortierer_8:sand,dirt").orElseThrow());
        return List.copyOf(out);
    }

    private static Map<Pos, BlockSpec> cells(ModuleLibrary.Module m) {
        Map<Pos, BlockSpec> out = new HashMap<>();
        for (Change c : m.cells()) {
            out.put(c.pos(), c.block());
        }
        return out;
    }

    @Test
    void everyMachineHasAUniqueValidNameAndFitsTheModuleLimits() {
        Set<String> names = new HashSet<>();
        for (Machines.Machine m : LIB.machines()) {
            String n = m.module().name();
            assertTrue(n.matches("[a-z0-9_]{2,32}"), n);
            assertTrue(names.add(n), "doppelt: " + n);
            Pos s = m.module().size();
            // Breite/Tiefe bis zum Raster des Logik-Compilers (16 Terme → 57 breit, 5 Eingänge → 43 tief; LogicCircuit):
            // Vorher fest 32 – die ALU-Scheibe (5 Eingänge, 2026-10-31) ist 43 tief und passt trotzdem in jeden Bauplatz.
            // Ausnahme addierer_4bit/akkumulator_4bit: vier Volladdierer nebeneinander (4 × 28), kein einzelnes Raster;
            // der Akkumulator hat davor die Rückführung und dahinter Speicher und Taktleitung (45 tief), der mit Löschen
            // zusätzlich die Sperre vor dem Master und die Löschleitung hinter der Taktleitung (47 tief).
            boolean chain = n.equals("addierer_4bit") || n.equals("akkumulator_4bit")
                    || n.equals(Machines.CLEAR_ACCUMULATOR);
            // Ausnahme anzeige: 7 Ausgänge nebeneinander plus Leitungen und Ziffer dahinter (SevenSegment), passt in
            // jeden Bauplatz (bau.max-ausdehnung 192).
            boolean display = n.equals("anzeige");
            int maxX = display ? 128 : chain ? 4 * Machines.ADDER_STRIDE : 57;
            int maxZ = display ? 80 : chain ? 48 : 44;
            assertTrue(s.x() <= maxX && s.y() <= 32 && s.z() <= maxZ, n + " " + s);
            assertFalse(m.module().description().isBlank(), n);
        }
        assertTrue(names.containsAll(Set.of("nicht_gatter", "oder_gatter", "und_gatter", "xor_gatter", "takt",
                "kolbentuer", "halbaddierer", "rs_speicher", "automatiktuer", "wasserfahrstuhl", "nand_gatter",
                "nor_gatter", "xnor_gatter", "lampenreihe", "klingel", "glocke", "abwaerts_fahrstuhl", "nachtlicht",
                "t_flipflop", "sammelstelle", "kombischloss", "pfeilfalle", "zaehler", "zuckerrohrfarm",
                "volladdierer", "impulsverlaengerer", "schmelzanlage", "item_sortierer")));
    }

    /**
     * Volladdierer kommt aus dem Logik-Compiler: Die Tabellen der Probe müssen die echten Summen sein (S = Parität,
     * CO = mindestens zwei Einsen) – nicht nur das, was der Compiler aus der Formel abschreibt.
     */
    @Test
    void fullAdderTablesAreBinaryAddition() {
        Machines.Machine m = LIB.machine("volladdierer").orElseThrow();
        assertEquals(java.util.List.of("A", "B", "CI"), m.inputs().stream().map(Machines.Pin::name).toList());
        assertEquals(java.util.List.of("S", "CO"), m.outputs().stream().map(Machines.Pin::name).toList());
        for (int bits = 0; bits < 8; bits++) {
            String key = "A=" + (bits & 1) + ",B=" + (bits >> 1 & 1) + ",CI=" + (bits >> 2 & 1);
            int sum = Integer.bitCount(bits);
            assertEquals(sum % 2 == 1, m.expect().get("S").get(key), key);
            assertEquals(sum >= 2, m.expect().get("CO").get(key), key);
        }
    }

    /**
     * 4-Bit-Addierer: Die Stichproben der Probe sind echte Summen (0..31), jede Stufe setzt ihren Übertrag über
     * Verstärker in die nächste, und nur Bit 0 hat einen CI-Hebel – die anderen bekommen ihn von der Stufe davor.
     */
    @Test
    void fourBitAdderChainsTheCarryThroughAllStages() {
        Machines.Machine m = LIB.machine("addierer_4bit").orElseThrow();
        assertEquals(java.util.List.of("A0", "B0", "CI", "A1", "B1", "A2", "B2", "A3", "B3"),
                m.inputs().stream().map(Machines.Pin::name).toList());
        assertEquals(java.util.List.of("S0", "S1", "S2", "S3", "C4"),
                m.outputs().stream().map(Machines.Pin::name).toList());
        for (Machines.Step st : m.sequence()) {
            int a = 0;
            int b = 0;
            int sum = 0;
            for (int i = 0; i < 4; i++) {
                a |= st.inputs().get("A" + i) ? 1 << i : 0;
                b |= st.inputs().get("B" + i) ? 1 << i : 0;
                sum |= st.expect().get("S" + i) ? 1 << i : 0;
            }
            sum |= st.expect().get("C4") ? 16 : 0;
            assertEquals(a + b + (st.inputs().get("CI") ? 1 : 0), sum, st.toString());
        }
        // Mindestens einmal läuft der Übertrag durch alle vier Stufen (15 + 1).
        assertTrue(m.sequence().stream().anyMatch(st -> st.expect().get("C4") && !st.expect().get("S0")
                && !st.expect().get("S3") && !st.inputs().get("CI")));
        Map<Pos, BlockSpec> c = cells(m.module());
        long levers = c.values().stream().filter(b -> b.id().equals("lever")).count();
        assertEquals(9, levers, "8 Datenhebel + CI");
        for (int bit = 0; bit < 3; bit++) {
            int dx = bit * Machines.ADDER_STRIDE;
            assertEquals("repeater", c.get(new Pos(dx + 25, 3, 27)).id(), "Übertrag raus, Stufe " + bit);
            assertEquals("repeater", c.get(new Pos(dx + 27, 1, 16)).id(), "Übertrag rein, Stufe " + (bit + 1));
            assertEquals("redstone_wire", c.get(new Pos(dx + Machines.ADDER_STRIDE, 1, 16)).id());
            for (int z = 16; z <= 27; z++) {
                int dz = z;
                assertTrue(c.entrySet().stream().anyMatch(e -> e.getKey().x() == dx + 26 && e.getKey().z() == dz
                        && e.getValue().id().equals("redstone_wire")), "Übertragsleitung fehlt bei z " + z);
            }
        }
        assertEquals("redstone_lamp", c.get(new Pos(3 * Machines.ADDER_STRIDE + 25, 3, 27)).id(), "C4");
    }

    /**
     * Akkumulator: Der Ablauf addiert B nur bei steigendem Takt (Master-Slave), jede Q-Lampe sitzt unter einem Draht
     * der Rückführung (unter einem Verstärker bliebe sie dunkel), und die A-Eingänge sind keine Hebel mehr.
     */
    @Test
    void accumulatorAddsBOnEveryRisingClock() {
        Machines.Machine m = LIB.machine("akkumulator_4bit").orElseThrow();
        assertEquals(java.util.List.of("B0", "B1", "B2", "B3", "CI", "T"),
                m.inputs().stream().map(Machines.Pin::name).toList());
        int acc = 0;
        boolean clock = false;
        boolean wrapped = false;
        for (Machines.Step st : m.sequence()) {
            int b = 0;
            int q = 0;
            for (int i = 0; i < 4; i++) {
                b |= st.inputs().get("B" + i) ? 1 << i : 0;
                q |= st.expect().get("Q" + i) ? 1 << i : 0;
            }
            boolean t = st.inputs().get("T");
            if (t && !clock) {
                wrapped |= acc + b > 15;
                acc = (acc + b) & 15;
            }
            clock = t;
            assertEquals(acc, q, st.toString());
        }
        assertTrue(wrapped, "Überlauf mindestens einmal");
        Map<Pos, BlockSpec> c = cells(m.module());
        assertEquals(6, c.values().stream().filter(b -> b.id().equals("lever")).count(), "B0..B3, CI, T");
        Machines.Pin clockPin = m.inputs().getLast();
        assertEquals("lever", c.get(clockPin.at()).id(), "Takt");
        for (Machines.Pin q : m.outputs()) {
            assertEquals("redstone_lamp", c.get(q.at()).id(), q.name());
            assertEquals("redstone_wire", c.get(q.at().add(0, 1, 0)).id(), q.name() + " braucht Draht darüber");
        }
        // Master-Riegel mit Verzögerung 3: kürzer öffnet der Master, bevor der Slave zu ist (siehe accumulator4).
        assertTrue(c.values().stream().filter(b -> b.asString().contains("delay=3")).count() >= 4);
    }

    /**
     * Akkumulator mit Löschen (2026-11-20): Der Ablauf der Probe ist das, was die Maschine können soll – ein Takt mit
     * L an ergibt 0 statt AKKU + B, L allein bei T aus ändert nichts, danach rechnet er normal weiter, und einmal wird
     * 15 gelöscht, also jedes der vier Bits. B und L ändern sich nie im selben Schritt wie der Takt (Haltezeit: sonst
     * misst die Probe einen Wettlauf zwischen Daten und Riegel, keinen Schaltungsfehler).
     */
    @Test
    void clearAccumulatorSetsZeroOnTheClockWhileLIsOn() {
        Machines.Machine m = LIB.machine(Machines.CLEAR_ACCUMULATOR).orElseThrow();
        assertEquals(List.of("B0", "B1", "B2", "B3", "CI", "T", "L"),
                m.inputs().stream().map(Machines.Pin::name).toList());
        assertEquals(List.of("Q0", "Q1", "Q2", "Q3"), m.outputs().stream().map(Machines.Pin::name).toList());
        int acc = 0;
        boolean clock = false;
        int prevB = 0;
        boolean prevL = false;
        boolean clearedWithB = false;
        boolean clearedFifteen = false;
        boolean heldWithoutClock = false;
        boolean addedAfterClear = false;
        boolean justCleared = false;
        boolean wrapped = false;
        for (Machines.Step st : m.sequence()) {
            int b = 0;
            int q = 0;
            for (int i = 0; i < 4; i++) {
                b |= st.inputs().get("B" + i) ? 1 << i : 0;
                q |= st.expect().get("Q" + i) ? 1 << i : 0;
            }
            boolean t = st.inputs().get("T");
            boolean l = st.inputs().get("L");
            if (t != clock) {
                assertEquals(prevB, b, "B ändert sich mit dem Takt: " + st);
                assertEquals(prevL, l, "L ändert sich mit dem Takt: " + st);
            }
            if (t && !clock) {
                if (l) {
                    clearedWithB |= b != 0 && acc != 0;
                    clearedFifteen |= acc == 15;
                    acc = 0;
                    justCleared = true;
                } else {
                    addedAfterClear |= justCleared && b != 0;
                    wrapped |= acc + b > 15;
                    acc = (acc + b) & 15;
                    justCleared = false;
                }
            } else if (l && !t) {
                heldWithoutClock |= acc != 0;
            }
            clock = t;
            prevB = b;
            prevL = l;
            assertEquals(acc, q, st.toString());
        }
        assertTrue(clearedWithB, "Löschen mit B ≠ 0 – sonst sähe ein Akkumulator ohne Sperre nur B = 0 + 0");
        assertTrue(clearedFifteen, "Löschen von 15: alle vier Sperren");
        assertTrue(heldWithoutClock, "L ohne Takt lässt den Wert stehen");
        assertTrue(addedAfterClear, "nach dem Löschen wird wieder addiert");
        assertTrue(wrapped, "Überlauf nach dem Löschen");
    }

    /**
     * Löschnetz des Akkumulators mit Löschen: Vom Hebel L aus erreicht die Leitung genau die vier Sperren – je ein
     * Komparator im Subtraktionsmodus zwischen dem Verstärker an der Summenlampe und dem Master, von der Seite gespeist
     * durch einen Verstärker (Draht käme mit weniger als 15 an, und 15 − Stärke ließe eine 1 durch). Sie berührt
     * nichts sonst: keine Lampe, keinen anderen Hebel, kein Netz des Takts. Verfolgt werden Draht, Verstärker in ihrer
     * Richtung und die Blöcke, die beide speisen (trace). Für die Teile dieser Maschine – Draht, Verstärker, Komparator,
     * Fackel, Lampe, Hebel – ist das großzügiger als Minecraft (Draht speist hier jeden Nachbarblock, nicht nur die,
     * auf die er zeigt, und verbindet sich mit jedem Draht eine Stufe schräg): ein Kontakt hier ist höchstens einer zu
     * viel, nie einer zu wenig. Ob die Maschine wirklich löscht, prüft MachineProbe in vier Richtungen.
     */
    @Test
    void clearLineFeedsExactlyTheFourGatesAndNothingElse() {
        Machines.Machine m = LIB.machine(Machines.CLEAR_ACCUMULATOR).orElseThrow();
        Map<Pos, BlockSpec> c = cells(m.module());
        Pos lever = m.inputs().getLast().at();
        assertEquals("L", m.inputs().getLast().name());
        Net net = trace(c, lever);
        assertEquals(4, net.gates().size(), "Sperren: " + net.gates());
        for (Pos gate : net.gates()) {
            BlockSpec cmp = c.get(gate);
            assertEquals("comparator", cmp.id(), gate.toString());
            assertEquals("subtract", cmp.props().get("mode"), gate + " " + cmp);
            assertEquals("front", cmp.props().get("facing"), gate + " " + cmp);
            assertEquals("minecraft:repeater[facing=front]", c.get(gate.add(0, 0, -1)).asString(),
                    "liest die Summenlampe");
            assertEquals("redstone_lamp", c.get(gate.add(0, 0, -2)).id(), "Summenlampe vor der Sperre");
            assertEquals("minecraft:repeater[facing=front]", c.get(gate.add(0, 0, 1)).asString(),
                    "Master hinter der Sperre");
            assertEquals("repeater", c.get(gate.add(1, 0, 1)).id(), "Riegel am Master");
        }
        assertTrue(net.sideFromRepeater(), "Seiteneingang ohne Verstärker davor");
        assertTrue(net.others().isEmpty(), "Löschleitung trifft " + net.others());
        Net clock = trace(c, m.inputs().get(m.inputs().size() - 2).at());
        assertTrue(clock.gates().isEmpty(), "Takt speist eine Sperre");
        assertTrue(java.util.Collections.disjoint(net.cells(), clock.cells()), "Löschleitung berührt die Taktleitung");
    }

    /** Ergebnis von trace: besuchte Zellen, von der Seite erreichte Komparatoren, alles andere Erreichte. */
    private record Net(Set<Pos> cells, Set<Pos> gates, boolean sideFromRepeater, Set<String> others) {
    }

    /**
     * Folgt einem Netz ab dem Hebel at: Draht zu Draht (auch eine Stufe schräg), in einen Verstärker nur von hinten,
     * aus ihm in die Zelle vor ihm. Ein Komparator, von der Seite erreicht, ist eine Sperre; ein Komparator von hinten,
     * ein Verstärker, auf dessen Seite ein anderer zeigt (Riegel), eine Lampe oder ein Hebel sind „anderes“. Feste
     * Blöcke, an die Draht grenzt (darunter oder daneben) oder in die ein Verstärker zeigt, gelten als gespeist; was
     * an einem gespeisten Block hängt oder ihn liest (Verstärker, Komparator, Fackel, Lampe), ist ebenfalls „anderes“,
     * und Draht neben einem Block, in den ein Verstärker zeigt, wird weiter verfolgt.
     */
    private static Net trace(Map<Pos, BlockSpec> c, Pos at) {
        record Visit(Pos p, Pos src) {
        }
        Set<Pos> seen = new HashSet<>();
        Set<Pos> gates = new HashSet<>();
        Set<String> others = new HashSet<>();
        boolean allSidesFromRepeater = true;
        java.util.ArrayDeque<Visit> todo = new java.util.ArrayDeque<>();
        int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        seen.add(at);
        todo.add(new Visit(at.add(0, -1, 0), at));                         // Block, an dem der Hebel hängt
        for (int[] d : around) {
            todo.add(new Visit(at.add(d[0], 0, d[2]), at));
        }
        while (!todo.isEmpty()) {
            Visit v = todo.poll();
            Pos p = v.p();
            BlockSpec b = c.get(p);
            if (b == null || seen.contains(p)) {
                continue;
            }
            Pos offset = new Pos(v.src().x() - p.x(), v.src().y() - p.y(), v.src().z() - p.z());
            switch (b.id()) {
                case "redstone_wire" -> {
                    seen.add(p);
                    todo.add(new Visit(p.add(0, -1, 0), p));
                    for (int[] d : around) {
                        for (int dy = -1; dy <= 1; dy++) {
                            Pos n = p.add(d[0], dy, d[2]);
                            if (dy == 0 || c.get(n) != null && c.get(n).id().equals("redstone_wire")) {
                                todo.add(new Visit(n, p));
                            }
                        }
                    }
                }
                case "repeater" -> {
                    Pos in = inputOffset(b);
                    if (offset.equals(in)) {
                        seen.add(p);
                        todo.add(new Visit(p.add(-in.x(), 0, -in.z()), p));
                    } else if (isDiode(c.get(v.src())) && offset.x() * in.x() + offset.z() * in.z() == 0) {
                        others.add("Riegel an " + p);
                    }
                }
                case "comparator" -> {
                    Pos in = inputOffset(b);
                    if (offset.equals(in)) {
                        others.add("Komparator von hinten bei " + p);
                    } else if (!offset.equals(new Pos(-in.x(), 0, -in.z()))) {
                        gates.add(p);
                        allSidesFromRepeater &= c.get(v.src()).id().equals("repeater");
                    }
                }
                case "redstone_lamp", "lever" -> others.add(b.id() + " bei " + p);
                default -> {
                    // Jeder andere Block gilt als leitend (auch Glas – großzügig); Fackeln und Hebel speist Draht nicht.
                    BlockSpec s = c.get(v.src());
                    boolean strong = isDiode(s) || s.id().equals("lever");
                    if (b.isAir() || NEEDS_FLOOR.contains(b.id()) || b.id().equals("redstone_wall_torch")
                            || !strong && !s.id().equals("redstone_wire")) {
                        continue;
                    }
                    seen.add(p);
                    for (int[] d : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}}) {
                        Pos n = p.add(d[0], d[1], d[2]);
                        BlockSpec nb = c.get(n);
                        if (nb == null || n.equals(v.src())) {
                            continue;
                        }
                        Pos back = new Pos(p.x() - n.x(), p.y() - n.y(), p.z() - n.z());
                        boolean reads = isDiode(nb) && back.equals(inputOffset(nb));
                        boolean torch = nb.id().equals("redstone_wall_torch") && back.equals(wallOf(nb))
                                || nb.id().equals("redstone_torch") && d[1] == 1;
                        if (reads || torch || nb.id().equals("redstone_lamp")) {
                            others.add(nb.id() + " an gespeistem Block " + p + " bei " + n);
                        } else if (strong && nb.id().equals("redstone_wire")) {
                            todo.add(new Visit(n, p));
                        }
                    }
                }
            }
        }
        return new Net(seen, gates, allSidesFromRepeater, others);
    }

    private static boolean isDiode(BlockSpec b) {
        return b != null && (b.id().equals("repeater") || b.id().equals("comparator"));
    }

    /** Wo eine Wandfackel hängt: gegenüber der Seite, in die sie zeigt (lokal). */
    private static Pos wallOf(BlockSpec torch) {
        Pos away = inputOffset(torch);
        return new Pos(-away.x(), 0, -away.z());
    }

    /** Wo ein Verstärker oder Komparator sein Signal herbekommt (lokal, „facing“ = Seite des Eingangs). */
    private static Pos inputOffset(BlockSpec b) {
        return switch (b.props().getOrDefault("facing", "front")) {
            case "back" -> new Pos(0, 0, 1);
            case "left" -> new Pos(-1, 0, 0);
            case "right" -> new Pos(1, 0, 0);
            default -> new Pos(0, 0, -1);
        };
    }

    /**
     * Fingerabdruck jeder Maschine vor dem Akkumulator mit Löschen (Stand 2fe5a8a, 2026-11-20): Zellenzahl und die
     * ersten 16 Hexzeichen von SHA-256 über die sortierten Zeilen „x,y,z=block“, wie LogicCompilerTest.TESTED_LAYOUT.
     * Erzeugt aus den Klassen von 2fe5a8a; der neue Stand ergab für alle 47 dieselben Werte (seit T-016, 2026-11-21: 48 Einträge, item_sortierer_3 neu). Die Reihenfolge der
     * Zellen (Bau-Reihenfolge) sichert dieser Test NICHT – er sortiert vor dem Hashen; gemessen 2026-11-21 war sie
     * dennoch byte-gleich (Ausgabe aller 47 samt Pins und Ablauf gegen 2fe5a8a). Vorher hielt nichts fest, dass ein Umbau an einer Maschine die
     * anderen nicht mit ändert – akkumulator_4bit und der mit Löschen teilen sich den Code. Wer hier einen Wert
     * ändert, hat die Maschine geändert und lässt MachineProbe für sie in allen vier Richtungen neu laufen.
     */
    private static final Map<String, String> TESTED_LAYOUT = Map.ofEntries(
            Map.entry("nicht_gatter", "10 34afd7348cad0785"),
            Map.entry("oder_gatter", "19 92aca86e098aa64e"),
            Map.entry("und_gatter", "27 ca9119614d3a61e7"),
            Map.entry("xor_gatter", "56 a2e3c40d63e6554a"),
            Map.entry("takt", "21 377e7f05a647579a"),
            Map.entry("kolbentuer", "24 a6ef9ddb9a4e35b8"),
            Map.entry("halbaddierer", "118 879b7a45fa4866ac"),
            Map.entry("rs_speicher", "76 47b664db2583ef14"),
            Map.entry("automatiktuer", "20 80b54e9b36da5554"),
            Map.entry("wasserfahrstuhl", "62 7c9f01c423287202"),
            Map.entry("nand_gatter", "28 2dbdb7d647a4043c"),
            Map.entry("nor_gatter", "27 0904f7b5afa6df40"),
            Map.entry("xnor_gatter", "58 85016d6b56fe2ec3"),
            Map.entry("lampenreihe", "22 a9c6e12d81981b28"),
            Map.entry("klingel", "5 06d7d39228329ce3"),
            Map.entry("glocke", "6 04f4b1fddd1b91f0"),
            Map.entry("abwaerts_fahrstuhl", "62 e9025a179a5b8427"),
            Map.entry("nachtlicht", "6 0328a222fc4964a3"),
            Map.entry("t_flipflop", "8 c230dcb2fec163f0"),
            Map.entry("sammelstelle", "9 e22c0f6711a7fe0f"),
            Map.entry("kombischloss", "44 7b6b7965a506898b"),
            Map.entry("pfeilfalle", "10 7fdfc999211ae774"),
            Map.entry("zaehler", "25 dbedaacde3a282fd"),
            Map.entry("zuckerrohrfarm", "22 db0163b1b4ef28f0"),
            Map.entry("volladdierer", "1295 78ca86f19a3ce1c6"),
            Map.entry("impulsverlaengerer", "62 a01a1ccf5fc8d670"),
            Map.entry("schmelzanlage", "11 8425c185ca38af10"),
            Map.entry("item_sortierer", "34 3b4ccdbd21f6ac66"),
            // Mehrsorten-Sortierer (T-016, 2026-11-21): MachineProbe in vier Richtungen auf Spigot 1.21.11.
            Map.entry("item_sortierer_3", "114 b26956ee7a36ebc8"),
            Map.entry("xp_farm", "1864 0a3c3df5dfceecdf"),
            Map.entry("bruchsteingenerator", "53 1957141703414712"),
            Map.entry("weizenfarm", "300 181c9eb87b30e2ff"),
            Map.entry("huehnerfarm", "67 d41e2673490322d4"), // umlaut-ok
            Map.entry("eisenfarm", "388 332e5aed36a1c828"),
            Map.entry("kuerbisfarm", "160 3031ca04265ca127"), // umlaut-ok
            Map.entry("melonenfarm", "160 9a0966510f2e0092"),
            Map.entry("bambusfarm", "52 a196dd7dfadf6f6a"),
            Map.entry("kaktusfarm", "38 335868f05b8d1781"),
            Map.entry("d_speicher", "25 8fabd42fed42e937"),
            Map.entry("register_4bit", "144 44069ef98e0b57cd"),
            Map.entry("alu_1bit", "3361 d7c0710fcf274b77"),
            Map.entry("vergleicher_2bit", "2375 96e3cbb705de645c"),
            Map.entry("addierer_2bit", "2545 37f488fdc014d09b"),
            Map.entry("addierer_4bit", "5399 53e8baad71e0b385"),
            Map.entry("akkumulator_4bit", "8001 8491ac9ebc496ba5"),
            // Festwertspeicher (T-029, 2026-11-22): MachineProbe N/O/S/W auf Spigot 1.21.11, beide Hebelbelegungen.
            Map.entry(Machines.ROM, "726 5c18a951eb710b5f"),
            Map.entry("anzeige", "12595 33e31e66dd3e03d7"),
            Map.entry("befehlsknopf", "3 6e6d67c541b61570"),
            Map.entry("befehlskette", "7 c822ef3ad30d2637"));

    @Test
    void existingMachinesKeepTheTestedLayout() throws Exception {
        Set<String> all = new HashSet<>();
        for (Machines.Machine m : LIB.machines()) {
            all.add(m.module().name());
        }
        assertTrue(all.containsAll(TESTED_LAYOUT.keySet()), "fehlt: " + TESTED_LAYOUT.keySet());
        for (Map.Entry<String, String> e : TESTED_LAYOUT.entrySet()) {
            java.util.List<String> lines = new java.util.ArrayList<>();
            for (Change ch : LIB.machine(e.getKey()).orElseThrow().module().cells()) {
                lines.add(ch.pos().x() + "," + ch.pos().y() + "," + ch.pos().z() + "=" + ch.block().asString());
            }
            lines.sort(null);
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", lines).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(e.getValue(), lines.size() + " " + java.util.HexFormat.of().formatHex(hash).substring(0, 16),
                    e.getKey());
        }
    }

    /**
     * Item-Sortierer: Die Schwelle hängt an genau zwei Drähten zwischen Komparator und Block B (22 Stück → 1 → 0,
     * 23 → 2 → 1). Ein dritter Draht oder einer weniger, und die Sorte läuft durch bzw. F läuft leer – das sähe man
     * erst auf dem Server.
     */
    @Test
    void itemSorterHasExactlyTwoWiresBetweenComparatorAndTorchBlock() {
        Map<Pos, BlockSpec> c = cells(LIB.machine("item_sortierer").orElseThrow().module());
        assertEquals("comparator", c.get(new Pos(1, 2, 1)).id());
        assertEquals("redstone_wire", c.get(new Pos(2, 2, 1)).id());
        assertEquals("redstone_wire", c.get(new Pos(3, 2, 1)).id());
        assertEquals(null, c.get(new Pos(4, 2, 1)));
        assertEquals("stone", c.get(new Pos(3, 1, 1)).id());
        assertEquals("redstone_wall_torch", c.get(new Pos(3, 1, 2)).id());
        // F zeigt in den Komparator (kein Behälter), H unter F gibt in die Truhe davor.
        assertEquals("right", c.get(new Pos(0, 2, 1)).props().get("facing"));
        assertEquals("front", c.get(new Pos(0, 1, 1)).props().get("facing"));
    }

    /**
     * Mehrsorten-Sortierer: Jede Scheibe ist Block für Block die geprüfte des Ein-Sorten-Sortierers, nur um
     * MULTI_SORTER_STRIDE verschoben – ohne dessen Einwurf, Linientrichter und Rest-Truhe, die es einmal für die ganze
     * Linie gibt. Vorher gab es nur eine Sorte (Gedächtnis „Offen“). Wer eine Scheibe umbaut, ohne den Ein-Sorten-
     * Sortierer mitzuändern, sieht es hier – und hat eine ungeprüfte Schaltung.
     */
    @Test
    void multiSorterRepeatsTheTestedSliceForEveryKind() {
        Map<Pos, BlockSpec> single = cells(LIB.machine("item_sortierer").orElseThrow().module());
        Set<Pos> shared = Set.of(new Pos(0, 3, 0), new Pos(0, 3, 1), new Pos(0, 3, 2));
        for (int n = Machines.MULTI_SORTER_MIN; n <= Machines.MULTI_SORTER_MAX; n++) {
            ModuleLibrary.Module m = LIB.machine(Machines.MULTI_SORTER + n).orElseThrow().module();
            Map<Pos, BlockSpec> c = cells(m);
            int last = (n - 1) * Machines.MULTI_SORTER_STRIDE;
            assertEquals(new Pos(last + 4, 4, 4), m.size(), "n=" + n);
            for (int k = 0; k < n; k++) {
                int dx = k * Machines.MULTI_SORTER_STRIDE;
                for (Map.Entry<Pos, BlockSpec> e : single.entrySet()) {
                    if (shared.contains(e.getKey()) || e.getKey().y() == 0) {
                        continue;
                    }
                    Pos q = e.getKey().add(dx, 0, 0);
                    assertEquals(e.getValue().asString(), c.get(q) == null ? null : c.get(q).asString(),
                            "n=" + n + " Scheibe " + k + " bei " + q);
                }
            }
            // Linie: nach rechts bis zum letzten Filter, der letzte Trichter gibt nach hinten in den Rest.
            for (int x = 0; x <= last; x++) {
                assertEquals(x == last ? "back" : "right", c.get(new Pos(x, 3, 1)).props().get("facing"), "x=" + x);
            }
            assertEquals("chest", c.get(new Pos(last, 3, 2)).id());
            assertEquals("back", c.get(new Pos(0, 3, 0)).props().get("facing"));
            // Zwischen zwei Scheiben bleibt die Spalte frei (außer Linie und Boden): sonst koppeln Draht und Fackel.
            for (int k = 0; k + 1 < n; k++) {
                int gap = k * Machines.MULTI_SORTER_STRIDE + 4;
                for (int y = 1; y <= 2; y++) {
                    for (int z = 0; z <= 3; z++) {
                        assertEquals(null, c.get(new Pos(gap, y, z)), "n=" + n + " Lücke bei " + gap + "," + y + "," + z);
                    }
                }
            }
            long chests = c.values().stream().filter(b -> b.id().equals("chest")).count();
            assertEquals(n + 1, chests, "n=" + n + ": je Sorte eine Truhe plus Rest");
            assertTrue(m.size().x() <= 57, "n=" + n + " passt ins Logik-Raster");
        }
    }

    /**
     * Prüfablauf: Der Fremdgegenstand steht nie in der Sortenliste. Vorher fütterte er fest dirt; mit dirt als Sorte
     * sortierte die Maschine es richtig ein, und MachineProbe meldete auf Paper 26.2 für _4/_6/_7 12/12 einen
     * Scheinfehler (T-022, 2026-11-22). Wer dirt wieder fest einträgt, macht diesen Test rot.
     */
    @Test
    void sorterProbeNeverFeedsAKindAsForeignItem() {
        for (String name : List.of("item_sortierer_4:cobblestone,sand,dirt,oak_planks", "item_sortierer_2:dirt,coarse_dirt",
                "item_sortierer_8:sand,dirt", "item_sortierer_5")) {
            Machines.Machine m = LIB.machine(name).orElseThrow();
            java.util.Set<String> kinds = new java.util.HashSet<>();
            for (Machines.Pin p : m.outputs()) {
                if (p.name().startsWith("SORTE")) {
                    kinds.add(p.onState());
                }
            }
            for (Machines.Pin p : m.outputs()) {
                if (p.name().startsWith("FREMD") || p.name().equals("REST")) {
                    assertFalse(kinds.contains(p.onState()), name + ": " + p.name() + " prüft eine Sorte " + p.onState());
                }
            }
            for (Machines.Step st : m.sequence()) {
                for (String c : st.commands()) {
                    if (c.contains("container.4 with ")) {
                        String item = c.substring(c.indexOf(" with ") + 6, c.lastIndexOf(' '));
                        assertFalse(kinds.contains("items:" + item), name + ": füttert Sorte " + item + " als fremd");
                    }
                }
            }
        }
        assertEquals("dirt", Machines.sorterForeignItem(List.of("sand", "cobblestone")), "ohne dirt bleibt es dirt");
        assertEquals("coarse_dirt", Machines.sorterForeignItem(List.of("sand", "dirt")));
    }

    /** Nur 2–8 Sorten, nur bekannte, keine doppelten, nicht mehr Sorten als Scheiben – sonst gibt es das Bauteil nicht. */
    @Test
    void multiSorterNameRejectsWhatItCannotBuild() {
        for (String bad : List.of("item_sortierer_1", "item_sortierer_9", "item_sortierer_0", "item_sortierer_10",
                "item_sortierer_3:", "item_sortierer_2:sand,dirt,stone", "item_sortierer_3:sand,sand",
                "item_sortierer_3:keine_sorte", "item_sortierer_3:command_block", "item_sortierer_3:sand;dirt",
                "item_sortierer_3:sand,dirt_gespiegelt", "item_sortierer_3:diamond_sword",
                "item_sortierer_2:sand,ender_pearl", "item_sortierer_2:egg", "item_sortierer_2:oak_sign")) {
            assertTrue(LIB.machine(bad).isEmpty(), bad);
        }
        assertTrue(LIB.machine("ITEM_SORTIERER_4:Minecraft:Sand").isPresent());
        assertTrue(LIB.machine("item_sortierer_3:cobblestone,sand,oak_planks").isPresent());
        // Der Ein-Sorten-Sortierer heißt weiter so und ist kein Mehrsorten-Sortierer mit einer Scheibe.
        assertEquals(34, LIB.machine("item_sortierer").orElseThrow().module().cells().size());
        // Spiegeln über ModuleTransforms greift auch für den Namen mit Sorten.
        ModuleLibrary lib = new ModuleTransforms(LIB);
        assertTrue(lib.find("item_sortierer_3:sand,dirt_gespiegelt").isPresent());
    }

    /**
     * Stapelliste (tiers/item-stack-64.txt): nur bekannte Gegenstände, die typischen 1er- und 16er-Stapel fehlen, die
     * Sorten der Probe sind drin. Fehlte die Liste oder enthielte sie Schwerter, nähme der Sortierer sie still an.
     */
    @Test
    void multiSorterStackListHoldsOnlyKnownSixtyFourStacks() {
        for (String ok : List.of("cobblestone", "sand", "oak_planks", "andesite", "gravel", "oak_log", "stone", "glass",
                "granite", "dirt", "iron_ingot", "wheat_seeds")) {
            assertTrue(Machines.stacksTo64(ok), ok);
        }
        for (String no : List.of("diamond_sword", "ender_pearl", "egg", "snowball", "oak_sign", "water_bucket",
                "bucket", "totem_of_undying", "shulker_box", "keine_sorte")) {
            assertFalse(Machines.stacksTo64(no), no);
        }
        // Jeder Eintrag ist ein bekannter Gegenstand (dieselbe Datengenerator-Fassung wie item-ids.txt).
        try (java.io.InputStream in = Machines.class.getResourceAsStream("/tiers/item-stack-64.txt")) {
            assertNotNull(in, "tiers/item-stack-64.txt fehlt");
            List<String> ids = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines()
                    .map(String::trim).filter(t -> !t.isEmpty() && !t.startsWith("#")).toList();
            assertTrue(ids.size() > 1000, "Einträge: " + ids.size());
            for (String id : ids) {
                assertTrue(BlockTiers.knownItems().contains(id), id);
            }
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Wie die Sorten in den Bau kommen: ohne Angabe ohne Blockdaten (Stufe „alle“, wie der Ein-Sorten-Sortierer), mit
     * Angabe als Behälterinhalt der Filter – Stufe „Inhalte“, nie „Befehle“. Je Filter 18 Stück der Sorte und vier
     * umbenannte Platzhalter = 22, die Schwelle der Scheibe; die Filter ohne Angabe bleiben leer.
     */
    @Test
    void multiSorterKindsAreContainerContentsNotCommands() {
        ModuleLibrary.Module empty = LIB.machine("item_sortierer_5").orElseThrow().module();
        for (Change c : empty.cells()) {
            assertEquals(null, c.block().nbt(), "ohne Sorten keine Blockdaten: " + c.pos());
            assertEquals(Tier.EVERYONE, BlockTiers.place(c.block(), BlockTiers.WorldFacts.OVERWORLD).tier(),
                    c.block().asString());
        }
        ModuleLibrary.Module full = LIB.machine("item_sortierer_3:cobblestone,sand").orElseThrow().module();
        Map<Pos, BlockSpec> c = cells(full);
        Tier max = Tier.EVERYONE;
        for (BlockSpec b : c.values()) {
            Tier t = BlockTiers.place(b, BlockTiers.WorldFacts.OVERWORLD).tier();
            max = t.ordinal() > max.ordinal() ? t : max;
        }
        assertEquals(Tier.CONTENTS, max);
        for (int k = 0; k < 3; k++) {
            BlockSpec f = c.get(new Pos(k * Machines.MULTI_SORTER_STRIDE, 2, 1));
            assertEquals("hopper", f.id());
            if (k == 2) {
                assertEquals(null, f.nbt(), "dritter Filter ohne Angabe bleibt leer");
                assertEquals(null, c.get(new Pos(k * Machines.MULTI_SORTER_STRIDE, 1, 1)).nbt(), "H ohne Wartezeit");
                continue;
            }
            assertEquals(Tier.CONTENTS, BlockTiers.place(f, BlockTiers.WorldFacts.OVERWORLD).tier());
            String data = Snbt.write(f.nbt());
            assertTrue(data.contains(k == 0 ? "minecraft:cobblestone" : "minecraft:sand"), data);
            assertEquals(1, data.split("count:18", -1).length - 1, data);
            assertEquals(4, data.split("Sortierer-Platzhalter", -1).length - 1, data);
            assertEquals(5, data.split("Slot:", -1).length - 1, data);
            // H darunter wartet, bis die Fackel steht (gemessen: ohne lief F beim Bau 4–8 Stück aus).
            BlockSpec h = c.get(new Pos(k * Machines.MULTI_SORTER_STRIDE, 1, 1));
            assertEquals("{TransferCooldown:" + Machines.SORTER_FILLED_COOLDOWN + "}", Snbt.write(h.nbt()));
            assertEquals(Tier.CONTENTS, BlockTiers.place(h, BlockTiers.WorldFacts.OVERWORLD).tier());
        }
    }

    /**
     * Schmelzanlage: Der Ofen braucht 10 s, die Trichter ~0,8 s dazu; jeder Schritt mit Befehl wartet in MachineProbe
     * 3 s. Vorher kam „Barren da“ nach 12 s – 1,2 s Puffer, unter 20 TPS (Lastregelung, paralleler Bau) scheiterte die
     * Probe ohne Maschinenfehler. Jetzt: „noch keiner“ höchstens bis 9 s, „Barren da“ frühestens nach 14 s.
     */
    @Test
    void smelterChecksTheIngotOnlyWithLagBuffer() {
        java.util.List<Machines.Step> seq = LIB.machine("schmelzanlage").orElseThrow().sequence();
        int firstIngot = -1;
        for (int i = 0; i < seq.size(); i++) {
            Machines.Step st = seq.get(i);
            assertFalse(st.commands().isEmpty(), "Schritt " + (i + 1) + " ohne Befehl wartet nur 1,2 s");
            int seconds = 3 * (i + 1);
            Boolean ingot = st.expect().get("BARREN");
            if (Boolean.FALSE.equals(ingot)) {
                assertTrue(seconds <= 9, "„noch kein Barren“ nach " + seconds + " s – zu nah an 10 s Schmelzzeit");
            } else if (Boolean.TRUE.equals(ingot) && firstIngot < 0) {
                firstIngot = seconds;
            }
        }
        assertTrue(firstIngot >= 14, "„Barren da“ schon nach " + firstIngot + " s, weniger als 3 s Puffer");
    }

    /**
     * Die MODULE LIBRARY geht mit jedem LLM-Auftrag ungekürzt in den Prompt (hinter dem Scan, also nicht im Cache);
     * auf dem Celeron kostet jedes Token Prompt-Zeit. Die längste Beschreibung vor der vierten Runde hatte 217 Zeichen
     * (zaehler); die neuen hatten bis zu 300 (item_sortierer). Bedienhinweise gehören ins Javadoc.
     */
    /**
     * XP-Farm (2026-11-07): Fallhöhe, Wasser und Dunkelheit nachgerechnet am gebauten Raster. Schaden =
     * ceil(Fallstrecke − 3): 20-Leben-Mobs müssen überleben, Spinnen (16) nicht – sonst verstopfen sie den Schacht.
     */
    @Test
    void xpFarmDropLeavesMobsAliveAndKillsSpiders() {
        Map<Pos, BlockSpec> c = cells(LIB.machine("xp_farm").orElseThrow().module());
        int top = Machines.XP_FARM_CHANNEL_Y;
        // Start auf dem Kanalboden (oben bündig mit top), Landung auf Teppich (1/16 hoch) über einem Trichter.
        assertEquals("cobblestone", c.get(new Pos(9, top - 1, 8)).id(), "Kanalboden am Loch");
        assertEquals("gray_carpet", c.get(new Pos(9, 1, 9)).id());
        assertEquals("hopper", c.get(new Pos(9, 0, 9)).id());
        double fall = top - (1 + 1 / 16.0);
        int damage = (int) Math.ceil(fall - 3);
        assertTrue(damage < 20 && damage >= 16, "Schaden " + damage + " bei " + fall + " Blöcken");
        // Schacht frei von der Kammer bis unter das Dach; kein Wasser darin.
        for (int y = 2; y <= top + 3; y++) {
            for (int x = 9; x <= 10; x++) {
                for (int z = 9; z <= 10; z++) {
                    assertTrue(c.get(new Pos(x, y, z)) == null, "Schacht frei bei " + x + "," + y + "," + z);
                }
            }
        }
        // Je Kanal zwei Quellen über dem äußeren Ende der Bahnen (8 Felder vor dem Loch, eine Lage über dem Wasser),
        // sonst kein Wasser: Die Bahnen füllen sich erst, wenn ihre Wände stehen.
        long sources = c.entrySet().stream().filter(e -> e.getValue().id().equals("water")).count();
        assertEquals(8, sources);
        for (Pos p : List.of(new Pos(9, top + 1, 1), new Pos(10, top + 1, 18), new Pos(1, top + 1, 9),
                new Pos(18, top + 1, 10))) {
            assertEquals("water", c.get(p).id(), "Quelle " + p);
            assertTrue(c.get(p.add(0, -1, 0)) == null, "fällt in die Bahn " + p);
        }
        // Dunkel: Licht nur unter der Plattform (y=1) und auf dem Dach – keine Fackel im Laichraum.
        for (Map.Entry<Pos, BlockSpec> e : c.entrySet()) {
            if (e.getValue().id().contains("torch")) {
                assertTrue(e.getKey().y() == 1 || e.getKey().y() == top + 5, "Fackel " + e.getKey());
            }
        }
        // Zwei Blöcke Luft über der Laichfläche, Dach darüber: Endermen (2,9 hoch) laichen nicht.
        assertEquals("cobblestone", c.get(new Pos(4, top + 1, 4)).id());
        assertTrue(c.get(new Pos(4, top + 2, 4)) == null && c.get(new Pos(4, top + 3, 4)) == null);
        assertEquals("cobblestone", c.get(new Pos(4, top + 4, 4)).id());
        // Truhen vor der Scheibe im Boden, darüber Luft (zu erreichen, everyChestCanBeOpenedAndReached); unter dem Glas
        // der letzte Trichter, Schlagspalt über der Stufe.
        assertEquals("chest", c.get(new Pos(9, 0, 7)).id());
        assertTrue(c.get(new Pos(9, 1, 7)) == null, "über der Truhe Luft");
        assertEquals("hopper", c.get(new Pos(9, 0, 8)).id());
        assertEquals("glass", c.get(new Pos(9, 1, 8)).id());
        assertEquals("smooth_stone_slab", c.get(new Pos(9, 2, 8)).id());
    }

    /**
     * XP-Farm-Bahnen (2026-11-08, XpTrace Spigot Süd: eine Bahn blieb nach dem Bau trocken, ein Zombie sprang aus dem
     * 1 tiefen Kanal). Der Plan baut Lage für Lage von unten (PlanCompiler.order); jede Quelle liegt deshalb eine Lage
     * ÜBER allen Wänden, Böden und der Fläche neben den Bahnen – sie fließt erst, wenn die Bahn ringsum geschlossen ist.
     * Neben jedem Bahnfeld steht auf Wasserhöhe Stein, und die Fläche liegt 2 über dem Wasser: Ein Sprung (1,25)
     * führt nicht hinaus. Gegenprobe: alte Farm → beide Prüfungen rot.
     */
    @Test
    void xpFarmLanesAreClosedBeforeTheWaterFlowsAndCannotBeJumpedOutOf() {
        Map<Pos, BlockSpec> c = cells(LIB.machine("xp_farm").orElseThrow().module());
        int top = Machines.XP_FARM_CHANNEL_Y;
        int sourceY = c.entrySet().stream().filter(e -> e.getValue().id().equals("water")).mapToInt(e -> e.getKey().y())
                .min().orElseThrow();
        for (int x = 1; x <= 18; x++) {
            for (int z = 1; z <= 18; z++) {
                boolean channel = x == 9 || x == 10 || z == 9 || z == 10;
                boolean hole = (x == 9 || x == 10) && (z == 9 || z == 10);
                if (!channel || hole) {
                    continue;
                }
                assertTrue(c.get(new Pos(x, top, z)) == null, "Bahnfeld leer, das Wasser kommt von oben " + x + "," + z);
                for (int[] n : new int[][] {{x + 1, z}, {x - 1, z}, {x, z + 1}, {x, z - 1}}) {
                    boolean nChannel = n[0] == 9 || n[0] == 10 || n[1] == 9 || n[1] == 10;
                    if (nChannel) {
                        continue;
                    }
                    BlockSpec wall = c.get(new Pos(n[0], top, n[1]));
                    assertTrue(wall != null && !wall.isAir(), "Kanalwand neben " + x + "," + z + " bei " + n[0] + ","
                            + n[1]);
                    assertTrue(wall != null && sourceY > top, "Wand in einer tieferen Baulage als die Quellen");
                    BlockSpec floor = c.get(new Pos(n[0], top + 1, n[1]));
                    assertTrue(floor != null && !floor.isAir(), "Fläche über der Wand " + n[0] + "," + n[1]);
                }
            }
        }
        // Jede Quelle kann nur nach unten fließen: ringsum Stein (Steg, Fläche, Wand) oder die Nachbarquelle, darüber
        // ein Deckel. Eine Quelle über vollem Wasser fließt sonst zur Seite, die Bahn reichte ein Feld weiter, und
        // Wasser fiel ins Loch (gemessen 2026-11-08: Kammer unter Wasser, Teppiche weg).
        for (Map.Entry<Pos, BlockSpec> e : c.entrySet()) {
            if (!e.getValue().id().equals("water")) {
                continue;
            }
            Pos q = e.getKey();
            for (Pos n : List.of(q.add(1, 0, 0), q.add(-1, 0, 0), q.add(0, 0, 1), q.add(0, 0, -1), q.add(0, 1, 0))) {
                BlockSpec b = c.get(n);
                assertTrue(b != null && !b.isAir(), "Quelle " + q + " offen zu " + n);
            }
            assertTrue(c.get(q.add(0, -1, 0)) == null, "Quelle " + q + " fällt in ihre Bahn");
        }
        // Wasser auf top (Füße), Fläche oben auf top + 1, Mobs dort auf top + 2: 2 Blöcke Stufe.
        assertEquals(top + 1, sourceY, "Quellen eine Lage über den Bahnen");
        double step = (top + 2) - top;
        assertTrue(step > 1.25, "Stufe " + step + " ist mit einem Sprung zu nehmen");
    }

    /**
     * Jede Truhe einer Maschine geht auf und ist zu erreichen (2026-11-08): Über ihr nur Luft oder Glas (Glas leitet
     * nicht, ChestBlock lässt sie dann auf), und mindestens die Oberseite oder eine Seite grenzt an Luft. Außerhalb der
     * Maschine zählt nur, was ab y=1 liegt – darunter ist dort der Boden der Welt. Vorher lagen die Truhen der XP-Farm
     * unter Glas, ringsum Boden, Truhe und Trichter: Sie gingen auf, aber kein Spieler kam an sie heran.
     */
    @Test
    void everyChestCanBeOpenedAndReached() {
        for (Machines.Machine m : WITH_SORTERS) {
            Map<Pos, BlockSpec> c = cells(m.module());
            Pos s = m.module().size();
            c.forEach((p, b) -> {
                if (!b.id().equals("chest")) {
                    return;
                }
                String where = m.module().name() + " Truhe bei " + p;
                BlockSpec above = c.get(p.add(0, 1, 0));
                // Glas und Glasscheiben leiten nicht: Die Truhe geht darunter auf (Eisenfarm: Scheibe über der Truhe).
                assertTrue(above == null || above.isAir() || above.id().endsWith("glass")
                        || above.id().endsWith("glass_pane"), where + ": darüber " + above);
                boolean reachable = false;
                for (Pos d : List.of(new Pos(0, 1, 0), new Pos(1, 0, 0), new Pos(-1, 0, 0), new Pos(0, 0, 1),
                        new Pos(0, 0, -1))) {
                    Pos q = p.add(d.x(), d.y(), d.z());
                    boolean inside = q.x() >= 0 && q.y() >= 0 && q.z() >= 0 && q.x() < s.x() && q.y() < s.y()
                            && q.z() < s.z();
                    BlockSpec n = c.get(q);
                    reachable |= inside ? n == null || n.isAir() : q.y() >= 1;
                }
                assertTrue(reachable, where + ": keine Seite frei");
            });
        }
    }

    /**
     * Festwertspeicher: Jede Zeile hängt an genau den zwei Adressleitungen, die vom Wort ihrer Zeile abweichen
     * (Komparator − max(Seiten)), und der Ablauf liest jede Adresse in beiden Belegungen. Ohne diese Prüfung fiele eine
     * vertauschte Zeilenfolge oder Leitung erst auf dem Server auf – und eine Belegung, die einen Hebel nie umlegt,
     * gar nicht.
     */
    @Test
    void romRowsHangOnTheMismatchLinesAndTheProbeReadsEveryWordTwice() {
        Machines.Machine m = LIB.machine(Machines.ROM).orElseThrow();
        Map<Pos, BlockSpec> c = cells(m.module());
        // Leitung auf z 4k trägt: A0, A1, !A0, !A1, A0 – als (Bit, invertiert).
        int[][] line = {{0, 0}, {1, 0}, {0, 1}, {1, 1}, {0, 0}};
        assertEquals("redstone_wall_torch", c.get(new Pos(3, 1, 8)).id());
        assertEquals("redstone_wall_torch", c.get(new Pos(3, 1, 12)).id());
        for (int r = 0; r < 4; r++) {
            int word = Machines.ROM_ROW_WORD[r];
            for (int[] l : new int[][] {line[r], line[r + 1]}) {
                // Leitung ist „an“ (sperrt), wenn das Adressbit vom Wort abweicht: nicht invertiert bei Wortbit 0.
                assertEquals(l[1] == 1, (word >> l[0] & 1) == 1, "Zeile " + r + " Wort " + word);
            }
            assertTrue(line[r][0] != line[r + 1][0], "Zeile " + r + " braucht beide Adressbits");
            for (int i = 0; i < 4; i++) {
                Pos lever = Machines.romLever(word, i);
                assertEquals("lever", c.get(lever).id());
                assertEquals("comparator", c.get(lever.add(1, 0, 0)).id());
                assertEquals("subtract", c.get(lever.add(1, 0, 0)).props().get("mode"));
                assertEquals("repeater", c.get(lever.add(1, 0, -1)).id());
                assertEquals("repeater", c.get(lever.add(1, 0, 1)).id());
            }
        }
        // Jede Adresse in jeder Belegung genau einmal, Sollwerte = Wort; jeder Hebel wird mindestens einmal umgelegt.
        assertEquals(8, m.sequence().size());
        Set<String> switched = new HashSet<>();
        for (int s = 0; s < 8; s++) {
            Machines.Step st = m.sequence().get(s);
            int a = (st.inputs().get("A0") ? 1 : 0) + (st.inputs().get("A1") ? 2 : 0);
            assertEquals(s % 4, a);
            for (int b = 0; b < 4; b++) {
                assertEquals((Machines.ROM_PATTERNS[s / 4][a] >> b & 1) == 1, st.expect().get("D" + b), "Schritt " + s);
            }
            for (String cmd : st.commands()) {
                switched.add(cmd.replaceAll("powered=(true|false)", ""));
            }
        }
        assertEquals(16, switched.size(), switched.toString());
    }

    @Test
    void machineDescriptionsStayShortForThePrompt() {
        for (Machines.Machine m : WITH_SORTERS) {
            assertTrue(m.module().description().length() <= 220,
                    m.module().name() + ": " + m.module().description().length() + " Zeichen");
        }
    }

    /**
     * Item-Sortierer: Die Schwelle 22/23 rechnet mit 64er-Stapeln, und bis der Filtertrichter gefüllt ist, zieht er
     * beliebige Gegenstände aus der Linie. Beides muss in der Beschreibung stehen, die LLM und Spieler sehen.
     */
    @Test
    void itemSorterDescriptionNamesStackSizeAndFilling() {
        String d = LIB.machine("item_sortierer").orElseThrow().module().description();
        assertTrue(d.contains("stack to 64"), d);
        assertTrue(d.contains("before use"), d);
    }

    @Test
    void allCellsLieInsideTheSizeAndOnlyOnePerPosition() {
        for (Machines.Machine m : WITH_SORTERS) {
            Set<Pos> seen = new HashSet<>();
            Pos s = m.module().size();
            for (Change c : m.module().cells()) {
                Pos p = c.pos();
                assertTrue(seen.add(p), m.module().name() + " doppelt " + p);
                assertTrue(p.x() >= 0 && p.y() >= 0 && p.z() >= 0 && p.x() < s.x() && p.y() < s.y() && p.z() < s.z(),
                        m.module().name() + " außerhalb " + p);
            }
        }
    }

    @Test
    void wiresTorchesAndRepeatersStandOnSomethingSolid() {
        for (Machines.Machine m : WITH_SORTERS) {
            Map<Pos, BlockSpec> c = cells(m.module());
            c.forEach((p, b) -> {
                if (NEEDS_FLOOR.contains(b.id()) && !"wall".equals(b.props().get("face"))) {
                    BlockSpec below = c.get(p.add(0, -1, 0));
                    assertNotNull(below, m.module().name() + ": nichts unter " + b.id() + " bei " + p);
                    assertFalse(below.isAir() || NEEDS_FLOOR.contains(below.id()),
                            m.module().name() + ": " + b.id() + " bei " + p + " steht auf " + below.id());
                }
            });
        }
    }

    @Test
    void pinsPointAtTheRightBlocks() {
        for (Machines.Machine m : WITH_SORTERS) {
            Map<Pos, BlockSpec> c = cells(m.module());
            for (Machines.Pin in : m.inputs()) {
                assertTrue(Set.of("lever", "stone_pressure_plate", "stone_button").contains(c.get(in.at()).id()),
                        m.module().name() + " Eingang " + in.name() + ": " + c.get(in.at()).id());
            }
            for (Machines.Pin out : m.outputs()) {
                if (m.outputKind().equals("lamp") || m.outputKind().equals("blink")) {
                    assertEquals("redstone_lamp", c.get(out.at()).id(), m.module().name() + " Ausgang " + out.name());
                } else if (m.outputKind().equals("state")) {
                    // Geprüfter Zustand gehört zum Block an der Stelle (Wasser wird über Seelensand zur Blasensäule).
                    if (out.onState().startsWith("items:")) {
                        // Werfer und Spender sind Behälter wie Truhe und Trichter (pfeilfalle prüft Pfeile im Werfer).
                        assertTrue(Set.of("chest", "barrel", "hopper", "dispenser", "dropper")
                                .contains(c.get(out.at()).id()),
                                m.module().name() + " Ausgang " + out.name() + " ist kein Behälter");
                        continue;
                    }
                    if (out.onState().startsWith("entity:")) {
                        // Mob im Quader at..testAt (xp_farm, 2026-11-07): Dort darf nichts Festes stehen, sonst kann
                        // kein Mob hinein – Teppich zählt als begehbar (er liegt auf den Trichtern).
                        for (int x = Math.min(out.at().x(), out.testAt().x()); x <= Math.max(out.at().x(),
                                out.testAt().x()); x++) {
                            for (int y = Math.min(out.at().y(), out.testAt().y()); y <= Math.max(out.at().y(),
                                    out.testAt().y()); y++) {
                                for (int z = Math.min(out.at().z(), out.testAt().z()); z <= Math.max(out.at().z(),
                                        out.testAt().z()); z++) {
                                    BlockSpec b = c.get(new Pos(x, y, z));
                                    assertTrue(b == null || b.isAir() || b.id().endsWith("_carpet"),
                                            m.module().name() + " Ausgang " + out.name() + ": " + b + " im Mob-Quader");
                                }
                            }
                        }
                        continue;
                    }
                    if (out.onState().startsWith("entsteht:")) {
                        // Zustand, den die Maschine selbst herstellt (Bruchstein aus Lava und Wasser, abgebrochene
                        // Frucht, Spülwasser über dem Weizen, 2026-11-07): beim Bau noch nicht so, und kein Befehl des
                        // Ablaufs darf genau ihn setzen – sonst prüfte die Probe den Bau oder ihren eigenen setblock
                        // statt der Maschine.
                        String want = out.onState().substring("entsteht:".length());
                        BlockSpec built = c.get(out.at());
                        String cell = "setblock {" + out.at().x() + "," + out.at().y() + "," + out.at().z() + "} ";
                        boolean filled = m.sequence().stream().flatMap(st -> st.commands().stream())
                                .anyMatch(cmd -> cmd.startsWith(cell));
                        // Leer und Luft erwartet prüft nur etwas, wenn der Ablauf dort erst etwas hinsetzt, das die
                        // Maschine wieder wegnimmt (Frucht, Bambus, drittes Kaktusstück).
                        boolean empty = built == null || built.isAir();
                        assertTrue(empty ? !want.equals("air") || filled : !built.id().equals(want),
                                m.module().name() + " Ausgang " + out.name() + ": beim Bau schon "
                                        + (built == null ? "leer" : built));
                        String self = cell + "minecraft:" + want;
                        assertFalse(m.sequence().stream().flatMap(st -> st.commands().stream())
                                .anyMatch(cmd -> cmd.equals(self) || cmd.startsWith(self + " ")),
                                m.module().name() + " Ausgang " + out.name() + ": der Ablauf setzt ihn selbst");
                        continue;
                    }
                    if (c.get(out.at()) == null && out.onState().equals("air")) {
                        continue;  // muss frei bleiben (xp_farm: kein Wasser im Schacht) – die Probe fragt Luft ab
                    }
                    if (c.get(out.at()) == null || c.get(out.at()).isAir()) {
                        // Leer im Bau ist ein Ausgang nur, wenn der Ablauf genau diese Zelle mit genau dem geprüften
                        // Block füllt (zuckerrohrfarm: Wachsen dauert für die Probe zu lange). Ohne diese Bindung
                        // ginge ein vergessener Block als „wird schon gesetzt“ durch und die Probe fragte Luft ab.
                        // Ausdrücklich Luft (bruchsteingenerator: Lavafach, das der Bau freiräumt) zählt wie leer.
                        String fill = "setblock {" + out.at().x() + "," + out.at().y() + "," + out.at().z()
                                + "} minecraft:" + out.onState();
                        assertTrue(m.sequence().stream().flatMap(st -> st.commands().stream())
                                .anyMatch(cmd -> cmd.equals(fill) || cmd.startsWith(fill + " ")),
                                m.module().name() + " Ausgang " + out.name() + ": leer und von keinem Ablauf gefüllt");
                        continue;
                    }
                    String want = out.onState().replaceAll("\\[.*", "");
                    String built = c.get(out.at()).id();
                    assertTrue(want.equals(built) || want.equals("bubble_column") && built.equals("water"),
                            m.module().name() + " Ausgang " + out.name() + ": " + built + " statt " + want);
                } else {
                    assertTrue(c.get(out.at()) == null || c.get(out.at()).isAir(),
                            m.module().name() + ": Türöffnung muss beim Bau frei sein");
                }
            }
            // Jede Ausgangstabelle nennt genau alle Belegungen der Eingänge.
            int combos = 1 << m.inputs().size();
            m.expect().values().forEach(t -> assertEquals(combos, t.size(), m.module().name()));
            // Abläufe nennen in jedem Schritt jeden Ausgang – außer einem reinen Warteschritt (leer, mit Befehl), der
            // nur Zeit verstreichen lässt (schmelzanlage); der letzte Schritt prüft immer.
            m.sequence().forEach(st -> assertTrue(st.expect().size() == m.outputs().size()
                    || st.expect().isEmpty() && !st.commands().isEmpty(), m.module().name()));
            if (!m.sequence().isEmpty()) {
                assertFalse(m.sequence().getLast().expect().isEmpty(), m.module().name());
            }
        }
    }

    @Test
    void machinesCompileThroughThePlanCompilerInEveryDirection() throws Exception {
        // Grenzen wie config.yml (bau.max-ausdehnung 192): Vorher 6000/48 – addierer_4bit ist 110 breit.
        PlanCompiler compiler = new PlanCompiler(id -> true, "stone", new PlanCompiler.Limits(150000, 192), LIB);
        for (Machines.Machine m : WITH_SORTERS) {
            for (String facing : new String[] {"front", "back", "left", "right"}) {
                CompiledBuild b = compiler.compile(PlanParser.parse("{\"steps\":[{\"op\":\"module\",\"name\":\""
                        + m.module().name() + "\",\"pos\":[0,0,0],\"facing\":\"" + facing + "\"}]}"));
                assertTrue(b.warnings().isEmpty(), m.module().name() + " " + facing + ": " + b.warnings());
                int n = 0;
                for (Action a : b.actions()) {
                    n += a.changes().size();
                }
                assertEquals(m.module().cells().size(), n, m.module().name() + " " + facing);
            }
        }
    }
}
