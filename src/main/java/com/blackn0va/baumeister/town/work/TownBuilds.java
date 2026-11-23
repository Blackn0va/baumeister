package com.blackn0va.baumeister.town.work;

import com.blackn0va.baumeister.plan.BlockCatalog;
import com.blackn0va.baumeister.plan.CompiledBuild;
import com.blackn0va.baumeister.plan.Orientation;
import com.blackn0va.baumeister.plan.Orientation.Cardinal;
import com.blackn0va.baumeister.plan.PlanCompiler;
import com.blackn0va.baumeister.plan.PlanException;
import com.blackn0va.baumeister.plan.PlanParser;
import com.blackn0va.baumeister.plan.Pos;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;

/**
 * Was Bewohner in einem Baufeld bauen und wo (Vertrag v013, AK-45). Nur die Wahl – gebaut wird über denselben Weg wie
 * jeder Bau des Baumeisters (BuildService.startTown → BuildJob: Rechteprüfung je Block, Stufen, Rückgängig, Baubuch).
 *
 * Kleinbauten aus den vorhandenen Plan-Formen (line, set, fill, walls, door, roof, garden), kein eigener Bauweg und
 * kein eigenes Format: Jeder Eintrag ist ein Bauplan im Format des LLM, geht durch PlanParser und PlanCompiler und wird
 * im Plugin noch einmal genau so übersetzt. Kein Haus über op house (Einrichtung mit Kiste und Ofen – Bewohner setzen
 * nie Behälter, Vertrag v013 Nicht-Ziele), keine Bauteile aus der Bibliothek (Redstone).
 *
 * Überbau-Schutz hier schon auf den echten Zellen, VOR dem Bau: Jede Zelle über dem Boden muss Luft oder eine
 * Wiesenpflanze sein, jede Zelle im Boden (Beet) natürlicher Boden, rundum ein Block Abstand frei, keine Behälter und
 * kein Redstone daneben (Safety), alles im Feld. Der Bau prüft danach noch einmal mit PlanReview.checkOverbuild wie
 * jeder Bau – zwei Stufen, damit eine Lücke in einer nicht reicht. Wer die Prüfung hier lockert, lässt Bewohner Zäune
 * in Weizen und Hütten an Hauswände setzen.
 *
 * Threads: zustandslos bis auf den Zellen-Puffer (Hauptthread). Fehler: Ein Katalogeintrag, der nicht übersetzt,
 * fällt heraus (KindsTest hält alle übersetzbar); plan() liefert dann eben eine andere Art oder nichts.
 */
public final class TownBuilds {

    /** Präfix im Baubuch (BuildLedger): Daran erkennt TownLife.builds die Bauten der Bewohner (keine Doppelorte). */
    public static final String ORDER_PREFIX = "stadtleben:";
    /** So viele Ecken prüft plan() je Aufruf höchstens – der Rest beim nächsten Mal ab cursor. */
    public static final int CANDIDATES_PER_CALL = 24;
    /** Raster der Ecken: jede dritte Spalte – ein Bau ist mindestens 1 breit plus 1 Abstand je Seite. */
    static final int STRIDE = 3;
    /** Wiesenpflanzen, die ein Bau ersetzen darf wie Luft. */
    static final Set<String> REPLACEABLE = Set.of("air", "cave_air", "short_grass", "tall_grass", "fern", "large_fern",
            "dandelion", "poppy", "cornflower", "oxeye_daisy", "azure_bluet", "allium", "blue_orchid", "red_tulip",
            "orange_tulip", "white_tulip", "pink_tulip", "lily_of_the_valley", "short_dry_grass", "tall_dry_grass",
            "bush", "leaf_litter", "wildflowers");

    /** Die Kleinbauten; plan = Bauplan wie vom LLM (lokal: x rechts, y=0 erste Luftschicht, z von vorn weg). */
    public enum Kind {
        ZAUN("ein Zaun", """
                {"design":"zaun","reply":"","steps":[{"op":"line","block":"oak_fence","from":[0,0,0],"to":[4,0,0]}]}
                """),
        LATERNE("eine Laterne", """
                {"design":"laterne","reply":"","steps":[
                {"op":"line","block":"oak_fence","from":[0,0,0],"to":[0,1,0]},
                {"op":"set","block":"lantern","pos":[0,2,0]}]}
                """),
        BEET("ein Gartenbeet", """
                {"design":"beet","reply":"","steps":[
                {"op":"garden","block":"oak_fence","from":[0,-1,0],"to":[4,-1,4],"facing":"front"}]}
                """),
        MARKTSTAND("ein Marktstand", """
                {"design":"marktstand","reply":"","steps":[
                {"op":"line","block":"oak_fence","from":[0,0,0],"to":[0,1,0]},
                {"op":"line","block":"oak_fence","from":[2,0,0],"to":[2,1,0]},
                {"op":"line","block":"oak_fence","from":[0,0,2],"to":[0,1,2]},
                {"op":"line","block":"oak_fence","from":[2,0,2],"to":[2,1,2]},
                {"op":"set","block":"spruce_planks","pos":[1,0,0]},
                {"op":"fill","block":"spruce_slab","from":[0,2,0],"to":[2,2,2]}]}
                """),
        BRUNNEN("ein Brunnen", """
                {"design":"brunnen","reply":"","steps":[
                {"op":"walls","block":"cobblestone","from":[0,0,0],"to":[2,0,2]},
                {"op":"set","block":"water","pos":[1,0,1]},
                {"op":"line","block":"oak_fence","from":[0,1,0],"to":[0,2,0]},
                {"op":"line","block":"oak_fence","from":[2,1,2],"to":[2,2,2]},
                {"op":"fill","block":"oak_slab","from":[0,3,0],"to":[2,3,2]}]}
                """),
        // Bezeichner und Schlüssel in der Felddatei ASCII (umlaut-ok), der Text mit Umlaut.
        HUETTE("eine Hütte", """
                {"design":"hütte","reply":"","steps":[
                {"op":"fill","block":"spruce_planks","from":[0,0,0],"to":[4,0,4]},
                {"op":"walls","block":"oak_planks","from":[0,1,0],"to":[4,3,4]},
                {"op":"door","block":"oak_door","pos":[2,1,0],"facing":"front"},
                {"op":"set","block":"glass_pane","pos":[0,2,2]},
                {"op":"set","block":"glass_pane","pos":[4,2,2]},
                {"op":"roof","block":"spruce_stairs","from":[0,4,0],"to":[4,4,4],"shape":"gable","overhang":0,
                 "gable_block":"oak_planks"}]}
                """);

        private final String german;
        private final String plan;

        Kind(String german, String plan) {
            this.german = german;
            this.plan = plan.strip();
        }

        /** „ein Marktstand“ – für Chat, Log und Baubuch. */
        public String german() {
            return german;
        }

        /** Der Bauplan (JSON wie vom LLM). */
        public String plan() {
            return plan;
        }

        /** Name im Log und in der Datei („marktstand“). */
        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static Kind of(String key) {
            for (Kind k : values()) {
                if (k.key().equals(key)) {
                    return k;
                }
            }
            return null;
        }
    }

    /** Ein lokaler Block des übersetzten Plans: Position und was hin soll (Material ohne Zustand). */
    record Cell(Pos pos, String type) {
    }

    /**
     * Ein Bauauftrag: Ecke (x, y, z) = lokale (0,0,0), Blickrichtung forward (lokales +z, die Vorderseite zeigt zur
     * Feldmitte), cells = alle gesetzten Weltblöcke als {x, y, z}. Der Auftrag ist so geprüft, wie er gebaut wird.
     */
    public record Order(String field, Kind kind, String world, int x, int y, int z, Cardinal forward,
            List<int[]> cells) {

        public Order {
            cells = List.copyOf(cells);
        }

        /** Text fürs Baubuch und den Rückgängig-Stapel: „stadtleben: ein Marktstand (Feld acker)“. */
        public String orderText() {
            return ORDER_PREFIX + " " + kind.german() + " (Feld " + field + ")";
        }

        /** Waagrechte Ausdehnung {minX, minZ, maxX, maxZ} der Zellen. */
        public int[] box() {
            int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (int[] c : cells) {
                b[0] = Math.min(b[0], c[0]);
                b[1] = Math.min(b[1], c[2]);
                b[2] = Math.max(b[2], c[0]);
                b[3] = Math.max(b[3], c[2]);
            }
            return b;
        }
    }

    /**
     * Ein fertiger Bau im Feld (persistiert in stadtleben-felder.json). Merkstelle (ax, ay, az) mit dem Block, der dort
     * nach dem Bau stand: Steht er nicht mehr (Rückgängig, abgerissen), zählt der Bau nicht mehr – die Obergrenze gibt den
     * Platz frei, und er ist kein Arbeitsort mehr (prune).
     */
    public record Built(Kind kind, int minX, int y, int minZ, int maxX, int maxZ, int ax, int ay, int az,
            String anchorType, long at) {

        /** Steht der Bau noch? Ungeladen zählt als „ja“ (nicht prüfbar heißt nicht weg). */
        public boolean standing(Plot p) {
            String t = p.type(ax, ay, az);
            return "unloaded".equals(t) || anchorType.equals(t);
        }
    }

    /** Ergebnis einer Suche: Auftrag (null = keiner gefunden) und wo die nächste Suche weitermacht. */
    public record Found(Order order, int cursor) {
    }

    private final Map<Kind, List<Cell>> cells = new EnumMap<>(Kind.class);

    /**
     * catalog: welche Block-IDs es gibt (im Plugin BukkitBlockCatalog, im Test eine feste Liste). Übersetzt wird mit
     * kleinen Grenzen – ein Eintrag, der sie sprengt, ist ein Fehler im Katalog und fällt heraus.
     */
    public TownBuilds(BlockCatalog catalog) {
        PlanCompiler compiler = new PlanCompiler(catalog, "oak_planks", new PlanCompiler.Limits(400, 12));
        for (Kind k : Kind.values()) {
            try {
                CompiledBuild b = compiler.compile(PlanParser.parse(k.plan()));
                List<Cell> out = new ArrayList<>();
                for (CompiledBuild.Action a : b.actions()) {
                    for (CompiledBuild.Change c : a.changes()) {
                        if (!c.block().id().equals("air")) {
                            out.add(new Cell(c.pos(), c.block().id()));
                        }
                    }
                }
                if (!out.isEmpty()) {
                    cells.put(k, List.copyOf(out));
                }
            } catch (PlanException e) {
                java.util.logging.Logger.getLogger("Baumeister").warning(() -> "stadtleben | bauen_katalog_fehler | art="
                        + k.key() + " grund=" + e.getMessage());
            }
        }
    }

    /** Arten, die übersetzt haben (Test: alle). */
    public Set<Kind> kinds() {
        return cells.keySet();
    }

    /** Was an den Zellen einer Art hin soll, in der Reihenfolge von Order.cells (Test-Welt, Messung). */
    public List<String> types(Kind k) {
        return cells.getOrDefault(k, List.of()).stream().map(Cell::type).toList();
    }

    /** Anzahl Blöcke einer Art (Status, Messung). */
    public int blocks(Kind k) {
        return cells.getOrDefault(k, List.of()).size();
    }

    /**
     * Grund, warum in diesem Feld jetzt nicht gebaut wird, oder null = darf. Reihenfolge = Schutzstellen, alle
     * fail-closed: Schalter, Weltänderung, Freigabe des Feldes, ein laufender Bau, Obergrenze, Pause.
     */
    public static String gate(BuildSettings s, boolean worldChange, Field f, boolean running, int standing,
            long now, long nextAt) {
        if (!s.enabled()) {
            return "aus (stadtleben.bauen.an)";
        }
        if (!worldChange) {
            return "aus (stadtleben.weltänderung)";
        }
        if (f == null || !f.build()) {
            return "kein Baufeld";
        }
        if (running) {
            return "ein Bau läuft schon";
        }
        if (standing >= s.perField()) {
            return "Obergrenze " + s.perField() + " erreicht";
        }
        if (now < nextAt) {
            return "Pause";
        }
        return null;
    }

    /**
     * Nächsten Bau im Feld suchen: Art reihum nach Anzahl der stehenden Bauten (abwechslungsreich), Ecken im Raster ab
     * cursor, höchstens CANDIDATES_PER_CALL Ecken je Aufruf. taken: belegte Blöcke (Task.Change.key) anderer Arbeiten.
     */
    public Found plan(Field f, Plot p, List<Built> standing, int cursor, LongPredicate taken) {
        List<Kind> order = new ArrayList<>();
        Kind[] all = Kind.values();
        for (int i = 0; i < all.length; i++) {
            Kind k = all[Math.floorMod(standing.size() + i, all.length)];
            if (cells.containsKey(k)) {
                order.add(k);
            }
        }
        if (order.isEmpty()) {
            return new Found(null, cursor);
        }
        int side = (2 * f.radius()) / STRIDE + 1;
        int grid = side * side;
        int start = Math.floorMod(cursor, grid);
        int count = Math.min(CANDIDATES_PER_CALL, grid);
        // Art außen, Ecken und Richtungen innen: Vorher gewann die erste Ecke, an der IRGENDETWAS passte – und da große
        // Bauten von der Mitte weg wuchsen, über den Feldrand hinaus. Live 20 min (T-037, Lauf 1): 7 Bauten, nur Zaun,
        // Laterne, Marktstand; Beet, Brunnen und Hütte nie. Wer die Schleifen zurücktauscht, bekommt ein Laternendorf.
        for (Kind k : order) {
            for (int n = 0; n < count; n++) {
                int c = (start + n) % grid;
                int bx = f.x() - f.radius() + (c % side) * STRIDE;
                int bz = f.z() - f.radius() + (c / side) * STRIDE;
                Cardinal away = away(f, bx, bz);
                for (Cardinal forward : new Cardinal[] {away, away.clockwise(), away.opposite(),
                        away.opposite().clockwise()}) {
                    Order o = fit(f, p, k, bx, bz, forward, taken);
                    if (o != null) {
                        return new Found(o, c + 1);
                    }
                }
            }
        }
        return new Found(null, start + count);
    }

    /** Passt die Art mit Ecke (bx, bz) ins Feld? Blickrichtung von der Feldmitte weg (Vorderseite zur Mitte). */
    Order fit(Field f, Plot p, Kind k, int bx, int bz, LongPredicate taken) {
        return fit(f, p, k, bx, bz, away(f, bx, bz), taken);
    }

    /** Wie oben mit fester Blickrichtung forward (lokales +z). */
    Order fit(Field f, Plot p, Kind k, int bx, int bz, Cardinal forward, LongPredicate taken) {
        Integer ground = ground(f, p, bx, bz);
        if (ground == null) {
            return null;
        }
        Orientation o = new Orientation(forward);
        int y0 = ground + 1;
        List<int[]> world = new ArrayList<>();
        java.util.Set<Long> columns = new java.util.HashSet<>();
        for (Cell cell : cells.get(k)) {
            Pos w = o.toWorldOffset(cell.pos());
            int x = bx + w.x();
            int y = y0 + w.y();
            int z = bz + w.z();
            if (!f.contains(f.world(), x, y, z)) {
                return null;
            }
            String now = p.type(x, y, z);
            boolean below = y <= ground;
            if (below ? !Safety.GROUND.contains(now) : !REPLACEABLE.contains(now)) {
                return null;
            }
            if (Safety.nearForbidden(p, x, y, z) || taken.test(key(x, y, z))) {
                return null;
            }
            world.add(new int[] {x, y, z});
            columns.add(((long) x << 32) | (z & 0xFFFFFFFFL));
        }
        // Eben und rundum frei: jede Spalte samt einem Block Rand hat den Boden auf derselben Höhe und darüber
        // wachsen höchstens Wiesenpflanzen. So steht kein Bau am Hang, auf einer Kante oder an einem anderen Bau, und
        // ein Weg rundum bleibt frei.
        java.util.Set<Long> ring = new java.util.HashSet<>();
        for (long col : columns) {
            int x = (int) (col >> 32);
            int z = (int) col;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    ring.add(((long) (x + dx) << 32) | ((z + dz) & 0xFFFFFFFFL));
                }
            }
        }
        for (long col : ring) {
            int x = (int) (col >> 32);
            int z = (int) col;
            if (Math.abs(x - f.x()) > f.radius() || Math.abs(z - f.z()) > f.radius()) {
                return null;
            }
            if (!Safety.GROUND.contains(p.type(x, ground, z)) || !REPLACEABLE.contains(p.type(x, y0, z))) {
                return null;
            }
            if (!columns.contains(col) && (!REPLACEABLE.contains(p.type(x, y0 + 1, z))
                    || Safety.nearForbidden(p, x, y0, z))) {
                return null;
            }
        }
        return new Order(f.name(), k, f.world(), bx, y0, bz, forward, world);
    }

    /** Boden an (x, z): oberster natürlicher Boden mit Luft darüber, höchstens 3 über / DOWN unter der Feldhöhe. */
    static Integer ground(Field f, Plot p, int x, int z) {
        for (int y = f.y() + 3; y >= f.y() - Field.DOWN; y--) {
            String t = p.type(x, y, z);
            if ("unloaded".equals(t)) {
                return null;
            }
            if (Safety.GROUND.contains(t) && REPLACEABLE.contains(p.type(x, y + 1, z))) {
                return y;
            }
            if (!REPLACEABLE.contains(t)) {
                return null;
            }
        }
        return null;
    }

    /** Himmelsrichtung von der Feldmitte zur Ecke (größere Achse); in der Mitte Süden. */
    static Cardinal away(Field f, int x, int z) {
        int dx = x - f.x();
        int dz = z - f.z();
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Cardinal.EAST : Cardinal.WEST;
        }
        return dz < 0 ? Cardinal.NORTH : Cardinal.SOUTH;
    }

    /** Wie Task.Change.key – damit Feldarbeit und Bau dieselbe Belegung teilen. */
    public static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (long) y & 0xFFFL;
    }

    /**
     * Nach dem Bau: der fertige Bau zum Merken, mit der ersten Zelle, an der jetzt das Erwartete steht (Stufe ließ
     * nicht alles setzen → eine andere Zelle). null = nichts davon steht – dann gilt der Bau als nicht gebaut.
     */
    public Built remember(Order o, Plot p, long now) {
        List<Cell> local = cells.get(o.kind());
        if (local == null) {
            return null;
        }
        int[] box = o.box();
        for (int i = 0; i < o.cells().size() && i < local.size(); i++) {
            int[] c = o.cells().get(i);
            String t = p.type(c[0], c[1], c[2]);
            // Nur Zellen über dem Boden: Dort stand vorher Luft oder Gras (fit), nach einem Rückgängig wieder. Eine
            // Bodenzelle des Beets (Grasblock → Grasblock) sähe nach dem Rückgängig genauso aus wie vorher – der Bau
            // zählte dann ewig weiter.
            if (c[1] >= o.y() && !REPLACEABLE.contains(t) && t.equals(local.get(i).type())) {
                return new Built(o.kind(), box[0], o.y(), box[1], box[2], box[3], c[0], c[1], c[2], t, now);
            }
        }
        return null;
    }

    /** Nur die Bauten, die noch stehen. */
    public static List<Built> standing(List<Built> all, Plot p) {
        List<Built> out = new ArrayList<>();
        for (Built b : all) {
            if (b.standing(p)) {
                out.add(b);
            }
        }
        return out;
    }
}
