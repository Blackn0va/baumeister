package com.blackn0va.baumeister.redstone;

import com.blackn0va.baumeister.plan.Pos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Liest Redstone-Schaltungen aus den echten Blockzuständen und beschreibt sie für Vera und den Baumeister.
 *
 * Vorher (bis 2026-11-06) sahen beide Redstone nur als Zeichen „r“ im Grundriss (SiteSurvey.symbol): ohne Richtung,
 * ohne Strom, ohne Zustand. Auf „warum geht meine Lampe nicht?“ konnte das Modell nur raten. Jetzt kommen Bauteil,
 * Richtung und Zustand mit, dazu die Stellen, an denen das Signal hängen bleibt.
 *
 * Die Welt rechnet das Redstone selbst; hier wird nichts simuliert, nur der Zustand gelesen (getAsString) und gegen
 * gemessene Regeln geprüft. Gemessen 2026-11-06 auf Paper 1.21.11-132 (probes/redstone, Texte in RedstoneReaderTest):
 * - Verstärker und Komparator lesen auf ihrer facing-Seite und geben gegenüber aus (DiodeBlock.getInputSignal:
 *   pos.relative(FACING)); facing=east hinter einem Hebel im Westen blieb aus, die Lampe dahinter auch.
 * - Staub: 15 am Hebel, je Block eins weniger, der 16. hat 0 – die Lampe nach 17 Blöcken blieb aus.
 * - Wandfackel hängt am Block gegenüber ihrem facing; bekommt der Strom, ist sie aus (lit=false).
 * - Seitlich gespeister Verstärker: locked=true, powered=false trotz Signal am Eingang.
 * - Beobachter: schaut nach facing, gibt hinten aus (ObserverBlock.updateNeighborsInFront).
 *
 * Threads: zustandslos. Fehler: unbekannte Blöcke zählen nicht als Bauteil; nie Ausnahmen.
 */
public final class RedstoneReader {

    /** Mehr Bauteile werden nicht ausgewertet – eine Rechnerhalle beschreibt sonst niemand sinnvoll in einem Satz. */
    static final int MAX_PARTS = 3000;
    static final int MAX_CIRCUITS = 6;
    static final int MAX_FINDINGS = 6;

    public enum Kind {
        SOURCE, WIRE, DIODE, CONSUMER
    }

    /** Ein Block als ID ohne „minecraft:“ plus Zustände (aus BlockData.getAsString). */
    public record Cell(String id, Map<String, String> props) {

        public static Cell parse(String data) {
            String s = data == null ? "air" : data.strip();
            if (s.startsWith("minecraft:")) {
                s = s.substring("minecraft:".length());
            }
            int open = s.indexOf('[');
            if (open < 0) {
                return new Cell(s, Map.of());
            }
            Map<String, String> props = new LinkedHashMap<>();
            String inner = s.substring(open + 1, s.endsWith("]") ? s.length() - 1 : s.length());
            for (String kv : inner.split(",")) {
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    props.put(kv.substring(0, eq).strip(), kv.substring(eq + 1).strip());
                }
            }
            return new Cell(s.substring(0, open), Map.copyOf(props));
        }

        String prop(String key) {
            return props.getOrDefault(key, "");
        }

        int power() {
            try {
                return Integer.parseInt(prop("power"));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    public record Part(Pos pos, Cell cell, Kind kind) {

        /** Führt gerade Signal (Quelle an, Staub über 0, Verstärker an) bzw. ist eingeschaltet (Verbraucher). */
        public boolean on() {
            String id = cell.id();
            return switch (id) {
                case "redstone_wire", "daylight_detector", "target", "sculk_sensor", "calibrated_sculk_sensor"
                        -> cell.power() > 0;
                case "redstone_torch", "redstone_wall_torch", "redstone_lamp" -> "true".equals(cell.prop("lit"));
                case "redstone_block" -> true;
                case "piston", "sticky_piston" -> "true".equals(cell.prop("extended"));
                case "dispenser", "dropper", "crafter" -> "true".equals(cell.prop("triggered"));
                case "hopper" -> "false".equals(cell.prop("enabled"));
                default -> "true".equals(cell.prop("powered"));
            };
        }

        /** Signal im Sinne „hier kommt Strom her“ – Verbraucher zählen nicht (ein ausgefahrener Kolben speist nichts). */
        boolean signal() {
            return kind != Kind.CONSUMER && on();
        }
    }

    /** hint = Erklärung (Fackel kehrt um), kein Fehler – kommt in der Antwort hinter den Fehlern. */
    /**
     * fix = BlockData-Text, der den Fehler an pos behebt (null = kein sicherer Umbau bekannt); fixText sagt Vera dazu.
     * Nur zwei Umbauten, beide gemessen eindeutig: Verstärker/Komparator umdrehen und am Ende eines aufgebrauchten
     * Signals einen Verstärker setzen. Lücken schließen oder Fackeln versetzen ist Bauen nach Absicht – das rät sie nicht.
     */
    public record Finding(Pos pos, String text, boolean hint, String fix, String fixText) {

        Finding(Pos pos, String text) {
            this(pos, text, false, null, null);
        }

        Finding(Pos pos, String text, boolean hint) {
            this(pos, text, hint, null, null);
        }
    }

    public record Circuit(int number, Pos min, Pos max, List<Part> parts, List<Finding> findings) {
    }

    /** Ergebnis: Schaltungen nach Größe, je Schaltung Bauteile und Auffälligkeiten. */
    public record Report(List<Circuit> circuits, int parts, boolean truncated) {

        public boolean empty() {
            return circuits.isEmpty();
        }

        public List<Finding> findings() {
            List<Finding> all = new ArrayList<>();
            circuits.forEach(c -> all.addAll(c.findings()));
            return all;
        }
    }

    private RedstoneReader() {
    }

    /** Ist diese Block-ID ein Redstone-Bauteil (Quelle, Leitung, Verstärker oder Verbraucher)? */
    public static boolean isPart(String id) {
        return kind(Cell.parse(id).id()) != null;
    }

    static Kind kind(String id) {
        if (id.equals("redstone_wire")) {
            return Kind.WIRE;
        }
        if (id.equals("repeater") || id.equals("comparator")) {
            return Kind.DIODE;
        }
        if (id.equals("lever") || id.endsWith("_button") || id.endsWith("_pressure_plate")
                || id.equals("redstone_torch") || id.equals("redstone_wall_torch") || id.equals("redstone_block")
                || id.equals("daylight_detector") || id.equals("target") || id.equals("observer")
                || id.equals("tripwire_hook") || id.equals("sculk_sensor") || id.equals("calibrated_sculk_sensor")
                || id.equals("lectern")) {
            return Kind.SOURCE;
        }
        if (id.equals("redstone_lamp") || id.equals("piston") || id.equals("sticky_piston") || id.endsWith("_door")
                || id.endsWith("_trapdoor") || id.endsWith("_fence_gate") || id.equals("dispenser")
                || id.equals("dropper") || id.equals("note_block") || id.equals("hopper") || id.equals("powered_rail")
                || id.equals("activator_rail") || id.equals("tnt") || id.equals("bell") || id.endsWith("copper_bulb")
                || id.equals("crafter")) {
            return Kind.CONSUMER;
        }
        return null;
    }

    /**
     * @param blocks  alle nicht-leeren Blöcke im Bereich, Wert = BlockData-Text (mit oder ohne „minecraft:“)
     * @param dirName Himmelsrichtung (north/east/south/west/up/down) → Wort in der Ausgabe („Norden“ für Vera,
     *                lokale Planrichtung für den Baumeister)
     */
    public static Report read(Map<Pos, String> blocks, Function<String, String> dirName) {
        Map<Pos, Part> parts = new HashMap<>();
        Map<Pos, Cell> cells = new HashMap<>();
        boolean truncated = false;
        for (Map.Entry<Pos, String> e : blocks.entrySet()) {
            Cell c = Cell.parse(e.getValue());
            cells.put(e.getKey(), c);
            Kind k = kind(c.id());
            if (k != null) {
                if (parts.size() >= MAX_PARTS) {
                    truncated = true;
                    continue;
                }
                parts.put(e.getKey(), new Part(e.getKey(), c, k));
            }
        }
        List<List<Part>> groups = group(parts);
        groups.sort(Comparator.comparingInt((List<Part> g) -> -g.size()));
        List<Circuit> circuits = new ArrayList<>();
        for (List<Part> g : groups) {
            // Eine Tür allein ist keine Schaltung (jedes Haus hat welche).
            if (g.stream().allMatch(p -> p.kind() == Kind.CONSUMER)) {
                continue;
            }
            if (circuits.size() >= MAX_CIRCUITS) {
                truncated = true;
                break;
            }
            g.sort(Comparator.comparingInt((Part p) -> p.pos().y()).thenComparingInt(p -> p.pos().x())
                    .thenComparingInt(p -> p.pos().z()));
            circuits.add(new Circuit(circuits.size() + 1, min(g), max(g), List.copyOf(g),
                    findings(g, parts, cells, dirName)));
        }
        return new Report(List.copyOf(circuits), parts.size(), truncated);
    }

    // ---------------------------------------------------------------- Gruppen

    /** Zusammen gehört, was höchstens 2 Blöcke auseinander liegt – so fällt auch die Lücke auf, die nichts verbindet. */
    private static List<List<Part>> group(Map<Pos, Part> parts) {
        Map<Pos, Integer> id = new HashMap<>();
        List<List<Part>> out = new ArrayList<>();
        for (Part start : parts.values()) {
            if (id.containsKey(start.pos())) {
                continue;
            }
            List<Part> g = new ArrayList<>();
            ArrayList<Part> todo = new ArrayList<>(List.of(start));
            id.put(start.pos(), out.size());
            while (!todo.isEmpty()) {
                Part p = todo.remove(todo.size() - 1);
                g.add(p);
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dy = -2; dy <= 2; dy++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            Pos n = p.pos().add(dx, dy, dz);
                            Part q = parts.get(n);
                            if (q != null && !id.containsKey(n)) {
                                id.put(n, out.size());
                                todo.add(q);
                            }
                        }
                    }
                }
            }
            out.add(g);
        }
        return out;
    }

    private static Pos min(List<Part> g) {
        int x = Integer.MAX_VALUE;
        int y = Integer.MAX_VALUE;
        int z = Integer.MAX_VALUE;
        for (Part p : g) {
            x = Math.min(x, p.pos().x());
            y = Math.min(y, p.pos().y());
            z = Math.min(z, p.pos().z());
        }
        return new Pos(x, y, z);
    }

    private static Pos max(List<Part> g) {
        int x = Integer.MIN_VALUE;
        int y = Integer.MIN_VALUE;
        int z = Integer.MIN_VALUE;
        for (Part p : g) {
            x = Math.max(x, p.pos().x());
            y = Math.max(y, p.pos().y());
            z = Math.max(z, p.pos().z());
        }
        return new Pos(x, y, z);
    }

    // ---------------------------------------------------------------- Auffälligkeiten

    private static final Pos[] SIX = {new Pos(1, 0, 0), new Pos(-1, 0, 0), new Pos(0, 1, 0), new Pos(0, -1, 0),
        new Pos(0, 0, 1), new Pos(0, 0, -1)};

    private static List<Finding> findings(List<Part> g, Map<Pos, Part> parts, Map<Pos, Cell> cells,
            Function<String, String> dir) {
        List<Finding> out = new ArrayList<>();
        boolean anySignal = g.stream().anyMatch(Part::signal);
        for (Part p : g) {
            switch (p.kind()) {
                case WIRE -> {
                    // Nur wenn hinter dem toten Ende etwas Strom braucht. Im Spiel 2026-11-06 meldete Vera Staubenden
                    // in einer laufenden Anlage, die einfach ausliefen (Leitung von der Mitte gespeist, beide Enden 0).
                    if (p.cell().power() == 0 && wireNeighbours(p.pos(), parts).stream()
                            .anyMatch(q -> q.cell().power() == 1) && starves(p, parts)) {
                        Fix fx = fadeFix(p, parts);
                        out.add(new Finding(fx == null ? p.pos() : fx.at(), "Bei " + at(p.pos()) + " ist das Signal "
                                + "aufgebraucht: Staub trägt nur 15 Blöcke weit. Ein Verstärker davor frischt es auf.",
                                false, fx == null ? null : fx.data(), fx == null ? null
                                        : "Ich setze bei " + at(fx.at()) + " einen Verstärker."));
                    }
                }
                case DIODE -> diodeFinding(p, parts, cells, dir).ifPresent(out::add);
                case SOURCE -> {
                    // Nur wenn sie einen dunklen Verbraucher daneben erklärt. Vorher bei JEDER ausgeschalteten Fackel:
                    // Im Spiel 2026-11-06 (Anlage mit 240 Bauteilen) bestand Veras Antwort nur aus zwei solchen
                    // Sätzen über Fackeln, die als NICHT-Gatter genau richtig aus waren – der echte dritte Fund fiel
                    // hinter die Längengrenze.
                    if (isTorch(p) && !p.on() && besideDarkConsumer(p, parts)) {
                        Pos base = torchBase(p);
                        Cell c = cells.get(base);
                        if (c != null) {
                            out.add(new Finding(p.pos(), "Die Fackel bei " + at(p.pos()) + " ist aus, weil ihr Block ("
                                    + name(c.id()) + " bei " + at(base) + ") Strom bekommt – Fackeln kehren das Signal "
                                    + "um.", true));
                        }
                    }
                }
                case CONSUMER -> {
                    if (anySignal && !p.on() && !fedOrExplained(p, parts)) {
                        Part near = nearestSignal(p, g);
                        out.add(new Finding(p.pos(), name(p.cell().id()) + " bei " + at(p.pos())
                                + " bekommt gerade kein Signal" + (near == null ? "."
                                        : "; das nächste Signal ist " + name(near.cell().id()) + " bei "
                                                + at(near.pos()) + " (" + distance(p.pos(), near.pos())
                                                + " Blöcke). Dazwischen fehlt eine Verbindung.")));
                    }
                }
                default -> {
                }
            }
        }
        // Fehler vor Erklärungen, dann kappen: Die Teile laufen nach Höhe und Lage durch, nicht nach Wichtigkeit.
        out.sort(Comparator.comparing(Finding::hint));
        return List.copyOf(out.subList(0, Math.min(MAX_FINDINGS, out.size())));
    }

    private static java.util.Optional<Finding> diodeFinding(Part p, Map<Pos, Part> parts, Map<Pos, Cell> cells,
            Function<String, String> dir) {
        String facing = p.cell().prop("facing");
        Pos f = offset(facing);
        if (f == null) {
            return java.util.Optional.empty();
        }
        String what = name(p.cell().id());
        if ("true".equals(p.cell().prop("locked"))) {
            // Erklärung, kein Fehler: In Speichern (Latch) ist das Sperren gewollt.
            return java.util.Optional.of(new Finding(p.pos(), "Der " + what + " bei " + at(p.pos()) + " ist von der "
                    + "Seite gesperrt (ein Verstärker oder Komparator speist ihn seitlich) und gibt nichts weiter – in "
                    + "einem Speicher ist das gewollt.", true));
        }
        if (p.on()) {
            return java.util.Optional.empty();
        }
        Part in = parts.get(p.pos().add(f.x(), f.y(), f.z()));
        Part outSide = parts.get(p.pos().add(-f.x(), -f.y(), -f.z()));
        boolean outSignal = outSide != null && outSide.signal();
        // Eingang „tot“: nur Luft oder ein ausgeschalteter Verbraucher davor. Vorher genügte „Eingang gerade ohne
        // Signal“ – im Spiel 2026-11-06 meldete Vera so einen Verstärker in einer laufenden Anlage, der dort als Diode
        // diente (Eingang angeschlossen, nur gerade aus; hinter ihm Strom von anderer Seite). Ein fester Block vor dem
        // Eingang kann Strom tragen, den der Leser nicht sieht – dann lieber schweigen.
        Pos inPos = p.pos().add(f.x(), f.y(), f.z());
        boolean deadInput = in == null ? !cells.containsKey(inPos) : in.kind() == Kind.CONSUMER && !in.on();
        if (deadInput && outSignal) {
            return java.util.Optional.of(new Finding(p.pos(), "Der " + what + " bei " + at(p.pos())
                    + " zeigt vermutlich falsch herum: Das Signal kommt aus Richtung " + dir.apply(opposite(facing))
                    + ", sein Eingang zeigt aber nach " + dir.apply(facing) + ". Dreh ihn um.", false,
                    turned(p.cell(), opposite(facing)), "Ich drehe den " + what + " bei " + at(p.pos()) + " um."));
        }
        return java.util.Optional.empty();
    }

    private static boolean besideDarkConsumer(Part torch, Map<Pos, Part> parts) {
        for (Pos d : SIX) {
            Part q = parts.get(torch.pos().add(d.x(), d.y(), d.z()));
            if (q != null && q.kind() == Kind.CONSUMER && !q.on()) {
                return true;
            }
        }
        return false;
    }

    /** Gleicher Block mit neuer Richtung, frisch ausgeschaltet (die Welt rechnet den Zustand danach neu). */
    static String turned(Cell c, String facing) {
        StringBuilder sb = new StringBuilder("minecraft:").append(c.id()).append('[');
        java.util.Map<String, String> props = new java.util.TreeMap<>(c.props());
        props.put("facing", facing);
        if (props.containsKey("powered")) {
            props.put("powered", "false");
        }
        if (props.containsKey("locked")) {
            props.put("locked", "false");
        }
        sb.append(String.join(",", props.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()));
        return sb.append(']').toString();
    }

    record Fix(Pos at, String data) {
    }

    /**
     * Verstärker an die Stelle des ersten Staubs mit Stärke 0, Eingang zum Staub mit Stärke 1 – in gerader Linie auf
     * gleicher Höhe und nur, wenn dahinter die Leitung weitergeht (sonst gäbe er sein Signal ins Leere). Biegt die
     * Leitung genau dort ab (im Spiel 2026-11-06: „das baue ich nicht von selbst um“), einen Block früher: auf den Staub
     * mit Stärke 1, Eingang zur 2 – der gibt dann 15 in die Kurve.
     */
    private static Fix fadeFix(Part p, Map<Pos, Part> parts) {
        int[][] flat = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        String[] names = {"east", "west", "south", "north"};
        for (int i = 0; i < flat.length; i++) {
            int[] d = flat[i];
            Part q = parts.get(p.pos().add(d[0], 0, d[2]));
            if (q == null || q.kind() != Kind.WIRE || q.cell().power() != 1) {
                continue;
            }
            Part beyond = parts.get(p.pos().add(-d[0], 0, -d[2]));
            if (beyond != null) {
                return new Fix(p.pos(), repeater(names[i]));
            }
            Part before = parts.get(q.pos().add(d[0], 0, d[2]));
            if (before != null && before.kind() == Kind.WIRE && before.cell().power() == 2) {
                return new Fix(q.pos(), repeater(names[i]));
            }
        }
        return null;
    }

    private static String repeater(String facing) {
        return "minecraft:repeater[delay=1,facing=" + facing + ",locked=false,powered=false]";
    }

    /**
     * Braucht hinter dem toten Staub etwas Strom? Folgt der toten Leitung (Stärke 0) bis 40 Blöcke und sucht daneben
     * einen ausgeschalteten Verbraucher oder Verstärker/Komparator.
     */
    private static boolean starves(Part start, Map<Pos, Part> parts) {
        java.util.ArrayDeque<Part> todo = new java.util.ArrayDeque<>(List.of(start));
        java.util.Set<Pos> seen = new java.util.HashSet<>(List.of(start.pos()));
        while (!todo.isEmpty() && seen.size() <= 40) {
            Part w = todo.poll();
            for (Pos d : SIX) {
                Part n = parts.get(w.pos().add(d.x(), d.y(), d.z()));
                if (n != null && !n.on() && (n.kind() == Kind.CONSUMER || n.kind() == Kind.DIODE)) {
                    return true;
                }
            }
            for (Part n : wireNeighbours(w.pos(), parts)) {
                if (n.cell().power() == 0 && seen.add(n.pos())) {
                    todo.add(n);
                }
            }
        }
        return false;
    }

    /** Liegt Signal direkt an, oder erklärt eine ausgeschaltete Fackel daneben das Aus? */
    private static boolean fedOrExplained(Part p, Map<Pos, Part> parts) {
        for (Pos d : SIX) {
            Part q = parts.get(p.pos().add(d.x(), d.y(), d.z()));
            if (q != null && (q.signal() || isTorch(q) || q.kind() == Kind.DIODE
                    && "true".equals(q.cell().prop("locked")))) {
                return true;
            }
        }
        return false;
    }

    private static Part nearestSignal(Part p, List<Part> g) {
        Part best = null;
        for (Part q : g) {
            if (q.signal() && (best == null || distance(p.pos(), q.pos()) < distance(p.pos(), best.pos()))) {
                best = q;
            }
        }
        return best;
    }

    /** Nachbarstaub: waagrecht daneben oder eine Stufe höher/tiefer schräg (so verbindet sich Staub). */
    private static List<Part> wireNeighbours(Pos at, Map<Pos, Part> parts) {
        List<Part> out = new ArrayList<>();
        int[][] flat = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : flat) {
            for (int dy = -1; dy <= 1; dy++) {
                Part q = parts.get(at.add(d[0], dy, d[1]));
                if (q != null && q.kind() == Kind.WIRE) {
                    out.add(q);
                }
            }
        }
        return out;
    }

    private static boolean isTorch(Part p) {
        return p.cell().id().equals("redstone_torch") || p.cell().id().equals("redstone_wall_torch");
    }

    /** Wandfackel: Block gegenüber facing (gemessen); Standfackel: der Block darunter. */
    static Pos torchBase(Part p) {
        if (p.cell().id().equals("redstone_wall_torch")) {
            Pos f = offset(p.cell().prop("facing"));
            if (f != null) {
                return p.pos().add(-f.x(), -f.y(), -f.z());
            }
        }
        return p.pos().add(0, -1, 0);
    }

    static Pos offset(String facing) {
        return switch (facing) {
            case "north" -> new Pos(0, 0, -1);
            case "south" -> new Pos(0, 0, 1);
            case "east" -> new Pos(1, 0, 0);
            case "west" -> new Pos(-1, 0, 0);
            case "up" -> new Pos(0, 1, 0);
            case "down" -> new Pos(0, -1, 0);
            default -> null;
        };
    }

    static String opposite(String facing) {
        return switch (facing) {
            case "north" -> "south";
            case "south" -> "north";
            case "east" -> "west";
            case "west" -> "east";
            case "up" -> "down";
            case "down" -> "up";
            default -> facing;
        };
    }

    private static int distance(Pos a, Pos b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z());
    }

    public static String at(Pos p) {
        return p.x() + " " + p.y() + " " + p.z();
    }

    // ---------------------------------------------------------------- Beschreibung

    /** Die ganze Lage für das Modell, höchstens maxChars Zeichen. */
    public static String describe(Report r, Function<String, String> dir, int maxChars) {
        if (r.empty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(r.circuits().size()).append(r.circuits().size() == 1 ? " Schaltung" : " Schaltungen")
                .append(r.truncated() ? " (nicht alle gezeigt)" : "").append(". Koordinaten x y z.\n");
        for (Circuit c : r.circuits()) {
            sb.append("Schaltung ").append(c.number()).append(" (").append(at(c.min())).append(" bis ")
                    .append(at(c.max())).append("): ").append(parts(c.parts(), dir)).append('\n');
            for (Finding f : c.findings()) {
                sb.append(f.hint() ? "  Hinweis: " : "  Auffällig: ").append(f.text()).append('\n');
            }
        }
        String s = sb.toString().stripTrailing();
        return s.length() <= maxChars ? s : s.substring(0, Math.max(0, maxChars - 2)) + " …";
    }

    /** Für Vera direkt gesagt: die Auffälligkeiten, sonst eine kurze Entwarnung mit dem Stand. */
    public static String verdict(Report r, Function<String, String> dir) {
        return verdict(r, dir, 280);
    }

    /** max = Zeichen für die Funde (Vera sagt höchstens CompanionReply.MAX_SAY = 300 am Stück). */
    public static String verdict(Report r, Function<String, String> dir, int max) {
        if (r.empty()) {
            return "Ich sehe hier keine Redstone-Schaltung. Stell dich näher dran oder schau sie an.";
        }
        List<Finding> all = new ArrayList<>(r.findings());
        // Fehler aller Schaltungen vor allen Erklärungen.
        all.sort(Comparator.comparing(Finding::hint));
        if (!all.isEmpty() && all.get(0).hint()) {
            return "Ich sehe keinen Fehler. " + all.get(0).text();
        }
        if (all.isEmpty()) {
            Circuit c = r.circuits().get(0);
            long on = c.parts().stream().filter(p -> p.kind() == Kind.CONSUMER && p.on()).count();
            long off = c.parts().stream().filter(p -> p.kind() == Kind.CONSUMER && !p.on()).count();
            return "Ich sehe keinen Fehler in der Schaltung bei " + at(c.min()) + ": " + c.parts().size()
                    + " Bauteile, " + on + " Verbraucher an, " + off + " aus."
                    + (off > 0 && c.parts().stream().noneMatch(Part::signal) ? " Gerade ist nur nichts eingeschaltet."
                            : "");
        }
        StringBuilder sb = new StringBuilder();
        for (Finding f : all) {
            if (sb.length() + f.text().length() > max) {
                break;
            }
            sb.append(sb.isEmpty() ? "" : " ").append(f.text());
        }
        if (sb.isEmpty()) {
            sb.append(all.get(0).text(), 0, Math.min(max, all.get(0).text().length()));
        }
        return sb.toString();
    }

    private static String parts(List<Part> parts, Function<String, String> dir) {
        List<String> out = new ArrayList<>();
        List<Part> wires = parts.stream().filter(p -> p.kind() == Kind.WIRE).toList();
        for (Part p : parts) {
            if (p.kind() != Kind.WIRE) {
                out.add(name(p.cell().id()) + " " + at(p.pos()) + " " + state(p, dir));
            }
        }
        if (!wires.isEmpty()) {
            int lo = wires.stream().mapToInt(p -> p.cell().power()).min().orElse(0);
            int hi = wires.stream().mapToInt(p -> p.cell().power()).max().orElse(0);
            out.add("Staub " + wires.size() + (wires.size() == 1 ? " Block" : " Blöcke") + ", Stärke "
                    + (lo == hi ? String.valueOf(hi) : hi + " bis " + lo));
        }
        return String.join("; ", out);
    }

    private static final Set<String> FACING_MATTERS = Set.of("observer", "piston", "sticky_piston", "dispenser",
            "dropper", "hopper", "crafter");

    static String state(Part p, Function<String, String> dir) {
        Cell c = p.cell();
        String id = c.id();
        String facing = c.prop("facing");
        return switch (id) {
            case "lever" -> p.on() ? "an" : "aus";
            case "redstone_torch", "redstone_wall_torch" -> p.on() ? "brennt" : "aus";
            case "redstone_block" -> "liefert immer Strom";
            case "repeater" -> "Eingang " + dir.apply(facing) + ", Verzögerung " + c.prop("delay")
                    + (p.on() ? ", an" : ", aus") + ("true".equals(c.prop("locked")) ? ", gesperrt" : "");
            case "comparator" -> "Eingang " + dir.apply(facing) + ", " + ("subtract".equals(c.prop("mode"))
                    ? "subtrahiert" : "vergleicht") + (p.on() ? ", an" : ", aus");
            case "observer" -> "schaut nach " + dir.apply(facing) + ", gibt nach " + dir.apply(opposite(facing))
                    + " aus" + (p.on() ? ", feuert" : "");
            case "redstone_lamp" -> p.on() ? "leuchtet" : "aus";
            case "piston", "sticky_piston" -> "drückt nach " + dir.apply(facing) + (p.on() ? ", ausgefahren"
                    : ", eingefahren");
            case "daylight_detector", "target", "sculk_sensor", "calibrated_sculk_sensor" -> "Stärke " + c.power();
            case "hopper" -> "gibt nach " + dir.apply(facing) + (p.on() ? ", gesperrt durch Strom" : "");
            default -> {
                if (id.endsWith("_door") || id.endsWith("_trapdoor") || id.endsWith("_fence_gate")) {
                    yield ("true".equals(c.prop("open")) ? "offen" : "zu") + (p.on() ? ", mit Strom" : "");
                }
                if (id.endsWith("_button")) {
                    yield p.on() ? "gedrückt" : "nicht gedrückt";
                }
                if (id.endsWith("_pressure_plate")) {
                    yield p.on() ? "belastet" : "frei";
                }
                yield (FACING_MATTERS.contains(id) && !facing.isEmpty() ? "nach " + dir.apply(facing) + ", " : "")
                        + (p.on() ? "an" : "aus");
            }
        };
    }

    static String name(String id) {
        String n = NAMES.get(id);
        if (n != null) {
            return n;
        }
        if (id.endsWith("_trapdoor")) {
            return "Falltür";
        }
        if (id.endsWith("_door")) {
            return "Tür";
        }
        if (id.endsWith("_fence_gate")) {
            return "Zauntor";
        }
        if (id.endsWith("_button")) {
            return "Knopf";
        }
        if (id.endsWith("_pressure_plate")) {
            return "Druckplatte";
        }
        if (id.endsWith("copper_bulb")) {
            return "Kupferlampe";
        }
        return id.replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("lever", "Hebel"), Map.entry("redstone_torch", "Fackel"),
            Map.entry("redstone_wall_torch", "Fackel"), Map.entry("redstone_block", "Redstoneblock"),
            Map.entry("daylight_detector", "Tageslichtsensor"), Map.entry("target", "Zielblock"),
            Map.entry("observer", "Beobachter"), Map.entry("tripwire_hook", "Stolperdrahthaken"),
            Map.entry("sculk_sensor", "Sculk-Sensor"), Map.entry("calibrated_sculk_sensor", "Sculk-Sensor"),
            Map.entry("lectern", "Lesepult"), Map.entry("redstone_wire", "Staub"), Map.entry("repeater", "Verstärker"),
            Map.entry("comparator", "Komparator"), Map.entry("redstone_lamp", "Lampe"), Map.entry("piston", "Kolben"),
            Map.entry("sticky_piston", "Klebekolben"), Map.entry("dispenser", "Werfer"),
            Map.entry("dropper", "Spender"), Map.entry("note_block", "Notenblock"), Map.entry("hopper", "Trichter"),
            Map.entry("powered_rail", "Antriebsschiene"), Map.entry("activator_rail", "Aktivierungsschiene"),
            Map.entry("tnt", "TNT"), Map.entry("bell", "Glocke"), Map.entry("crafter", "Werkbank-Automat"),
            Map.entry("stone", "Stein"));
}
