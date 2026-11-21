package com.blackn0va.baumeister.plan;

import com.blackn0va.baumeister.logic.LogicCircuit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Eingebaute Maschinen: Redstone-Schaltungen als Code, nicht als Schematic.
 *
 * Warum im Code: Ein LLM entwirft Redstone aus dem Kopf praktisch nie funktionsfähig (gemessen); eine
 * Schematic-Bibliothek muss erst jemand von Hand bauen. Hier liegt jede Maschine Block für Block fest und wird auf dem
 * Testserver automatisch geprüft (MachineProbe: bauen, Eingänge schalten, Lampen/Kolben abfragen, Wahrheitstabelle
 * vergleichen). Nur was dort besteht, gehört hierher – wer eine Maschine ändert, lässt die Probe erneut laufen.
 *
 * Koordinaten lokal wie bei jedem Bauteil: x nach rechts, y nach oben, z vom Betrachter weg; Vorderseite (Hebel) bei
 * z=0, Boden auf y=0. Richtungen in Zuständen lokal (front/back/left/right/up/down) – Orientation dreht sie.
 * Vanilla-Konventionen (Java 1.21): repeater/comparator[facing=X] = Signal kommt von Seite X; redstone_wall_torch/
 * lever[face=wall][facing=X] = zeigt von der Wand weg nach X; piston[facing=X] = schiebt nach X;
 * observer[facing=X] = Gesicht schaut nach X und beobachtet den Block dort, der Ausgang (roter Punkt) liegt auf der
 * Gegenseite – wie ObserverBlock und RedStoneWireBlock.shouldConnectTo (Draht verbindet nur an der Rückseite).
 */
public final class Machines implements ModuleLibrary {

    /**
     * Ein Ein- oder Ausgang. testAt: wo die Probe statt des Hebels einen Redstone-Block setzt bzw. was sie abfragt.
     * onState nur bei outputKind "state": der Blockzustand, der „1“ bedeutet (z. B. iron_door[open=true]).
     */
    public record Pin(String name, Pos at, Pos testAt, String onState) {
    }

    /**
     * Ein Schritt eines Ablaufs: erst commands (Serverbefehle, „{x,y,z}“ = lokale Position, die Probe rechnet sie in
     * Weltkoordinaten um – z. B. Uhrzeit setzen, Gegenstand einlegen), dann diese Eingänge anlegen, dann diese
     * Ausgänge erwarten.
     */
    public record Step(Map<String, Boolean> inputs, Map<String, Boolean> expect, List<String> commands) {

        Step(Map<String, Boolean> inputs, Map<String, Boolean> expect) {
            this(inputs, expect, List.of());
        }
    }

    /**
     * expect: Ausgangsname → erwarteter Zustand je Eingangsbelegung (Schlüssel z. B. "A=1,B=0") – für Schaltungen
     * ohne Gedächtnis. sequence: für Schaltungen mit Gedächtnis (Speicher) – Schritte in dieser Reihenfolge; dann
     * bleibt expect leer.
     */
    public record Machine(Module module, List<Pin> inputs, List<Pin> outputs, String outputKind,
            Map<String, Map<String, Boolean>> expect, List<Step> sequence) {

        Machine(Module module, List<Pin> inputs, List<Pin> outputs, String outputKind,
                Map<String, Map<String, Boolean>> expect) {
            this(module, inputs, outputs, outputKind, expect, List.of());
        }
    }

    private final Map<String, Machine> machines = new LinkedHashMap<>();

    public Machines() {
        add(notGate());
        add(orGate());
        add(andGate());
        add(xorGate());
        add(clock());
        add(pistonDoor());
        add(halfAdder());
        add(rsLatch());
        add(automaticDoor());
        add(waterElevator());
        add(nandGate());
        add(norGate());
        add(xnorGate());
        add(lampRow());
        add(doorbell());
        add(bell());
        add(downElevator());
        add(nightLight());
        add(tFlipFlop());
        add(collector());
        add(combinationLock());
        add(arrowTrap());
        add(counter());
        add(sugarCaneFarm());
        add(fullAdder());
        add(pulseExtender());
        add(smelter());
        add(itemSorter());
        add(multiItemSorter(MULTI_SORTER_PROBED, List.of()));
        add(xpFarm());
        // Farmen 2026-11-07 (Wunsch) – je mit Probe auf dem Testserver.
        add(cobbleGenerator());
        add(wheatFarm());
        add(chickenFarm());
        add(ironFarm());
        add(stemFarm("kuerbisfarm", "pumpkin_stem", "pumpkin", "Pumpkin"));
        add(stemFarm("melonenfarm", "melon_stem", "melon", "Melon"));
        add(bambooFarm());
        add(cactusFarm());
        // Bausteine eines Prozessors (2026-10-31, Wunsch)
        add(dLatch());
        add(register4());
        add(logicMachine("alu_1bit", ALU_1BIT, "1-bit ALU slice of a processor: levers A, B, C (carry), P, Q on the left, "
                + "front to back; PQ = 00 AND, 01 OR, 10 XOR, 11 A+B+C. Lamps at the back: R result, K carry."));
        add(logicMachine("vergleicher_2bit", COMPARATOR_2BIT, "2-bit comparator: levers A1, B1, A0, B0 on the left, "
                + "front to back (A = A1A0, B = B1B0); lamps at the back: G (A > B), E (A = B), L (A < B)."));
        add(logicMachine("addierer_2bit", QuickOrders.ADDER, "2-bit adder (calculator): levers A0, B0, A1, B1 on the "
                + "left, front to back; lamps at the back S0 (1), S1 (2), C (4) show A + B."));
        add(adder4());
        add(accumulator4(false));
        add(accumulator4(true));
        add(display());
        // Befehlsblock-Maschinen (Plan B7, 2026-11-08): nur mit der Stufe Befehle baubar; Probe in 4 Richtungen.
        add(CommandMachines.button());
        add(CommandMachines.chain());
    }

    /**
     * 7-Segment-Anzeige 0–F (logic/SevenSegment): Decoder aus dem Logik-Compiler, Ziffer flach im Boden. Sollwerte für
     * jede der 27 Lampen bei allen 16 Werten – die Probe prüft so auch jede einzelne Lampe, nicht nur je Segment eine.
     */
    private static Machine display() {
        com.blackn0va.baumeister.logic.SevenSegment.Display d = com.blackn0va.baumeister.logic.SevenSegment.compile();
        Module raw = d.module();
        Module mod = new Module("anzeige", "7-segment display 0-F (hex): levers A (1), B (2), C (4), D (8) on the left, "
                + "front to back; the digit lies flat in the floor at the back (top of the digit = away from the "
                + "levers). Shows the binary number of the levers.", raw.size(), raw.cells());
        List<String> names = List.of("A", "B", "C", "D");
        List<Pin> ins = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            Pos p = d.levers().get(i);
            ins.add(new Pin(names.get(i), p, p, null));
        }
        List<Pin> outs = new ArrayList<>();
        Map<String, Map<String, Boolean>> expect = new LinkedHashMap<>();
        for (Map.Entry<Character, List<Pos>> seg : d.segments().entrySet()) {
            for (int i = 0; i < seg.getValue().size(); i++) {
                String name = seg.getKey() + String.valueOf(i + 1);
                Pos lamp = seg.getValue().get(i);
                outs.add(new Pin(name, lamp, lamp, null));
                Map<String, Boolean> table = new LinkedHashMap<>();
                for (int v = 0; v < 16; v++) {
                    List<String> key = new ArrayList<>();
                    for (int b = 0; b < names.size(); b++) {
                        key.add(names.get(b) + "=" + (v >> b & 1));
                    }
                    table.put(String.join(",", key), com.blackn0va.baumeister.logic.SevenSegment.lit(v, seg.getKey()));
                }
                expect.put(name, table);
            }
        }
        return new Machine(mod, List.copyOf(ins), List.copyOf(outs), "lamp", expect);
    }

    private void add(Machine m) {
        machines.put(m.module().name(), m);
    }

    public Optional<Machine> machine(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        Machine m = machines.get(n);
        return m != null ? Optional.of(m) : multiItemSorter(n);
    }

    public List<Machine> machines() {
        return List.copyOf(machines.values());
    }

    @Override
    public Optional<Module> find(String name) {
        return machine(name).map(Machine::module);
    }

    @Override
    public List<Module> all() {
        return machines.values().stream().map(Machine::module).toList();
    }

    // ------------------------------------------------------------------ Bauweise

    /** Kleiner Baukasten: Blöcke setzen, Boden darunter, Abmessung ermitteln. */
    private static final class Grid {
        private final Map<Pos, BlockSpec> cells = new LinkedHashMap<>();

        BlockSpec at(int x, int y, int z) {
            return cells.get(new Pos(x, y, z));
        }

        Grid put(int x, int y, int z, BlockSpec block) {
            cells.put(new Pos(x, y, z), block);
            return this;
        }

        Grid put(int x, int y, int z, String block) {
            try {
                cells.put(new Pos(x, y, z), BlockSpec.parse(block));
            } catch (PlanException e) {
                throw new IllegalArgumentException(block, e);
            }
            return this;
        }

        /** Durchgehender Boden auf y=0 unter allem – Draht, Fackeln, Verstärker brauchen einen festen Untergrund. */
        Module module(String name, String description) {
            int maxX = 0;
            int maxY = 0;
            int maxZ = 0;
            for (Pos p : cells.keySet()) {
                maxX = Math.max(maxX, p.x());
                maxY = Math.max(maxY, p.y());
                maxZ = Math.max(maxZ, p.z());
            }
            List<CompiledBuild.Change> list = new ArrayList<>();
            for (int x = 0; x <= maxX; x++) {
                for (int z = 0; z <= maxZ; z++) {
                    Pos floor = new Pos(x, 0, z);
                    if (!cells.containsKey(floor)) {
                        list.add(new CompiledBuild.Change(floor, BlockSpec.of("smooth_stone")));
                    }
                }
            }
            cells.forEach((p, b) -> list.add(new CompiledBuild.Change(p, b)));
            return new Module(name, description, new Pos(maxX + 1, maxY + 1, maxZ + 1), List.copyOf(list));
        }
    }

    private static Pin pin(String name, int x, int y, int z) {
        return new Pin(name, new Pos(x, y, z), new Pos(x, y, z), null);
    }

    private static Pin statePin(String name, int x, int y, int z, String onState) {
        return new Pin(name, new Pos(x, y, z), new Pos(x, y, z), onState);
    }

    private static Map<String, Boolean> bits(String... pairs) {
        return table(pairs);
    }

    private static Map<String, Boolean> table(String... pairs) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1].equals("1"));
        }
        return m;
    }

    private static final String LEVER = "lever[face=floor,facing=back]";
    private static final String WIRE = "redstone_wire";

    // ------------------------------------------------------------------ Maschinen

    /** NICHT: Hebel → Draht in einen Block, Wandfackel auf der Rückseite, Lampe dahinter. */
    private static Machine notGate() {
        Grid g = new Grid()
                .put(0, 1, 0, LEVER)
                .put(0, 1, 1, WIRE)
                .put(0, 1, 2, "stone")
                .put(0, 1, 3, "redstone_wall_torch[facing=back]")
                .put(0, 1, 4, "redstone_lamp");
        return new Machine(g.module("nicht_gatter", "NOT gate: lever in front (A), lamp at the back lights when A is OFF."),
                List.of(pin("A", 0, 1, 0)), List.of(pin("Q", 0, 1, 4)), "lamp",
                Map.of("Q", table("A=0", "1", "A=1", "0")));
    }

    /** ODER: zwei Hebel, Draht führt zusammen in eine Lampe. */
    private static Machine orGate() {
        Grid g = new Grid()
                .put(0, 1, 0, LEVER)
                .put(2, 1, 0, LEVER)
                .put(0, 1, 1, WIRE)
                .put(1, 1, 1, WIRE)
                .put(2, 1, 1, WIRE)
                .put(1, 1, 2, WIRE)
                .put(1, 1, 3, "redstone_lamp");
        return new Machine(g.module("oder_gatter", "OR gate: levers A (front left) and B (front right), lamp at the "
                + "back lights when A or B is ON."),
                List.of(pin("A", 0, 1, 0), pin("B", 2, 1, 0)), List.of(pin("Q", 1, 1, 3)), "lamp",
                Map.of("Q", table("A=0,B=0", "0", "A=1,B=0", "1", "A=0,B=1", "1", "A=1,B=1", "1")));
    }

    /**
     * UND = NICHT(NICHT A ODER NICHT B): Jeder Eingang schaltet die Fackel auf seinem Block aus; der Draht zwischen
     * den Fackeln speist einen dritten Block, dessen Wandfackel nur brennt, wenn beide Fackeln aus sind.
     */
    private static Machine andGate() {
        Grid g = new Grid()
                .put(0, 1, 0, LEVER)
                .put(2, 1, 0, LEVER)
                .put(0, 1, 1, WIRE)
                .put(2, 1, 1, WIRE)
                .put(0, 1, 2, "stone")
                .put(1, 1, 2, "stone")
                .put(2, 1, 2, "stone")
                .put(0, 2, 2, "redstone_torch")
                .put(1, 2, 2, WIRE)
                .put(2, 2, 2, "redstone_torch")
                .put(1, 1, 3, "redstone_wall_torch[facing=back]")
                .put(1, 1, 4, "redstone_lamp");
        return new Machine(g.module("und_gatter", "AND gate: levers A (front left) and B (front right), lamp at the "
                + "back lights only when A and B are ON."),
                List.of(pin("A", 0, 1, 0), pin("B", 2, 1, 0)), List.of(pin("Q", 1, 1, 4)), "lamp",
                Map.of("Q", table("A=0,B=0", "0", "A=1,B=0", "0", "A=0,B=1", "0", "A=1,B=1", "1")));
    }

    /**
     * XOR mit zwei Komparatoren im Subtraktionsmodus: links A−B, rechts B−A, beide geben in dieselbe Lampe. Die
     * Seiteneingänge kommen über Verstärker mit voller Stärke 15 – sonst bliebe bei A=B=1 ein Rest (gerechnet:
     * Draht über 7 Blöcke = 9, 15−9 = 6 → Lampe an).
     */
    private static Machine xorGate() {
        Grid g = new Grid()
                .put(0, 1, 2, LEVER)                                   // A
                .put(6, 1, 2, LEVER)                                   // B
                .put(1, 1, 2, WIRE)                                    // A → Rückseite K1
                .put(2, 1, 2, "comparator[facing=left,mode=subtract]") // K1: Eingang links, Ausgang rechts
                .put(3, 1, 2, "redstone_lamp")                         // gemeinsamer Ausgang
                .put(4, 1, 2, "comparator[facing=right,mode=subtract]")// K2: Eingang rechts, Ausgang links
                .put(5, 1, 2, WIRE)                                    // B → Rückseite K2
                // A vorne herum zur Vorderseite von K2
                .put(0, 1, 1, WIRE).put(0, 1, 0, WIRE).put(1, 1, 0, WIRE).put(2, 1, 0, WIRE).put(3, 1, 0, WIRE)
                .put(4, 1, 0, WIRE)
                .put(4, 1, 1, "repeater[facing=front,delay=1]")
                // B hinten herum zur Rückseite von K1
                .put(6, 1, 3, WIRE).put(6, 1, 4, WIRE).put(5, 1, 4, WIRE).put(4, 1, 4, WIRE).put(3, 1, 4, WIRE)
                .put(2, 1, 4, WIRE)
                .put(2, 1, 3, "repeater[facing=back,delay=1]");
        return new Machine(g.module("xor_gatter", "XOR gate: lever A on the left side, lever B on the right side, lamp "
                + "in the middle lights when exactly one of A, B is ON."),
                List.of(pin("A", 0, 1, 2), pin("B", 6, 1, 2)), List.of(pin("Q", 3, 1, 2)), "lamp",
                Map.of("Q", table("A=0,B=0", "0", "A=1,B=0", "1", "A=0,B=1", "1", "A=1,B=1", "0")));
    }

    /**
     * Takt: Fackel an einem Block, ihr Signal läuft über zwei Verstärker (je 4 Ticks) zurück in den Block. Halbe
     * Periode ≈ 9 Redstone-Ticks – weit unter der Durchbrenn-Grenze (8 Wechsel in 60 Spielticks).
     */
    private static Machine clock() {
        Grid g = new Grid()
                .put(2, 1, 1, "stone")
                .put(2, 1, 2, "redstone_wall_torch[facing=back]")
                .put(2, 2, 2, "redstone_lamp")
                .put(2, 1, 3, WIRE).put(1, 1, 3, WIRE).put(0, 1, 3, WIRE)
                .put(0, 1, 2, "repeater[facing=back,delay=4]")
                .put(0, 1, 1, WIRE)
                .put(1, 1, 1, "repeater[facing=left,delay=4]");
        return new Machine(g.module("takt", "Clock: the lamp on top blinks about once per second; tap the redstone "
                + "wire at the back right for a pulsing signal."),
                List.of(), List.of(pin("Q", 2, 2, 2)), "blink", Map.of());
    }

    /**
     * Kolbentür 1 breit, 2 hoch: zwei klebrige Kolben links, Hebel an der Vorderseite. Hebel AN → Kolben schieben die
     * Türblöcke in die Öffnung (zu), Hebel AUS → Öffnung frei. Der Hebel versorgt den oberen Kolben über den Block,
     * an dem er hängt; den unteren erreicht das über die Quasi-Konnektivität von Java-Kolben.
     */
    private static Machine pistonDoor() {
        Grid g = new Grid()
                .put(0, 1, 1, "sticky_piston[facing=right]")
                .put(0, 2, 1, "sticky_piston[facing=right]")
                .put(1, 1, 1, "stone_bricks")
                .put(1, 2, 1, "stone_bricks")
                .put(3, 1, 1, "stone_bricks")
                .put(3, 2, 1, "stone_bricks")
                .put(0, 3, 1, "stone_bricks").put(1, 3, 1, "stone_bricks").put(2, 3, 1, "stone_bricks")
                .put(3, 3, 1, "stone_bricks")
                .put(0, 2, 0, "stone_bricks")
                .put(0, 2, -1, "lever[face=wall,facing=front]");
        Module mod = g.module("kolbentuer", "Piston door, 1 wide and 2 high (opening at x=2): lever on the front "
                + "left; lever ON = door closed, OFF = open.");
        // Hebel hängt bei z=-1 vor der Front: Modul so verschieben, dass alles bei z >= 0 liegt.
        mod = shiftZ(mod, 1);
        Pin lever = new Pin("A", new Pos(0, 2, 0), new Pos(0, 2, 1), null);
        return new Machine(mod, List.of(lever), List.of(pin("TUER_UNTEN", 2, 1, 2), pin("TUER_OBEN", 2, 2, 2)),
                "solid", Map.of("TUER_UNTEN", table("A=0", "0", "A=1", "1"), "TUER_OBEN", table("A=0", "0", "A=1", "1")));
    }

    /**
     * Halbaddierer – die erste Stufe eines Redstone-Rechenwerks: Summe S = A XOR B (geprüfter XOR-Aufbau oben),
     * Übertrag C = A UND B (geprüfter UND-Aufbau dahinter). A läuft links an x=0 nach hinten, B rechts herum über z=6
     * zum zweiten Eingang des UND – beide Wege berühren keinen Draht des XOR (je ein freier Block Abstand).
     */
    private static Machine halfAdder() {
        Grid g = new Grid()
                .put(0, 1, 2, LEVER)                                   // A
                .put(6, 1, 2, LEVER)                                   // B
                .put(1, 1, 2, WIRE)
                .put(2, 1, 2, "comparator[facing=left,mode=subtract]")
                .put(3, 1, 2, "redstone_lamp")                         // S
                .put(4, 1, 2, "comparator[facing=right,mode=subtract]")
                .put(5, 1, 2, WIRE)
                .put(0, 1, 1, WIRE).put(0, 1, 0, WIRE).put(1, 1, 0, WIRE).put(2, 1, 0, WIRE).put(3, 1, 0, WIRE)
                .put(4, 1, 0, WIRE)
                .put(4, 1, 1, "repeater[facing=front,delay=1]")
                .put(6, 1, 3, WIRE).put(6, 1, 4, WIRE).put(5, 1, 4, WIRE).put(4, 1, 4, WIRE).put(3, 1, 4, WIRE)
                .put(2, 1, 4, WIRE)
                .put(2, 1, 3, "repeater[facing=back,delay=1]")
                // A nach hinten zum UND
                .put(0, 1, 3, WIRE).put(0, 1, 4, WIRE).put(0, 1, 5, WIRE).put(0, 1, 6, WIRE).put(0, 1, 7, WIRE)
                // B nach hinten und hinüber zum UND
                .put(6, 1, 5, WIRE).put(6, 1, 6, WIRE).put(5, 1, 6, WIRE).put(4, 1, 6, WIRE).put(3, 1, 6, WIRE)
                .put(2, 1, 6, WIRE).put(2, 1, 7, WIRE)
                // UND
                .put(0, 1, 8, "stone").put(1, 1, 8, "stone").put(2, 1, 8, "stone")
                .put(0, 2, 8, "redstone_torch").put(1, 2, 8, WIRE).put(2, 2, 8, "redstone_torch")
                .put(1, 1, 9, "redstone_wall_torch[facing=back]")
                .put(1, 1, 10, "redstone_lamp");                       // C
        return new Machine(g.module("halbaddierer", "Half adder (first stage of a redstone calculator): lever A on the "
                + "left, lever B on the right; lamp S in the middle = sum (A XOR B), lamp C at the back left = carry "
                + "(A AND B)."),
                List.of(pin("A", 0, 1, 2), pin("B", 6, 1, 2)), List.of(pin("S", 3, 1, 2), pin("C", 1, 1, 10)), "lamp",
                Map.of("S", table("A=0,B=0", "0", "A=1,B=0", "1", "A=0,B=1", "1", "A=1,B=1", "0"),
                        "C", table("A=0,B=0", "0", "A=1,B=0", "0", "A=0,B=1", "0", "A=1,B=1", "1")));
    }

    /**
     * RS-Speicher (1 Bit): zwei geprüfte NICHT-Gatter über Kreuz. Fackel T1 hält über ihren Draht den Block X2 an,
     * Fackel T2 über den langen Draht hinten herum den Block X1. S schaltet X1 → T1 aus → T2 an (Q=1) und T2 hält X1
     * fest, auch wenn S wieder aus ist; R umgekehrt. Die Drähte halten einen Block Abstand zu den Fackeln, die sie
     * nicht speisen sollen – eine Fackel versorgt jeden Draht neben sich.
     */
    private static Machine rsLatch() {
        Grid g = new Grid()
                .put(2, 1, 0, LEVER)                                   // S
                .put(2, 1, 1, WIRE)
                .put(2, 1, 2, "stone")                                 // X1
                .put(2, 1, 3, "redstone_wall_torch[facing=back]")      // T1
                .put(2, 2, 3, "redstone_lamp")                         // QN
                .put(6, 1, 0, LEVER)                                   // R
                .put(6, 1, 1, WIRE)
                .put(6, 1, 2, "stone")                                 // X2
                .put(6, 1, 3, "redstone_wall_torch[facing=back]")      // T2
                .put(6, 2, 3, "redstone_lamp")                         // Q
                // T1 → X2
                .put(3, 1, 3, WIRE).put(4, 1, 3, WIRE).put(4, 1, 2, WIRE).put(5, 1, 2, WIRE)
                // T2 → hinten herum → X1
                .put(7, 1, 3, WIRE).put(7, 1, 4, WIRE).put(7, 1, 5, WIRE)
                .put(6, 1, 5, WIRE).put(5, 1, 5, WIRE).put(4, 1, 5, WIRE).put(3, 1, 5, WIRE).put(2, 1, 5, WIRE)
                .put(1, 1, 5, WIRE).put(0, 1, 5, WIRE)
                .put(0, 1, 4, WIRE).put(0, 1, 3, WIRE).put(0, 1, 2, WIRE).put(1, 1, 2, WIRE);
        return new Machine(g.module("rs_speicher", "RS latch, 1-bit memory: lever S (front left) sets, lever R (front "
                + "right) resets; lamp Q on the right stays lit after S until R, lamp QN on the left is the opposite."),
                List.of(pin("S", 2, 1, 0), pin("R", 6, 1, 0)), List.of(pin("Q", 6, 2, 3), pin("QN", 2, 2, 3)), "lamp",
                Map.of(), List.of(
                        new Step(bits("S", "0", "R", "1"), bits("Q", "0", "QN", "1")),
                        new Step(bits("S", "0", "R", "0"), bits("Q", "0", "QN", "1")),
                        new Step(bits("S", "1", "R", "0"), bits("Q", "1", "QN", "0")),
                        new Step(bits("S", "0", "R", "0"), bits("Q", "1", "QN", "0")),
                        new Step(bits("S", "0", "R", "1"), bits("Q", "0", "QN", "1")),
                        new Step(bits("S", "0", "R", "0"), bits("Q", "0", "QN", "1"))));
    }

    /** Automatiktür: Eisentür im Steinrahmen, Druckplatte davor und dahinter – wer drauftritt, öffnet sie. */
    private static Machine automaticDoor() {
        Grid g = new Grid()
                .put(0, 1, 1, "stone_bricks").put(0, 2, 1, "stone_bricks")
                .put(2, 1, 1, "stone_bricks").put(2, 2, 1, "stone_bricks")
                .put(0, 3, 1, "stone_bricks").put(1, 3, 1, "stone_bricks").put(2, 3, 1, "stone_bricks")
                .put(1, 1, 1, "iron_door[half=lower,facing=front,hinge=left]")
                .put(1, 2, 1, "iron_door[half=upper,facing=front,hinge=left]")
                .put(1, 1, 0, "stone_pressure_plate")
                .put(1, 1, 2, "stone_pressure_plate");
        return new Machine(g.module("automatiktuer", "Automatic iron door in a stone frame: pressure plates in front and "
                + "behind open it while someone stands on them."),
                List.of(pin("VORN", 1, 1, 0), pin("HINTEN", 1, 1, 2)),
                List.of(statePin("OFFEN", 1, 1, 1, "iron_door[open=true]")), "state",
                Map.of("OFFEN", table("VORN=0,HINTEN=0", "0", "VORN=1,HINTEN=0", "1", "VORN=0,HINTEN=1", "1",
                        "VORN=1,HINTEN=1", "1")));
    }

    /**
     * Wasserfahrstuhl: Seelensand unter einer Wassersäule im Glasrohr – das Wasser wird zur Blasensäule und trägt
     * nach oben. Unten eine Tür (Wasser fließt nicht in Türen), oben offen zum Aussteigen.
     */
    private static Machine waterElevator() {
        int top = 6;
        Grid g = new Grid().put(1, 0, 1, "soul_sand");
        for (int y = 1; y <= top; y++) {
            for (int x = 0; x <= 2; x++) {
                for (int z = 0; z <= 2; z++) {
                    boolean centre = x == 1 && z == 1;
                    boolean door = x == 1 && z == 0 && y <= 2;
                    if (!centre && !door) {
                        g.put(x, y, z, "glass");
                    }
                }
            }
            if (y < top) {
                g.put(1, y, 1, "water");
            }
        }
        g.put(1, 1, 0, "oak_door[half=lower,facing=front,hinge=left]");
        g.put(1, 2, 0, "oak_door[half=upper,facing=front,hinge=left]");
        return new Machine(g.module("wasserfahrstuhl", "Water elevator: glass tube with a bubble column over soul sand; "
                + "enter through the door at the front, it carries you up " + (top - 1) + " blocks."),
                List.of(), List.of(statePin("BLASEN", 1, 3, 1, "bubble_column[drag=false]")), "state",
                Map.of("BLASEN", table("", "1")));
    }


    // ------------------------------------------------------------------ Ausbau 2026-10-28 (zweite Runde)

    /** NAND: UND-Aufbau ohne die letzte Umkehrung – der Draht zwischen den beiden Fackeln führt in die Lampe. */
    private static Machine nandGate() {
        Grid g = new Grid()
                .put(0, 1, 0, LEVER)
                .put(2, 1, 0, LEVER)
                .put(0, 1, 1, WIRE)
                .put(2, 1, 1, WIRE)
                .put(0, 1, 2, "stone")
                .put(1, 1, 2, "stone")
                .put(2, 1, 2, "stone")
                .put(0, 2, 2, "redstone_torch")
                .put(1, 2, 2, WIRE)
                .put(2, 2, 2, "redstone_torch")
                .put(1, 1, 3, "stone")
                .put(1, 2, 3, WIRE)
                .put(1, 2, 4, "redstone_lamp");
        return new Machine(g.module("nand_gatter", "NAND gate: levers A (front left) and B (front right), lamp at the "
                + "back (raised) is OFF only when A and B are both ON."),
                List.of(pin("A", 0, 1, 0), pin("B", 2, 1, 0)), List.of(pin("Q", 1, 2, 4)), "lamp",
                Map.of("Q", table("A=0,B=0", "1", "A=1,B=0", "1", "A=0,B=1", "1", "A=1,B=1", "0")));
    }

    /** NOR: ODER-Aufbau, dessen Draht in einen Block führt; die Wandfackel dahinter kehrt um. */
    private static Machine norGate() {
        Grid g = new Grid()
                .put(0, 1, 0, LEVER)
                .put(2, 1, 0, LEVER)
                .put(0, 1, 1, WIRE)
                .put(1, 1, 1, WIRE)
                .put(2, 1, 1, WIRE)
                .put(1, 1, 2, WIRE)
                .put(1, 1, 3, "stone")
                .put(1, 1, 4, "redstone_wall_torch[facing=back]")
                .put(1, 1, 5, "redstone_lamp");
        return new Machine(g.module("nor_gatter", "NOR gate: levers A (front left) and B (front right), lamp at the "
                + "back lights only when both are OFF."),
                List.of(pin("A", 0, 1, 0), pin("B", 2, 1, 0)), List.of(pin("Q", 1, 1, 5)), "lamp",
                Map.of("Q", table("A=0,B=0", "1", "A=1,B=0", "0", "A=0,B=1", "0", "A=1,B=1", "0")));
    }

    /**
     * XNOR: geprüfter XOR-Aufbau, die Komparatoren speisen statt der Lampe einen Block; die Fackel obendrauf kehrt um,
     * die Lampe sitzt über der Fackel. Eine Wandfackel hinter dem Block ginge nicht – sie stünde neben dem Draht für B.
     */
    private static Machine xnorGate() {
        Grid g = new Grid()
                .put(0, 1, 2, LEVER)
                .put(6, 1, 2, LEVER)
                .put(1, 1, 2, WIRE)
                .put(2, 1, 2, "comparator[facing=left,mode=subtract]")
                .put(3, 1, 2, "stone")
                .put(3, 2, 2, "redstone_torch")
                .put(3, 3, 2, "redstone_lamp")
                .put(4, 1, 2, "comparator[facing=right,mode=subtract]")
                .put(5, 1, 2, WIRE)
                .put(0, 1, 1, WIRE).put(0, 1, 0, WIRE).put(1, 1, 0, WIRE).put(2, 1, 0, WIRE).put(3, 1, 0, WIRE)
                .put(4, 1, 0, WIRE)
                .put(4, 1, 1, "repeater[facing=front,delay=1]")
                .put(6, 1, 3, WIRE).put(6, 1, 4, WIRE).put(5, 1, 4, WIRE).put(4, 1, 4, WIRE).put(3, 1, 4, WIRE)
                .put(2, 1, 4, WIRE)
                .put(2, 1, 3, "repeater[facing=back,delay=1]");
        return new Machine(g.module("xnor_gatter", "XNOR gate (equality): lever A on the left, lever B on the right, the "
                + "lamp on top in the middle lights when A and B are equal."),
                List.of(pin("A", 0, 1, 2), pin("B", 6, 1, 2)), List.of(pin("Q", 3, 3, 2)), "lamp",
                Map.of("Q", table("A=0,B=0", "1", "A=1,B=0", "0", "A=0,B=1", "0", "A=1,B=1", "1")));
    }

    /** Lampenreihe: Draht obenauf versorgt jede Lampe darunter – ein Hebel schaltet die ganze Reihe. */
    private static Machine lampRow() {
        Grid g = new Grid()
                .put(0, 1, 0, "stone")
                .put(0, 2, 0, LEVER);
        for (int x = 0; x < 5; x++) {
            g.put(x, 1, 1, "redstone_lamp").put(x, 2, 1, WIRE);
        }
        return new Machine(g.module("lampenreihe", "Light switch with a row of 5 lamps: lever on the block at the front "
                + "left switches all lamps."),
                List.of(pin("A", 0, 2, 0)), List.of(pin("L1", 0, 1, 1), pin("L5", 4, 1, 1)), "lamp",
                Map.of("L1", table("A=0", "0", "A=1", "1"), "L5", table("A=0", "0", "A=1", "1")));
    }

    /** Klingel: Knopf vorn am Notenblock. */
    private static Machine doorbell() {
        Grid g = new Grid()
                .put(0, 1, 1, "note_block")
                .put(0, 1, 0, "stone_button[face=wall,facing=front]")
                .put(0, 2, 1, "stone_bricks");
        return new Machine(g.module("klingel", "Doorbell: button on the front of a note block; pressing it plays a "
                + "note."),
                List.of(pin("KNOPF", 0, 1, 0)), List.of(statePin("TON", 0, 1, 1, "note_block[powered=true]")), "state",
                Map.of("TON", table("KNOPF=0", "0", "KNOPF=1", "1")));
    }

    /** Glocke: Hebel neben einer Glocke auf dem Boden – an = sie läutet. */
    private static Machine bell() {
        Grid g = new Grid()
                .put(0, 1, 1, LEVER)
                .put(1, 1, 1, "bell[attachment=floor,facing=front]");
        return new Machine(g.module("glocke", "Village bell on the floor with a lever beside it; switching the lever "
                + "on rings the bell."),
                List.of(pin("A", 0, 1, 1)), List.of(statePin("LAEUTET", 1, 1, 1, "bell[powered=true]")), "state",
                Map.of("LAEUTET", table("A=0", "0", "A=1", "1")));
    }

    /** Abwärts-Fahrstuhl: Magmablock unter der Wassersäule zieht nach unten; oben offen zum Einsteigen, unten Tür. */
    private static Machine downElevator() {
        int top = 6;
        Grid g = new Grid().put(1, 0, 1, "magma_block");
        for (int y = 1; y <= top; y++) {
            for (int x = 0; x <= 2; x++) {
                for (int z = 0; z <= 2; z++) {
                    boolean centre = x == 1 && z == 1;
                    boolean door = x == 1 && z == 0 && y <= 2;
                    if (!centre && !door) {
                        g.put(x, y, z, "glass");
                    }
                }
            }
            if (y < top) {
                g.put(1, y, 1, "water");
            }
        }
        g.put(1, 1, 0, "oak_door[half=lower,facing=front,hinge=left]");
        g.put(1, 2, 0, "oak_door[half=upper,facing=front,hinge=left]");
        return new Machine(g.module("abwaerts_fahrstuhl", "Down elevator: glass tube with a bubble column over a magma "
                + "block; jump in from the top, it pulls you down " + (top - 1) + " blocks, exit through the door."),
                List.of(), List.of(statePin("SOG", 1, 3, 1, "bubble_column[drag=true]")), "state",
                Map.of("SOG", table("", "1")));
    }

    /**
     * Nachtlicht: umgekehrter Tageslichtsensor → Draht → Lampe; nachts an, tags aus. Der Ablauf setzt klares Wetter:
     * Regen dunkelt den Sensor ab, die Lampe bleibt dann auch mittags an – gemessen 2026-10-29, Regression Richtung
     * Ost einmal FEHLER, mit „weather rain“ nachgestellt FEHLER, mit „weather clear“ BESTANDEN. Das ist Vanilla, kein
     * Baufehler; ohne den Befehl hinge die Probe am Zufallswetter des Testservers.
     */
    private static Machine nightLight() {
        Grid g = new Grid()
                .put(0, 1, 0, "daylight_detector[inverted=true]")
                .put(1, 1, 0, WIRE)
                .put(2, 1, 0, "redstone_lamp");
        return new Machine(g.module("nachtlicht", "Night light: inverted daylight detector switches the lamp on at "
                + "night and off during the day (needs open sky)."),
                List.of(), List.of(pin("Q", 2, 1, 0)), "lamp", Map.of(), List.of(
                        new Step(Map.of(), bits("Q", "1"), List.of("weather clear", "time set 18000")),
                        new Step(Map.of(), bits("Q", "0"), List.of("weather clear", "time set 6000"))));
    }

    /**
     * T-Flipflop mit Kupferbirne (seit 1.21): Sie wechselt bei jeder steigenden Flanke zwischen an und aus und gibt
     * leuchtend 15 an einen Komparator – ein Flipflop in einem Block. Erste Fassung (2026-10-28) waren zwei Spender,
     * die sich einen Gegenstand zuschieben: beide feuern im selben Tick, und je nach Reihenfolge der Blockupdates –
     * also je nach Himmelsrichtung – schob der zweite ihn gleich zurück (Probe Richtung Nord: nie umgeschaltet).
     * Gewachst, damit sie nicht oxidiert und dunkler wird.
     */
    private static Machine tFlipFlop() {
        Grid g = new Grid()
                .put(0, 1, 0, LEVER)
                .put(0, 1, 1, "waxed_copper_bulb")
                .put(0, 1, 2, "comparator[facing=front]")
                .put(0, 1, 3, "redstone_lamp");
        return new Machine(g.module("t_flipflop", "T flip-flop (toggle) with a copper bulb: every time the lever (or a "
                + "button) at the front turns on, the bulb and the lamp at the back switch between on and off."),
                List.of(pin("T", 0, 1, 0)), List.of(pin("Q", 0, 1, 3)), "lamp", Map.of(), List.of(
                        new Step(bits("T", "0"), bits("Q", "0")),
                        new Step(bits("T", "1"), bits("Q", "1")),
                        new Step(bits("T", "0"), bits("Q", "1")),
                        new Step(bits("T", "1"), bits("Q", "0")),
                        new Step(bits("T", "0"), bits("Q", "0")),
                        new Step(bits("T", "1"), bits("Q", "1"))));
    }

    /** Sammelstelle: zwei Trichter hintereinander in eine Truhe – der Grundbaustein jeder Farm und Sortieranlage. */
    private static Machine collector() {
        Grid g = new Grid()
                .put(0, 1, 1, "hopper[facing=right]")
                .put(1, 1, 1, "hopper[facing=right]")
                .put(2, 1, 1, "chest[facing=front]");
        return new Machine(g.module("sammelstelle", "Item collector: two hoppers in a row feeding a chest; items dropped "
                + "onto the left hopper end up in the chest."),
                List.of(), List.of(statePin("TRUHE", 2, 1, 1, "items:minecraft:cobblestone")), "state", Map.of(),
                List.of(new Step(Map.of(), bits("TRUHE", "1"),
                        List.of("item replace block {0,1,1} container.0 with minecraft:cobblestone 1"))));
    }

    // ------------------------------------------------------------------ Ausbau 2026-10-28 (dritte Runde)

    /**
     * Kombinationsschloss: Tür offen nur bei A=1, B=0, C=1, also OFFEN = NOR(¬A, B, ¬C) – das Muster des geprüften
     * UND-Gatters mit einem dritten Eingang. A und C schalten je die Fackel auf ihrem Block aus (¬A, ¬C); der Hebel B
     * steht erhöht direkt am Sammeldraht zwischen den Fackeln und speist ihn ohne Umkehrung (B). Der Sammeldraht hält den
     * Block (2,1,2) an, dessen Wandfackel die Tür versorgt – sie brennt nur, wenn keiner der drei Wege Strom führt.
     * Wer B auf einen eigenen Stein ohne Verbindung zum Sammeldraht setzt, hat ein UND aus A und C: Die Tür geht dann
     * auch bei A=1, B=1, C=1 auf.
     */
    private static Machine combinationLock() {
        Grid g = new Grid()
                .put(0, 1, 0, LEVER)                                   // A
                .put(4, 1, 0, LEVER)                                   // C
                .put(0, 1, 1, WIRE)
                .put(4, 1, 1, WIRE)
                .put(2, 1, 1, "stone")                                 // Sockel für B
                .put(2, 2, 1, LEVER)                                   // B, neben dem Sammeldraht
                .put(0, 1, 2, "stone").put(1, 1, 2, "stone").put(2, 1, 2, "stone").put(3, 1, 2, "stone")
                .put(4, 1, 2, "stone")
                .put(0, 2, 2, "redstone_torch")                        // ¬A
                .put(4, 2, 2, "redstone_torch")                        // ¬C
                .put(1, 2, 2, WIRE).put(2, 2, 2, WIRE).put(3, 2, 2, WIRE) // Sammeldraht
                .put(2, 1, 3, "redstone_wall_torch[facing=back]")
                .put(2, 1, 4, "iron_door[half=lower,facing=front,hinge=left]")
                .put(2, 2, 4, "iron_door[half=upper,facing=front,hinge=left]");
        return new Machine(g.module("kombischloss", "Combination lock: levers A (front left), B (raised, front middle) "
                + "and C (front right); the iron door at the back opens only for the code A ON, B OFF, C ON. Any other "
                + "setting keeps it shut."),
                List.of(pin("A", 0, 1, 0), pin("B", 2, 2, 1), pin("C", 4, 1, 0)),
                List.of(statePin("OFFEN", 2, 1, 4, "iron_door[open=true]")), "state",
                Map.of("OFFEN", table("A=0,B=0,C=0", "0", "A=1,B=0,C=0", "0", "A=0,B=1,C=0", "0",
                        "A=1,B=1,C=0", "0", "A=0,B=0,C=1", "0", "A=1,B=0,C=1", "1", "A=0,B=1,C=1", "0",
                        "A=1,B=1,C=1", "0")));
    }

    /**
     * Pfeilfalle: Druckplatte → Draht → Stein, der Werfer obendrauf schießt quer über die Platte (Kopfhöhe). Steinerne
     * Platte, weil eine hölzerne auch auf den eigenen Pfeil reagieren würde. Geprüft wird über den Inhalt des Werfers:
     * Ein Schuss leert ihn, Nachladen bei weiter gedrückter Platte löst nichts aus (Werfer feuern nur auf die steigende
     * Flanke, triggered bleibt gesetzt), erst Loslassen und erneutes Drauftreten schießt wieder.
     */
    private static Machine arrowTrap() {
        Grid g = new Grid()
                .put(0, 1, 1, "stone")
                .put(0, 2, 1, "dispenser[facing=right]")
                .put(1, 1, 1, WIRE)
                .put(2, 1, 1, "stone_pressure_plate");
        String load = "item replace block {0,2,1} container.0 with minecraft:arrow 1";
        return new Machine(g.module("pfeilfalle", "Arrow trap: whoever steps on the pressure plate on the right is shot "
                + "by the dispenser on the left at head height; fill the dispenser with arrows."),
                List.of(pin("PLATTE", 2, 1, 1)), List.of(statePin("PFEIL", 0, 2, 1, "items:minecraft:arrow")), "state",
                Map.of(), List.of(
                        new Step(bits("PLATTE", "0"), bits("PFEIL", "1"), List.of(load)),
                        new Step(bits("PLATTE", "1"), bits("PFEIL", "0")),
                        new Step(bits("PLATTE", "1"), bits("PFEIL", "1"), List.of(load)),
                        new Step(bits("PLATTE", "0"), bits("PFEIL", "1")),
                        new Step(bits("PLATTE", "1"), bits("PFEIL", "0"))));
    }

    /**
     * 2-Bit-Zähler aus zwei Kupferbirnen (T-Flipflop-Aufbau, s. t_flipflop): B0 kippt bei jeder steigenden Flanke von
     * T, ihr Komparator speist Stein X; Lampe Q0 neben X zeigt Bit 0. Die Wandfackel NT hinter X ist ¬B0 – sie gibt B1
     * genau dann eine steigende Flanke, wenn B0 von an auf aus fällt. Das zählt aufwärts (00, 01, 10, 11, 00). Ohne NT,
     * mit B1 direkt an X, kippte B1 beim Anspringen von B0 – das wäre ein Rückwärtszähler.
     *
     * B1 steht mit lit=true im Bauplan, obwohl der Zähler bei 0 beginnt: PlanCompiler.order setzt Angehängtes
     * (Fackeln, Hebel, Komparatoren) erst nach allen festen Blöcken. Wenn NT gesetzt wird, steht B1 schon und X ist
     * ungespeist (B0 aus) – NT brennt sofort und gibt B1 eine steigende Flanke, B1 kippt einmal. Vorbelegt mit
     * lit=true landet sie dadurch auf aus; mit lit=false stünde der Zähler nach dem Bau auf 2. Abgeleitet aus der
     * Bau-Reihenfolge, nicht gemessen – MachineProbe misst beide Fassungen. Zeigt sich der Startzustand dort nicht
     * verlässlich, ist der Ausweg der Rückwärtszähler oben: Ohne NT erzeugt beim Bau nichts eine Flanke.
     */
    private static Machine counter() {
        Grid g = new Grid()
                .put(0, 1, 0, LEVER)                                   // T
                .put(0, 1, 1, "waxed_copper_bulb")                     // B0
                .put(0, 1, 2, "comparator[facing=front]")
                .put(0, 1, 3, "stone")                                 // X
                .put(1, 1, 3, "redstone_lamp")                         // Q0
                .put(0, 1, 4, "redstone_wall_torch[facing=back]")      // NT = ¬B0
                .put(0, 1, 5, "waxed_copper_bulb[lit=true]")           // B1, Grund oben
                .put(0, 1, 6, "comparator[facing=front]")
                .put(0, 1, 7, "redstone_lamp");                        // Q1
        return new Machine(g.module("zaehler", "2-bit counter with copper bulbs: every time the lever (or a button) at "
                + "the front turns on, the count goes up by one; lamp Q0 beside the middle shows bit 0, lamp Q1 at the "
                + "back shows bit 1 (0, 1, 2, 3, then back to 0)."),
                List.of(pin("T", 0, 1, 0)), List.of(pin("Q0", 1, 1, 3), pin("Q1", 0, 1, 7)), "lamp", Map.of(),
                List.of(
                        new Step(bits("T", "0"), bits("Q0", "0", "Q1", "0")),
                        new Step(bits("T", "1"), bits("Q0", "1", "Q1", "0")),
                        new Step(bits("T", "0"), bits("Q0", "1", "Q1", "0")),
                        new Step(bits("T", "1"), bits("Q0", "0", "Q1", "1")),
                        new Step(bits("T", "0"), bits("Q0", "0", "Q1", "1")),
                        new Step(bits("T", "1"), bits("Q0", "1", "Q1", "1")),
                        new Step(bits("T", "0"), bits("Q0", "1", "Q1", "1")),
                        new Step(bits("T", "1"), bits("Q0", "0", "Q1", "0")),
                        new Step(bits("T", "0"), bits("Q0", "0", "Q1", "0"))));
    }

    /**
     * Zuckerrohrfarm: Rohr auf Sand neben Wasser (die Quelle sitzt in einer Mulde aus Stein und läuft nicht aus).
     * Der Beobachter schaut auf die dritte Rohrhöhe; wächst das Rohr dorthin, gibt er hinten einen Puls in den Draht
     * auf Stein K, K speist den Kolben, der das mittlere Stück abschlägt – das obere fällt mit, das untere wächst
     * nach. Im Bau stehen nur Sand und unterstes Rohr; MITTE und die Spitze setzt erst der Ablauf per setblock, das
     * Wachsen dauert für eine Probe zu lange. Ein setblock auf das beobachtete Feld löst den Beobachter genauso aus.
     */
    private static Machine sugarCaneFarm() {
        Grid g = new Grid()
                .put(0, 1, 1, "stone").put(1, 1, 0, "stone").put(1, 1, 2, "stone") // Mulde für das Wasser
                .put(1, 1, 1, "water")
                .put(2, 1, 1, "sand")
                .put(2, 2, 1, "sugar_cane")                            // UNTEN
                .put(2, 3, 2, "piston[facing=front]")                  // schlägt MITTE ab
                .put(2, 4, 2, "observer[facing=front]")                // schaut auf die Spitze (2,4,1)
                .put(2, 3, 3, "stone")                                 // K
                .put(2, 4, 3, WIRE);                                   // hinter dem Beobachter
        return new Machine(g.module("zuckerrohrfarm", "Sugar cane farm: sugar cane on sand next to water; when it grows "
                + "three blocks tall, the observer on top fires the piston, which breaks the middle piece. The cane "
                + "drops as items and the bottom piece keeps growing."),
                List.of(), List.of(statePin("UNTEN", 2, 2, 1, "sugar_cane"), statePin("MITTE", 2, 3, 1, "sugar_cane")),
                "state", Map.of(), List.of(
                        new Step(Map.of(), bits("UNTEN", "1", "MITTE", "1"),
                                List.of("setblock {2,3,1} minecraft:sugar_cane")),
                        new Step(Map.of(), bits("UNTEN", "1", "MITTE", "0"),
                                List.of("setblock {2,4,1} minecraft:sugar_cane"))));
    }


    // ------------------------------------------------------------------ Farmen 2026-11-07

    /** Höhe der Kanäle (Wasser, Fallbeginn) über dem Boden der Schlagkammer – siehe xpFarm. */
    static final int XP_FARM_CHANNEL_Y = 23;

    /**
     * XP-Farm ohne Spawner (dunkle Plattform; Wunsch 2026-11-07 – das LLM baute 60 fill-Schritte
     * Bruchstein mit eingemauertem Wasser, die Korrektur sprengte den Kontext). Oben eine dunkle Plattform 20 × 20:
     * vier Laichflächen 8 × 8 (Boden y=24, Mobs laufen auf 25, Dach auf 27 – 2 Blöcke Luft, Endermen passen nicht),
     * dazwischen vier Kanäle, 2 breit; ihr Wasser steht auf y=23, 2 tiefer als die Fläche. Die Quellen liegen eine Lage
     * über dem äußeren Ende jeder Bahn (y=24) und fallen hinein; das Wasser läuft 7 weiter und endet am Loch: Loch und
     * Schacht bleiben trocken, die Strömung schiebt die Mobs hinein.
     *
     * Bahnen 2026-11-08 (vorher Wasser auf Höhe der Fläche, 1 tief, Quelle auf Bahnhöhe): XpTrace auf Spigot, Richtung
     * Süd, 20 Zombies an der Stelle der Probe – 18 kamen mit 2 Leben an, 0 starben, 2 blieben hängen, einer brauchte
     * 30 s. Ursache 1: Bahn 1 des vorderen Kanals war nach dem Bau ab dem dritten Feld trocken, in zwei Bauten
     * nacheinander (Nord, Ost, West nie). Die Quelle lag in derselben Baulage wie die Fläche daneben; deren Felder
     * waren während des Baus noch Löcher, das fließende Wasser nahm den Weg zum nächsten Loch (FlowingFluid.getSpread)
     * statt nach vorn und bekam danach keinen Fluss-Tick mehr. Ein Zombie, der in die trockene Bahn trieb, blieb an der
     * Lochkante stehen – ohne Spieler in der Nähe friert Spigot ihn dort ein (Aktivierungsweite). Ursache 2: Aus dem
     * 1 tiefen Kanal sprang ein Zombie auf die Fläche. Jetzt baut der Plan Lage für Lage von unten (PlanCompiler.order):
     * Wenn die Quellen eine Lage höher fließen, stehen alle Kanalwände schon, und 2 Blöcke Stufe überspringt kein Mob
     * (Sprung 1,25). Wer die Quellen zurück auf Bahnhöhe legt, baut je nach Richtung wieder trockene Bahnen. Jede
     * Quelle sitzt in einer Nische: Steg daneben, Deckel darüber. Eine Quelle über vollem Wasser fließt zur Seite; ohne
     * Steg reichte die Bahn ein Feld weiter und füllte Loch und Schacht (gemessen 2026-11-08, erster Bau der neuen
     * Fassung: Kammer unter Wasser, Teppiche weg). Unter Steg und Deckel ist die Bahn 1 hoch – dorthin kommt kein Mob,
     * die Strömung schiebt ihn ohnehin zum Loch.
     *
     * Fall: Schaden = ceil(Fallstrecke − 3), Landung auf Teppich über den Trichtern (y=1,0625). Gemessen 2026-11-07
     * (MachineProbe, Spigot 1.21.11): Mit dem Kanal auf y=22 landete ein Zombie nach 4 s mit 3 Leben – die Fallstrecke
     * zählt erst, wenn der Mob ganz aus dem Wasser ist, rund 1 Block weniger als gerechnet. Deshalb y=23: gerechnet 21,94
     * Blöcke, gemessen 1–2 Leben übrig; auch ganz ohne Wasserabzug bliebe 1 (19 Schaden). Spinnen (16 Leben) sterben in
     * jedem Fall und verstopfen den 2×2-Schacht nicht. Ohne Teppich sänke ein Mob in die Mulde des Trichters (Kragen
     * 11/16), fiele 0,31 weiter – bei y=23 und vollem Fall wäre das der Tod.
     *
     * Unten: Schlagkammer 2 × 2 vor dem Spieler, vorn Glas und darüber eine untere Stufe – der Spalt 2,5..3,0 zeigt Kopf
     * und Oberkörper, durch ihn schlägt der Spieler; ein Mob kommt dort nicht durch. Trichter unter den Teppichen bringen
     * die Beute unter dem Glas hindurch in zwei Truhen VOR der Scheibe, im Boden, wo der Spieler steht (Luft darüber:
     * sie gehen auf und sind zu erreichen). Vorher (2026-11-07) lagen die Truhen unter dem Glas: Es ließ sie aufgehen,
     * aber keine Seite war zu erreichen – oben Glas, ringsum Boden, Truhe und Trichter. Die Beute kam nicht heraus
     * (gefunden 2026-11-08 beim Schreiben der Bedienhinweise, MachinesTest.everyChestCanBeOpenedAndReached). Wer sie
     * zurück unter das Glas legt, baut Truhen, die niemand öffnen kann. Fackeln am Boden und auf dem
     * Dach verhindern Laichen außerhalb der Plattform. Grenzen: Babyzombies sind zu klein für den Spalt, Hexen behalten
     * 8 Leben, Creeper schnell schlagen. Laichen nur, wo der Spieler 24 Blöcke weg ist – die äußeren Teile der Plattform.
     */
    private static Machine xpFarm() {
        Grid g = new Grid();
        int top = XP_FARM_CHANNEL_Y;
        for (int x = 0; x <= 19; x++) {
            for (int z = 0; z <= 19; z++) {
                boolean wall = x == 0 || x == 19 || z == 0 || z == 19;
                boolean channelX = x == 9 || x == 10;
                boolean channelZ = z == 9 || z == 10;
                boolean hole = channelX && channelZ;
                g.put(x, top + 4, z, "cobblestone");                        // Dach
                if (wall) {
                    for (int y = top - 1; y <= top + 3; y++) {
                        g.put(x, y, z, "cobblestone");
                    }
                } else if (hole) {
                    continue;                                               // Loch: oben offen bis in den Schacht
                } else if (channelX || channelZ) {
                    g.put(x, top - 1, z, "cobblestone");                    // Kanalboden
                    int d = channelX ? (z < 9 ? 9 - z : z - 10) : (x < 9 ? 9 - x : x - 10);
                    if (d == 8) {
                        g.put(x, top + 1, z, "water");                      // Quelle über dem äußeren Ende der Bahn
                        g.put(x, top + 2, z, "cobblestone");                // Deckel: kein Mob fällt in die Quelle
                    } else if (d == 7) {
                        // Steg neben der Quelle: Ist die Bahn darunter voll, fließt eine Quelle zur Seite
                        // (FlowingFluid.spread). Ohne Steg lief sie hier über die Bahn, fiel hinein, die Bahn
                        // reichte ein Feld weiter, und Wasser fiel ins Loch – Schacht und Kammer standen unter Wasser,
                        // die Teppiche brachen (gemessen 2026-11-08, Spigot Nord, Stufe 6 statt 7 an der Lochkante).
                        g.put(x, top + 1, z, "cobblestone");
                    }
                } else {
                    g.put(x, top + 1, z, "cobblestone");                    // Laichfläche
                    if (nextToChannel(x, z)) {
                        g.put(x, top, z, "cobblestone");                    // Kanalwand auf Bahnhöhe
                    }
                }
            }
        }
        // Schacht 2 × 2 von der Kammer bis unter den Kanalboden, rundum Bruchstein.
        for (int y = 3; y <= top - 2; y++) {
            for (int x = 8; x <= 11; x++) {
                for (int z = 8; z <= 11; z++) {
                    if (x == 8 || x == 11 || z == 8 || z == 11) {
                        g.put(x, y, z, "cobblestone");
                    }
                }
            }
        }
        for (int y = 1; y <= 2; y++) {                                       // Kammer: Seiten und Rückwand
            g.put(8, y, 9, "cobblestone").put(8, y, 10, "cobblestone");
            g.put(11, y, 9, "cobblestone").put(11, y, 10, "cobblestone");
            g.put(9, y, 11, "cobblestone").put(10, y, 11, "cobblestone");
        }
        for (int x = 9; x <= 10; x++) {
            g.put(x, 0, 10, "hopper[facing=front]");                        // hintere Trichter → vordere
            g.put(x, 0, 9, "hopper[facing=front]");                         // vordere → unter das Glas
            g.put(x, 0, 8, "hopper[facing=front]");                         // unter dem Glas → Truhe davor
            g.put(x, 0, 7, "chest[facing=front]");                          // vor der Scheibe, darüber Luft
            g.put(x, 1, 9, "gray_carpet").put(x, 1, 10, "gray_carpet");
            g.put(x, 1, 8, "glass");                                        // hält die Mobs in der Kammer
            g.put(x, 2, 8, "smooth_stone_slab[type=bottom]");               // Schlagspalt 2,5..3,0
        }
        for (int a : new int[] {2, 7, 12, 17}) {
            for (int b : new int[] {2, 7, 12, 17}) {
                g.put(a, 1, b, "torch");                                    // Boden unter der Plattform
                g.put(a, top + 5, b, "torch");                              // Dach
            }
        }
        String box = "entity:%1$s,nbt={Health:2.0f}|entity:%1$s,nbt={Health:1.0f}";
        String wait = "time query gametime";
        String clean = "kill @e[{@9,1,9},type=!minecraft:player,type=!minecraft:mannequin,distance=..40]";
        return new Machine(g.module("xp_farm", "XP farm (mob farm, no spawner): a dark platform 24 blocks up, water "
                + "carries mobs to a drop shaft; they land in the kill chamber at the front with 1 heart or less – hit them "
                + "through the slab gap, loot goes to the chests."),
                List.of(), List.of(
                        new Pin("ZOMBIE", new Pos(9, 1, 9), new Pos(10, 2, 10), String.format(box, "zombie")),
                        new Pin("SKELETT", new Pos(9, 1, 9), new Pos(10, 2, 10),
                                String.format(box, "skeleton")),
                        statePin("TROCKEN", 9, 2, 9, "air"),
                        statePin("BEUTE", 9, 0, 7, "items:minecraft:diamond")),
                "state", Map.of(), List.of(
                        new Step(Map.of(), Map.of(), List.of(clean,
                                "summon minecraft:zombie {9," + top + ",3} {PersistenceRequired:1b}")),
                        new Step(Map.of(), Map.of(), List.of(wait)),
                        new Step(Map.of(), Map.of(), List.of(wait)),
                        new Step(Map.of(), Map.of(), List.of(wait)),
                        new Step(Map.of(), bits("ZOMBIE", "1", "SKELETT", "0", "TROCKEN", "1", "BEUTE", "0"),
                                List.of(wait)),
                        new Step(Map.of(), Map.of(), List.of(
                                "summon minecraft:skeleton {16," + top + ",10} {PersistenceRequired:1b}")),
                        new Step(Map.of(), Map.of(), List.of(wait)),
                        new Step(Map.of(), Map.of(), List.of(wait)),
                        new Step(Map.of(), Map.of(), List.of(wait)),
                        new Step(Map.of(), bits("ZOMBIE", "1", "SKELETT", "1", "TROCKEN", "1", "BEUTE", "0"),
                                List.of(wait)),
                        new Step(Map.of(), bits("ZOMBIE", "0", "SKELETT", "0", "TROCKEN", "1", "BEUTE", "0"),
                                List.of(clean)),
                        // Beute: ein Gegenstand auf den Teppich, die Trichter bringen ihn unter dem Glas hindurch in die
                        // Truhe vor der Scheibe (drei Trichter, je 0,4 s). Ein Diamant, kein Knochen: Das kill davor
                        // lässt die Probe-Mobs ihre Beute fallen – ein Knochen des Skeletts stünde sonst schon vorher in
                        // der Truhe, und der Schritt davor („noch keine Beute“) fiele zufällig durch.
                        new Step(Map.of(), bits("ZOMBIE", "0", "SKELETT", "0", "TROCKEN", "1", "BEUTE", "1"),
                                List.of("summon minecraft:item {9,1,9} {Item:{id:\"minecraft:diamond\",count:1}}",
                                        "#bis 10"))));
    }


    /** Grenzt das Feld der XP-Plattform an einen Kanal oder das Loch (x bzw. z 9/10, innerhalb der Wände)? */
    private static boolean nextToChannel(int x, int z) {
        int[][] n = {{x + 1, z}, {x - 1, z}, {x, z + 1}, {x, z - 1}};
        for (int[] p : n) {
            boolean inside = p[0] >= 1 && p[0] <= 18 && p[1] >= 1 && p[1] <= 18;
            if (inside && (p[0] == 9 || p[0] == 10 || p[1] == 9 || p[1] == 10)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Bruchsteingenerator (Wunsch 2026-11-07). Rinne von links: Lavafach (die
     * Lava gießt der Spieler – Lava setzt das Plugin nie, BlockRules), Bruchsteinfeld (vorn offen zum Abbauen),
     * Wasserfeld über einer Mulde, Wasserquelle. Das Wasser fließt in die Mulde und darum nicht weiter zur Seite
     * (FlowingFluid.isWaterHole) – erreichte es die Lavaquelle, würde sie zu Obsidian. Fließt Lava ins Bruchsteinfeld,
     * hat sie seitlich Wasser neben sich und wird Bruchstein (LiquidBlock.shouldSpreadLiquid); nach dem Abbauen fließt
     * neue nach. Wasser UNTER dem Bruchsteinfeld ginge nicht: Lava, die von oben in Wasser fließt, macht das Wasser zu
     * Stein (LavaFluid.spreadTo) – der Generator wäre nach dem ersten Block tot.
     */
    private static Machine cobbleGenerator() {
        Grid g = new Grid();
        for (int x = 0; x <= 5; x++) {
            for (int z = 0; z <= 2; z++) {
                g.put(x, 1, z, "stone");
            }
            g.put(x, 2, 2, "stone");
            g.put(x, 2, 0, x == 2 ? "air" : "stone");                   // vorn offen nur vor dem Bruchstein
        }
        g.put(0, 2, 1, "stone").put(5, 2, 1, "stone");                 // Enden der Rinne
        g.put(1, 2, 1, "air").put(2, 2, 1, "air");                     // Lavafach, Bruchsteinfeld
        g.put(3, 1, 1, "water");                                       // Mulde, gleich gefüllt: der Bau prüft sie
        g.put(4, 2, 1, "water");                                       // Quelle; das Feld (3,2,1) füllt sich selbst
        String wait = "time query gametime";
        return new Machine(g.module("bruchsteingenerator", "Cobblestone generator: pour a lava bucket into the slot on "
                + "the left; cobblestone keeps forming in the opening next to it – mine it and it comes back."),
                List.of(), List.of(statePin("LAVA", 1, 2, 1, "lava"),
                        statePin("BRUCHSTEIN", 2, 2, 1, "entsteht:cobblestone")),
                "state", Map.of(), List.of(
                        new Step(Map.of(), Map.of(), List.of("setblock {1,2,1} minecraft:lava")),
                        new Step(Map.of(), bits("LAVA", "1", "BRUCHSTEIN", "1"), List.of(wait)),
                        new Step(Map.of(), Map.of(), List.of("setblock {2,2,1} minecraft:air")),
                        new Step(Map.of(), bits("LAVA", "1", "BRUCHSTEIN", "1"), List.of(wait))));
    }

    /**
     * Weizenfarm zum Spülen (Wunsch 2026-11-07): Ackerland
     * 9 × 7, in der Mitte eine obere Stufe mit Wasser darin – sie bewässert alles in 4 Feldern (FarmBlock.isNearWater
     * zählt auch eingeschlossenes Wasser). Offenes Wasser dort ginge nicht: Die Spülflut sähe darüber ein Loch
     * (FlowingFluid.isWaterHole), flösse nur noch dorthin und ließe die Reihen dahinter stehen. Hinten neun Spender mit
     * Wassereimer, darauf ein Draht zum Hebel. Hebel an: Jeder Spender setzt eine Quelle vor sich, das Wasser läuft
     * genau 7 Felder bis über die Trichter vorn und schwemmt das Korn hinein, die Trichter führen in die Truhe rechts.
     * Hebel aus und wieder an: Die Spender holen das Wasser zurück. Säen tut der Spieler. Langsam: Ein Trichter reicht
     * 2,5 Gegenstände je Sekunde weiter, die Saat von 62 Feldern braucht bis in die Truhe eine halbe Minute (gemessen
     * 2026-11-07, Spigot 1.21.11: 25–31 s, Wasser nach 2,1 s zurück).
     *
     * Braucht freien Himmel: Weizen hält sich nur bei Licht ≥ 8 (CropBlock.canSurvive) und platzt sonst beim nächsten
     * Nachbar-Update ab – die vier Fackeln am Rand reichen nicht bis in die Mitte. Gemessen 2026-11-07: Unter dem
     * stehen gebliebenen Dach einer XP-Farm-Probe verlor die hintere Reihe beim Bau 4 Felder.
     */
    private static Machine wheatFarm() {
        Grid g = new Grid();
        for (int x = 1; x <= 9; x++) {
            for (int z = 1; z <= 7; z++) {
                if (x == 5 && z == 4) {
                    g.put(x, 1, z, "smooth_stone_slab[type=top,waterlogged=true]");
                } else {
                    g.put(x, 1, z, "farmland[moisture=7]").put(x, 2, z, "wheat");
                }
            }
            g.put(x, 1, 0, "hopper[facing=right]");                     // Sammelrinne vorn
            g.put(x, 1, 8, "stone").put(x, 2, 8, "dispenser[facing=front]").put(x, 3, 8, WIRE);
        }
        g.put(10, 1, 0, "chest[facing=front]");
        for (int z = 0; z <= 8; z++) {
            g.put(0, 1, z, "stone").put(0, 2, z, "stone");
            if (z > 0) {
                g.put(10, 1, z, "stone").put(10, 2, z, "stone");
            }
        }
        g.put(0, 3, 2, "torch").put(0, 3, 6, "torch").put(10, 3, 2, "torch").put(10, 3, 6, "torch");
        g.put(10, 3, 8, LEVER);
        List<String> fill = new ArrayList<>();
        for (int x = 1; x <= 9; x++) {
            fill.add("item replace block {" + x + ",2,8} container.0 with minecraft:water_bucket");
        }
        fill.add("setblock {2,2,3} minecraft:wheat[age=7]");
        fill.add("setblock {7,2,6} minecraft:wheat[age=7]");
        String wait = "time query gametime";
        return new Machine(g.module("weizenfarm", "Wheat farm: 9×7 farmland with water in the middle, needs open sky. "
                + "Put a water bucket into every dispenser at the back; lever on washes the crop into the hoppers and "
                + "chest, off and on again takes the water back."),
                List.of(pin("HEBEL", 10, 3, 8)), List.of(statePin("WEIZEN", 10, 1, 0, "items:minecraft:wheat"),
                        statePin("NASS", 4, 2, 2, "entsteht:water"),
                        statePin("FEUCHT", 1, 1, 1, "farmland[moisture=7]")), "state", Map.of(), List.of(
                        new Step(bits("HEBEL", "0"), Map.of(), fill),
                        new Step(bits("HEBEL", "1"), bits("WEIZEN", "1", "NASS", "1", "FEUCHT", "1"),
                                List.of("#bis 60")),
                        new Step(bits("HEBEL", "0"), Map.of(), List.of(wait)),
                        new Step(bits("HEBEL", "1"), bits("WEIZEN", "1", "NASS", "0", "FEUCHT", "1"),
                                List.of("#bis 20")),
                        // Bewässerung: Zufallsticks hoch, trockenes Ackerland fiele in Sekunden auf 0 (und zu Erde).
                        new Step(bits("HEBEL", "1"), Map.of(), List.of("weather clear",
                                "gamerule random_tick_speed 1000")),
                        new Step(bits("HEBEL", "1"), Map.of(), List.of(wait)),
                        new Step(bits("HEBEL", "1"), bits("WEIZEN", "1", "NASS", "0", "FEUCHT", "1"),
                                List.of("gamerule random_tick_speed 3"))));
    }

    /**
     * Hühnerfarm (Eier): drei Kammern 1 × 1 aus Glas, 2 hoch und oben offen – ein Huhn kommt nicht heraus, Eier lassen
     * sich hineinwerfen (eins von acht schlüpft). Das Huhn sitzt in der Mulde eines Trichters, das Ei fällt hinein, die
     * Trichter führen über eine Rinne in die Truhe rechts. Ein Huhn legt alle 5–10 Minuten ein Ei.
     */
    private static Machine chickenFarm() {
        Grid g = new Grid();
        for (int x = 0; x <= 6; x++) {
            for (int y = 2; y <= 3; y++) {
                if (x >= 1 && x <= 5) {
                    g.put(x, y, 0, "glass").put(x, y, 2, "glass");
                }
                if (x % 2 == 0) {
                    g.put(x, y, 1, "glass");                            // Trennwände
                }
            }
        }
        for (int x = 1; x <= 5; x++) {
            g.put(x, 1, 0, "hopper[facing=right]");                     // Rinne vorn
            g.put(x, 1, 1, x % 2 == 1 ? "hopper[facing=front]" : "stone");
            g.put(x, 1, 2, "stone");
        }
        g.put(0, 1, 1, "stone").put(6, 1, 1, "stone");
        g.put(6, 1, 0, "chest[facing=front]");                          // darüber nichts: sie geht auf
        String wait = "time query gametime";
        return new Machine(g.module("huehnerfarm", "Egg farm: three glass cells, open at the top – put a chicken into "
                + "each (or throw eggs in); the eggs drop through the hoppers into the chest on the right."),
                List.of(), List.of(statePin("EIER", 6, 1, 0, "items:minecraft:egg"),
                        new Pin("HUHN", new Pos(1, 2, 1), new Pos(1, 3, 1), "entity:chicken")), "state", Map.of(),
                List.of(
                        new Step(Map.of(), Map.of(), List.of(
                                "summon minecraft:chicken {1,2,1} {PersistenceRequired:1b}",
                                "summon minecraft:item {3,3,1} {Item:{id:\"minecraft:egg\",count:1}}")),
                        new Step(Map.of(), bits("EIER", "1", "HUHN", "1"), List.of(wait)),
                        new Step(Map.of(), bits("EIER", "1", "HUHN", "0"), List.of(
                                "kill @e[{@1,2,1},type=minecraft:chicken,distance=..2]"))));
    }

    /**
     * Kürbis- und Melonenfarm: vier Stängel auf bewässertem Ackerland, die Frucht kann nur auf ein Feld (Erde rechts
     * vom Stängel; links und hinten Stein, vorn Wasser – StemBlock wächst nur auf Erde oder Ackerland). Der Beobachter
     * schaut von oben auf den STÄNGEL, nicht auf die Frucht: Wächst eine, wird er zum angehängten Stängel, der
     * Beobachter gibt einen Puls, der Kolben rechts zerbricht die Frucht (Kürbis und Melone lassen sich nicht schieben,
     * sie fallen als Gegenstand). Ein Beobachter über der Frucht sähe auch den Kolbenkopf kommen und gehen – wer ihn
     * dorthin setzt, baut einen Takt, der den Kolben ohne Ende schlagen lässt.
     */
    private static Machine stemFarm(String name, String stem, String fruit, String what) {
        Grid g = new Grid();
        for (int u = 0; u < 4; u++) {
            int ox = u * 4;
            for (int x = ox; x <= ox + 3; x++) {
                for (int z = 0; z <= 3; z++) {
                    g.put(x, 1, z, "stone");
                }
            }
            g.put(ox + 1, 1, 1, "water");                                // bewässert den Acker
            g.put(ox + 1, 1, 2, "farmland[moisture=7]").put(ox + 1, 2, 2, stem + "[age=7]");
            g.put(ox + 2, 1, 2, "dirt");                                 // einziges Fruchtfeld
            g.put(ox + 1, 3, 2, "observer[facing=down]").put(ox + 1, 4, 2, WIRE);
            g.put(ox + 2, 3, 2, "stone").put(ox + 2, 4, 2, WIRE);
            g.put(ox + 3, 3, 2, "stone").put(ox + 3, 4, 2, WIRE);
            g.put(ox + 3, 2, 2, "piston[facing=left]");
        }
        return new Machine(g.module(name, what + " farm: four stems on watered farmland; when a " + fruit
                        + " grows, the observer on the stem fires the piston that breaks it – pick up the drops."),
                List.of(), List.of(statePin("FRUCHT", 2, 2, 2, "entsteht:air"), statePin("STIEL", 1, 2, 2, stem)),
                "state", Map.of(), List.of(
                        // Wachsen wie im Spiel (StemBlock.randomTick): Frucht setzen, dann den Stängel anhängen.
                        new Step(Map.of(), bits("FRUCHT", "1", "STIEL", "1"), List.of(
                                "setblock {2,2,2} minecraft:" + fruit,
                                "setblock {1,2,2} plan:attached_" + stem + "[facing=right]"))));
    }

    /**
     * Bambusfarm wie die Zuckerrohrfarm: Bambus auf Erde, der Beobachter oben schaut auf das dritte Stück; wächst der
     * Bambus dorthin, stößt der Kolben das zweite ab (Bambus zerbricht, alles darüber fällt mit), unten wächst nach.
     */
    private static Machine bambooFarm() {
        Grid g = new Grid();
        for (int u = 0; u < 4; u++) {
            int x = u * 2;
            g.put(x, 1, 1, "dirt").put(x, 2, 1, "bamboo");
            g.put(x, 3, 2, "piston[facing=front]");                     // stößt das zweite Stück (x,3,1)
            g.put(x, 4, 2, "observer[facing=front]");                    // schaut auf das dritte (x,4,1)
            g.put(x, 3, 3, "stone").put(x, 4, 3, WIRE);
        }
        return new Machine(g.module("bambusfarm", "Bamboo farm: four bamboo shoots; when one grows three blocks tall, "
                + "the observer fires the piston that breaks it above the bottom piece – collect the bamboo."),
                List.of(), List.of(statePin("UNTEN", 0, 2, 1, "bamboo"), statePin("MITTE", 0, 3, 1, "entsteht:air"),
                        statePin("SPITZE", 0, 4, 1, "entsteht:air")), "state", Map.of(),
                List.of(new Step(Map.of(), bits("UNTEN", "1", "MITTE", "1", "SPITZE", "1"), List.of(
                        "setblock {0,3,1} minecraft:bamboo", "setblock {0,4,1} minecraft:bamboo"))));
    }

    /**
     * Kaktusfarm: Kaktus auf Sand, auf Höhe des dritten Stücks ein Zaunpfosten daneben (frei schwebend: ein Block unter
     * ihm stünde neben dem zweiten Stück und bräche es). Wächst der Kaktus dorthin, bricht das neue Stück ab (Kaktus
     * verträgt keinen festen Nachbarn) und fällt als Gegenstand – landet es auf dem Kaktus, verbrennt es; nicht jeder
     * Ertrag kommt an. Vier Pflanzen.
     */
    private static Machine cactusFarm() {
        Grid g = new Grid();
        for (int u = 0; u < 4; u++) {
            int x = u * 3;
            g.put(x, 1, 1, "sand").put(x, 2, 1, "cactus").put(x, 3, 1, "cactus");
            g.put(x + 1, 4, 1, "oak_fence");
        }
        return new Machine(g.module("kaktusfarm", "Cactus farm: four cacti on sand with a fence post at the height of "
                + "the third piece – new pieces break off and drop."),
                List.of(), List.of(statePin("UNTEN", 0, 2, 1, "cactus"), statePin("MITTE", 0, 3, 1, "cactus"),
                        statePin("DRITTES", 0, 4, 1, "entsteht:air")), "state", Map.of(),
                // Ein per setblock gesetzter Kaktus prüft sich nicht selbst (nur bei Nachbar-Updates,
                // CactusBlock.updateShape) – der Stein darüber und wieder weg löst das Update aus wie das Wachsen.
                List.of(new Step(Map.of(), bits("UNTEN", "1", "MITTE", "1", "DRITTES", "1"), List.of(
                        "setblock {0,4,1} minecraft:cactus", "setblock {0,5,1} minecraft:stone",
                        "setblock {0,5,1} minecraft:air"))));
    }

    /**
     * Eisenfarm ohne Lava (Wunsch 2026-11-07; Lava setzt das
     * Plugin nie, BlockRules). Vorn die Wasserfläche über der Grube, hinten drei Dorfbewohner auf Betten (die stellt
     * der Spieler: Betten sind gesperrt, BlockRules), dahinter zwei Zombies mit Namensschild. Sieht ein Bewohner einen
     * Zombie, gerät er in Panik und ruft alle 5 s einen Golem – ab 3 Bewohnern, die in den letzten 24 000 Ticks
     * geschlafen haben (Villager.spawnGolemIfNeeded, last_slept setzt nur SleepInBed beim Einschlafen). Der Golem
     * erscheint bis 8 Felder seitlich und 6 hoch oder tief um den Bewohner auf einem festen Block mit Luft darüber
     * (SpawnUtil.Strategy.LEGACY_IRON_GOLEM, IronGolem.checkSpawnObstruction): Das gibt es hier nur auf der
     * Wasserfläche – alles andere ist Glas, hat keine 3 Felder Luft oder liegt außer Reichweite
     * (IronFarmSightTest.golemsSpawnOnlyOnTheWater). Er stirbt auf Seelenlagerfeuern, Trichter bringen Eisen und Mohn
     * in die Truhe.
     *
     * Auf der Fläche fließt das Wasser von vorn bis in die Grube und schiebt jeden Golem hinein; die Quellen liegen eine
     * Lage ÜBER der ersten Reihe und fallen hinein. Vorher (2026-11-07/08) zweimal anders: Mit Quellen auf Flächenhöhe
     * lief das Wasser in nur einer von drei Bahnen bis zur Grube (26.2-Kopie) – während des Baus nahm es den Weg zu den
     * noch offenen Nachbarfeldern (wie bei der XP-Farm, FlowingFluid.getSpread). Danach ein stehender Teich aus Quellen:
     * Dort trieb nichts, ein Golem musste selbst zur Grube laufen und tat es nicht immer – gemessen 2026-11-08 (Spigot,
     * Richtung Ost) stand er über 80 s fast reglos auf dem Teich, nachts kam darum 150 s kein Golem in die Grube, am Tag
     * erst nach 130 s (vorher schon Spigot Süd „über 150 s“). Jeder lebende Golem in 16 Feldern hält den nächsten auf
     * (GolemSensor). Der Plan baut Lage für Lage von unten (PlanCompiler.order): Wenn die Quellen fließen, stehen Boden
     * und Wände der Fläche schon. Wer sie zurück auf Flächenhöhe legt, baut wieder trockene Bahnen.
     *
     * Rund um die Uhr (2026-11-08): Vorher sahen die Bewohner EINEN Zombie nur im Stehen, durch einen Spalt unter dem
     * Dach. Gemessen (Spigot, echter Schlaf ohne vorgegebenes last_slept): Ein Bewohner, der den Zombie im Stehen sieht,
     * legt sich abends nie hin – Panik ersetzt die Schlaf-Aktivität (VillagerGoalPackages.getPanicPackage hat kein
     * SleepInBed, VillagerCalmDown kehrt erst ohne Feind zum Zeitplan zurück); 90 s Nacht, kein Bett belegt. Zombie
     * weg: alle drei schlafen nach 1 s. Die alte Farm lief im Spiel also höchstens einen Tag nach dem letzten Schlaf
     * und gar nicht, wenn der Zombie vor der ersten Nacht kam. Eine Sicht im Liegen UND Stehen allein hilft nicht: Dann
     * bleiben sie ab dem ersten Blick für immer in Panik.
     *
     * Jetzt zwei Zombies hinter der Sichtreihe (z = 13), getrennte Sichtwege:
     * - Nachtzombie unten rechts (Füße 9,0, Auge 10,74): nur durch den Schlitz 10,5–11,0 über den unteren
     *   Stufen. Ein liegender Bewohner hat das Auge 0,2 über der Liegehöhe – gemessen sinkt er von 0,6875 auf die
     *   Bettoberkante 0,5625, Auge also 10,76 – und sieht ihn flach durch den Schlitz. Stehende (Auge 12,18) schauen
     *   von oben auf den Stein über dem Schlitz. Nachts also: einschlafen (last_slept), Zombie sehen, aufwachen, im
     *   Stehen nichts mehr sehen, nach 5 s (LAST_WOKEN) wieder einschlafen – bei jeder Panik ein Golem-Versuch.
     * - Tagzombie oben in der Mitte (Füße 10,5 auf der unteren Stufe, Auge 12,24): durch den Schlitz 12,0–12,81 unter den
     *   Eisenfalltüren, die geschlossen oben liegen. Darüber umgekehrte Tageslichtsensoren: Nachts haben sie Strom, die
     *   Falltüren klappen auf und versperren den Schlitz – die Stehenden verlieren den Zombie und können schlafen.
     * Wer die Sensoren weglässt, baut wieder eine Farm, die nach einem Tag stillsteht (Gegenprobe ohne=1,13,13;…).
     * Beide Schlitze sind höchstens 0,81 hoch: Auch Baby-Zombies (0,975) und Bewohner kommen nicht durch, und hoch
     * genug zum Hineinspringen (1,25) liegt keiner. Zombies stehen 1,6 hinter den Bewohnern, weiter als ihre
     * Schlagweite (0,3 + 0,83 + 0,3). Die Sichtlinien prüft IronFarmSightTest am Bau (Kollisionsformen, Strahl ganz
     * abgetastet, nicht nur an Blockkanten – das hatte 2026-11-08 einen falschen Nachtentwurf durchgelassen).
     */
    private static Machine ironFarm() {
        Grid g = new Grid();
        for (int x = 0; x <= 4; x++) {
            g.put(x, 9, 0, "glass").put(x, 10, 0, "glass");             // vordere Wand der Wasserfläche
            for (int y = 1; y <= 9; y++) {
                g.put(x, y, 10, "stone");                               // Rückwand der Grube, trägt die Kammer
            }
            g.put(x, 10, 10, "glass").put(x, 11, 10, "glass");          // Vorderwand der Kammer, darüber der Spalt
        }
        for (int z = 1; z <= 7; z++) {
            for (int x = 1; x <= 3; x++) {
                g.put(x, 8, z, "stone");                                // Laichfläche
            }
            // Seitenwände aus Glasscheiben: dünn in der Mitte ihres Felds (7/16..9/16), ein Golem (1,4 breit) am
            // Rand berührt sie nicht. Mit vollem Glas ragte ein Golem der äußeren Bahn 0,2 in die Wand und blieb am
            // nächsten Wandblock hängen (Spigot Ost, genau 0,7 vor dessen Kante, über 80 s) – nachts kam darum
            // 150 s kein Golem in die Grube (gemessen 2026-11-08).
            g.put(0, 9, z, "glass_pane").put(0, 10, z, "glass_pane").put(4, 9, z, "glass_pane")
                    .put(4, 10, z, "glass_pane");
        }
        for (int x = 1; x <= 3; x++) {
            g.put(x, 10, 1, "water");                                   // Quelle über der ersten Reihe, fließt zur Grube
            for (int y = 1; y <= 7; y++) {
                g.put(x, y, 7, "stone");                                // Grube vorn zu, unter der Fläche nichts
            }
            g.put(x, 1, 9, "hopper[facing=front]");
            g.put(x, 1, 8, "hopper[facing=right]");
            g.put(x, 2, 8, "soul_campfire").put(x, 2, 9, "soul_campfire");
        }
        g.put(4, 1, 8, "chest[facing=right]");
        g.put(0, 1, 8, "stone").put(0, 1, 9, "stone").put(4, 1, 9, "stone");
        for (int y = 2; y <= 10; y++) {
            for (int z = 8; z <= 9; z++) {
                // Glasscheiben wie an der Fläche, sonst hinge ein Golem der äußeren Bahn am Grubenrand; auch über der
                // Truhe: Scheiben leiten nicht, sie geht auf (ChestBlock).
                g.put(0, y, z, "glass_pane").put(4, y, z, "glass_pane");
            }
        }
        // Kammer: zwei Felder je Bett auf Stein, darüber 2 Felder Luft bis zum Glasdach.
        for (int x = 1; x <= 3; x++) {
            g.put(x, 9, 11, "stone").put(x, 9, 12, "stone");
            g.put(x, 10, 11, "air").put(x, 10, 12, "air");             // Bettplätze (Spieler)
            g.put(x, 13, 11, "glass").put(x, 13, 12, "glass");
        }
        for (int z = 11; z <= 12; z++) {
            for (int y = 10; y <= 12; y++) {
                g.put(0, y, z, "glass").put(4, y, z, "glass");
            }
        }
        // Sichtreihe hinter den Kopfenden: unten der Nachtschlitz über der Stufe, oben der Tagschlitz unter der
        // Falltür, darüber die Sensoren (Glas obendrauf: auf dem Sensor könnte sonst ein Golem stehen).
        for (int x = 1; x <= 3; x++) {
            g.put(x, 9, 13, "stone").put(x, 10, 13, "smooth_stone_slab[type=bottom]").put(x, 11, 13, "stone");
            g.put(x, 12, 13, "iron_trapdoor[facing=front,half=top,open=false,powered=false]");
            g.put(x, 13, 13, "daylight_detector[inverted=true]");
            g.put(x, 14, 13, "glass");
        }
        for (int x : new int[] {0, 4}) {
            for (int y = 9; y <= 12; y++) {
                g.put(x, y, 13, "stone");
            }
            g.put(x, 13, 13, "glass");
        }
        // Käfige: Tagzombie in der Mitte (x 2) auf der unteren Stufe, Nachtzombie rechts (x 3) unten auf Stein – in der
        // Mitte, weil schräge Blicke von links sonst an der Käfigwand enden (IronFarmSightTest: 8 577 von 9 375 frei,
        // als der Tagzombie rechts saß); die Blicke der Liegenden zum Nachtzombie laufen über die Stufe des Tagzombies.
        // Stein ringsum, oben Glas (kein Golemplatz); über dem Tagzombie getöntes Glas, sonst verbrennt er am Tag.
        for (int x = 0; x <= 4; x++) {
            for (int y = 8; y <= 12; y++) {
                g.put(x, y, 14, "stone").put(x, y, 15, "stone");
            }
            g.put(x, 13, 14, "glass").put(x, 13, 15, "glass");
        }
        g.put(3, 9, 14, "air").put(3, 10, 14, "air");                    // Nachtzombie
        g.put(2, 10, 14, "smooth_stone_slab[type=bottom]");
        g.put(2, 11, 14, "air").put(2, 12, 14, "air");                   // Tagzombie
        g.put(2, 13, 14, "tinted_glass");
        // Neben seinem Kopf je ein Feld frei: Steht er hinten in der Ecke und der Bewohner vorn auf der anderen Seite,
        // endete der schräge Blick sonst an der Käfigwand (16 von 9 375 Blicken, IronFarmSightTest). Hinein kommt er
        // dort nicht – darunter Stein, 1,5 über seinen Füßen.
        g.put(1, 12, 14, "air").put(3, 12, 14, "air");
        String[] heads = {"{1,10,12}", "{2,10,12}", "{3,10,12}"};
        String sleep = "#schlaf %d " + String.join(" ", heads);
        List<String> night = new ArrayList<>();
        night.add(CLEAN_IRON);
        night.add("time set 13000");                                    // echte Nacht, echter Schlaf
        night.add("weather clear");                                     // Regen gäbe den Sensoren auch am Tag Strom
        for (int x = 1; x <= 3; x++) {
            // Kopfende hinten: so legt ein Spieler sie, der vorn auf der Glaswand steht und nach hinten schaut.
            night.add("setblock {" + x + ",10,11} plan:red_bed[facing=back,part=foot]");
            night.add("setblock {" + x + ",10,12} plan:red_bed[facing=back,part=head]");
        }
        for (int x = 1; x <= 3; x++) {
            night.add("summon minecraft:villager {" + x + ",11,12} {PersistenceRequired:1b}");
        }
        night.add(String.format(sleep, 90));
        return new Machine(g.module("eisenfarm", "Iron farm without lava: 3 beds and 3 villagers in the chamber, "
                + "2 name-tagged zombies in the cages behind (low one seen from bed at night, high one by day); "
                + "golems drop onto soul campfires."),
                // Golem-Quader nur unten in der Grube: Der Selektor wächst je Achse um bis zu 2 Felder in Weltrichtung
                // (MachineProbe: Abstand + 0,99, Minecraft legt +1 drauf). Bis y=8 reichte er in Richtung Nord auf die
                // Fläche und meldete einen Golem dort als „in der Grube“ (gemessen 2026-11-08).
                List.of(), List.of(new Pin("GOLEM", new Pos(1, 3, 8), new Pos(3, 4, 9), "entity:iron_golem"),
                        statePin("EISEN", 4, 1, 8, "items:minecraft:iron_ingot")), "state", Map.of(), List.of(
                        // 1. Nacht ohne Zombies: alle drei schlafen wirklich (kein vorgegebenes last_slept).
                        new Step(Map.of(), bits("GOLEM", "0", "EISEN", "0"), night),
                        // 2. Zombies hinein, sie liegen: nur der Nachtzombie ist zu sehen – Golem bei Nacht. Nachts
                        // ruft nur die kurze Panik nach dem Einschlafen (eine Sensorrunde, ~20 Ticks) einen Golem, also
                        // seltener als am Tag: gemessen 51–135 s (Spigot Nord 2026-11-08), daher 240 s Frist.
                        new Step(Map.of(), bits("GOLEM", "1", "EISEN", "0"), List.of(
                                "summon minecraft:zombie {3,9,14} {PersistenceRequired:1b,IsBaby:0b}",
                                "summon minecraft:zombie {2,11,14} {PersistenceRequired:1b,IsBaby:0b}", "#bis 240")),
                        new Step(Map.of(), bits("GOLEM", "0", "EISEN", "1"), List.of("#bis 90")),
                        // 3. Tag: Falltüren zu, die Stehenden sehen den Tagzombie.
                        new Step(Map.of(), bits("GOLEM", "1", "EISEN", "1"), List.of("time set 1000", "#bis 240")),
                        new Step(Map.of(), bits("GOLEM", "0", "EISEN", "1"), List.of("#bis 90")),
                        // 4. Wieder Nacht: Wer den ganzen Tag in Panik war, schläft trotzdem wieder ein (Sensoren) –
                        // daran hing die alte Farm – und ruft danach wieder Golems.
                        new Step(Map.of(), bits("GOLEM", "1", "EISEN", "1"), List.of("time set 13000",
                                String.format(sleep, 120), "#bis 240")),
                        new Step(Map.of(), bits("GOLEM", "0", "EISEN", "1"), List.of(CLEAN_IRON))));
    }

    /**
     * Bewohner, Zombie und Golems der Eisenfarm weg – übrig gebliebene riefen sonst Golems in die nächste Probe. Ort im
     * Selektor ({@…}), nicht über „execute positioned … run kill“: Hinter „run“ trifft auf Paper mit EssentialsX dessen
     * kill, und „run minecraft:kill“ kennt Spigot nicht (gemessen 2026-11-07, „Incorrect argument“).
     */
    private static final String CLEAN_IRON = "kill @e[{@2,6,8},type=!minecraft:player,type=!minecraft:mannequin,"
            + "distance=..24]";

    // ------------------------------------------------------------------ Ausbau 2026-10-29 (vierte Runde)

    /**
     * 1-Bit-Rechenwerk (ALU-Scheibe) mit vier Operationen, gewählt über P und Q: 00 UND, 01 ODER, 10 XOR, 11 Addition
     * mit Übertrag C → K. Das ist eine Stelle des Rechenwerks eines Prozessors; vier davon mit durchgereichtem Übertrag
     * ergeben ein 4-Bit-Rechenwerk. A, B, C zuerst genannt, damit die Hebel vorn die Daten sind und hinten die
     * Operation (LogicExpr: Reihenfolge des ersten Auftretens). 12 Terme, das Raster trägt 16.
     */
    static final String ALU_1BIT = "R = (A^B^C)&P&Q | A&B&!P&!Q | (A|B)&!P&Q | (A^B)&P&!Q; K = (A&B | C&(A^B))&P&Q";

    /** 2-Bit-Vergleicher: G = A > B, E = A = B, L = A < B (A = A1A0, B = B1B0). 10 Terme. */
    static final String COMPARATOR_2BIT = "G = A1&!B1 | !(A1^B1)&A0&!B0; E = !(A1^B1)&!(A0^B0); "
            + "L = !A1&B1 | !(A1^B1)&!A0&B0";

    /** Formel des Volladdierers; Namen sind die Pins der Probe (Eingänge groß, wie LogicExpr sie führt). */
    static final String FULL_ADDER = "S = A xor B xor CI; CO = A&B | CI&(A xor B)";

    /**
     * Volladdierer – was der Halbaddierer nicht kann: drei Bits (A, B, Übertrag CI) zu Summe S und Übertrag CO; jede
     * Stelle eines Rechenwerks ist einer. Nicht von Hand verdrahtet, sondern aus der Formel durch den Logik-Compiler
     * (mehrere Ausgänge, LogicCircuit): dasselbe Raster, das die LogicProbe in allen Richtungen prüft. Ein Entwurf
     * von Hand scheiterte schon in der Gegenprüfung (Verstärker wechseln nie die Höhe).
     */
    /**
     * Schaltung aus einer Formel über den Logik-Compiler (wie der Volladdierer): Hebel und Lampen kommen aus dem
     * übersetzten Raster, die Sollwerte aus der Wahrheitstabelle der Formel – die Probe schaltet jede Belegung.
     */
    private static Machine logicMachine(String name, String formula, String description) {
        LogicCircuit.Circuit c = LogicCircuit.compile(formula);
        Module raw = c.module();
        Module mod = new Module(name, description, raw.size(), raw.cells());
        List<Pin> ins = new ArrayList<>();
        for (int i = 0; i < c.inputs().size(); i++) {
            Pos p = c.inputPins().get(i);
            ins.add(new Pin(c.inputs().get(i), p, p, null));
        }
        List<Pin> outs = new ArrayList<>();
        for (LogicCircuit.Output o : c.outputs()) {
            outs.add(new Pin(o.name(), o.lamp(), o.lamp(), null));
        }
        return new Machine(mod, List.copyOf(ins), List.copyOf(outs), "lamp", LogicCircuit.truthTables(c));
    }

    /**
     * D-Speicher (getaktet, 1 Bit) über die Verriegelung von Verstärkern: Der Datenverstärker DV gibt D an die Lampe Q
     * weiter, solange er offen ist. Zeigt ein eingeschalteter Verstärker in seine Seite, ist er verriegelt und hält den
     * letzten Zustand. Den Riegel VR speist die Wandfackel F, die am Block S hängt; S bekommt den Takt T. T an → S an →
     * F aus → VR aus → DV offen, Q folgt D. T aus → F an → VR an → DV verriegelt, Q bleibt. So speichert ein
     * Prozessor-Register jedes Bit. Fackel, Riegel und Drähte halten einen Block Abstand zu allem, was sie nicht
     * speisen sollen (RS-Speicher: eine Fackel versorgt jeden Draht neben sich).
     */
    private static Machine dLatch() {
        Grid g = new Grid()
                .put(0, 1, 0, LEVER)                                   // D
                .put(0, 1, 1, WIRE)
                .put(0, 1, 2, "repeater[facing=front]")                // DV
                .put(0, 1, 3, "redstone_lamp")                         // Q
                .put(1, 1, 2, "repeater[facing=right]")                // VR: Eingang rechts, zeigt in die Seite von DV
                .put(2, 1, 2, "redstone_wall_torch[facing=left]")      // F, hängt an S
                .put(3, 1, 2, "stone")                                 // S
                .put(3, 1, 1, WIRE)
                .put(3, 1, 0, LEVER);                                  // T (Takt)
        return new Machine(g.module("d_speicher", "D latch, 1 bit of memory with a clock: lever D (front left) is the "
                + "data, lever T (front right) the clock; while T is on, lamp Q (left, behind) follows D; when T goes "
                + "off, Q keeps the last value until T is on again."),
                List.of(pin("D", 0, 1, 0), pin("T", 3, 1, 0)), List.of(pin("Q", 0, 1, 3)), "lamp", Map.of(), List.of(
                        new Step(bits("T", "1", "D", "1"), bits("Q", "1")),
                        new Step(bits("T", "1", "D", "0"), bits("Q", "0")),
                        new Step(bits("T", "1", "D", "1"), bits("Q", "1")),
                        new Step(bits("T", "0", "D", "1"), bits("Q", "1")),
                        new Step(bits("T", "0", "D", "0"), bits("Q", "1")),
                        new Step(bits("T", "1", "D", "0"), bits("Q", "0")),
                        new Step(bits("T", "0", "D", "0"), bits("Q", "0")),
                        new Step(bits("T", "0", "D", "1"), bits("Q", "0"))));
    }

    /**
     * 4-Bit-Register: vier D-Speicher hintereinander (Abstand 5), ein gemeinsamer Takt. Statt je Bit einen Takthebel
     * läuft der Takt als Draht auf einer Steinschiene (x=3): Der Draht obenauf speist jeden Schienenblock darunter,
     * also auch die vier Blöcke S, an denen die Wandfackeln hängen. Nach 9 Blöcken frischt ein Verstärker auf der
     * Schiene das Signal auf (Stärke 15 reicht 15 Blöcke, die Schiene ist 20 lang). Takt an: alle Q übernehmen D0..D3;
     * Takt aus: alle vier halten – wie das Register eines Prozessors. Haltezeit: Daten erst ändern, wenn der Takt aus
     * ist – der Riegel greift 2–3 Ticks nach der fallenden Flanke, eine gleichzeitige Datenänderung überholt ihn.
     */
    private static Machine register4() {
        Grid g = new Grid();
        for (int z = 0; z < 20; z++) {
            g.put(3, 1, z, "stone");                                   // Taktschiene
            if (z > 0) {
                g.put(3, 2, z, z == 10 ? "repeater[facing=front]" : WIRE);
            }
        }
        g.put(3, 2, 0, LEVER);                                         // T
        List<Pin> ins = new ArrayList<>();
        List<Pin> outs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            int z = 5 * i;
            g.put(0, 1, z, LEVER)                                      // Di
                    .put(0, 1, z + 1, WIRE)
                    .put(0, 1, z + 2, "repeater[facing=front]")
                    .put(0, 1, z + 3, "redstone_lamp")                 // Qi
                    .put(1, 1, z + 2, "repeater[facing=right]")
                    .put(2, 1, z + 2, "redstone_wall_torch[facing=left]");
            ins.add(pin("D" + i, 0, 1, z));
            outs.add(pin("Q" + i, 0, 1, z + 3));
        }
        ins.add(pin("T", 3, 2, 0));
        return new Machine(g.module("register_4bit", "4-bit register (processor memory): levers D0..D3 on the left, "
                + "front to back; clock lever T on the rail at the front right. While T is on, lamps Q0..Q3 behind each "
                + "D follow D; T off: all four hold."),
                List.copyOf(ins), List.copyOf(outs), "lamp", Map.of(), List.of(
                        new Step(bits("T", "1", "D0", "1", "D1", "0", "D2", "1", "D3", "0"),
                                bits("Q0", "1", "Q1", "0", "Q2", "1", "Q3", "0")),
                        new Step(bits("T", "0", "D0", "1", "D1", "0", "D2", "1", "D3", "0"),
                                bits("Q0", "1", "Q1", "0", "Q2", "1", "Q3", "0")),
                        new Step(bits("T", "0", "D0", "0", "D1", "1", "D2", "0", "D3", "1"),
                                bits("Q0", "1", "Q1", "0", "Q2", "1", "Q3", "0")),
                        new Step(bits("T", "1", "D0", "0", "D1", "1", "D2", "0", "D3", "1"),
                                bits("Q0", "0", "Q1", "1", "Q2", "0", "Q3", "1")),
                        // Haltezeit wie bei jedem Register: erst Takt aus, DANN Daten ändern. Beides im selben Schritt
                        // verlor die Bits (Probe 2026-10-31): Der Riegel greift nach 2–3 Ticks, neue Daten sind nach 1 da.
                        new Step(bits("T", "0", "D0", "0", "D1", "1", "D2", "0", "D3", "1"),
                                bits("Q0", "0", "Q1", "1", "Q2", "0", "Q3", "1")),
                        new Step(bits("T", "0", "D0", "0", "D1", "0", "D2", "0", "D3", "0"),
                                bits("Q0", "0", "Q1", "1", "Q2", "0", "Q3", "1"))));
    }

    /** Abstand der Stufen im 4-Bit-Addierer: Volladdierer (26 breit) plus zwei Spalten für die Übertragsleitung. */
    static final int ADDER_STRIDE = 28;

    /**
     * 4-Bit-Addierer mit durchgereichtem Übertrag (Stufe 2 des Prozessors, 2026-10-31): Der Logik-Compiler trägt nur
     * 5 Eingänge, ein 4-Bit-Addierer hat 9 – also vier Volladdierer nebeneinander, Bit 0 links. Die Übertragslampe
     * CO jeder Stufe steht ganz rechts hinten; an ihrer Stelle gibt ein Verstärker den Übertrag in die freie Spalte
     * rechts daneben (die äußerste Spalte des Volladdierers ist bis auf die Lampe leer – gemessen mit
     * FullAdderGeometryProbe). Dort läuft ein Draht nach vorn, steigt von y 3 auf y 1 ab und geht über einen zweiten
     * Verstärker (Signal 4 → 15) in die Zelle, wo bei der nächsten Stufe der CI-Hebel stünde. Warum zwei Spalten
     * Abstand: Bei einer läge der Draht neben dem Eingangsnetz der nächsten Stufe (Drähte bei x 0, z 17/18) und
     * verbände sich damit. Die Geometrie wird hier geprüft statt angenommen – ändert der Compiler sein Raster, scheitert
     * schon der Aufbau der Bibliothek (MachinesTest), nicht erst die Probe im Spiel.
     */
    private static Machine adder4() {
        LogicCircuit.Circuit fa = fullAdderForChain();
        Pos ci = fa.inputPins().get(2);
        Pos co = fa.outputs().get(1).lamp();
        Grid g = new Grid();
        adderChain(g, fa, 0, true);
        List<Pin> ins = new ArrayList<>();
        List<Pin> outs = new ArrayList<>();
        for (int bit = 0; bit < 4; bit++) {
            int dx = bit * ADDER_STRIDE;
            ins.add(pin("A" + bit, dx + fa.inputPins().get(0).x(), fa.inputPins().get(0).y(),
                    fa.inputPins().get(0).z()));
            ins.add(pin("B" + bit, dx + fa.inputPins().get(1).x(), fa.inputPins().get(1).y(),
                    fa.inputPins().get(1).z()));
            if (bit == 0) {
                ins.add(pin("CI", ci.x(), ci.y(), ci.z()));
            }
            Pos s = fa.outputs().getFirst().lamp();
            outs.add(pin("S" + bit, dx + s.x(), s.y(), s.z()));
        }
        outs.add(pin("C4", 3 * ADDER_STRIDE + co.x(), co.y(), co.z()));
        // Stichproben statt aller 512 Belegungen: jede Stelle einzeln, der Übertrag durch alle vier Stufen hin und
        // wieder zurück (1111 + 0001, danach 1111 + 0000), Überlauf mit CI, und Muster ohne jeden Übertrag.
        List<Step> steps = new ArrayList<>();
        int[][] samples = {{0, 0, 0}, {5, 3, 0}, {15, 1, 0}, {15, 0, 0}, {7, 1, 0}, {10, 5, 0}, {15, 15, 1},
                {9, 6, 1}, {3, 3, 0}, {12, 4, 0}, {0, 0, 1}, {8, 8, 0}, {6, 9, 0}};
        for (int[] v : samples) {
            steps.add(adderStep(v[0], v[1], v[2]));
        }
        return new Machine(g.module("addierer_4bit", "4-bit adder (processor arithmetic): four full adders, bit 0 "
                + "left; levers A and B at the front left of each, carry in CI on the first. Lamps at the back: "
                + "S0..S3, C4 (overflow) far right."), List.copyOf(ins), List.copyOf(outs), "lamp", Map.of(),
                List.copyOf(steps));
    }

    /** Volladdierer für die Kette – mit Prüfung genau der Geometrie, auf die adderChain baut. */
    private static LogicCircuit.Circuit fullAdderForChain() {
        LogicCircuit.Circuit fa = LogicCircuit.compile(FULL_ADDER);
        Pos size = fa.module().size();
        Pos ci = fa.inputPins().get(2);
        Pos co = fa.outputs().get(1).lamp();
        Pos s = fa.outputs().getFirst().lamp();
        if (size.x() + 2 != ADDER_STRIDE || co.x() != size.x() - 1 || ci.x() != 0 || co.z() <= ci.z()
                || co.y() < ci.y() || co.z() - ci.z() + 1 > 15 || s.z() != size.z() - 1 || s.y() != 3
                || fa.inputPins().getFirst().z() != 0) {
            throw new IllegalStateException("Volladdierer hat ein anderes Raster: " + size + " CI " + ci + " CO " + co
                    + " S " + s);
        }
        for (CompiledBuild.Change c : fa.module().cells()) {
            if (c.pos().x() == co.x() && c.pos().y() > 0 && !c.pos().equals(co)) {
                throw new IllegalStateException("Spalte neben dem Übertrag ist belegt: " + c.pos());
            }
        }
        return fa;
    }

    /**
     * Die vier Stufen samt Übertragsleitungen in g, um dz nach hinten versetzt. aLevers=false: an Stelle der A-Hebel
     * Draht – dort speist der Akkumulator.
     */
    private static void adderChain(Grid g, LogicCircuit.Circuit fa, int dz, boolean aLevers) {
        Pos size = fa.module().size();
        Pos ci = fa.inputPins().get(2);
        Pos co = fa.outputs().get(1).lamp();
        Pos a = fa.inputPins().getFirst();
        for (int bit = 0; bit < 4; bit++) {
            int dx = bit * ADDER_STRIDE;
            boolean last = bit == 3;
            for (CompiledBuild.Change c : fa.module().cells()) {
                Pos p = c.pos();
                if (bit > 0 && p.equals(ci) || !aLevers && p.equals(a)) {
                    g.put(dx + p.x(), p.y(), dz + p.z(), WIRE);        // statt Hebel: Übertrag bzw. Akkumulator
                } else if (!last && p.equals(co)) {
                    g.put(dx + p.x(), p.y(), dz + p.z(), "repeater[facing=left]");
                    g.put(dx + p.x(), p.y() - 1, dz + p.z(), "stone");
                } else {
                    g.put(dx + p.x(), p.y(), dz + p.z(), c.block());
                }
            }
            if (!last) {
                // Übertragsleitung in Spalte x = size.x(): zwei Drähte auf Höhe der Lampe, dann je Block eins tiefer
                // bis auf die Höhe des CI-Eingangs, dann flach nach vorn bis z von CI.
                int x = dx + size.x();
                int y = co.y();
                for (int z = co.z(); z >= ci.z(); z--) {
                    if (z < co.z() - 1 && y > ci.y()) {
                        y--;
                    }
                    g.put(x, y, dz + z, WIRE);
                    if (y > 1) {
                        g.put(x, y - 1, dz + z, "stone");
                    }
                }
                g.put(x + 1, ci.y(), dz + ci.z(), "repeater[facing=left]");
            }
        }
    }

    /** Vorlauf des Akkumulators: so weit liegt das Rechenwerk hinten, davor läuft die Rückführung zu den A-Eingängen. */
    private static final int ACC_FRONT = 7;

    /**
     * 4-Bit-Akkumulator – der Kern eines Prozessors (Stufe 2, 2026-10-31): Bei jedem Takt T (aus → an) wird B zum
     * gespeicherten Wert AKKU addiert, AKKU := AKKU + B. Das Rechenwerk ist addierer_4bit; hinter jeder Summenlampe sitzt
     * ein Master-Slave-Speicher aus zwei verriegelten Verstärkern (wie d_speicher), seine Ausgabe läuft über eine
     * Schiene auf y 6 über das Rechenwerk nach vorn und dort in den A-Eingang derselben Stufe. Lampen Q0..Q3 in der
     * Schiene vorn zeigen AKKU, die Summenlampen hinten AKKU + B.
     *
     * Warum Master-Slave statt eines d_speicher: Ein offener Speicher in der Rückführung rechnet weiter, solange T an
     * ist – AKKU + B + B + … im Kreis. Der Master ist offen, solange T aus ist (Riegel direkt am Takt), der Slave,
     * solange T an ist (Riegel über eine Fackel, wie d_speicher). Zeitfolge in Redstone-Ticks: T an → Master zu nach
     * 4 (Verstärker 3 + 1), Slave offen nach 2; das neue AKKU braucht durch Rückführung und Rechenwerk weit mehr als 4,
     * der geschlossene Master sieht es nicht. T aus → Slave zu nach 2, Master offen nach 4 – sein neuer Wert erreicht
     * den Slave erst danach. Der Riegelverstärker des Masters (delay 3) ist Reserve, nicht Bedingung: Mit delay 1
     * bleibt ein Redstone-Tick Abstand, und die Probe bestand auch so (Gegenprobe 2026-10-31). Wer dagegen den
     * Master-Riegel ganz weglässt, hat einen einfachen Speicher in der Rückführung – siehe oben.
     *
     * Geometrie je Stufe (lokal, Rechenwerk ab z 7): Summenlampe (14,3,34) → Verstärker (14,3,35) liest die Lampe →
     * Master (14,3,36), Riegel (15,3,36) ← (16,3,36) ← Taktschiene x 17 → Draht (14,3,37) → Slave (14,3,38), Riegel
     * (15,3,38) ← Wandfackel (16,3,38) an der Schiene. Die Spalte x 15 an der Lampe ist frei, x 16–19 (z 34) ist
     * Draht des Übertragsnetzes – deshalb beginnt die Taktschiene erst bei z 36, eine Reihe Abstand dazu. Takt-Hebel
     * hinten links, die Taktleitung läuft auf z 44 nach rechts, je Stufe ein Abzweig.
     *
     * clear=false baut akkumulator_4bit, clear=true die Maschine mit Löschen (CLEAR_ACCUMULATOR, siehe
     * accumulatorClear). Mit false läuft jede Zeile genau wie vor dem 2026-11-20 – Zelle für Zelle und in derselben
     * Reihenfolge, belegt durch MachinesTest.existingMachinesKeepTheTestedLayout. Wer hier für das Löschen etwas
     * außerhalb von „if (clear)“ verschiebt, ändert den auf dem Testserver geprüften akkumulator_4bit mit.
     */
    private static Machine accumulator4(boolean clear) {
        LogicCircuit.Circuit fa = fullAdderForChain();
        Grid g = new Grid();
        adderChain(g, fa, ACC_FRONT, false);
        int back = ACC_FRONT + fa.module().size().z() - 1;                 // z der Summenlampen (34)
        int sx = fa.outputs().getFirst().lamp().x();                       // x der Summenlampe (14)
        int gate = back + 2;                                               // z der Löschsperre (nur clear)
        int master = clear ? gate + 1 : back + 2;                          // z des Masters (36, mit Sperre 37)
        int bus = master + 8;                                              // Taktleitung (44, mit Sperre 45)
        List<Pin> ins = new ArrayList<>();
        List<Pin> outs = new ArrayList<>();
        for (int bit = 0; bit < 4; bit++) {
            int dx = bit * ADDER_STRIDE;
            int x = dx + sx;
            g.put(x, 3, back + 1, "repeater[facing=front]");              // liest die Summenlampe
            if (clear) {
                g.put(x, 3, gate, "comparator[facing=front,mode=subtract]"); // Sperre: L an → 0 in den Master
            }
            g.put(x, 3, master, "repeater[facing=front]")                 // Master
                    .put(x + 1, 3, master, "repeater[facing=right]")
                    .put(x + 2, 3, master, "repeater[facing=right,delay=3]")
                    .put(x, 3, master + 1, WIRE)
                    .put(x, 3, master + 2, "repeater[facing=front]")      // Slave
                    .put(x + 1, 3, master + 2, "repeater[facing=right]")
                    .put(x + 2, 3, master + 2, "redstone_wall_torch[facing=left]");
            for (int z = back + 1; z <= master + 2; z++) {
                g.put(x, 2, z, "stone");
            }
            g.put(x + 1, 2, master, "stone").put(x + 2, 2, master, "stone").put(x + 1, 2, master + 2, "stone");
            // Taktschiene der Stufe: Stein auf y 3, Draht darauf, von der Taktleitung bis zum Master-Riegel.
            for (int z = master; z <= bus; z++) {
                g.put(x + 3, 3, z, "stone").put(x + 3, 4, z, WIRE);
            }
            // Rückführung: hinter dem Slave hoch auf y 6, eine Spalte nach links, über das Rechenwerk nach vorn,
            // vor der Vorderkante hinunter auf y 1, nach links bis x 0 und von vorn in den A-Eingang.
            List<Pos> path = new ArrayList<>();
            path.add(new Pos(x, 3, master + 3));
            path.add(new Pos(x, 4, master + 4));
            path.add(new Pos(x, 5, master + 5));
            path.add(new Pos(x, 6, master + 6));
            for (int z = master + 6; z >= ACC_FRONT - 1; z--) {
                path.add(new Pos(x - 1, 6, z));
            }
            for (int y = 5; y >= 1; y--) {
                path.add(new Pos(x - 1, y, ACC_FRONT - 1 - (6 - y)));
            }
            for (int xx = x - 2; xx >= dx; xx--) {
                path.add(new Pos(xx, 1, 1));
            }
            for (int z = 2; z < ACC_FRONT; z++) {
                path.add(new Pos(dx, 1, z));
            }
            route(g, path, 15, true);
            // Q: ein Schienenstein vorn wird zur Lampe – der Draht darüber speist sie. Nicht unter einem Verstärker
            // (der gibt nur nach vorn), also die erste Drahtzelle ab der Vorderkante des Rechenwerks.
            int qz = ACC_FRONT;
            while (!WIRE.equals(g.at(x - 1, 6, qz).id())) {
                qz++;
            }
            g.put(x - 1, 5, qz, "redstone_lamp");
            ins.add(pin("B" + bit, dx + fa.inputPins().get(1).x(), fa.inputPins().get(1).y(),
                    ACC_FRONT + fa.inputPins().get(1).z()));
            outs.add(pin("Q" + bit, x - 1, 5, qz));
        }
        // Taktleitung: Hebel links, Draht nach rechts, Verstärker vor jedem Abzweig und einer dazwischen.
        int first = sx + 3;
        g.put(first - 1, 3, bus, "stone").put(first - 1, 4, bus, LEVER);
        for (int x = first; x <= 3 * ADDER_STRIDE + first; x++) {
            int rel = (x - first) % ADDER_STRIDE;
            boolean repeater = x > first && (rel == ADDER_STRIDE - 1 || rel == 14);
            g.put(x, 3, bus, "stone").put(x, 4, bus, repeater ? "repeater[facing=left]" : WIRE);
        }
        Pos ci = fa.inputPins().get(2);
        ins.add(pin("CI", ci.x(), ci.y(), ACC_FRONT + ci.z()));
        ins.add(pin("T", first - 1, 4, bus));
        if (clear) {
            ins.add(accumulatorClear(g, sx, gate, bus));
        }
        // Ablauf: B nur bei T aus ändern (Haltezeit wie register_4bit). 3+3 = 6, +5 = 11, +5 = 16 → 0 (Überlauf),
        // 15, dann +1 → 0: der Übertrag läuft durch alle vier Stufen und zurück in den Speicher.
        List<Step> steps = new ArrayList<>();
        int acc = 0;
        int[][] plan = clear ? CLEAR_PLAN : new int[][] {{3, 0}, {3, 1}, {3, 0}, {3, 1}, {3, 0}, {5, 0}, {5, 1},
                {5, 0}, {5, 1}, {5, 0}, {15, 0}, {15, 1}, {15, 0}, {1, 0}, {1, 1}, {1, 0}};
        boolean clock = false;
        for (int[] p : plan) {
            boolean t = p[1] == 1;
            boolean l = clear && p[2] == 1;
            if (t && !clock) {
                acc = l ? 0 : (acc + p[0]) & 15;
            }
            clock = t;
            Map<String, Boolean> in = new LinkedHashMap<>();
            Map<String, Boolean> out = new LinkedHashMap<>();
            for (int i = 0; i < 4; i++) {
                in.put("B" + i, (p[0] >> i & 1) == 1);
                out.put("Q" + i, (acc >> i & 1) == 1);
            }
            in.put("T", t);
            if (clear) {
                in.put("L", l);
            }
            steps.add(new Step(in, out));
        }
        Module mod = clear
                ? g.module(CLEAR_ACCUMULATOR, "4-bit accumulator with clear: each clock T adds B to the stored value; "
                        + "with lever L on, the clock sets it to 0 instead. Levers B front left, T and L back left; "
                        + "lamps Q0..Q3 show the value.")
                : g.module("akkumulator_4bit", "4-bit accumulator: each clock adds B to the stored value. "
                        + "Levers B at the front left of each adder, clock T at the back left; lamps Q0..Q3 above the "
                        + "front show the value, bit 0 left.");
        return new Machine(mod, List.copyOf(ins), List.copyOf(outs), "lamp", Map.of(), List.copyOf(steps));
    }

    /**
     * Name der Maschine mit Löschen: ASCII wie alle Bauteilnamen; „Akkumulator mit Löschen“ trifft ihn als ganze
     * Wortfolge (RequestHints.findNames), „Akkumulator“ allein weiter keinen.
     */
    static final String CLEAR_ACCUMULATOR = "akkumulator_mit_loeschen"; // umlaut-ok

    /**
     * Ablauf der Maschine mit Löschen je Schritt {B, T, L}. B und L ändern sich nur bei T aus (Haltezeit wie B im
     * akkumulator_4bit). Geprüft wird: L allein ändert nichts (Löschen wirkt mit dem Takt), Takt mit L ergibt 0 statt
     * AKKU + B, nach dem Löschen rechnet er normal weiter, Löschen von 15 setzt alle vier Bits zurück (jede der vier
     * Sperren einmal mit 1 davor), und der Überlauf geht danach wie vorher.
     */
    private static final int[][] CLEAR_PLAN = {
            {3, 0, 0}, {3, 1, 0}, {3, 0, 0}, {5, 0, 0}, {5, 1, 0}, {5, 0, 0},         // 3, dann 8
            {5, 0, 1}, {5, 1, 1}, {5, 0, 1}, {5, 0, 0}, {5, 1, 0}, {5, 0, 0},         // L: 8 → 0, dann +5 = 5
            {10, 0, 0}, {10, 1, 0}, {10, 0, 0}, {15, 0, 1}, {15, 1, 1}, {15, 0, 1},   // 15, L mit B = 15 → 0
            {15, 0, 0}, {15, 1, 0}, {15, 0, 0}, {1, 0, 0}, {1, 1, 0}, {1, 0, 0}};     // 15, +1 → 0 (Überlauf)

    /**
     * Löschen für CLEAR_ACCUMULATOR (2026-11-20, Vorstufe zum Steuerwerk aus „Prozessor Stufe 3“ im
     * Gedächtnis): Mit dem Hebel L an setzt der nächste Takt AKKU := 0 statt AKKU + B. Vorher ging zurück auf 0 nur
     * über eine passende Addition bis zum Überlauf.
     *
     * Wie: Zwischen dem Verstärker, der die Summenlampe liest, und dem Master sitzt je Stufe ein Komparator im
     * Subtraktionsmodus (z gate). Hinten 15 vom Verstärker, von links 15 vom Löschverstärker – heraus kommt 0, der
     * Master übernimmt bei T aus eine 0, der Takt gibt sie an den Slave. Ohne L kommt die Summe unverändert durch. Der
     * Seiteneingang muss ein Verstärker sein: Draht käme mit weniger als 15 an, der Komparator ließe 15 − Stärke ≥ 1
     * durch, und der Master sähe trotz L eine 1. Vergleichsmodus wäre falsch herum: 15 gegen 15 gibt 15.
     *
     * Warum mit dem Takt und nicht sofort: In einem Master-Slave-Speicher ist immer einer der beiden Riegel zu, und ein
     * verriegelter Verstärker lässt sich von außen nicht auf 0 zwingen, nur entriegeln. Sofortiges Löschen bräuchte
     * getrennte Riegelleitungen für Master und Slave und eine Verzögerung beim Loslassen, sonst fängt der Slave den
     * alten Master-Wert wieder ein (durchgerechnet 2026-11-20, je ein Redstone-Tick Abstand). Mit dem Takt ist L ein
     * Dateneingang wie B: Ein Steuerwerk legt ihn an, der Takt führt ihn aus – so löscht auch ein Prozessor.
     *
     * Geometrie (lokal, Stufe bei x = dx + sx): Sperre (x, 3, gate); Löschverstärker (x − 1, 3, gate) zeigt von
     * links hinein. Die Leitung dahin kommt von hinten auf y 1 (dort liegt hinter dem Rechenwerk sonst nichts),
     * Spalte x − 2, und steigt erst die letzten zwei Felder auf y 3 – mit einem Feld Abstand zur Reihe der
     * Summenlampen (z gate − 2, Draht des ODER-Netzes), Draht direkt daneben verbände sich mit ihr. Von rechts ginge
     * es nicht: Dort liegen die Master-Riegel und die Taktschiene (x + 3), Draht auf y 3 neben ihrem Stein verbände
     * sich schräg nach oben mit ihrem Draht auf y 4. Die Löschleitung läuft hinter der Taktleitung (z bus + 1) auf
     * y 1 nach rechts, Verstärker vor jedem Abzweig und einer dazwischen wie dort; jeder Abzweig beginnt mit einem
     * Verstärker, der nur von der Leitung nimmt. Hebel L hinten links am Anfang der Leitung.
     *
     * Speicher nach dem Bau auf 0: derselbe Weg wie akkumulator_4bit – BuildJob setzt jeden verriegelten Verstärker,
     * der an ist, nach dem Bau auf aus (6eb5eab). Der Komparator ist kein Speicher. Gibt den Pin von L zurück.
     */
    private static Pin accumulatorClear(Grid g, int sx, int gate, int bus) {
        int j0 = sx - 2;                                                   // Spalte des ersten Abzweigs (12)
        int line = bus + 1;                                                // z der Löschleitung (46)
        List<Pos> cells = new ArrayList<>();
        for (int bit = 0; bit < 4; bit++) {
            int j = bit * ADDER_STRIDE + j0;
            cells.add(new Pos(j, 1, bus));
            List<Pos> path = new ArrayList<>();
            for (int z = bus - 1; z >= gate + 2; z--) {
                path.add(new Pos(j, 1, z));
            }
            path.add(new Pos(j, 2, gate + 1));
            path.add(new Pos(j, 3, gate));
            path.add(new Pos(j + 1, 3, gate));                             // Löschverstärker, zeigt in die Sperre
            for (Pos p : path) {
                cells.add(p);
                if (p.y() > 1) {
                    cells.add(p.add(0, -1, 0));
                }
            }
            requireFree(g, cells);
            cells.clear();
            g.put(j, 1, bus, "repeater[facing=back]");                     // Abzweig: nimmt nur von der Leitung
            route(g, path, 15, true);
        }
        int last = 3 * ADDER_STRIDE + j0;
        for (int x = j0 - 1; x <= last; x++) {
            cells.add(new Pos(x, 1, line));
        }
        requireFree(g, cells);
        g.put(j0 - 1, 1, line, LEVER);                                     // L
        for (int x = j0; x <= last; x++) {
            int rel = (x - j0) % ADDER_STRIDE;
            boolean repeater = x > j0 && (rel == ADDER_STRIDE - 1 || rel == 14);
            g.put(x, 1, line, repeater ? "repeater[facing=left]" : WIRE);
        }
        return pin("L", j0 - 1, 1, line);
    }

    /** Die Löschleitung darf nichts überbauen – route() und put() überschrieben belegte Zellen still. */
    private static void requireFree(Grid g, List<Pos> cells) {
        for (Pos p : cells) {
            if (g.at(p.x(), p.y(), p.z()) != null) {
                throw new IllegalStateException("Löschleitung trifft belegte Zelle " + p + ": " + g.at(p.x(), p.y(),
                        p.z()));
            }
        }
    }

    /**
     * Leitung entlang path (Nachbarzellen, je Schritt höchstens ein Block höher/tiefer): Draht, Stein darunter ab
     * y 2, und ein Verstärker in Laufrichtung, wo die Stärke sonst unter 2 fiele – nur auf geraden, flachen Stücken
     * (Verstärker wechseln nie die Höhe). lastRepeater: Die letzte Zelle ist ein Verstärker, der mit voller Stärke in
     * die Zelle dahinter gibt (Eingänge des Logik-Rasters rechnen mit 15 wie von einem Hebel). Reicht die Stärke
     * nirgends, scheitert schon der Aufbau der Bibliothek statt erst die Probe im Spiel.
     */
    private static void route(Grid g, List<Pos> path, int start, boolean lastRepeater) {
        int n = path.size();
        // Plätze, an denen ein Verstärker stehen kann: flach und gerade (Ein- und Ausgang in Laufrichtung).
        boolean[] slot = new boolean[n];
        for (int i = 1; i < n; i++) {
            Pos p = path.get(i);
            Pos prev = path.get(i - 1);
            Pos next = i + 1 < n ? path.get(i + 1) : null;
            slot[i] = prev.y() == p.y() && (next == null ? lastRepeater : next.y() == p.y()
                    && next.x() - p.x() == p.x() - prev.x() && next.z() - p.z() == p.z() - prev.z());
        }
        if (lastRepeater && !slot[n - 1]) {
            throw new IllegalStateException("Letzter Verstärker passt nicht bei " + path.getLast());
        }
        int s = start;                                                     // Stärke der Zelle davor
        for (int i = 0; i < n; i++) {
            Pos p = path.get(i);
            boolean repeater = false;
            if (i == n - 1 && lastRepeater) {
                repeater = true;
            } else if (slot[i]) {
                // So spät wie möglich: Ohne Verstärker hier werden i..j-1 Draht (s-1 … s-(j-i)), und vor dem nächsten
                // Platz j muss noch Signal ankommen. Treppen und Ecken dazwischen tragen keinen Verstärker.
                int j = i + 1;
                while (j < n && !slot[j]) {
                    j++;
                }
                repeater = s - (j - i) < 1;
            }
            if (repeater) {
                Pos prev = path.get(i - 1);
                if (s < 1) {
                    throw new IllegalStateException("Leitung zu schwach vor " + p);
                }
                String facing = prev.x() < p.x() ? "left" : prev.x() > p.x() ? "right" : prev.z() < p.z() ? "front"
                        : "back";
                g.put(p.x(), p.y(), p.z(), "repeater[facing=" + facing + "]");
                s = 16;                                                    // die nächste Zelle bekommt 15
            } else {
                s = i == 0 ? start : s - 1;
                if (s < 1) {
                    throw new IllegalStateException("Leitung zu schwach bei " + p);
                }
                g.put(p.x(), p.y(), p.z(), WIRE);
            }
            if (p.y() > 1) {
                g.put(p.x(), p.y() - 1, p.z(), "stone");
            }
        }
    }

    /** Ein Prüfschritt des 4-Bit-Addierers: A + B + CI, erwartet S0..S3 und den Überlauf C4. */
    static Step adderStep(int a, int b, int ci) {
        int sum = a + b + ci;
        Map<String, Boolean> in = new LinkedHashMap<>();
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) {
            in.put("A" + i, (a >> i & 1) == 1);
            in.put("B" + i, (b >> i & 1) == 1);
            out.put("S" + i, (sum >> i & 1) == 1);
        }
        in.put("CI", ci == 1);
        out.put("C4", sum > 15);
        return new Step(in, out);
    }

    private static Machine fullAdder() {
        LogicCircuit.Circuit c = LogicCircuit.compile(FULL_ADDER);
        Module raw = c.module();
        Module mod = new Module("volladdierer", "Full adder: levers A, B, CI (carry in) on the left, front to back; "
                + "lamps at the back: S (sum) left, CO (carry out) right.", raw.size(), raw.cells());
        List<Pin> ins = new ArrayList<>();
        for (int i = 0; i < c.inputs().size(); i++) {
            Pos p = c.inputPins().get(i);
            ins.add(new Pin(c.inputs().get(i), p, p, null));
        }
        List<Pin> outs = new ArrayList<>();
        for (LogicCircuit.Output o : c.outputs()) {
            outs.add(new Pin(o.name(), o.lamp(), o.lamp(), null));
        }
        return new Machine(mod, List.copyOf(ins), List.copyOf(outs), "lamp", LogicCircuit.truthTables(c));
    }

    /**
     * Impulsverlängerer: Ein Knopf gibt 1–1,5 s Strom – zu kurz, um durch eine Tür zu gehen oder eine Lampe sinnvoll
     * leuchten zu lassen. Hier läuft der Eingang direkt (Draht x=1/x=2) und zugleich durch eine Kette aus sechs
     * Verstärkern zu je 4 Ticks (x=0); hinter jedem Paar (8 Redstone-Ticks) zweigt ein Draht über einen Verstärker als
     * Diode in die Sammelleitung zur Lampe ab. Die Lampe bleibt so ~2,5 s länger an als der Eingang, ohne Lücke für
     * jeden Puls ab 8 Ticks (Steinknopf 10, Holzknopf 15). Warum Abgriffe statt nur des Kettenendes: Eine reine
     * Verzögerung schiebt einen kurzen Puls nur nach hinten – Lampe an, aus, später nochmal an. Warum Dioden: Ohne sie
     * speiste die Sammelleitung die Kette rückwärts, und alles hielte sich gegenseitig für immer an.
     */
    private static Machine pulseExtender() {
        Grid g = new Grid()
                .put(0, 1, 0, LEVER)                                   // Eingang (Knopf/Platte statt Hebel geht)
                .put(1, 1, 0, WIRE);                                   // direkter Weg
        for (int z = 0; z <= 10; z++) {
            g.put(2, 1, z, WIRE);                                      // Sammelleitung
        }
        for (int stage = 0; stage < 3; stage++) {
            int z = 1 + 3 * stage;
            g.put(0, 1, z, "repeater[facing=front,delay=4]")
                    .put(0, 1, z + 1, "repeater[facing=front,delay=4]")
                    .put(0, 1, z + 2, WIRE)                            // Abgriff hinter dem Paar
                    .put(1, 1, z + 2, "repeater[facing=left,delay=1]"); // Diode in die Sammelleitung
        }
        g.put(2, 1, 11, "redstone_lamp");
        String wait = "time query gametime";
        return new Machine(g.module("impulsverlaengerer", "Pulse extender: a button or plate at the front left keeps the "
                + "lamp at the back right on ~2.5 s longer (swap the lamp for an iron door or piston)."),
                List.of(pin("I", 0, 1, 0)), List.of(pin("Q", 2, 1, 11)), "lamp", Map.of(), List.of(
                        new Step(bits("I", "0"), bits("Q", "0")),
                        new Step(bits("I", "1"), bits("Q", "1")),
                        // 1,2 s nach dem Loslassen: die Kette hält die Lampe noch (~2,5 s).
                        new Step(bits("I", "0"), bits("Q", "1")),
                        // weitere 3 s später (Befehl = längere Wartezeit der Probe): aus.
                        new Step(bits("I", "0"), bits("Q", "0"), List.of(wait))));
    }

    /**
     * Schmelzanlage: Trichter oben füllt den Ofen (Erz, Sand, rohes Fleisch …), Trichter links den Brennstoff, Trichter
     * darunter holt das Fertige in die Truhe. Die Grundmaschine jeder Erzverarbeitung; keine Redstone-Teile, nur die
     * festen Regeln des Ofens (oben = Eingabe, Seite = Brennstoff, unten = Ausgabe). Ein Ofen braucht 10 s je Stück,
     * die Trichter ~0,8 s dazu. Die Probe prüft bis 9 s, dass nichts sofort da ist, wartet bei 12 s nur (leerer
     * Schritt) und erwartet den Barren nach 15 s. Vorher kam die Prüfung schon nach 12 s – 1,2 s Puffer, unter 20 TPS
     * (Lastregelung, paralleler Bau) wäre die Probe ohne Maschinenfehler gescheitert (Prüfbefund 2026-10-29).
     */
    private static Machine smelter() {
        Grid g = new Grid()
                .put(1, 3, 1, "hopper[facing=down]")                   // Eingabe
                .put(0, 2, 1, "hopper[facing=right]")                  // Brennstoff
                .put(1, 2, 1, "furnace[facing=front]")
                .put(1, 1, 1, "hopper[facing=back]")                   // Ausgabe
                .put(1, 1, 2, "chest[facing=front]");
        String wait = "time query gametime";
        return new Machine(g.module("schmelzanlage", "Auto smelter: ore, sand or raw food into the top hopper, coal "
                + "into the left hopper; results collect in the chest at the back."),
                List.of(), List.of(statePin("BARREN", 1, 1, 2, "items:minecraft:iron_ingot"),
                        statePin("BRENNT", 1, 2, 1, "furnace[lit=true]")), "state", Map.of(), List.of(
                        new Step(Map.of(), bits("BARREN", "0", "BRENNT", "1"), List.of(
                                "item replace block {1,3,1} container.0 with minecraft:raw_iron 2",
                                "item replace block {0,2,1} container.0 with minecraft:coal 1")),
                        new Step(Map.of(), bits("BARREN", "0", "BRENNT", "1"), List.of(wait)),
                        new Step(Map.of(), bits("BARREN", "0", "BRENNT", "1"), List.of(wait)),
                        new Step(Map.of(), Map.of(), List.of(wait)),
                        new Step(Map.of(), bits("BARREN", "1", "BRENNT", "1"), List.of(wait))));
    }

    /**
     * Item-Sortierer (eine Sorte): Was oben in den Trichter fällt, läuft über den Linientrichter L2 in die Truhe
     * hinten oben – außer der Sorte, mit der der Filtertrichter F darunter gefüllt ist; die zieht F aus L2.
     *
     * Wie: F hält 18 Stück der Sorte und je 1 Platzhalter in den anderen vier Fächern = 22 Stück, der Komparator
     * liest daraus Stärke 1; ein 23. Stück macht 2 (Komparator: 1 + ⌊14 · Füllstand⌋). Zwei Drähte dahinter bleibt
     * von 1 nichts übrig, von 2 genau 1 – das speist Block B, dessen Fackel aus geht. Die Fackel sperrt über einen
     * Draht den Trichter H unter F; ohne Sperre zieht H ein Stück aus F, F fällt auf 22, die Fackel sperrt wieder. F
     * zeigt seitlich in den Komparator, also in nichts, was Gegenstände nimmt – sonst gäbe F selbst alles ab. Wer
     * statt der Sperre von H die von F baut: F zieht dann auch nichts mehr aus L2, die Sorte läuft durch.
     * Zwei Drähte, nicht einer: Mit einem bekäme B schon bei 22 Stück Strom, H stünde immer offen und F liefe leer.
     *
     * Grenzen, die deshalb in der Beschreibung stehen: Die Schwelle 22/23 gilt nur für Sorten, die sich bis 64
     * stapeln – bei 16er-Stapeln (Eier, Enderperlen, Schilder) passen keine 18 Stück in ein Fach, der Füllstand und
     * damit die Stärke stimmen nicht mehr. Und solange F nicht gefüllt ist, zieht F beliebige Gegenstände aus L2, bis
     * 23 erreicht sind, und sortiert danach falsch. Die Platzhalter müssen umbenannt sein, sonst zieht F deren Sorte
     * mit. Die Bedienschritte im Einzelnen stehen hier und nicht in der Beschreibung: Die MODULE LIBRARY geht mit
     * jedem LLM-Auftrag in den Prompt (MachinesTest.machineDescriptionsStayShortForThePrompt).
     */
    private static Machine itemSorter() {
        Grid g = new Grid()
                .put(0, 3, 0, "hopper[facing=back]")                   // Einwurf L1
                .put(0, 3, 1, "hopper[facing=back]")                   // Linie L2
                .put(0, 3, 2, "chest[facing=front]")                   // Rest
                .put(0, 2, 1, "hopper[facing=right]")                  // Filter F, zeigt in den Komparator
                .put(0, 1, 1, "hopper[facing=front]")                  // H, gesperrt bis F 23 Stück hat
                .put(0, 1, 0, "chest[facing=front]")                   // Sortiert
                .put(1, 1, 1, "stone")
                .put(1, 2, 1, "comparator[facing=left]")               // liest F
                .put(2, 1, 1, "stone")
                .put(2, 2, 1, WIRE)
                .put(3, 1, 1, "stone")                                 // B
                .put(3, 2, 1, WIRE)
                .put(3, 1, 2, "redstone_wall_torch[facing=back]")      // an B
                .put(3, 1, 3, WIRE).put(2, 1, 3, WIRE).put(1, 1, 3, WIRE).put(0, 1, 3, WIRE)
                .put(0, 1, 2, WIRE);                                   // zeigt in H
        String filter = "item replace block {0,2,1} container.";
        String wait = "time query gametime";
        return new Machine(g.module("item_sortierer", "Sorts one item kind (must stack to 64): before use put 18 of it "
                + "plus 1 renamed item in each other slot of the middle hopper. Input front top, sorted front bottom, "
                + "rest back top."),
                List.of(), List.of(statePin("SORTIERT", 0, 1, 0, "items:minecraft:cobblestone"),
                        statePin("FREMD_UNTEN", 0, 1, 0, "items:minecraft:dirt"),
                        statePin("REST", 0, 3, 2, "items:minecraft:dirt"),
                        statePin("SORTE_OBEN", 0, 3, 2, "items:minecraft:cobblestone")), "state", Map.of(), List.of(
                        new Step(Map.of(), bits("SORTIERT", "0", "FREMD_UNTEN", "0", "REST", "0", "SORTE_OBEN", "0"),
                                List.of(filter + "0 with minecraft:cobblestone 18",
                                        filter + "1 with minecraft:stick 1", filter + "2 with minecraft:stick 1",
                                        filter + "3 with minecraft:stick 1", filter + "4 with minecraft:stick 1")),
                        new Step(Map.of(), bits("SORTIERT", "1", "FREMD_UNTEN", "0", "REST", "1", "SORTE_OBEN", "0"),
                                List.of("item replace block {0,3,0} container.0 with minecraft:cobblestone 4",
                                        "item replace block {0,3,0} container.1 with minecraft:dirt 3")),
                        new Step(Map.of(), bits("SORTIERT", "1", "FREMD_UNTEN", "0", "REST", "1", "SORTE_OBEN", "0"),
                                List.of(wait))));
    }

    /** Namensstamm des Item-Sortierers für mehrere Sorten: „item_sortierer_3“, mit Sorten „item_sortierer_3:sand,dirt“. */
    public static final String MULTI_SORTER = "item_sortierer_";
    /** Kleinste und größte Zahl Sorten (Vertrag AK-18). */
    public static final int MULTI_SORTER_MIN = 2;
    public static final int MULTI_SORTER_MAX = 8;
    /** Die Fassung, die in der Bibliothek steht und von MachineProbe in vier Richtungen geprüft ist. */
    public static final int MULTI_SORTER_PROBED = 3;
    /**
     * Abstand zweier Scheiben entlang x. Eine Scheibe belegt x..x+3 (Filter, Komparator, zwei Drähte samt Fackel). Bei
     * Abstand 4 läge der Filter der nächsten Scheibe direkt vor dem letzten Draht: Der Draht zeigt in ihn, sperrt ihn,
     * und der Filter zöge nichts mehr aus der Linie; ebenso speiste die Fackel den Rückdraht der Nachbarscheibe und hielte
     * deren unteren Trichter für immer zu. Wer den Abstand verkleinert, koppelt die Scheiben.
     */
    public static final int MULTI_SORTER_STRIDE = 5;
    /** Platzhalter in den Fächern 2–5 eines vorbefüllten Filters – umbenannt, damit er sich mit keinem Stock stapelt. */
    static final String SORTER_PLACEHOLDER = "{id:\"minecraft:stick\",count:1,components:{\"minecraft:custom_name\":"
            + "\"Sortierer-Platzhalter\"}}";
    private static final java.util.regex.Pattern MULTI_SORTER_NAME = java.util.regex.Pattern.compile(
            "item_sortierer_(\\d)(?::([a-z0-9_]+(?:,[a-z0-9_]+)*))?");

    /**
     * Sortierer für N Sorten aus dem Namen: „item_sortierer_N“ (2 ≤ N ≤ 8, Filter leer, der Spieler füllt sie selbst –
     * Stufe „alle“ wie beim Ein-Sorten-Sortierer) oder „item_sortierer_N:a,b,…“ mit bis zu N Sorten, deren Filter beim
     * Bau gefüllt werden. Unbekannte, doppelte, nicht bis 64 stapelbare oder zu viele Sorten → leer (das Bauteil gibt es dann nicht, fail-closed):
     * Ein Tippfehler soll keinen Sortierer bauen, der eine falsche Sorte zieht.
     */
    static Optional<Machine> multiItemSorter(String name) {
        java.util.regex.Matcher mt = MULTI_SORTER_NAME.matcher(name.replace("minecraft:", ""));
        if (!mt.matches()) {
            return Optional.empty();
        }
        int n = Integer.parseInt(mt.group(1));
        if (n < MULTI_SORTER_MIN || n > MULTI_SORTER_MAX) {
            return Optional.empty();
        }
        List<String> kinds = mt.group(2) == null ? List.of() : List.of(mt.group(2).split(","));
        if (kinds.size() > n || kinds.stream().distinct().count() != kinds.size()) {
            return Optional.empty();
        }
        for (String k : kinds) {
            // Befehls- oder unbekannte Gegenstände nie in einen Bau legen; alles andere stuft PlacementGate je Zelle.
            if (BlockTiers.item(k).tier() == Tier.COMMANDS) {
                return Optional.empty();
            }
            // Nur 64er-Stapel (Prüfung 2026-11-21): Vorher baute „item_sortierer_3:diamond_sword“ einen Filter mit
            // count:18 – ein Schwert stapelt sich nicht, Fach 1 hielte 1 Stück, und die Schwelle 22/23 stimmte nie.
            if (!stacksTo64(k)) {
                return Optional.empty();
            }
        }
        return Optional.of(multiItemSorter(n, kinds));
    }

    private static volatile java.util.Set<String> stack64;

    /**
     * Stapelt sich die Sorte in 1.21.11 und 26.2 bis 64 (tiers/item-stack-64.txt, aus dem Datengenerator)? Fehlt die
     * Liste im Plugin, gilt nichts als stapelbar – dann gibt es keinen vorbefüllten Sortierer, aber keinen falschen.
     */
    static boolean stacksTo64(String id) {
        java.util.Set<String> s = stack64;
        if (s == null) {
            java.util.Set<String> loaded = new java.util.HashSet<>();
            try (java.io.InputStream in = Machines.class.getResourceAsStream("/tiers/item-stack-64.txt")) {
                if (in != null) {
                    for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                            .split("\n")) {
                        String t = line.trim();
                        if (!t.isEmpty() && !t.startsWith("#")) {
                            loaded.add(t);
                        }
                    }
                }
            } catch (java.io.IOException e) {
                // fail-closed (s. oben): leere Liste, jede Sorte wird abgelehnt.
                loaded.clear();
            }
            s = java.util.Set.copyOf(loaded);
            stack64 = s;
        }
        return s.contains(id);
    }

    /**
     * Item-Sortierer für N Sorten (Vertrag AK-18): N Scheiben des Ein-Sorten-Sortierers (itemSorter, dort steht das
     * Warum jedes Blocks) im Abstand MULTI_SORTER_STRIDE nebeneinander. Statt L2 läuft oben eine Trichterlinie nach
     * rechts über alle Filter; jeder Filter F zieht nur seine Sorte heraus, der letzte Linientrichter gibt den Rest in
     * die Truhe hinten rechts. Die Scheibe ist Block für Block die geprüfte – nur L2 zeigt nach rechts statt nach
     * hinten, und die Rest-Truhe steht einmal am Ende. Den Ein-Sorten-Sortierer selbst lässt das unverändert
     * (MachinesTest.TESTED_LAYOUT).
     *
     * Wie die Sorten hineinkommen: ohne Angabe füllt der Spieler jeden Filter selbst (18 Stück plus 4 umbenannte
     * Platzhalter), der Bau braucht dann nur die Stufe „alle“. Mit Angabe im Namen trägt jeder Filter seine Gegenstände
     * als Blockdaten (Items) – das ist die Stufe „Inhalte“ (BlockTiers: Gegenstände im Behälter), die PlacementGate
     * beim Setzen je Zelle prüft; ohne das Recht entfällt der ganze Sortierer (Gruppe, ganz oder gar nicht). Keine
     * Befehle, keine Befehlsblöcke: Die Daten gehen wie jedes Bauteil über „data merge“ an den Server.
     * Alternative verworfen: die Filter nach dem Bau per Befehl füllen (wie die Probe) – das wäre ein Serverbefehl aus
     * einem Plan und bräuchte die Stufe Befehle.
     */
    static Machine multiItemSorter(int n, List<String> kinds) {
        int last = (n - 1) * MULTI_SORTER_STRIDE;
        Grid g = new Grid().put(0, 3, 0, "hopper[facing=back]");            // Einwurf L1
        for (int x = 0; x <= last; x++) {
            g.put(x, 3, 1, x == last ? "hopper[facing=back]" : "hopper[facing=right]"); // Linie
        }
        g.put(last, 3, 2, "chest[facing=front]");                            // Rest
        for (int k = 0; k < n; k++) {
            int x = k * MULTI_SORTER_STRIDE;
            boolean given = k < kinds.size();
            g.put(x, 2, 1, filterHopper(given ? kinds.get(k) : null))           // Filter F
                    .put(x, 1, 1, lowerHopper(given))                        // H, gesperrt bis F 23 Stück hat
                    .put(x, 1, 0, "chest[facing=front]")                     // Sorte k
                    .put(x + 1, 1, 1, "stone")
                    .put(x + 1, 2, 1, "comparator[facing=left]")             // liest F
                    .put(x + 2, 1, 1, "stone")
                    .put(x + 2, 2, 1, WIRE)
                    .put(x + 3, 1, 1, "stone")                               // B
                    .put(x + 3, 2, 1, WIRE)
                    .put(x + 3, 1, 2, "redstone_wall_torch[facing=back]")    // an B
                    .put(x + 3, 1, 3, WIRE).put(x + 2, 1, 3, WIRE).put(x + 1, 1, 3, WIRE).put(x, 1, 3, WIRE)
                    .put(x, 1, 2, WIRE);                                     // zeigt in H
        }
        String name = MULTI_SORTER + n + (kinds.isEmpty() ? "" : ":" + String.join(",", kinds));
        // ≤ 220 Zeichen (MachinesTest.machineDescriptionsStayShortForThePrompt); Bedienschritte in FarmGuide.
        String description = "Sorts N item kinds (N 2-8, name item_sortierer_N; item_sortierer_N:sand,dirt prefills). "
                + "Else before use put 18 of a kind (must stack to 64) + 1 renamed item per other slot in each middle "
                + "hopper.";
        // Sorten der Probe: die angegebenen, die übrigen Scheiben mit festen Sorten, die die Probe selbst einfüllt.
        List<String> probe = new ArrayList<>(kinds);
        for (String k : List.of("cobblestone", "sand", "oak_planks", "andesite", "gravel", "oak_log", "stone",
                "glass", "granite")) {
            if (probe.size() < n && !probe.contains(k)) {
                probe.add(k);
            }
        }
        return sorterProbe(g.module(name, description), n, List.copyOf(probe), kinds.size());
    }

    /**
     * Wartezeit des unteren Trichters H in Ticks, wenn sein Filter beim Bau gefüllt wird. Gemessen 2026-11-21 (Spigot
     * 1.21.11, item_sortierer_3:cobblestone,sand,oak_planks, Nord): Ohne sie verloren die Filter beim Bau 8, 6 und 4 der
     * 18 Stück an ihre Truhe – H stand offen, bis Fackel und Rückdraht der Scheibe gesetzt waren (~3 s bei Scheibe 0,
     * der Bau setzt die Filter vor den Fackeln). Liefe Fach 1 leer, zöge H danach die Platzhalter, und F nähme jede
     * Sorte an. 600 Ticks (30 s) sind das Zehnfache; danach hält die Fackel H wie im leeren Bau. Eine Sorte, die in
     * diesen 30 s ankommt, bleibt in F und läuft danach in ihre Truhe. Stufe wie die Gegenstände: Inhalte.
     */
    static final int SORTER_FILLED_COOLDOWN = 600;

    /** H: gibt in die Truhe davor; unter einem beim Bau gefüllten Filter erst nach SORTER_FILLED_COOLDOWN Ticks. */
    private static BlockSpec lowerHopper(boolean filled) {
        String raw = "hopper[facing=front]" + (filled ? "{TransferCooldown:" + SORTER_FILLED_COOLDOWN + "}" : "");
        try {
            return BlockSpec.parseTrusted(raw);
        } catch (PlanException e) {
            throw new IllegalArgumentException(raw, e);
        }
    }

    /** Filtertrichter F: zeigt in den Komparator; mit Sorte 18 Stück plus 4 Platzhalter als Blockdaten. */
    private static BlockSpec filterHopper(String kind) {
        String raw = "hopper[facing=right]";
        if (kind != null) {
            StringBuilder items = new StringBuilder("{Items:[{Slot:0b,id:\"minecraft:" + kind + "\",count:18}");
            for (int slot = 1; slot <= 4; slot++) {
                items.append(",{Slot:").append(slot).append("b,").append(SORTER_PLACEHOLDER.substring(1));
            }
            raw += items.append("]}");
        }
        try {
            return BlockSpec.parseTrusted(raw);
        } catch (PlanException e) {
            throw new IllegalArgumentException(raw, e);
        }
    }

    /**
     * Ablauf für MachineProbe: Filter füllen (nur die ohne Sorte im Namen – die anderen prüft die Probe darauf, dass
     * die Blockdaten angekommen sind), dann je Sorte 4 Stück und 3 Erde in den Einwurf. Erwartet: jede Sorte in ihrer
     * Truhe und nicht in der Nachbartruhe, Erde nur im Rest, keine Sorte im Rest. Der erste Schritt erwartet alle
     * Truhen leer: Ein Filter, der beim Bau ausläuft (H noch offen, gemessen 2026-11-21 vor SORTER_FILLED_COOLDOWN),
     * hat dort schon seine Sorte abgelegt.
     */
    private static Machine sorterProbe(Module module, int n, List<String> kinds, int prefilled) {
        int last = (n - 1) * MULTI_SORTER_STRIDE;
        List<Pin> outs = new ArrayList<>();
        List<String> none = new ArrayList<>();
        List<String> done = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            int x = k * MULTI_SORTER_STRIDE;
            String s = String.valueOf(k + 1);
            outs.add(statePin("SORTE" + s, x, 1, 0, "items:minecraft:" + kinds.get(k)));
            outs.add(statePin("QUER" + s, x, 1, 0, "items:minecraft:" + kinds.get((k + 1) % n)));
            outs.add(statePin("FREMD" + s, x, 1, 0, "items:minecraft:dirt"));
            outs.add(statePin("REST_SORTE" + s, last, 3, 2, "items:minecraft:" + kinds.get(k)));
            outs.add(statePin("FILTER" + s, x, 2, 1, "items:minecraft:" + kinds.get(k)));
            none.addAll(List.of("SORTE" + s, "0", "QUER" + s, "0", "FREMD" + s, "0", "REST_SORTE" + s, "0"));
            done.addAll(List.of("SORTE" + s, "1", "QUER" + s, "0", "FREMD" + s, "0", "REST_SORTE" + s, "0"));
            // Nach Schritt 1 ist jeder Filter voll: vorbefüllt aus dem Bau, sonst von den Befehlen des Schritts.
            none.addAll(List.of("FILTER" + s, "1"));
            done.addAll(List.of("FILTER" + s, "1"));
        }
        outs.add(statePin("REST", last, 3, 2, "items:minecraft:dirt"));
        none.addAll(List.of("REST", "0"));
        done.addAll(List.of("REST", "1"));
        String wait = "time query gametime";
        List<String> setup = new ArrayList<>();
        if (prefilled < n) {
            for (int k = prefilled; k < n; k++) {
                String f = "item replace block {" + k * MULTI_SORTER_STRIDE + ",2,1} container.";
                setup.add(f + "0 with minecraft:" + kinds.get(k) + " 18");
                for (int slot = 1; slot <= 4; slot++) {
                    setup.add(f + slot + " with minecraft:stick[minecraft:custom_name=\"Sortierer-Platzhalter\"] 1");
                }
            }
        } else {
            setup.add(wait);
        }
        // Der Einwurf hat 5 Fächer: je Sorte 4 Stück, Erde dazu; mehr Sorten als 4 kommen in einem zweiten Schub.
        List<String> feed = new ArrayList<>();
        List<String> feed2 = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            (k < 4 ? feed : feed2).add("item replace block {0,3,0} container." + (k % 4) + " with minecraft:"
                    + kinds.get(k) + " 4");
        }
        feed.add("item replace block {0,3,0} container.4 with minecraft:dirt 3");
        List<Step> seq = new ArrayList<>();
        seq.add(new Step(Map.of(), bits(none.toArray(String[]::new)), setup));
        List<String> first = new ArrayList<>(feed);
        first.add(feed2.isEmpty() ? "#bis 60" : wait);
        seq.add(new Step(Map.of(), feed2.isEmpty() ? bits(done.toArray(String[]::new)) : Map.of(), first));
        if (!feed2.isEmpty()) {
            // Erster Schub: 19 Stück zu 0,4 s, bis der Einwurf leer ist (item replace überschriebe den Rest).
            for (int i = 0; i < 4; i++) {
                seq.add(new Step(Map.of(), Map.of(), List.of(wait)));
            }
            List<String> second = new ArrayList<>(feed2);
            second.add("#bis 60");
            seq.add(new Step(Map.of(), bits(done.toArray(String[]::new)), second));
        }
        // Nachlauf: Was noch in der Linie steckt, darf danach nicht falsch landen.
        for (int i = 0; i < 3; i++) {
            seq.add(new Step(Map.of(), Map.of(), List.of(wait)));
        }
        seq.add(new Step(Map.of(), bits(done.toArray(String[]::new)), List.of(wait)));
        return new Machine(module, List.of(), List.copyOf(outs), "state", Map.of(), List.copyOf(seq));
    }

    private static Module shiftZ(Module m, int dz) {
        List<CompiledBuild.Change> moved = new ArrayList<>();
        for (CompiledBuild.Change c : m.cells()) {
            moved.add(new CompiledBuild.Change(c.pos().add(0, 0, dz), c.block()));
        }
        // Boden auch unter der neuen Vorderreihe (Grid legte ihn nur bis z>=0 der alten Lage).
        for (int x = 0; x < m.size().x(); x++) {
            Pos floor = new Pos(x, 0, 0);
            if (moved.stream().noneMatch(c -> c.pos().equals(floor))) {
                moved.add(new CompiledBuild.Change(floor, BlockSpec.of("smooth_stone")));
            }
        }
        return new Module(m.name(), m.description(), new Pos(m.size().x(), m.size().y(), m.size().z() + dz),
                List.copyOf(moved));
    }
}
