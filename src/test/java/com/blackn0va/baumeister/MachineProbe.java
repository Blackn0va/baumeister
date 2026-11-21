package com.blackn0va.baumeister;

import com.blackn0va.baumeister.plan.BlockSpec;
import com.blackn0va.baumeister.plan.CompiledBuild;
import com.blackn0va.baumeister.plan.Machines;
import com.blackn0va.baumeister.plan.Orientation;
import com.blackn0va.baumeister.plan.Orientation.Cardinal;
import com.blackn0va.baumeister.plan.Pos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Messprobe, kein Unit-Test: prüft jede eingebaute Maschine auf dem lokalen Testserver über RCON.
 *
 * Je Maschine: Platz räumen, mit „/bm maschine“ über das Plugin bauen (derselbe Weg wie im Spiel: Compiler, Drehung,
 * ShapeHelper, Physik), warten bis gebaut, dann jede Eingangsbelegung anlegen und die Ausgänge abfragen.
 * Eingang AN = Redstone-Block an der Teststelle statt des Hebels – „setblock“ auf einen Hebel löst keine
 * Nachbar-Updates am Trägerblock aus (Bauteil „lampe“), ein Redstone-Block schon.
 *
 * Aufruf (Testserver läuft, Plugin-JAR eingespielt):
 *   java -cp "target/classes;target/test-classes;lib/*" com.blackn0va.baumeister.MachineProbe C:\swb\baumeister-testserver [richtung] [name …]
 * Exit 0 = alle bestanden, 1 = mindestens eine Maschine falsch.
 *
 * Gegenprobe (2026-11-07): „ohne=x,y,z;x,y,z“ unter den Namen entfernt diese lokalen Zellen nach der Bauprüfung –
 * eine Farm ohne ihr tragendes Teil (Beobachter, Wasserquelle, Trichter) muss dann FEHLER melden. Meldet sie trotzdem
 * BESTANDEN, prüft der Ablauf das Teil nicht.
 *
 * Befehle im Ablauf: „{x,y,z}“ lokale Position · „{@x,y,z}“ dieselbe als Selektor-Mitte (x=…,y=…,z=…, für
 * „kill @e[{@1,2,1},distance=..2]“) · „plan:&lt;block&gt;“ Block in Planrichtungen (facing=right …), wird
 * gedreht wie beim Bau · „#bis &lt;s&gt;“ kein Befehl: nach dem Schritt bis zu s Sekunden warten, bis alle Ausgänge
 * stimmen (langsame Farmen: Trichterkette, Golem), und die Zeit ausgeben · „#schlaf &lt;s&gt; {x,y,z} …“ an dieser
 * Stelle bis zu s Sekunden warten, bis jedes genannte Bett mindestens einmal belegt war (Eisenfarm, 2026-11-08:
 * echter Schlaf statt vorgegebenem last_slept); sonst gilt die Maschine als durchgefallen.
 */
public final class MachineProbe {

    private static final int BASE_Y = 120;

    /** Räumhöhe über der Ecke: höchste Maschine der Bibliothek plus Rand (xp_farm 28, Fackeln auf dem Dach). */
    private static final int CLEAR_HIGH = new Machines().machines().stream()
            .mapToInt(m -> m.module().size().y()).max().orElse(8) + 2;

    /** Gegenprobe: lokale Zellen, die nach der Bauprüfung entfernt werden (Argument „ohne=…“). */
    static List<Pos> without = List.of();

    public static void main(String[] args) throws Exception {
        Path server = Path.of(args.length > 0 ? args[0] : "C:\\swb\\baumeister-testserver");
        Cardinal dir = args.length > 1 ? Cardinal.parse(args[1]) : Cardinal.NORTH;
        Set<String> only = new HashSet<>();
        List<Pos> remove = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            if (args[i].startsWith("ohne=")) {
                for (String p : args[i].substring("ohne=".length()).split(";")) {
                    String[] c = p.split(",");
                    remove.add(new Pos(Integer.parseInt(c[0].strip()), Integer.parseInt(c[1].strip()),
                            Integer.parseInt(c[2].strip())));
                }
            } else {
                only.add(args[i]);
            }
        }
        without = List.copyOf(remove);
        Properties props = new Properties();
        try (var r = Files.newBufferedReader(server.resolve("server.properties"))) {
            props.load(r);
        }
        String world = props.getProperty("level-name", "world");
        int port = Integer.parseInt(props.getProperty("rcon.port"));
        Machines lib = new Machines();
        int failed = 0;
        try (Rcon rcon = new Rcon(port, props.getProperty("rcon.password"))) {
            // Abstand aus der Größe beider Nachbarn statt fest 16: Der Volladdierer (26 × 28) ragte sonst in drei
            // Nachbarflächen, und deren Räumen (±12) hätte ihn nicht erfasst.
            // Namen außerhalb der Bibliothek (Mehrsorten-Sortierer „item_sortierer_5“, „item_sortierer_3:sand,dirt“,
            // 2026-11-21) über machine() – vorher prüfte die Probe nur, was machines() aufzählt, und überging einen
            // unbekannten Namen stumm mit „ALLE BESTANDEN“.
            List<Machines.Machine> todo = new ArrayList<>();
            Set<String> listed = new HashSet<>();
            for (Machines.Machine m : lib.machines()) {
                listed.add(m.module().name());
                if (only.isEmpty() || only.contains(m.module().name())) {
                    todo.add(m);
                }
            }
            for (String n : only) {
                if (listed.contains(n)) {
                    continue;
                }
                var extra = lib.machine(n);
                if (extra.isPresent()) {
                    todo.add(extra.get());
                } else {
                    System.out.println("FEHLER    " + n + " – gibt es nicht");
                    failed++;
                }
            }
            int x = 0;
            int previous = 0;
            for (Machines.Machine m : todo) {
                int radius = radius(m);
                x += previous == 0 ? 0 : previous + radius + 4;
                previous = radius;
                Pos corner = new Pos(x, BASE_Y, 0);
                boolean ok = probe(rcon, world, dir, corner, m);
                System.out.println((ok ? "BESTANDEN " : "FEHLER    ") + m.module().name() + " (" + dir + ")");
                if (!ok) {
                    failed++;
                }
            }
        }
        System.out.println(failed == 0 ? "ALLE BESTANDEN" : failed + " Maschine(n) fehlerhaft");
        System.exit(failed == 0 ? 0 : 1);
    }

    /**
     * Wartezeit ab dem fertigen Bau, bevor die Probe etwas schaltet oder entfernt. Der Lag-Wächter
     * (BuildService.watchForLag, seit 33b7583) misst 3 s nach dem Ende 10 s lang, was im Bau von selbst schaltet, und
     * baut ab, was über bau.lag-grenze-je-sekunde (50) liegt; RCON-Befehle zählen dabei nicht als Eingriff. Gemessen
     * 2026-11-20 (Spigot 1.21.11, Nord): Die Probe schaltete schon in diesem Fenster, der Wächter zählte 271
     * (akkumulator_4bit) bzw. 240 (Akkumulator mit Löschen) Vorgänge je Sekunde und baute beide mitten im Ablauf ab –
     * „stimmt nach nie“ ab Schritt 3, FEHLER ohne Schaltungsfehler. Mit der Wartezeit misst er echten Leerlauf wie nach
     * dem Bau eines Spielers (Zeile „lag | gemessen“ im Server-Log), und eine echte Lag-Maschine fällt weiter durch.
     * 16 statt 13 s: „bereit“ sieht die Probe bis zu 1 s zu spät, und unter Last laufen weniger als 20 Ticks je Sekunde.
     * Wer die Wartezeit entfernt, prüft große Schaltungen wieder gegen den Wächter statt gegen ihre Wahrheitstabelle.
     */
    static final long LAG_WATCH_MS = 16_000;

    private static boolean probe(Rcon rcon, String world, Cardinal dir, Pos corner, Machines.Machine m)
            throws Exception {
        return probe(rcon, world, dir, corner, m, null, settleMs(m));
    }

    /**
     * Wartezeit je Belegung. Breiter als das Logik-Raster (57) sind nur verkettete Schaltungen wie addierer_4bit: Dort
     * läuft der Übertrag durch vier Volladdierer nacheinander – 1200 ms reichen nicht sicher. Die Probe misst dort
     * zusätzlich, wann die Ausgänge zum ersten Mal stimmen (Zeile „stimmt nach … ms“).
     */
    static int settleMs(Machines.Machine m) {
        return m.module().size().x() > 57 ? 5000 : 1200;
    }

    /** Räumweite um die Ecke: die Maschine dreht sich in jede Richtung, also die längere Seite plus Rand, mind. 12. */
    static int radius(Machines.Machine m) {
        return Math.max(12, Math.max(m.module().size().x(), m.module().size().z()) + 2);
    }

    /**
     * buildCommand: Befehl ohne Ort, an den „&lt;welt&gt; x y z richtung“ gehängt wird bzw. der „{ort}“ enthält; null =
     * „bm maschine &lt;name&gt; …“. LogicProbe nutzt das für „bm logikbei {ort} &lt;Formel&gt;“.
     * settleMs: Wartezeit nach jeder Eingangsbelegung ohne Befehle (Maschinen 1200, Logik-Raster länger).
     */
    static boolean probe(Rcon rcon, String world, Cardinal dir, Pos corner, Machines.Machine m, String buildCommand,
            int settleMs) throws Exception {
        Orientation o = new Orientation(dir);
        String name = m.module().name();
        // Platz: großzügig um die Ecke räumen (alle Richtungen), Chunks dauerhaft laden. In Scheiben, damit kein fill
        // über die Grenze von 32 768 Blöcken kommt (bei Radius 30 wären es 37 210).
        int reach = radius(m);
        int x0 = corner.x() - reach;
        int z0 = corner.z() - reach;
        rcon.command(String.format("forceload add %d %d %d %d", x0, z0, x0 + 2 * reach, z0 + 2 * reach));
        // Scheibenbreite so, dass jedes fill unter 32 768 Blöcken bleibt (10 hoch × Tiefe); bei addierer_4bit
        // (Radius 112) wären 16 Spalten 36 000.
        // Höhe aus dem Bauwerk (xp_farm 26 hoch); vorher fest +8 – darüber blieben Reste des letzten Laufs stehen.
        // Mindestens so hoch wie die höchste Maschine (CLEAR_HIGH): Gemessen 2026-11-07 stand über der Weizenfarm
        // noch Dach und Fackelreihe einer XP-Farm-Probe (y=143..147), darunter war es dunkel, und Weizen platzte beim
        // Bau ab (CropBlock.canSurvive braucht Licht ≥ 8) – die Probe meldete FEHLER, nur in Richtung Nord.
        int high = Math.max(CLEAR_HIGH, m.module().size().y() + 1);
        int slice = Math.max(1, Math.min(16, 32768 / ((high + 2) * (2 * reach + 1))));
        for (int x = x0; x <= x0 + 2 * reach; x += slice) {
            rcon.command(String.format("fill %d %d %d %d %d %d air", x, corner.y() - 1, z0, Math.min(x + slice - 1,
                    x0 + 2 * reach), corner.y() + high, z0 + 2 * reach));
        }
        // Tiere, Mobs und Gegenstände vom letzten Lauf weg: Übrig gebliebene Dorfbewohner der Eisenfarm riefen sonst
        // Golems in die nächste Maschine, Hühner liefen durch die Hühnerfarm.
        // Den Baumeister (Mannequin) nicht: Er steht nach dem letzten Bau noch da und baut gleich die nächste Maschine.
        rcon.command(String.format("minecraft:kill @e[type=!minecraft:player,type=!minecraft:mannequin,x=%d,y=%d,z=%d,"
                + "dx=%d,dy=%d,dz=%d]", x0, corner.y() - 1, z0, 2 * reach, high + 2, 2 * reach));
        Thread.sleep(300);
        String where = String.format("%s %d %d %d %s", world, corner.x(), corner.y(), corner.z(),
                dir.name().substring(0, 1).toLowerCase());
        String reply = rcon.command(buildCommand == null ? "bm maschine " + name + " " + where
                : buildCommand.replace("{ort}", where));
        if (!reply.isBlank()) {
            System.out.println("  " + reply.strip());
        }
        waitBuilt(rcon, m.module().cells().size());
        long built = System.currentTimeMillis();
        Map<Pos, BlockSpec> cells = new LinkedHashMap<>();
        for (CompiledBuild.Change c : m.module().cells()) {
            cells.put(c.pos(), c.block());
        }
        // Gebaut wie geplant? Jede Zelle mit Block-ID (Zustände prüft die Funktion).
        int wrong = 0;
        for (Map.Entry<Pos, BlockSpec> e : cells.entrySet()) {
            Pos w = world(corner, o, e.getKey());
            String id = e.getValue().id();
            boolean here = test(rcon, String.format("execute if block %d %d %d minecraft:%s", w.x(), w.y(), w.z(), id))
                    // Wasser über Seelensand wird von selbst zur Blasensäule – das ist das Ziel, keine Abweichung.
                    || id.equals("water") && test(rcon, String.format("execute if block %d %d %d minecraft:bubble_column",
                            w.x(), w.y(), w.z()))
                    // Ein ausgewachsener Stängel (age=7) neben freiem Fruchtfeld hat schon während des Baus eine
                    // Frucht angesetzt – die Farm tut ihre Arbeit. Gemessen 2026-11-14 (Spigot, melonenfarm Nord,
                    // random_tick_speed 3): „fehlt/anders: melon_stem“, in 8 früheren Läufen nie.
                    || id.endsWith("_stem") && test(rcon, String.format("execute if block %d %d %d minecraft:attached_%s",
                            w.x(), w.y(), w.z(), id));
            if (!here) {
                if (wrong++ < 5) {
                    System.out.println("  fehlt/anders: " + e.getValue().id() + " bei lokal " + e.getKey());
                }
            }
        }
        if (wrong > 0) {
            System.out.println("  " + wrong + " Zellen weichen ab");
            return false;
        }
        // Erst nach der Leerlauf-Messung des Lag-Wächters schalten oder entfernen (siehe LAG_WATCH_MS).
        long rest = built + LAG_WATCH_MS - System.currentTimeMillis();
        if (rest > 0) {
            Thread.sleep(rest);
        }
        for (Pos p : without) {
            Pos w = world(corner, o, p);
            rcon.command(String.format("setblock %d %d %d minecraft:air", w.x(), w.y(), w.z()));
            System.out.println("  Gegenprobe: lokal " + p + " entfernt (war " + cells.get(p) + ")");
        }
        Thread.sleep(1500);
        if (m.outputKind().equals("blink")) {
            Pos q = world(corner, o, m.outputs().getFirst().testAt());
            Set<Boolean> seen = new HashSet<>();
            for (int i = 0; i < 12; i++) {
                seen.add(test(rcon, String.format("execute if block %d %d %d minecraft:redstone_lamp[lit=true]",
                        q.x(), q.y(), q.z())));
                Thread.sleep(250);
            }
            System.out.println("  Lampe gesehen: " + seen);
            return seen.size() == 2;
        }
        boolean ok = true;
        List<Machines.Pin> ins = m.inputs();
        if (!m.sequence().isEmpty()) {
            // Schaltung mit Gedächtnis: Schritte in Reihenfolge, Ausgänge nach jedem Schritt.
            int n = 0;
            for (Machines.Step step : m.sequence()) {
                n++;
                int until = 0;
                for (String cmd : step.commands()) {
                    if (cmd.startsWith("#bis ")) {
                        until = Integer.parseInt(cmd.substring("#bis ".length()).strip());
                        continue;
                    }
                    if (cmd.startsWith(SLEEP)) {
                        ok &= waitAsleep(rcon, name, n, cmd, corner, o);
                        continue;
                    }
                    String w = vanilla(localToWorld(cmd, corner, o));
                    String r = rcon.command(w);
                    System.out.println("  " + name + " Befehl: " + w + (r.isBlank() ? "" : " → " + r.strip()));
                    // Gemessen 2026-11-07: „gamerule randomTickSpeed“ heißt seit 1.21.11 random_tick_speed – der
                    // Server lehnte ab, die Probe lief weiter und hätte die Bewässerung nie geprüft. Abgelehnt =
                    // durchgefallen.
                    // „Fehler:“/„Befehlshilfe:“: Antworten von EssentialsX – dann lief dessen Befehl statt des vanilla.
                    if (r.contains("Incorrect argument") || r.contains("Unknown or incomplete command")
                            || r.contains("Unknown command") || r.contains("Fehler:") || r.contains("Befehlshilfe:")) {
                        System.out.println("  " + name + " BEFEHL ABGELEHNT – Ablauf prüft nicht, was er soll");
                        ok = false;
                    }
                }
                List<String> key = new ArrayList<>();
                for (Machines.Pin pin : ins) {
                    boolean on = step.inputs().getOrDefault(pin.name(), false);
                    Pos w = world(corner, o, pin.testAt());
                    String block = on ? "minecraft:redstone_block" : o.toWorld(cells.get(pin.testAt())).asString();
                    rcon.command(String.format("setblock %d %d %d %s", w.x(), w.y(), w.z(), block));
                    key.add(pin.name() + "=" + (on ? 1 : 0));
                }
                // Mit Befehlen länger warten: Tageslichtsensor misst nur alle 20 Ticks, Trichter brauchen 8 je Schritt.
                if (until > 0) {
                    long t0 = System.nanoTime();
                    long first = -1;
                    while (System.nanoTime() - t0 < until * 1_000_000_000L) {
                        if (matches(rcon, m, corner, o, step.expect())) {
                            first = (System.nanoTime() - t0) / 1_000_000;
                            break;
                        }
                        Thread.sleep(250);
                    }
                    System.out.println("  " + name + " Schritt " + n + " stimmt nach "
                            + (first < 0 ? "nie (" + until + " s)" : first + " ms"));
                } else if (settleMs > 1200 && step.commands().isEmpty() && !step.expect().isEmpty()) {
                    long t0 = System.nanoTime();
                    long first = -1;
                    while (System.nanoTime() - t0 < settleMs * 1_000_000L) {
                        if (first < 0 && matches(rcon, m, corner, o, step.expect())) {
                            first = (System.nanoTime() - t0) / 1_000_000;
                        }
                        Thread.sleep(100);
                    }
                    System.out.println("  " + name + " Schritt " + (n) + " stimmt nach "
                            + (first < 0 ? "nie" : first + " ms"));
                } else {
                    Thread.sleep(step.commands().isEmpty() ? settleMs : 3000);
                }
                if (step.expect().isEmpty()) {
                    // Reiner Warteschritt (schmelzanlage): nur Zeit verstreichen lassen, nichts prüfen.
                    System.out.println(String.format("  %-12s Schritt %d nur warten", name, n));
                    continue;
                }
                for (Machines.Pin out : m.outputs()) {
                    boolean actual = output(rcon, m, out, corner, o);
                    boolean expected = step.expect().get(out.name());
                    System.out.println(String.format("  %-12s Schritt %d %s → %s = %s%s", name, n, String.join(",", key),
                            out.name(), actual ? 1 : 0, actual == expected ? "" : "   ERWARTET " + (expected ? 1 : 0)));
                    ok &= actual == expected;
                }
            }
            restoreInputs(rcon, corner, o, ins, cells);
            return ok;
        }
        for (int bits = 0; bits < (1 << ins.size()); bits++) {
            List<String> key = new ArrayList<>();
            for (int i = 0; i < ins.size(); i++) {
                boolean on = (bits >> i & 1) == 1;
                Machines.Pin pin = ins.get(i);
                Pos w = world(corner, o, pin.testAt());
                String block = on ? "minecraft:redstone_block" : o.toWorld(cells.get(pin.testAt())).asString();
                rcon.command(String.format("setblock %d %d %d %s", w.x(), w.y(), w.z(), block));
                key.add(pin.name() + "=" + (on ? 1 : 0));
            }
            Thread.sleep(settleMs);
            String k = String.join(",", key);
            for (Machines.Pin out : m.outputs()) {
                boolean actual = output(rcon, m, out, world(corner, o, out.testAt()));
                boolean expected = m.expect().get(out.name()).get(k);
                System.out.println(String.format("  %-12s %s → %s = %s%s", name, k, out.name(), actual ? 1 : 0,
                        actual == expected ? "" : "   ERWARTET " + (expected ? 1 : 0)));
                ok &= actual == expected;
            }
        }
        restoreInputs(rcon, corner, o, ins, cells);
        return ok;
    }

    /** Ablauf-Anweisung „#schlaf &lt;s&gt; {x,y,z} …“: Betten, die mindestens einmal belegt sein müssen. */
    static final String SLEEP = "#schlaf ";

    /** Sekunden und lokale Bettpositionen einer „#schlaf“-Anweisung. */
    record SleepWait(int seconds, List<Pos> beds) {
    }

    static SleepWait sleepWait(String cmd) {
        String[] parts = cmd.substring(SLEEP.length()).strip().split("\\s+");
        List<Pos> beds = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            java.util.regex.Matcher m = LOCAL.matcher(parts[i]);
            if (!m.matches()) {
                throw new IllegalArgumentException("Bettposition " + parts[i] + " in " + cmd);
            }
            beds.add(new Pos(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))));
        }
        if (beds.isEmpty()) {
            throw new IllegalArgumentException("keine Betten in " + cmd);
        }
        return new SleepWait(Integer.parseInt(parts[0]), List.copyOf(beds));
    }

    /**
     * Wartet, bis jedes Bett einmal belegt war. Einmal reicht und alle 150 ms wird nachgesehen: Sieht ein Liegender den
     * Nachtzombie, wacht er nach höchstens einer Sensorrunde (20 Ticks) wieder auf – belegt ist das Bett dann nur kurz,
     * kommt aber nach 5 s (LAST_WOKEN) wieder (Eisenfarm, 2026-11-08). Wer daraus „gerade alle belegt“ macht, misst
     * Zufall statt Schlaf.
     */
    private static boolean waitAsleep(Rcon rcon, String name, int step, String cmd, Pos corner, Orientation o)
            throws Exception {
        SleepWait w = sleepWait(cmd);
        Set<Pos> seen = new HashSet<>();
        long t0 = System.nanoTime();
        while (seen.size() < w.beds().size() && System.nanoTime() - t0 < w.seconds() * 1_000_000_000L) {
            for (Pos bed : w.beds()) {
                if (seen.contains(bed)) {
                    continue;
                }
                Pos p = world(corner, o, bed);
                if (test(rcon, String.format("execute if block %d %d %d #minecraft:beds[occupied=true]", p.x(), p.y(),
                        p.z()))) {
                    seen.add(bed);
                }
            }
            Thread.sleep(150);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        boolean all = seen.size() == w.beds().size();
        System.out.println("  " + name + " Schritt " + step + (all ? " alle Betten belegt gesehen nach " + ms + " ms"
                : " SCHLAF FEHLT: " + seen.size() + "/" + w.beds().size() + " Betten belegt in " + w.seconds() + " s"));
        return all;
    }

    /** Stimmen gerade alle Ausgänge mit dem Soll überein? Nur zum Messen der Laufzeit, nicht für das Urteil. */
    private static boolean matches(Rcon rcon, Machines.Machine m, Pos corner, Orientation o,
            Map<String, Boolean> expect) throws Exception {
        for (Machines.Pin out : m.outputs()) {
            // Mit corner/o: Mob-Ausgänge (entity:) fragen den Quader ab – die Fassung mit nur einer Stelle sähe dort
            // einen Block namens „entity:…“ und meldete immer 0.
            if (output(rcon, m, out, corner, o) != expect.get(out.name())) {
                return false;
            }
        }
        return true;
    }

    /** Eingänge zurück in den Bauzustand. */
    private static void restoreInputs(Rcon rcon, Pos corner, Orientation o, List<Machines.Pin> ins,
            Map<Pos, BlockSpec> cells) throws Exception {
        for (Machines.Pin pin : ins) {
            Pos w = world(corner, o, pin.testAt());
            rcon.command(String.format("setblock %d %d %d %s", w.x(), w.y(), w.z(),
                    o.toWorld(cells.get(pin.testAt())).asString()));
        }
    }

    /**
     * Wie unten, mit Mobs: onState „entity:&lt;art&gt;[,&lt;selektor&gt;]“, mehrere mit „|“ – ein Mob dieser Art im Quader
     * zwischen pin.at und pin.testAt (xp_farm: Zombie mit 2 oder 1 Leben in der Schlagkammer).
     */
    private static boolean output(Rcon rcon, Machines.Machine m, Machines.Pin out, Pos corner, Orientation o)
            throws Exception {
        if (out.onState() == null || !out.onState().startsWith("entity:")) {
            return output(rcon, m, out, world(corner, o, out.testAt()));
        }
        Pos a = world(corner, o, out.at());
        Pos b = world(corner, o, out.testAt());
        // dx..dz bis kurz vor die Außenkante des letzten Blocks: Der Quader des Selektors endet bei x+dx, nicht am Ende
        // des Blocks x+dx. Gemessen 2026-11-07: Ein Zombie mit 2 Leben bei z=-8,3 (Block -9) lag außerhalb von
        // „z=-10,dz=1“ – die Probe meldete ZOMBIE=0, obwohl die Farm richtig lieferte.
        String area = String.format(java.util.Locale.ROOT, "x=%d,y=%d,z=%d,dx=%.2f,dy=%.2f,dz=%.2f",
                Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()),
                Math.abs(a.x() - b.x()) + 0.99, Math.abs(a.y() - b.y()) + 0.99, Math.abs(a.z() - b.z()) + 0.99);
        for (String alt : out.onState().split("\\|")) {
            String spec = alt.substring("entity:".length());
            int comma = spec.indexOf(',');
            String type = comma < 0 ? spec : spec.substring(0, comma);
            String extra = comma < 0 ? "" : "," + spec.substring(comma + 1);
            if (test(rcon, "execute if entity @e[type=minecraft:" + type + "," + area + extra + "]")) {
                return true;
            }
        }
        return false;
    }

    /** Ausgang „1“? lamp: Lampe leuchtet · state: Block im Zustand pin.onState · solid: dort steht etwas. */
    private static boolean output(Rcon rcon, Machines.Machine m, Machines.Pin out, Pos w) throws Exception {
        return switch (m.outputKind()) {
            case "lamp" -> test(rcon, String.format("execute if block %d %d %d minecraft:redstone_lamp[lit=true]",
                    w.x(), w.y(), w.z()));
            case "state" -> out.onState().startsWith("entsteht:")
                    ? test(rcon, String.format("execute if block %d %d %d minecraft:%s", w.x(), w.y(), w.z(),
                            out.onState().substring("entsteht:".length())))
                    : out.onState().startsWith("items:")
                    ? test(rcon, String.format("execute if items block %d %d %d container.* %s", w.x(), w.y(), w.z(),
                            out.onState().substring("items:".length())))
                    : test(rcon, String.format("execute if block %d %d %d minecraft:%s", w.x(), w.y(), w.z(),
                            out.onState()));
            default -> !test(rcon, String.format("execute if block %d %d %d minecraft:air", w.x(), w.y(), w.z()));
        };
    }

    private static final java.util.regex.Pattern LOCAL = java.util.regex.Pattern.compile("\\{(-?\\d+),(-?\\d+),(-?\\d+)\\}");

    /** „{1,2,3}“ in einem Befehl → Weltkoordinaten „x y z“. */
    static String localToWorld(String cmd, Pos corner, Orientation o) {
        // „{@x,y,z}“ → „x=…,y=…,z=…“ (Blockmitte wie bei execute positioned), für Selektoren mit distance.
        java.util.regex.Matcher s = SELECTOR_POS.matcher(cmd);
        StringBuilder sel = new StringBuilder();
        while (s.find()) {
            Pos w = world(corner, o, new Pos(Integer.parseInt(s.group(1)), Integer.parseInt(s.group(2)),
                    Integer.parseInt(s.group(3))));
            // Mitte als Zahl, nicht als Text „-1“ + „.5“ (= −1,5 statt −0,5 – MachineProbeCommandsTest).
            s.appendReplacement(sel, String.format(java.util.Locale.ROOT, "x=%.1f,y=%d,z=%.1f", w.x() + 0.5, w.y(),
                    w.z() + 0.5));
        }
        s.appendTail(sel);
        cmd = sel.toString();
        java.util.regex.Matcher m = LOCAL.matcher(cmd);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Pos w = world(corner, o, new Pos(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3))));
            m.appendReplacement(out, w.x() + " " + w.y() + " " + w.z());
        }
        m.appendTail(out);
        // „plan:attached_pumpkin_stem[facing=right]“ → Weltzustand wie beim Bau (der Ablauf läuft in alle 4
        // Richtungen).
        java.util.regex.Matcher b = PLAN_BLOCK.matcher(out.toString());
        StringBuilder rotated = new StringBuilder();
        while (b.find()) {
            try {
                b.appendReplacement(rotated, java.util.regex.Matcher.quoteReplacement(
                        o.toWorld(BlockSpec.parse(b.group(1))).asString()));
            } catch (com.blackn0va.baumeister.plan.PlanException e) {
                throw new IllegalArgumentException(b.group(1), e);
            }
        }
        b.appendTail(rotated);
        return rotated.toString();
    }

    /**
     * Vanilla-Befehl mit Namensraum. Gemessen 2026-11-07 auf der 26.2-Kopie von dev01: EssentialsX überschreibt dort
     * item, kill, time und weather – „item replace …“ antwortete „Nur Ingame-Spieler können item benutzen“, die Spender
     * der Weizenfarm blieben leer, und die Probe meldete eine kaputte Farm; das Huhn der Hühnerfarm überlebte „kill“.
     * „minecraft:“ vorn trifft auf Paper und Spigot den Vanilla-Befehl. Nur vorn: Hinter „execute … run“ kennt Spigot
     * „minecraft:kill“ nicht (Incorrect argument), und ohne Namensraum trifft Paper dort Essentials – deshalb nennen die
     * Abläufe den Ort im Selektor ({@x,y,z}) statt über execute. Wer das Präfix entfernt, prüft auf dev01-Fassungen die
     * Plugins statt der Maschine.
     */
    static String vanilla(String cmd) {
        String word = cmd.split(" ", 2)[0];
        return word.contains(":") || word.equals("bm") || !word.matches("[a-z_]+") ? cmd : "minecraft:" + cmd;
    }

    private static final java.util.regex.Pattern SELECTOR_POS =
            java.util.regex.Pattern.compile("\\{@(-?\\d+),(-?\\d+),(-?\\d+)\\}");

    private static final java.util.regex.Pattern PLAN_BLOCK =
            java.util.regex.Pattern.compile("plan:([a-z0-9_]+(?:\\[[^\\]]*\\])?)");

    private static Pos world(Pos corner, Orientation o, Pos local) {
        Pos off = o.toWorldOffset(local);
        return new Pos(corner.x() + off.x(), corner.y() + off.y(), corner.z() + off.z());
    }

    private static boolean test(Rcon rcon, String cmd) throws Exception {
        String r = rcon.command(cmd);
        return r.contains("Test passed");
    }

    /**
     * Wartet, bis der NPC fertig ist („/bm status“ meldet keinen laufenden Bau mehr). Höchstens 120 s oder 100 ms je
     * Block: Gemessen 2026-10-29 baut der NPC auf dem Testserver ~25 Blöcke/s, der 2-Bit-Addierer (2 545 Blöcke)
     * brauchte 2 min 20 s und lief in die alte feste Grenze von 120 s.
     */
    private static void waitBuilt(Rcon rcon, int blocks) throws Exception {
        long until = System.currentTimeMillis() + Math.max(120_000, blocks * 100L);
        Thread.sleep(1000);
        while (System.currentTimeMillis() < until) {
            String s = rcon.command("bm status");
            if (s.contains("Zustand: bereit")) {
                Thread.sleep(500);
                return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Bau nicht fertig geworden");
    }
}
