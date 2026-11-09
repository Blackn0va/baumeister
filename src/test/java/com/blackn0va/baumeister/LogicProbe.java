package com.blackn0va.baumeister;

import com.blackn0va.baumeister.logic.LogicCircuit;
import com.blackn0va.baumeister.plan.Machines;
import com.blackn0va.baumeister.plan.Orientation.Cardinal;
import com.blackn0va.baumeister.plan.Pos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Messprobe Logik-Compiler (2026-10-28): baut jede Formel mit „/bm logikbei“ auf dem lokalen Testserver und prüft die
 * volle Wahrheitstabelle über MachineProbe (Eingang an = Redstone-Block statt Hebel, jede Lampe abfragen).
 *
 *   java -cp "target/classes;target/test-classes;lib/*" com.blackn0va.baumeister.LogicProbe C:\swb\baumeister-testserver [richtung] [formel …]
 * Formel „mehr“ = {@link #MULTI}, „alle“ = beide Listen. Exit 0 = alle bestanden.
 */
public final class LogicProbe {

    /** Querschnitt: 1–5 Eingänge, Negationen, XOR (2 und 4 Terme), Mehrheit, Klammern, 5 Terme. */
    public static final List<String> DEFAULT = List.of(
            "Q = A und B",
            "Q = A oder B",
            "Q = nicht A",
            "Q = A xor B",
            "Q = A und nicht B",
            "Mehrheit = A&B | A&C | B&C",
            "Licht = A und (B oder nicht C)",
            "S = A xor B xor C",
            "Q = (A oder B) und nicht (C und D)",
            "Q = A & !B & C & !D & E",
            "Q = (A xor B) | (C & D & E)");

    /**
     * Mehrere Ausgänge (2026-10-29): Volladdierer, 2-Bit-Addierer (Leitungs-Verstärker, 6 Terme für S1 →
     * ODER-Verstärker), Vergleicher, 2-zu-4-Dekoder (4 Lampen), 4-Bit-Parität (8 Terme in einer Zeile).
     */
    public static final List<String> MULTI = List.of(
            "S = A xor B xor Ci; Co = A&B | Ci&(A xor B)",
            "S0 = A0 ^ B0; S1 = A1 ^ B1 ^ (A0 & B0); C = A1&B1 | (A1|B1)&A0&B0",
            "Größer = A & !B; Gleich = !(A ^ B); Kleiner = !A & B",
            "D0 = !A&!B; D1 = A&!B; D2 = !A&B; D3 = A&B",
            "P = A xor B xor C xor D");

    /**
     * Stockwerke (2026-11-07, „Redstone in 3D“): erzwungen 3D, automatisch gestapelt (17 Terme, 8 Ausgänge) und drei
     * Stockwerke. Prüft die Eingangstürme (zwei Umkehrungen je Stockwerk) und dass die Ebenen sich nicht stören.
     */
    public static final List<String> THREE_D = List.of(
            "3d S = A xor B; C = A & B",
            "P = A ^ B ^ C ^ D ^ E; U = A & B",
            "D0 = !A&!B&!C; D1 = A&!B&!C; D2 = !A&B&!C; D3 = A&B&!C; D4 = !A&!B&C; D5 = A&!B&C; D6 = !A&B&C; "
                    + "D7 = A&B&C",
            "3d S0 = A0 ^ B0; S1 = A1 ^ B1 ^ (A0 & B0); C = A1&B1 | (A1|B1)&A0&B0");

    /** Je Stockwerk über dem ersten: 2 Fackeln und 1 Verstärker mehr im Weg (≈ 0,5 s), großzügig gerechnet. */
    private static final int SETTLE_PER_FLOOR_MS = 600;

    /**
     * Nach dem Umschalten warten. Vorher fest 1,2 s (MachineProbe); der längste Weg im Raster ist jetzt Umkehrer,
     * Leitungs-Verstärker, Abgriff-Fackel, 5 Sammler-Verstärker, End-Fackel, ODER-Verstärker ≈ 10 Redstone-Ticks =
     * 1 s – zu knapp für 1,2 s samt Lampen-Abfallverzögerung.
     */
    private static final int SETTLE_MS = 2000;

    private LogicProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path server = Path.of(args.length > 0 ? args[0] : "C:\\swb\\baumeister-testserver");
        Cardinal dir = args.length > 1 ? Cardinal.parse(args[1]) : Cardinal.NORTH;
        List<String> formulas = args.length > 2 ? List.of(args).subList(2, args.length) : DEFAULT;
        if (formulas.equals(List.of("mehr"))) {
            formulas = MULTI;
        } else if (formulas.equals(List.of("3d"))) {
            formulas = THREE_D;
        } else if (formulas.equals(List.of("alle"))) {
            formulas = new ArrayList<>(DEFAULT);
            formulas.addAll(MULTI);
        }
        Properties props = new Properties();
        try (var r = Files.newBufferedReader(server.resolve("server.properties"))) {
            props.load(r);
        }
        String world = props.getProperty("level-name", "world");
        int failed = 0;
        try (Rcon rcon = new Rcon(Integer.parseInt(props.getProperty("rcon.port")), props.getProperty("rcon.password"))) {
            int x = 2000;
            int previous = 0;
            for (String f : formulas) {
                LogicCircuit.Circuit c = LogicCircuit.compile(f);
                List<Machines.Pin> ins = new ArrayList<>();
                for (int i = 0; i < c.inputPins().size(); i++) {
                    Pos p = c.inputPins().get(i);
                    ins.add(new Machines.Pin(c.inputs().get(i), p, p, null));
                }
                List<Machines.Pin> outs = new ArrayList<>();
                for (LogicCircuit.Output o : c.outputs()) {
                    outs.add(new Machines.Pin(o.name(), o.lamp(), o.lamp(), null));
                }
                Machines.Machine m = new Machines.Machine(c.module(), ins, outs, "lamp", LogicCircuit.truthTables(c),
                        List.of());
                // Eigene Fläche je Formel: Abstand aus der Größe beider Nachbarn – die Raster sind bis 57 breit und
                // drehen sich in jede Richtung um die Ecke.
                int radius = MachineProbe.radius(m);
                x += previous + radius + 4;
                previous = radius;
                Pos corner = new Pos(x, 120, 2000);
                int floors = (c.module().size().y() + 1) / LogicCircuit.FLOOR;
                boolean ok = MachineProbe.probe(rcon, world, dir, corner, m, "bm logikbei {ort} " + f,
                        SETTLE_MS + Math.max(0, floors - 1) * SETTLE_PER_FLOOR_MS);
                System.out.println((ok ? "BESTANDEN " : "FEHLER    ") + f + " (" + dir + ", " + c.terms().size()
                        + " Terme, " + c.outputs().size() + " Ausgänge)");
                failed += ok ? 0 : 1;
            }
        }
        System.out.println(failed == 0 ? "ALLE BESTANDEN" : failed + " Formel(n) fehlerhaft");
        System.exit(failed == 0 ? 0 : 1);
    }
}
