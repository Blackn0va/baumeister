package com.blackn0va.baumeister.redstone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.Pos;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * Redstone lesen an echten Blockzuständen: Die Testdaten schrieb probes/redstone/RedstoneProbe am 2026-11-06 auf
 * Paper 1.21.11-132 (Zeile = Spur x y z BlockData). Spuren: 0 Verstärker richtig, 1 Verstärker falsch herum, 2 17 Staub,
 * 3 Fackel als NICHT, 4 gesperrter Verstärker, 5 Kolben und Beobachter, 6 Komparator an Truhe, 7 Lampe ohne Verbindung.
 */
class RedstoneReaderTest {

    private static final Function<String, String> DIR = Map.of("north", "Norden", "south", "Süden", "east", "Osten",
            "west", "Westen", "up", "oben", "down", "unten")::get;

    private static Map<Integer, Map<Pos, String>> lanes() throws IOException {
        Map<Integer, Map<Pos, String>> out = new HashMap<>();
        try (InputStream in = RedstoneReaderTest.class.getResourceAsStream("/redstone/probe-2026-11-06.txt")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String[] f = line.strip().split(" ", 5);
                if (f.length == 5) {
                    out.computeIfAbsent(Integer.parseInt(f[0]), k -> new HashMap<>()).put(
                            new Pos(Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3])), f[4]);
                }
            }
        }
        return out;
    }

    private static RedstoneReader.Report lane(int n) throws IOException {
        return RedstoneReader.read(lanes().get(n), DIR);
    }

    private static String findings(RedstoneReader.Report r) {
        return String.join(" | ", r.findings().stream().map(RedstoneReader.Finding::text).toList());
    }

    @Test
    void parsesBlockDataText() {
        RedstoneReader.Cell c = RedstoneReader.Cell.parse(
                "minecraft:repeater[delay=1,facing=west,locked=false,powered=true]");
        assertEquals("repeater", c.id());
        assertEquals("west", c.props().get("facing"));
        assertEquals("stone", RedstoneReader.Cell.parse("minecraft:stone").id());
        assertEquals("air", RedstoneReader.Cell.parse(null).id());
        assertTrue(RedstoneReader.isPart("minecraft:redstone_wire[power=0]"));
        assertFalse(RedstoneReader.isPart("minecraft:oak_planks"));
    }

    @Test
    void workingCircuitHasNoFindingsAndDescribesItsParts() throws IOException {
        RedstoneReader.Report r = lane(0);
        assertEquals(1, r.circuits().size());
        assertEquals("", findings(r));
        String d = RedstoneReader.describe(r, DIR, 2000);
        assertTrue(d.contains("Hebel 0 0 0 an"), d);
        assertTrue(d.contains("Verstärker 3 0 0 Eingang Westen, Verzögerung 1, an"), d);
        assertTrue(d.contains("Lampe 4 0 0 leuchtet"), d);
        assertTrue(d.contains("Staub 2 Blöcke, Stärke 15 bis 14"), d);
    }

    @Test
    void repeaterFacingTheWrongWayIsFound() throws IOException {
        String f = findings(lane(1));
        assertTrue(f.contains("Verstärker bei 3 0 0 zeigt vermutlich falsch herum"), f);
        assertTrue(f.contains("kommt aus Richtung Westen, sein Eingang zeigt aber nach Osten"), f);
    }

    @Test
    void fixesAreSuggestedForTheTwoSafeCases() throws IOException {
        RedstoneReader.Finding back = lane(1).findings().stream().filter(f -> f.fix() != null).findFirst().orElseThrow();
        assertEquals(new Pos(3, 0, 0), back.pos());
        assertEquals("minecraft:repeater[delay=1,facing=west,locked=false,powered=false]", back.fix());
        RedstoneReader.Finding fade = lane(2).findings().stream().filter(f -> f.fix() != null).findFirst().orElseThrow();
        assertEquals(new Pos(16, 0, 0), fade.pos());
        assertEquals("minecraft:repeater[delay=1,facing=west,locked=false,powered=false]", fade.fix());
        // Endet die Leitung genau dort (kein Staub, keine Lampe dahinter), gäbe ein Verstärker ins Leere: kein Umbau.
        Map<Pos, String> end = new HashMap<>(lanes().get(2));
        end.remove(new Pos(17, 0, 0));
        end.remove(new Pos(18, 0, 0));
        assertTrue(RedstoneReader.read(end, DIR).findings().stream().allMatch(f -> f.fix() == null),
                "Verstärker ins Leere");
        // Lücke, Fackel, gesperrt: kein Umbau (Bauen nach Absicht).
        assertTrue(lane(7).findings().stream().allMatch(f -> f.fix() == null));
        assertTrue(lane(4).findings().stream().allMatch(f -> f.fix() == null));
    }

    @Test
    void deadWireEndWithNothingBehindIsNoFault() throws IOException {
        // Im Spiel 2026-11-06: Staub läuft in einer Anlage einfach aus – keine Lampe dahinter, kein Fehler.
        Map<Pos, String> m = new HashMap<>(lanes().get(2));
        m.remove(new Pos(18, 0, 0));
        assertEquals("", findings(RedstoneReader.read(m, DIR)));
    }

    @Test
    void fadeAtACornerGetsTheRepeaterOneBlockEarlier() throws IOException {
        // Leitung biegt am toten Staub ab: 16 (Stärke 0) geht nach Süden weiter zur Lampe – kein gerader Platz bei 16,
        // also Verstärker auf 15 (Stärke 1) mit Eingang zur 14 (Stärke 2).
        Map<Pos, String> m = new HashMap<>(lanes().get(2));
        m.remove(new Pos(17, 0, 0));
        m.remove(new Pos(18, 0, 0));
        m.put(new Pos(16, 0, 1), "minecraft:redstone_wire[east=none,north=side,power=0,south=side,west=none]");
        m.put(new Pos(16, 0, 2), "minecraft:redstone_lamp[lit=false]");
        RedstoneReader.Finding f = RedstoneReader.read(m, DIR).findings().stream().filter(x -> x.fix() != null)
                .findFirst().orElseThrow();
        assertEquals(new Pos(15, 0, 0), f.pos());
        assertEquals("minecraft:repeater[delay=1,facing=west,locked=false,powered=false]", f.fix());
    }

    @Test
    void repeaterUsedAsDiodeIsNoFault() {
        // Im Spiel 2026-11-06: Verstärker als Diode – Eingang angeschlossen, aber gerade aus; dahinter Strom von
        // anderer Seite. Vorher hieß das „falsch herum“.
        Map<Pos, String> m = new HashMap<>();
        m.put(new Pos(0, 0, 0), "minecraft:redstone_wire[east=side,north=none,power=0,south=none,west=side]");
        m.put(new Pos(1, 0, 0), "minecraft:repeater[delay=1,facing=west,locked=false,powered=false]");
        m.put(new Pos(2, 0, 0), "minecraft:redstone_wire[east=side,north=none,power=15,south=side,west=side]");
        m.put(new Pos(2, 0, 1), "minecraft:lever[face=floor,facing=east,powered=true]");
        assertEquals("", findings(RedstoneReader.read(m, DIR)));
        // Fester Block vor dem Eingang: kann Strom tragen, den der Leser nicht sieht – auch kein Fund.
        m.put(new Pos(0, 0, 0), "minecraft:stone");
        assertEquals("", findings(RedstoneReader.read(m, DIR)));
    }

    @Test
    void signalDyingAfterFifteenBlocksIsFound() throws IOException {
        String f = findings(lane(2));
        assertTrue(f.contains("Bei 16 0 0 ist das Signal aufgebraucht"), f);
        assertTrue(f.contains("Lampe bei 18 0 0 bekommt gerade kein Signal"), f);
    }

    @Test
    void unlitTorchIsExplainedAsInverter() throws IOException {
        String f = findings(lane(3));
        assertTrue(f.contains("Die Fackel bei 3 0 0 ist aus, weil ihr Block (Stein bei 2 0 0) Strom bekommt"), f);
        assertFalse(f.contains("Lampe"), "die Fackel erklärt die dunkle Lampe schon: " + f);
    }

    @Test
    void lockedRepeaterIsFound() throws IOException {
        String f = findings(lane(4));
        assertTrue(f.contains("Verstärker bei 2 0 0 ist von der Seite gesperrt"), f);
        assertFalse(f.contains("Lampe"), f);
        // Gesperrt ist in Speichern gewollt – Vera nennt es, aber nicht als Fehler.
        assertTrue(RedstoneReader.verdict(lane(4), DIR).startsWith("Ich sehe keinen Fehler. Der Verstärker"));
    }

    @Test
    void torchWorkingAsNotGateIsNoFinding() throws IOException {
        // Spur 3 ohne die Lampe: Die Fackel ist als NICHT richtig aus, nichts Dunkles daneben zu erklären.
        // Im Spiel 2026-11-06 bestand Veras Antwort sonst nur aus solchen Fackel-Sätzen.
        Map<Pos, String> m = new HashMap<>(lanes().get(3));
        m.remove(new Pos(4, 0, 0));
        assertEquals("", findings(RedstoneReader.read(m, DIR)));
    }

    @Test
    void faultsComeBeforeHints() throws IOException {
        Map<Pos, String> m = new HashMap<>();
        lanes().get(3).forEach((p, s) -> m.put(p, s));
        lanes().get(1).forEach((p, s) -> m.put(p.add(0, 0, 2), s));
        String v = RedstoneReader.verdict(RedstoneReader.read(m, DIR), DIR);
        assertTrue(v.startsWith("Der Verstärker bei 3 0 2 zeigt vermutlich falsch herum"), v);
    }

    @Test
    void comparatorAtChestWorks() throws IOException {
        RedstoneReader.Report r = lane(6);
        assertEquals("", findings(r));
        assertTrue(RedstoneReader.describe(r, DIR, 2000).contains("Komparator 1 0 0 Eingang Westen, vergleicht, an"));
    }

    @Test
    void gapBetweenLeverAndLampIsFound() throws IOException {
        String f = findings(lane(7));
        assertTrue(f.contains("Lampe bei 2 0 0 bekommt gerade kein Signal; das nächste Signal ist Hebel bei 0 0 0 "
                + "(2 Blöcke). Dazwischen fehlt eine Verbindung."), f);
    }

    @Test
    void lonelyDoorIsNoCircuit() {
        RedstoneReader.Report r = RedstoneReader.read(Map.of(new Pos(0, 0, 0),
                "minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]"), DIR);
        assertTrue(r.empty());
        assertEquals("", RedstoneReader.describe(r, DIR, 500));
    }

    @Test
    void switchedOffCircuitIsNoFault() {
        Map<Pos, String> m = new HashMap<>();
        m.put(new Pos(0, 0, 0), "minecraft:lever[face=floor,facing=east,powered=false]");
        m.put(new Pos(1, 0, 0), "minecraft:redstone_wire[east=side,north=none,power=0,south=none,west=side]");
        m.put(new Pos(2, 0, 0), "minecraft:redstone_lamp[lit=false]");
        RedstoneReader.Report r = RedstoneReader.read(m, DIR);
        assertEquals("", findings(r));
        assertTrue(RedstoneReader.verdict(r, DIR).contains("Gerade ist nur nichts eingeschaltet"));
    }

    @Test
    void verdictStaysShortAndNamesTheFault() throws IOException {
        String v = RedstoneReader.verdict(lane(1), DIR);
        assertTrue(v.length() <= 300, v);
        assertTrue(v.contains("falsch herum"), v);
        assertTrue(RedstoneReader.verdict(RedstoneReader.read(Map.of(), DIR), DIR).contains("keine Redstone"));
    }

    @Test
    void describeRespectsTheLimit() throws IOException {
        String d = RedstoneReader.describe(lane(2), DIR, 120);
        assertTrue(d.length() <= 120, d);
        assertTrue(d.endsWith("…"));
    }

    @Test
    void largeAreasAreCappedNotSlow() {
        Map<Pos, String> m = new HashMap<>();
        for (int x = 0; x < 80; x++) {
            for (int z = 0; z < 80; z++) {
                m.put(new Pos(x, 0, z), "minecraft:redstone_wire[power=" + (x % 16) + "]");
            }
        }
        long t0 = System.nanoTime();
        RedstoneReader.Report r = RedstoneReader.read(m, DIR);
        assertTrue(r.truncated());
        assertTrue(r.parts() <= RedstoneReader.MAX_PARTS);
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2000, "zu langsam");
        assertTrue(r.findings().size() <= RedstoneReader.MAX_FINDINGS * RedstoneReader.MAX_CIRCUITS);
        List<RedstoneReader.Circuit> c = r.circuits();
        assertFalse(c.isEmpty());
    }
}
