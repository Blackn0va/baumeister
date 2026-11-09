package com.blackn0va.baumeister.plan;

import com.blackn0va.baumeister.plan.BuildPlan.Op;
import com.blackn0va.baumeister.plan.BuildPlan.Step;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Bauformen, deren Geometrie das Plugin rechnet: Burg (castle), Mauerzug (wall), Brücke (bridge), Garten (garden)
 * und Baum (tree). Das LLM liefert nur Ort, Maße und Material.
 *
 * Warum: Gemessen 2026-10-28 scheiterte das kleine Modell an allem, was aus Wiederholung besteht (Hochhaus als
 * Vollzylinder, Brunnen mit eingemauertem Wasser, Anbau auf statt neben der Wand). Zinnenreihen, Mauerdicke,
 * Brückenbögen, Beete und Baumkronen sind genau solche Wiederholung – wie bei skyscraper und house rechnet sie der
 * Compiler sicher, und ein Plan wird ein einziger Schritt statt vierzig. Jede Form hält (jeder Block hängt über
 * Nachbarn an der untersten Schicht), ist begehbar (Tor, Wehrgang, Leitern, Brückendeck) und bleibt in dem
 * Rechteck, das das LLM angegeben hat – das prüft StructuresTest in allen vier Richtungen.
 *
 * Gemeinsames Muster: Jede Form wird in einem eigenen Rahmen gerechnet (u entlang der Tor- bzw. Laufrichtung, v quer
 * dazu, Tor und Außenseite bei kleinem v) und erst beim Setzen mit Orientation.rotateLocal gedreht – Positionen UND
 * Blockzustände (Leiter, Tor, Stamm- und Balkenachse). Wer nur die Positionen dreht, hängt Leitern an Luft.
 *
 * Threads: wie PlanCompiler ohne Bukkit, auf jedem Thread aufrufbar. Fehler: PlanException mit deutschem Satz, der
 * als Reparaturhinweis an das LLM geht.
 */
public final class Structures {

    /** Ziel der Blöcke: PlanCompiler.Canvas (spätere Schritte überschreiben den Zustand, nicht den Zeitpunkt). */
    @FunctionalInterface
    interface Sink {
        void put(Pos p, BlockSpec spec) throws PlanException;
    }

    /** 17: zwei Ecktürme mit Radius 2 (je 5) und das Torhaus (7) brauchen mit je einem Mauerblock dazwischen 17. */
    public static final int MIN_CASTLE_SIDE = 17;
    public static final int MAX_CASTLE_SIDE = 96;
    /** Unter 4 bliebe über der 3 hohen Durchfahrt kein Sturz. */
    public static final int MIN_CASTLE_HEIGHT = 4;
    /** Mauer-, Bogen- und Pfeilerhöhe; Burgtürme ragen 5 darüber hinaus, bleiben also unter 30. */
    public static final int MAX_STRUCTURE_HEIGHT = 24;
    public static final int MAX_WALL_WIDTH = 5;
    public static final int MAX_BRIDGE_WIDTH = 9;
    /** Kürzer ist keine Brücke, sondern ein Steg: zwei Auflager und mindestens zwei Blöcke Spannweite. */
    public static final int MIN_BRIDGE_LENGTH = 4;
    public static final int MIN_GARDEN_SIDE = 5;
    public static final int MAX_GARDEN_SIDE = 64;
    public static final int MIN_TREE_HEIGHT = 4;
    public static final int MAX_TREE_HEIGHT = 32;
    /** Ab dieser Höhe ein 2×2-Stamm: Ein 1 breiter Stamm unter einer Krone mit Radius 6 sieht wie ein Stiel aus. */
    static final int THICK_TRUNK_HEIGHT = 14;
    static final int MIN_TOWER_RADIUS = 2;
    static final int MAX_TOWER_RADIUS = 6;
    static final int DEFAULT_CASTLE_HEIGHT = 6;
    static final int DEFAULT_WALL_HEIGHT = 5;
    static final int DEFAULT_WIDTH = 3;
    /** Torhaus 7 breit (3 Durchfahrt, je 2 Wange), Torstube 3 hoch über dem Wehrgang. */
    private static final int GATE_HALF = 3;
    /** Laternen- und Pfeilerabstand: Licht etwa alle 6 Blöcke hält Monster vom Deck fern. */
    private static final int SPACING = 6;

    static final Set<String> CASTLE_STYLES = Set.of("round", "square");
    static final Set<String> BRIDGE_STYLES = Set.of("arch", "beam");
    static final Set<String> TREE_STYLES = Set.of("round", "cone");

    /** Längere Namen zuerst: dark_oak_planks beginnt nicht mit „oak_“, pale_oak aber würde sonst früher passen. */
    private static final List<String> WOODS = List.of("dark_oak", "pale_oak", "spruce", "birch", "jungle", "acacia",
            "mangrove", "cherry", "bamboo", "crimson", "warped", "oak");

    private static final List<String> FLOWERS = List.of("poppy", "dandelion", "cornflower", "oxeye_daisy", "allium",
            "azure_bluet");

    private final BlockCatalog catalog;
    private final PlanCompiler.Limits limits;

    Structures(BlockCatalog catalog, PlanCompiler.Limits limits) {
        this.catalog = catalog;
        this.limits = limits;
    }

    public static boolean handles(Op op) {
        return switch (op) {
            case CASTLE, WALL, BRIDGE, GARDEN, TREE -> true;
            default -> false;
        };
    }

    /** Vorgabe für height, wenn das LLM sie weglässt. 0 = „rechnet die Form selbst“ (Bogenhöhe, Baumgröße). */
    static int defaultHeight(Op op) {
        return switch (op) {
            case CASTLE -> DEFAULT_CASTLE_HEIGHT;
            case WALL -> DEFAULT_WALL_HEIGHT;
            case BRIDGE, TREE -> 0;
            default -> 1;
        };
    }

    static int defaultWidth(Op op) {
        return switch (op) {
            case CASTLE, WALL, BRIDGE -> DEFAULT_WIDTH;
            default -> 0;
        };
    }

    /** height 0 heißt bei Brücke und Baum „selbst rechnen“; bei allen anderen Ops bleibt 1 die Untergrenze. */
    static boolean allowsAutoHeight(Op op) {
        return op == Op.BRIDGE || op == Op.TREE;
    }

    /** Grenze aus plan-schema.json: Was darin liegt, hat die Grammatik zugelassen und wird nur noch gekappt. */
    private static final int SCHEMA_MAX_WIDTH = 9;

    /**
     * Prüft die Maße je Form. Läuft im Parser (Reparaturhinweis ans LLM) und noch einmal im Compiler, falls ein
     * Schritt am Parser vorbei entsteht – eine Burg mit 3 Blöcken Seitenlänge würde sonst Türme ineinander rechnen.
     *
     * Abgelehnt wird nur, was sich nicht kappen lässt (Grundriss, Endpunkte, style, facing) oder außerhalb des
     * Schemas liegt. height, width und radius innerhalb des Schemas kappt {@link #fit} mit Warnung: Vorher kostete
     * „castle width 1“ eine Reparaturrunde (1–3 min auf der CPU), obwohl die Grammatik den Wert zugelassen hatte
     * (Prüfer-Befund 2026-10-29). Wer hier wieder ablehnt, muss die Grenzen jeder Form in Prompt und Grammatik tragen.
     */
    static void validate(Step s, int n) throws PlanException {
        String where = "Schritt " + n + " (" + s.op().name().toLowerCase(Locale.ROOT) + "): ";
        if (s.op() == Op.CASTLE || s.op() == Op.WALL || s.op() == Op.BRIDGE) {
            range(where, "width", s.width(), 1, SCHEMA_MAX_WIDTH);
        }
        s = fit(s, n, new ArrayList<>());
        switch (s.op()) {
            case CASTLE -> {
                requireCorners(s, where);
                int w = side(s.from().x(), s.to().x());
                int d = side(s.from().z(), s.to().z());
                if (w < MIN_CASTLE_SIDE || d < MIN_CASTLE_SIDE || w > MAX_CASTLE_SIDE || d > MAX_CASTLE_SIDE) {
                    throw new PlanException(where + "castle braucht einen Grundriss von " + MIN_CASTLE_SIDE + "×"
                            + MIN_CASTLE_SIDE + " bis " + MAX_CASTLE_SIDE + "×" + MAX_CASTLE_SIDE + ", nicht " + w + "×"
                            + d + ".");
                }
                range(where, "height", s.height(), MIN_CASTLE_HEIGHT, MAX_STRUCTURE_HEIGHT);
                range(where, "width", s.width(), 2, MAX_WALL_WIDTH);
                if (s.radius() != 0) {
                    range(where, "radius", s.radius(), MIN_TOWER_RADIUS, MAX_TOWER_RADIUS);
                }
                style(where, s.style(), CASTLE_STYLES);
                side(where, s.facing());
            }
            case WALL -> {
                requireCorners(s, where);
                if (s.from().x() == s.to().x() && s.from().z() == s.to().z()) {
                    throw new PlanException(where + "wall braucht zwei verschiedene Endpunkte from und to.");
                }
                // „wall“ ist ein Zeichen neben „walls“ (Raumwände, from.y 1 bis to.y 3). Vorher wurde aus diesem
                // Tippfehler des Modells still eine Wehrmauer mit Zinnen schräg durch das Haus (Prüfer-Befund
                // 2026-10-29). Die Wehrmauer steht auf dem Boden, beide Enden haben dasselbe y.
                if (s.from().y() != s.to().y()) {
                    throw new PlanException(where + "wall ist die Wehrmauer zwischen zwei Bodenpunkten mit gleichem y"
                            + " (from " + s.from().y() + ", to " + s.to().y() + "). Raumwände baut op walls.");
                }
                range(where, "height", s.height(), 2, MAX_STRUCTURE_HEIGHT);
                range(where, "width", s.width(), 1, MAX_WALL_WIDTH);
                side(where, s.facing());
            }
            case BRIDGE -> {
                requireCorners(s, where);
                int len = Math.max(side(s.from().x(), s.to().x()), side(s.from().z(), s.to().z()));
                if (len < MIN_BRIDGE_LENGTH) {
                    throw new PlanException(where + "bridge braucht from und to mindestens " + MIN_BRIDGE_LENGTH
                            + " Blöcke auseinander (die beiden Enden des Decks), nicht " + len + ".");
                }
                range(where, "width", s.width(), 1, MAX_BRIDGE_WIDTH);
                if (s.height() != 0) {
                    range(where, "height", s.height(), 1, MAX_STRUCTURE_HEIGHT);
                }
                style(where, s.style(), BRIDGE_STYLES);
            }
            case GARDEN -> {
                requireCorners(s, where);
                int w = side(s.from().x(), s.to().x());
                int d = side(s.from().z(), s.to().z());
                if (w < MIN_GARDEN_SIDE || d < MIN_GARDEN_SIDE || w > MAX_GARDEN_SIDE || d > MAX_GARDEN_SIDE) {
                    throw new PlanException(where + "garden braucht einen Grundriss von " + MIN_GARDEN_SIDE + "×"
                            + MIN_GARDEN_SIDE + " bis " + MAX_GARDEN_SIDE + "×" + MAX_GARDEN_SIDE + ", nicht " + w + "×"
                            + d + ".");
                }
                side(where, s.facing());
            }
            case TREE -> {
                if (s.pos() == null) {
                    throw new PlanException(where + "pos fehlt.");
                }
                if (s.height() != 0) {
                    range(where, "height", s.height(), MIN_TREE_HEIGHT, MAX_TREE_HEIGHT);
                }
                style(where, s.style(), TREE_STYLES);
            }
            default -> throw new IllegalStateException("Keine Bauform: " + s.op());
        }
    }

    private static void requireCorners(Step s, String where) throws PlanException {
        if (s.from() == null || s.to() == null) {
            throw new PlanException(where + "from und to fehlen.");
        }
    }

    private static int side(int a, int b) {
        return Math.abs(b - a) + 1;
    }

    private static void range(String where, String key, int value, int min, int max) throws PlanException {
        if (value < min || value > max) {
            throw new PlanException(where + key + " muss " + min + " bis " + max + " sein, nicht " + value + ".");
        }
    }

    private static void style(String where, String style, Set<String> allowed) throws PlanException {
        if (!style.isEmpty() && !allowed.contains(style)) {
            throw new PlanException(where + "style muss " + String.join(" oder ", allowed.stream().sorted().toList())
                    + " sein, nicht \"" + style + "\".");
        }
    }

    /** Tor- bzw. Außenseite: nur die vier Seiten, up/down ergäben keinen Sinn. */
    private static void side(String where, String facing) throws PlanException {
        if (!facing.isEmpty() && !Orientation.isLocalDirection(facing)) {
            throw new PlanException(where + "facing muss front, back, left oder right sein.");
        }
    }

    /**
     * Kappt height, width und radius auf die Grenzen der Form und schreibt je gekapptem Wert eine Warnung. Werte
     * außerhalb des Schemas lässt sie stehen, die lehnt {@link #validate} ab. height 0 bei Brücke und Baum bleibt 0
     * („rechnet die Form selbst“), radius 0 bei der Burg ebenso.
     */
    static Step fit(Step s, int n, List<String> warnings) {
        String where = "Schritt " + n + " (" + s.op().name().toLowerCase(Locale.ROOT) + "): ";
        int height = s.height();
        int width = s.width();
        int radius = s.radius();
        switch (s.op()) {
            case CASTLE -> {
                height = clampNoted(where, "height", height, MIN_CASTLE_HEIGHT, MAX_STRUCTURE_HEIGHT, warnings);
                width = clampNoted(where, "width", width, 2, MAX_WALL_WIDTH, warnings);
                if (radius != 0) {
                    radius = clampNoted(where, "radius", radius, MIN_TOWER_RADIUS, MAX_TOWER_RADIUS, warnings);
                }
            }
            case WALL -> {
                height = clampNoted(where, "height", height, 2, MAX_STRUCTURE_HEIGHT, warnings);
                width = clampNoted(where, "width", width, 1, MAX_WALL_WIDTH, warnings);
            }
            case BRIDGE -> {
                if (height != 0) {
                    height = clampNoted(where, "height", height, 1, MAX_STRUCTURE_HEIGHT, warnings);
                }
            }
            case TREE -> {
                if (height != 0) {
                    height = clampNoted(where, "height", height, MIN_TREE_HEIGHT, MAX_TREE_HEIGHT, warnings);
                }
            }
            default -> {
                return s;
            }
        }
        if (height == s.height() && width == s.width() && radius == s.radius()) {
            return s;
        }
        return new Step(s.op(), s.block(), s.from(), s.to(), s.pos(), radius, height, s.hollow(), s.dome(),
                s.shape(), s.overhang(), s.gableBlock(), s.facing(), s.name(), s.floors(), s.windowBlock(),
                s.roofBlock(), s.expr(), width, s.style());
    }

    /** Nur Werte im Schema (height 1–48, width 1–9, radius 1–24) – alles andere bleibt für validate stehen. */
    private static int clampNoted(String where, String key, int value, int min, int max, List<String> warnings) {
        int schemaMax = switch (key) {
            case "height" -> 48;
            case "width" -> SCHEMA_MAX_WIDTH;
            default -> 24;
        };
        if (value < 1 || value > schemaMax || value >= min && value <= max) {
            return value;
        }
        int c = Math.clamp(value, min, max);
        warnings.add(where + key + " " + value + " gekappt auf " + c + " (erlaubt " + min + " bis " + max + ")");
        return c;
    }

    /** Baut den Schritt; main ist der schon gegen Katalog und Sperrliste aufgelöste block des Schritts. */
    void build(Step s, int n, BlockSpec main, Sink out, List<String> warnings) throws PlanException {
        validate(s, n);
        Step f = fit(s, n, warnings);
        switch (f.op()) {
            case CASTLE -> castle(f, main, out, warnings);
            case WALL -> wall(f, main, out);
            case BRIDGE -> bridge(f, main, out);
            case GARDEN -> garden(f, main, out);
            case TREE -> tree(f, n, main, out, warnings);
            default -> throw new IllegalStateException("Keine Bauform: " + f.op());
        }
    }

    /**
     * Die Zellen, die der Schritt setzt – für PlanLinter, damit er eine Zelle nur dann der Bauform zuordnet (und
     * nicht prüft), wenn die Form sie wirklich geschrieben hat. Vorher stand hier ein großzügiger Kasten (Baum ±9,
     * Brücke bis 25 unter dem Deck), und ein späterer tree schaltete die Prüfung einer schwebenden Laterne des LLM
     * daneben ab (Prüfer-Befund 2026-10-29, Gegenprobe StructuresTest
     * aLaterStructureDoesNotHideForeignFloatingBlocksFromTheLinter).
     *
     * Der Linter kennt den Katalog des Servers nicht: Gerechnet wird mit „jeder Block bekannt“. Fehlt auf dem Server
     * etwa die Laterne, enthält die Menge eine Zelle mehr als gebaut – das ordnet höchstens einen LLM-Block genau an
     * dieser Stelle der Form zu. Ein Schritt, den der Compiler auslassen würde (Block unlesbar, Maße abgelehnt), setzt
     * keine Zelle, die Menge ist dann leer. Gefährliche Blöcke lässt der Compiler seit 2026-11-08 stehen (die Stufe
     * prüft BuildJob beim Setzen) – deshalb hier keine Ausnahme mehr für sie.
     */
    static Set<Pos> cells(Step s, int n) {
        Set<Pos> out = new HashSet<>();
        BlockSpec main;
        try {
            main = BlockSpec.parse(s.block());
        } catch (PlanException e) {
            // Der Compiler lässt den Schritt mit dieser Meldung aus (PlanCompiler.emit) – er setzt also nichts.
            return out;
        }
        Structures all = new Structures(id -> true, new PlanCompiler.Limits(Integer.MAX_VALUE, Integer.MAX_VALUE));
        try {
            all.build(s, n, main, (p, spec) -> out.add(p), new ArrayList<>());
        } catch (PlanException e) {
            // Abgelehnte Maße: Auch der Compiler bricht hier ab, kein Block dieses Schritts steht im Bau.
            out.clear();
        }
        return out;
    }

    // ---------------------------------------------------------------- Burg

    /**
     * Burg im Rechteck from..to: Mauerring (Dicke width, Höhe height) mit Zinnen außen und Wehrgang innen, vier
     * Ecktürme (rund oder eckig) mit Turmstube auf Wehrgangshöhe, Leiter auf die Plattform und Zinnenkranz, und ein
     * Torhaus in der Mitte der Torseite (facing) mit 3 breiter Durchfahrt, Torstube und Leiter vom Hof hinauf. So ist
     * jeder Teil zu Fuß erreichbar: Hof → Leiter → Torstube → Wehrgang → Türme → Plattformen.
     *
     * Die Türme sitzen INNEN in den Ecken des Rechtecks statt außen vorzustehen: Das LLM gibt ein Rechteck an, und
     * alles muss darin bleiben – sonst stünde ein Turm im Nachbarbau, den der Überbau-Schutz gar nicht erwartet.
     * Unter der Turmstube ist der Turm massiv: Ein dunkler, unzugänglicher Raum wäre ein Monsternest.
     */
    private void castle(Step s, BlockSpec wall, Sink out, List<String> warnings) throws PlanException {
        int x0 = Math.min(s.from().x(), s.to().x());
        int x1 = Math.max(s.from().x(), s.to().x());
        int z0 = Math.min(s.from().z(), s.to().z());
        int z1 = Math.max(s.from().z(), s.to().z());
        int y0 = Math.min(s.from().y(), s.to().y());
        Frame f = Frame.rect(out, x0, x1, z0, z1, s.facing().isEmpty() ? "front" : s.facing());
        int w = f.width;
        int d = f.depth;
        int h = s.height();
        int t = s.width();
        checkVolume(2L * (w + d) * (t + 2) * (h + 6));
        boolean square = "square".equals(s.style());
        int mid = (w - 1) / 2;
        int g0 = mid - GATE_HALF;
        int g1 = mid + GATE_HALF;
        int gateDepth = t + 2;
        // Ecktürme und Torhaus dürfen sich auf der Torseite nicht überlappen (anstoßen ist recht: Turmstube und
        // Torstube haben dann eine gemeinsame Öffnung), seitlich müssen zwei Türme mit Mauer dazwischen passen. Bei
        // der Mindestgröße 17 bleibt genau Radius 2.
        int rMax = Math.min(Math.min(g0 - 1, w - 2 - g1) / 2, (d - 3) / 4);
        int r = s.radius() > 0 ? s.radius() : Math.clamp(Math.min(w, d) / 8, MIN_TOWER_RADIUS, MAX_TOWER_RADIUS);
        if (r > rMax) {
            if (s.radius() > 0) {
                warnings.add("Turmradius " + s.radius() + " passt nicht in " + w + "×" + d + ", gebaut mit " + rMax);
            }
            r = rMax;
        }
        int top = y0 + h;
        int towerTop = top + 4;
        BlockSpec air = BlockSpec.of("air");
        BlockSpec ladder = firstKnown("ladder");
        BlockSpec lantern = firstKnown("lantern");

        // Mauerring zwischen den Turmmitten, vom Fundament (y0) bis zum Wehrgang (top)
        for (int u = r; u <= w - 1 - r; u++) {
            for (int k = 0; k < t; k++) {
                column(f, u, k, y0, top, wall);
                column(f, u, d - 1 - k, y0, top, wall);
            }
        }
        for (int v = r; v <= d - 1 - r; v++) {
            for (int k = 0; k < t; k++) {
                column(f, k, v, y0, top, wall);
                column(f, w - 1 - k, v, y0, top, wall);
            }
        }
        // Torhaus massiv bis zum Wehrgang; die Durchfahrt wird unten ausgeschnitten.
        for (int u = g0; u <= g1; u++) {
            for (int v = 0; v < gateDepth; v++) {
                column(f, u, v, y0, top, wall);
            }
        }
        int[][] centers = {{r, r}, {w - 1 - r, r}, {r, d - 1 - r}, {w - 1 - r, d - 1 - r}};
        // Zinnen auf der Außenreihe, jeder zweite Block – nicht über Türmen und Torhaus (die haben eigene).
        for (int u = r; u <= w - 1 - r; u++) {
            if (u % 2 == 0 && !inAnyTower(u, 0, centers, r, square) && (u < g0 || u > g1)) {
                f.put(u, top + 1, 0, wall);
            }
            if (u % 2 == 0 && !inAnyTower(u, d - 1, centers, r, square)) {
                f.put(u, top + 1, d - 1, wall);
            }
        }
        for (int v = r; v <= d - 1 - r; v++) {
            if (v % 2 == 0 && !inAnyTower(0, v, centers, r, square)) {
                f.put(0, top + 1, v, wall);
            }
            if (v % 2 == 0 && !inAnyTower(w - 1, v, centers, r, square)) {
                f.put(w - 1, top + 1, v, wall);
            }
        }

        for (int[] c : centers) {
            int ou = c[0] < w / 2 ? -1 : 1;
            int ov = c[1] < d / 2 ? -1 : 1;
            tower(f, c[0], c[1], r, square, ou, y0, top, towerTop, wall, air, ladder, lantern);
            // Durchgang vom Wehrgang in die Turmstube: die Mauer läuft vom Turm weg (−ou bzw. −ov), Innenreihen der
            // Mauer ohne die Außenreihe (dort stehen die Zinnen).
            int bandU0 = ov < 0 ? 1 : d - t;
            int bandU1 = ov < 0 ? t - 1 : d - 2;
            int bandV0 = ou < 0 ? 1 : w - t;
            int bandV1 = ou < 0 ? t - 1 : w - 2;
            doorway(f, c[0], c[1], r, square, -ou, 0, bandU0, bandU1, top, air);
            doorway(f, c[0], c[1], r, square, 0, -ov, bandV0, bandV1, top, air);
        }

        // Durchfahrt: 3 breit durch die ganze Torhaustiefe, darüber mindestens ein Block Sturz.
        int passage = Math.min(4, h - 1);
        for (int u = mid - 1; u <= mid + 1; u++) {
            for (int v = 0; v < gateDepth; v++) {
                for (int y = y0 + 1; y <= y0 + passage; y++) {
                    f.put(u, y, v, air);
                }
            }
        }
        // Torstube über der Durchfahrt: Wände, Dach, Zinnenkranz
        for (int u = g0; u <= g1; u++) {
            for (int v = 0; v < gateDepth; v++) {
                boolean rim = u == g0 || u == g1 || v == 0 || v == gateDepth - 1;
                for (int y = top + 1; y <= top + 3; y++) {
                    f.put(u, y, v, rim ? wall : air);
                }
                f.put(u, top + 4, v, wall);
                if (rim && (u + v) % 2 == 0) {
                    f.put(u, top + 5, v, wall);
                }
            }
        }
        // Durchgänge zum Wehrgang links und rechts (Innenreihen der Mauer) und zur Leiter auf der Hofseite
        int lu = mid + 2;
        for (int y = top + 1; y <= top + 2; y++) {
            for (int v = 1; v < t; v++) {
                f.put(g0, y, v, air);
                f.put(g1, y, v, air);
            }
            f.put(lu, y, gateDepth - 1, air);
        }
        if (ladder != null) {
            // Leiter an der Hofseite des Torhauses; facing zeigt von der Wand weg (wie im Wohnhaus).
            for (int y = y0 + 1; y <= top; y++) {
                f.put(lu, y, gateDepth, ladder.with("facing", "back"));
            }
        }
        if (lantern != null) {
            f.put(mid, top + 3, gateDepth / 2, lantern.with("hanging", "true"));
        }
    }

    /** Senkrechte Säule aus einem Block. */
    private static void column(Frame f, int u, int v, int yLow, int yHigh, BlockSpec spec) throws PlanException {
        for (int y = yLow; y <= yHigh; y++) {
            f.put(u, y, v, spec);
        }
    }

    private static boolean inTower(int du, int dv, int r, boolean square) {
        if (square) {
            return Math.abs(du) <= r && Math.abs(dv) <= r;
        }
        return du * du + dv * dv < (r + 0.5) * (r + 0.5);
    }

    private static boolean towerEdge(int du, int dv, int r, boolean square) {
        return inTower(du, dv, r, square) && !(inTower(du + 1, dv, r, square) && inTower(du - 1, dv, r, square)
                && inTower(du, dv + 1, r, square) && inTower(du, dv - 1, r, square));
    }

    private static boolean inAnyTower(int u, int v, int[][] centers, int r, boolean square) {
        for (int[] c : centers) {
            if (inTower(u - c[0], v - c[1], r, square)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Turm: bis zum Wehrgang massiv, darüber eine 3 hohe Stube (Rand Mauer, innen Luft), Plattform, Zinnenkranz.
     * Leiter innen an der Außenwand (Seite ou) durch ein Loch in der Plattform, Hängelaterne in der Stube.
     */
    private static void tower(Frame f, int cu, int cv, int r, boolean square, int ou, int y0, int top, int towerTop,
            BlockSpec wall, BlockSpec air, BlockSpec ladder, BlockSpec lantern) throws PlanException {
        for (int dv = -r; dv <= r; dv++) {
            for (int du = -r; du <= r; du++) {
                if (!inTower(du, dv, r, square)) {
                    continue;
                }
                boolean edge = towerEdge(du, dv, r, square);
                int u = cu + du;
                int v = cv + dv;
                column(f, u, v, y0, top, wall);
                for (int y = top + 1; y < towerTop; y++) {
                    f.put(u, y, v, edge ? wall : air);
                }
                f.put(u, towerTop, v, wall);
                if (edge && (u + v) % 2 == 0) {
                    f.put(u, towerTop + 1, v, wall);
                }
            }
        }
        if (ladder != null) {
            // ou = −1: Wand bei −u, die Leiter schaut nach +u (right). Gleiche Regel wie Wohnhaus: facing weg von der
            // Wand, an der sie hängt.
            BlockSpec rung = ladder.with("facing", ou < 0 ? "right" : "left");
            for (int y = top + 1; y <= towerTop; y++) {
                f.put(cu + ou * (r - 1), y, cv, rung);
            }
        }
        if (lantern != null) {
            f.put(cu, towerTop - 1, cv, lantern.with("hanging", "true"));
        }
    }

    /**
     * Öffnet die Turmwand dort, wo der Wehrgang anstößt: jede Randzelle in den Innenreihen der Mauer (cross0..cross1
     * quer zur Laufrichtung), deren Nachbar in Laufrichtung (du, dv) schon außerhalb des Turms liegt – dort beginnt
     * die Mauerkrone. Nur diese Zellen: Ein breiter Schnitt durch den Turm risse bei dicker Mauer auch die Hofseite
     * der Stube auf (über dem Hof, 7 Blöcke tief).
     */
    private static void doorway(Frame f, int cu, int cv, int r, boolean square, int du, int dv, int cross0,
            int cross1, int top, BlockSpec air) throws PlanException {
        for (int ev = -r; ev <= r; ev++) {
            for (int eu = -r; eu <= r; eu++) {
                if (!towerEdge(eu, ev, r, square) || inTower(eu + du, ev + dv, r, square)) {
                    continue;
                }
                int cross = du != 0 ? cv + ev : cu + eu;
                if (cross < cross0 || cross > cross1) {
                    continue;
                }
                f.put(cu + eu, top + 1, cv + ev, air);
                f.put(cu + eu, top + 2, cv + ev, air);
            }
        }
    }

    // ---------------------------------------------------------------- Mauer

    /**
     * Mauerzug von from nach to (Fußpunkt from.y): Dicke width, Krone auf from.y + height, Zinnen jeden zweiten Block
     * auf der Außenreihe (Seite facing), dahinter der Wehrgang, eine Leiter am Anfang auf der Innenseite.
     *
     * Schräge Endpunkte laufen als Treppenlinie entlang der längeren Achse; wo die Linie quer springt, wird die
     * Lücke mit einer zusätzlichen Reihe geschlossen – sonst hingen zwei Mauerstücke nur über eine Kante zusammen
     * (bei Dicke 1 sichtbar als Loch). facing quer zur Mauer bestimmt die Außenseite; fehlt es oder liegt es längs,
     * gilt vorn (Mauer entlang x) bzw. links (entlang z).
     */
    private void wall(Step s, BlockSpec block, Sink out) throws PlanException {
        Pos a = s.from();
        Pos b = s.to();
        int y0 = a.y();
        boolean alongX = Math.abs(b.x() - a.x()) >= Math.abs(b.z() - a.z());
        Frame f = Frame.line(out, a, direction(b.x() - a.x(), b.z() - a.z()));
        Pos end = f.toFrame(b);
        int len = end.x() + 1;
        int dv = end.z();
        int t = s.width();
        int top = y0 + s.height();
        checkVolume((long) len * (t + Math.abs(dv) + 1) * (s.height() + 2));
        String outer = Orientation.isLocalDirection(s.facing()) ? s.facing() : "";
        if (!outer.equals(f.dir("front")) && !outer.equals(f.dir("back"))) {
            outer = alongX ? "front" : "left";
        }
        // sigma: Richtung der Innenreihen in v (von der Außenseite weg).
        int sigma = outer.equals(f.dir("back")) ? -1 : 1;
        int prev = 0;
        int ladderV = 0;
        for (int u = 0; u < len; u++) {
            int base = len == 1 ? 0 : (int) Math.round((double) dv * u / (len - 1));
            int lo = Math.min(base, u == 0 ? base : prev);
            int hi = Math.max(base, u == 0 ? base : prev);
            for (int row0 = lo; row0 <= hi; row0++) {
                for (int k = 0; k < t; k++) {
                    column(f, u, row0 + sigma * k, y0, top, block);
                }
            }
            if (u % 2 == 0) {
                f.put(u, top + 1, sigma > 0 ? lo : hi, block);
            }
            if (u == 1) {
                // Erste Zelle hinter der innersten Reihe dieser Spalte
                ladderV = (sigma > 0 ? hi : lo) + sigma * t;
            }
            prev = base;
        }
        BlockSpec ladder = firstKnown("ladder");
        if (ladder != null) {
            // Spalte u = 1 (ungerade, ohne Zinne): Bei Dicke 1 stünde über der Leiter in Spalte 0 eine Zinne, und
            // oben käme niemand auf die Mauer (StructuresTest, Wehrgang bei width 1).
            BlockSpec rung = ladder.with("facing", sigma > 0 ? "back" : "front");
            for (int y = y0 + 1; y <= top; y++) {
                f.put(1, y, ladderV, rung);
            }
        }
    }

    // ---------------------------------------------------------------- Brücke

    /**
     * Brücke von from nach to (die beiden Enden des Decks, Deckhöhe from.y) entlang der längeren Achse: Deck mit
     * Geländer auf beiden Seiten (Zaun aus derselben Holzart bzw. Mauer aus demselben Stein), Laternen alle 6 Blöcke.
     *
     * arch: Bogen unter dem Deck, an den Enden height (sonst ein Viertel der Länge, 2 bis 10) tief, in der Mitte nur
     * eine Schicht – die Last geht über die Widerlager an beiden Ufern. beam: flaches Deck auf Pfeilern alle 6 Blöcke
     * (height tief, Vorgabe 4) mit Längsträgern unter den Rändern. Holz ohne style wird beam: Ein Holzbogen über
     * 20 Blöcke ist kein Bauwerk, das ein Spieler erwartet.
     */
    private void bridge(Step s, BlockSpec deck, Sink out) throws PlanException {
        Pos a = s.from();
        Pos b = s.to();
        int yd = a.y();
        Frame f = Frame.line(out, a, direction(b.x() - a.x(), b.z() - a.z()));
        int len = f.toFrame(b).x() + 1;
        int rows = s.width() + 2;
        int c = (rows - 1) / 2;
        String wood = woodOf(deck.id());
        boolean beam = s.style().isEmpty() ? wood != null : s.style().equals("beam");
        int depth = s.height() > 0 ? s.height() : beam ? 4 : Math.clamp(len / 4, 2, 10);
        checkVolume((long) len * rows * (depth + 3));
        if (beam) {
            BlockSpec log = wood == null ? null : firstKnown(logFor(wood));
            BlockSpec pier = log != null ? log.with("axis", "y") : deck;
            BlockSpec girder = log != null ? log.with("axis", "x") : deck;
            for (int u = 0; u < len; u++) {
                boolean pierHere = u == 0 || u == len - 1 || u % SPACING == 0 && len - 1 - u >= 3;
                for (int k = 0; k < rows; k++) {
                    f.put(u, yd, k - c, deck);
                    if (pierHere) {
                        column(f, u, k - c, yd - depth, yd - 1, pier);
                    }
                }
                if (!pierHere) {
                    f.put(u, yd - 1, -c, girder);
                    f.put(u, yd - 1, rows - 1 - c, girder);
                }
            }
        } else {
            double half = (len - 1) / 2.0;
            for (int u = 0; u < len; u++) {
                double q = (u - half) / half;
                int under = (int) Math.round(depth * q * q);
                for (int k = 0; k < rows; k++) {
                    column(f, u, k - c, yd - 1 - under, yd, deck);
                }
            }
        }
        BlockSpec rail = railFor(deck);
        BlockSpec lantern = firstKnown("lantern");
        for (int u = 0; u < len; u++) {
            f.put(u, yd + 1, -c, rail);
            f.put(u, yd + 1, rows - 1 - c, rail);
            if (lantern != null && (u % SPACING == 0 || u == len - 1)) {
                f.put(u, yd + 2, -c, lantern);
                f.put(u, yd + 2, rows - 1 - c, lantern);
            }
        }
    }

    // ---------------------------------------------------------------- Garten

    /**
     * Garten im Rechteck from..to: Rasen, Weg vom Tor (Mitte der Seite facing) bis nach hinten und ab 9 Tiefe ein
     * Querweg, Blumenbeete auf dunkler Erde, Zaun rundum mit Tor und zwei Laternen, ab 11×11 zwei kleine Bäume
     * hinten. block ist der Zaun; Bretter, Stämme und Stein werden zum passenden Zaun bzw. zur Mauer.
     * Blumen statt Feldfrüchten: Ackerboden ohne Wasser in 4 Blöcken wird wieder zu Erde, und die Saat fällt ab.
     */
    private void garden(Step s, BlockSpec fenceIn, Sink out) throws PlanException {
        int x0 = Math.min(s.from().x(), s.to().x());
        int x1 = Math.max(s.from().x(), s.to().x());
        int z0 = Math.min(s.from().z(), s.to().z());
        int z1 = Math.max(s.from().z(), s.to().z());
        int y0 = Math.min(s.from().y(), s.to().y());
        Frame f = Frame.rect(out, x0, x1, z0, z1, s.facing().isEmpty() ? "front" : s.facing());
        int w = f.width;
        int d = f.depth;
        checkVolume((long) w * d * 3);
        int mid = (w - 1) / 2;
        int midV = (d - 1) / 2;
        boolean cross = d >= 9 && w >= 7;
        BlockSpec fence = fenceFor(fenceIn);
        String wood = woodOf(fence.id());
        BlockSpec gate = firstKnown((wood == null ? "oak" : wood) + "_fence_gate", "oak_fence_gate");
        BlockSpec grass = orElse(firstKnown("grass_block", "dirt"), fence);
        BlockSpec path = orElse(firstKnown("dirt_path", "gravel", "coarse_dirt"), grass);
        BlockSpec soil = orElse(firstKnown("coarse_dirt", "dirt"), grass);
        BlockSpec lantern = firstKnown("lantern");
        List<BlockSpec> flowers = new ArrayList<>();
        for (String id : FLOWERS) {
            BlockSpec fl = firstKnown(id);
            if (fl != null) {
                flowers.add(fl);
            }
        }
        boolean margin = w >= 9 && d >= 9;
        for (int u = 0; u < w; u++) {
            for (int v = 0; v < d; v++) {
                boolean ring = u == 0 || u == w - 1 || v == 0 || v == d - 1;
                boolean onPath = gardenPath(u, v, w, d, mid, midV, cross);
                boolean bed = !ring && !onPath && !(margin && (u == 1 || u == w - 2 || v == 1 || v == d - 2));
                f.put(u, y0, v, onPath || ring && u == mid && v == 0 ? path : bed ? soil : grass);
                if (ring) {
                    f.put(u, y0 + 1, v, u == mid && v == 0 && gate != null ? gate.with("facing", "front") : fence);
                } else if (bed && !flowers.isEmpty()) {
                    f.put(u, y0 + 1, v, flowers.get(Math.floorMod(u + 2 * v, flowers.size())));
                }
            }
        }
        if (lantern != null) {
            f.put(mid - 1, y0 + 2, 0, lantern);
            f.put(mid + 1, y0 + 2, 0, lantern);
        }
        if (w >= 11 && d >= 11) {
            // Krone Radius 2 um u = 2 bzw. w − 3: bleibt im Rechteck und über Zaun (y0+1) und Laternen (y0+2).
            String treeWood = wood == null ? "oak" : wood;
            growTree(out, f.at(2, y0, d - 3), 5, false, treeWood, grass);
            growTree(out, f.at(w - 3, y0, d - 3), 5, false, treeWood, grass);
        }
    }

    private static boolean gardenPath(int u, int v, int w, int d, int mid, int midV, boolean cross) {
        return u == mid && v >= 1 && v <= d - 2 || cross && v == midV && u >= 1 && u <= w - 2;
    }

    // ---------------------------------------------------------------- Baum

    /**
     * Baum bei pos (Bodenzelle unter dem Stamm): Gesamthöhe height (Vorgabe 7, Nadelbaum 9), block = Holzart
     * (Stamm, Bretter oder Laub derselben Art). round = Laubbaum mit ovaler Krone, cone = Nadelbaum in Quirlen, die
     * nach oben schmaler werden. Ab 14 ein 2×2-Stamm. Laub immer persistent – von Hand gesetztes Laub zerfällt sonst
     * nach Sekunden, sobald kein Stamm in 6 Blöcken steht.
     */
    private void tree(Step s, int n, BlockSpec block, Sink out, List<String> warnings) throws PlanException {
        String wood = woodOf(block.id());
        if (wood == null) {
            // Vorher still Eiche: Der Materialwunsch („Baum aus Stein“) ging ohne Hinweis verloren.
            warnings.add("Schritt " + n + " (tree): " + block.id() + " ist keine Holzart, gebaut als Eiche");
            wood = "oak";
        }
        boolean cone = s.style().isEmpty() ? wood.equals("spruce") : s.style().equals("cone");
        int h = s.height() > 0 ? s.height() : cone ? 9 : 7;
        growTree(out, s.pos(), h, cone, wood, block);
    }

    private void growTree(Sink out, Pos base, int h, boolean cone, String wood, BlockSpec fallback)
            throws PlanException {
        BlockSpec log = firstKnown(logFor(wood), "oak_log");
        log = log != null ? log.with("axis", "y") : fallback;
        BlockSpec leaves = firstKnown(leavesFor(wood), "oak_leaves");
        leaves = leaves == null ? fallback : leaves.id().endsWith("_leaves") ? leaves.with("persistent", "true")
                : leaves;
        BlockSpec dirt = orElse(firstKnown("dirt"), log);
        boolean thick = h >= THICK_TRUNK_HEIGHT;
        int tw = thick ? 2 : 1;
        double cx = base.x() + (tw - 1) / 2.0;
        double cz = base.z() + (tw - 1) / 2.0;
        int y0 = base.y();
        int trunkTop;
        if (cone) {
            int bottom = y0 + Math.max(2, h / 4);
            int rb = Math.clamp(h / 4 + (thick ? 1 : 0), 1, 6);
            int span = y0 + h - bottom;
            for (int y = bottom; y <= y0 + h; y++) {
                double rr = span == 0 ? 0 : rb * (double) (y0 + h - y) / span;
                // Quirle: jede zweite Schicht schmaler, wie bei einer Fichte im Spiel
                if ((y - bottom) % 2 == 1) {
                    rr = Math.max(0, rr - 0.7);
                }
                disc(out, cx, cz, y, rr, leaves);
            }
            trunkTop = y0 + h - 1;
        } else {
            int rc = Math.clamp((h + 1) / 3 + (thick ? 1 : 0), 2, 7);
            int ry = Math.max(1, Math.min(rc, (h - 2) / 2));
            int yc = y0 + h - ry;
            int reach = rc + 1;
            for (int y = yc - ry; y <= yc + ry; y++) {
                for (int x = (int) Math.floor(cx) - reach; x <= (int) Math.ceil(cx) + reach; x++) {
                    for (int z = (int) Math.floor(cz) - reach; z <= (int) Math.ceil(cz) + reach; z++) {
                        double ex = (x - cx) / (rc + 0.5);
                        double ey = (y - yc) / (ry + 0.5);
                        double ez = (z - cz) / (rc + 0.5);
                        if (ex * ex + ey * ey + ez * ez <= 1.0) {
                            out.put(new Pos(x, y, z), leaves);
                        }
                    }
                }
            }
            trunkTop = yc;
        }
        for (int dx = 0; dx < tw; dx++) {
            for (int dz = 0; dz < tw; dz++) {
                out.put(base.add(dx, 0, dz), dirt);
                for (int y = y0 + 1; y <= trunkTop; y++) {
                    out.put(new Pos(base.x() + dx, y, base.z() + dz), log);
                }
            }
        }
    }

    /** Waagrechte Laubscheibe; rr² + 0.5 hält bei Radius 0 genau die Stammsäule(n) (Abstand 0 bzw. 0,5²+0,5²). */
    private static void disc(Sink out, double cx, double cz, int y, double rr, BlockSpec leaves)
            throws PlanException {
        int reach = (int) Math.ceil(rr) + 1;
        for (int x = (int) Math.floor(cx) - reach; x <= (int) Math.ceil(cx) + reach; x++) {
            for (int z = (int) Math.floor(cz) - reach; z <= (int) Math.ceil(cz) + reach; z++) {
                double dx = x - cx;
                double dz = z - cz;
                if (dx * dx + dz * dz <= rr * rr + 0.5) {
                    out.put(new Pos(x, y, z), leaves);
                }
            }
        }
    }

    // ---------------------------------------------------------------- Material

    /** Holzart eines Blocks (spruce_planks → spruce, stripped_oak_log → oak), sonst null. */
    static String woodOf(String id) {
        String bare = id.startsWith("stripped_") ? id.substring("stripped_".length()) : id;
        for (String wood : WOODS) {
            if (bare.startsWith(wood + "_")) {
                return wood;
            }
        }
        return null;
    }

    private static String logFor(String wood) {
        return switch (wood) {
            case "crimson", "warped" -> wood + "_stem";
            case "bamboo" -> "bamboo_block";
            default -> wood + "_log";
        };
    }

    private static String leavesFor(String wood) {
        return switch (wood) {
            case "crimson" -> "nether_wart_block";
            case "warped" -> "warped_wart_block";
            case "bamboo" -> "oak_leaves";
            default -> wood + "_leaves";
        };
    }

    /**
     * Geländer zum Deckmaterial: Holz → Zaun derselben Art, Stein → Mauer desselben Steins (stone_bricks →
     * stone_brick_wall), sonst Bruchsteinmauer. Gibt es nichts davon, bleibt das Deckmaterial (eine Brüstung).
     */
    private BlockSpec railFor(BlockSpec deck) {
        String id = deck.id();
        if (id.endsWith("_fence") || id.endsWith("_wall")) {
            return BlockSpec.of(id);
        }
        String wood = woodOf(id);
        if (wood != null) {
            return orElse(firstKnown(wood + "_fence", "oak_fence"), deck);
        }
        List<String> candidates = new ArrayList<>();
        if (id.endsWith("_bricks")) {
            candidates.add(id.substring(0, id.length() - 1) + "_wall");
        }
        if (id.endsWith("_block")) {
            candidates.add(id.substring(0, id.length() - "_block".length()) + "_wall");
        }
        if (id.equals("bricks")) {
            candidates.add("brick_wall");
        }
        candidates.add(id + "_wall");
        candidates.add("cobblestone_wall");
        candidates.add("oak_fence");
        return orElse(firstKnown(candidates.toArray(String[]::new)), deck);
    }

    /** Zaun des Gartens: Zaun oder Mauer wie angegeben, sonst wie das Brückengeländer abgeleitet. */
    private BlockSpec fenceFor(BlockSpec block) {
        return railFor(block);
    }

    // ---------------------------------------------------------------- Hilfen

    /** Erster bekannter, nicht eingeschränkter Block ohne Zustände – Zubehör, das die Form selbst wählt (wie house). */
    private BlockSpec firstKnown(String... ids) {
        for (String id : ids) {
            if (!BlockRules.isRestricted(id) && catalog.isBlock(id)) {
                return BlockSpec.of(id);
            }
        }
        return null;
    }

    private static BlockSpec orElse(BlockSpec spec, BlockSpec other) {
        return spec != null ? spec : other;
    }

    private void checkVolume(long volume) throws PlanException {
        if (volume > (long) limits.maxBlocks() * 2) {
            throw new PlanException("Ein Schritt ist zu groß (" + volume + " Blöcke, erlaubt "
                    + limits.maxBlocks() + ").");
        }
    }

    /** Lokale Richtung des längeren Achsenanteils von (dx, dz): +x right, −x left, +z back, −z front. */
    static String direction(int dx, int dz) {
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? "right" : "left";
        }
        return dz > 0 ? "back" : "front";
    }

    /** Lokale Richtung nach quarterTurns Viertelumdrehungen – dieselbe Drehung wie für die Blockzustände. */
    private static String turn(String dir, int quarterTurns) {
        return Orientation.rotateLocal(BlockSpec.of("air").with("facing", dir), quarterTurns).props().get("facing");
    }

    /**
     * Rahmen einer Form: (u, y, v) → lokale Planposition, Blockzustände mitgedreht. Ohne Drehung ist u = +x und
     * v = +z; turns Viertelumdrehungen drehen beides wie Orientation.rotateLocal ein Bauteil.
     */
    private static final class Frame {
        private final Sink out;
        private final int turns;
        private final int ox;
        private final int oz;
        final int width;
        final int depth;

        private Frame(Sink out, int turns, int ox, int oz, int width, int depth) {
            this.out = out;
            this.turns = turns;
            this.ox = ox;
            this.oz = oz;
            this.width = width;
            this.depth = depth;
        }

        /** Rechteck x0..x1/z0..z1, Torseite side bei v = 0, u läuft an ihr entlang. */
        static Frame rect(Sink out, int x0, int x1, int z0, int z1, String side) {
            int turns = Orientation.quarterTurnsFor(side);
            boolean odd = turns % 2 == 1;
            int width = odd ? z1 - z0 + 1 : x1 - x0 + 1;
            int depth = odd ? x1 - x0 + 1 : z1 - z0 + 1;
            int minX = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            for (int u : new int[] {0, width - 1}) {
                for (int v : new int[] {0, depth - 1}) {
                    Pos r = Orientation.rotateLocal(new Pos(u, 0, v), turns);
                    minX = Math.min(minX, r.x());
                    minZ = Math.min(minZ, r.z());
                }
            }
            return new Frame(out, turns, x0 - minX, z0 - minZ, width, depth);
        }

        /** Linie ab start: u zeigt in die lokale Richtung along, v quer dazu. */
        static Frame line(Sink out, Pos start, String along) {
            // Ohne Drehung zeigt u nach right; rotateLocal dreht right → front → left → back.
            int turns = switch (along) {
                case "front" -> 1;
                case "left" -> 2;
                case "back" -> 3;
                default -> 0;
            };
            return new Frame(out, turns, start.x(), start.z(), 0, 0);
        }

        Pos at(int u, int y, int v) {
            Pos r = Orientation.rotateLocal(new Pos(u, y, v), turns);
            return new Pos(r.x() + ox, y, r.z() + oz);
        }

        /** Gegenstück zu at (ohne y): Planposition → (u, y, v). */
        Pos toFrame(Pos p) {
            return Orientation.rotateLocal(new Pos(p.x() - ox, p.y(), p.z() - oz), 4 - turns);
        }

        /** Planrichtung der Rahmenrichtung dir (front = −v, back = +v, right = +u, left = −u). */
        String dir(String frameDir) {
            return turn(frameDir, turns);
        }

        void put(int u, int y, int v, BlockSpec spec) throws PlanException {
            out.put(at(u, y, v), Orientation.rotateLocal(spec, turns));
        }
    }
}
