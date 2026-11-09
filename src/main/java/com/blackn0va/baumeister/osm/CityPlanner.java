package com.blackn0va.baumeister.osm;

import com.blackn0va.baumeister.osm.CityModel.Area;
import com.blackn0va.baumeister.osm.CityModel.Building;
import com.blackn0va.baumeister.osm.CityModel.Line;
import com.blackn0va.baumeister.osm.CityModel.P;
import com.blackn0va.baumeister.osm.CityModel.Road;
import com.blackn0va.baumeister.osm.CityModel.Tree;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stadtmodell → Bauabschnitte mit Blöcken. Rein und deterministisch (gleiche Daten → gleiche Stadt), ohne LLM: Die
 * Geometrie steht in OpenStreetMap, ein Modell würde sie nur verfälschen und Minuten kosten.
 *
 * Höhen relativ zum Bauplatz: y=0 ist die Bodenschicht (Straßen, Wiesen, Wasser, Erdgeschoss-Boden), Gebäude ab y=1.
 * Reihenfolge: erst der Boden in Kacheln von 32×32 (von der Mitte nach außen), dann die Gebäude (ebenso) – so stehen
 * Straßen, bevor Häuser darauf wachsen, und spätere Zellen überschreiben frühere (Haus über Gehweg).
 *
 * Fail-closed bei Größe: mehr als maxCells Blöcke → Ausnahme statt Server-Absturz.
 *
 * Etappen (2026-10-28): Eine ganze Großstadt passt nicht als ein Plan in den Speicher. Mit {@link Clip} plant
 * dieselbe Instanz Ausschnitt für Ausschnitt; die Palette wächst nur an (Indizes bleiben über alle Etappen gültig).
 * Gebäude gehören der Etappe, in der ihr Schwerpunkt liegt; Boden entsteht nur innerhalb des Ausschnitts und nie
 * unter einem Gebäude – sonst überschriebe die Nachbaretappe einen schon gebauten Hausboden mit Wiese.
 * Nicht threadsicher: Etappen nacheinander planen.
 */
public final class CityPlanner {

    /**
     * Ein Block: Position relativ zum Bauplatz, Material als Index in {@link Plan#palette()}.
     *
     * anchor: Säule, auf deren Geländehöhe sich y bezieht, als Versatz (dx, dz) zur eigenen Säule, gepackt je ein Byte
     * ({@link #anchor(int, int)}); 0 = die eigene Säule. Auf flachem Boden spielt das keine Rolle. Auf Gelände
     * (CityJob mit HeightField) folgt jede Bodenzelle ihrer Säule – das Laub eines Baums aber muss am Stamm hängen,
     * sonst zerfiele die Krone am Hang in Stufen. Gepackt statt zwei Feldern: Mit fünf int bleibt ein Cell bei
     * 32 Byte (12 Kopf + 20, wie vorher mit Auffüllung; gerechnet für komprimierte Klassenzeiger, nicht gemessen) –
     * bei Millionen Zellen je Etappe zählt das.
     */
    public record Cell(int x, int y, int z, int mat, int anchor) {

        /** Ohne Anker – die Zelle bezieht sich auf ihre eigene Säule. */
        public Cell(int x, int y, int z, int mat) {
            this(x, y, z, mat, 0);
        }

        public static int anchor(int dx, int dz) {
            return ((dx & 0xff) << 8) | (dz & 0xff);
        }

        public int anchorDx() {
            return (byte) (anchor >> 8);
        }

        public int anchorDz() {
            return (byte) anchor;
        }
    }

    /** Ausschnitt: x0 ≤ x &lt; x1, z0 ≤ z &lt; z1 und höchstens radius Blöcke von der Mitte. */
    public record Clip(int x0, int z0, int x1, int z1, int radius) {

        public static Clip all() {
            return new Clip(Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2, Integer.MAX_VALUE / 2, Integer.MAX_VALUE / 2,
                    Integer.MAX_VALUE / 2);
        }

        public boolean contains(int x, int z) {
            return x >= x0 && x < x1 && z >= z0 && z < z1 && (long) x * x + (long) z * z <= (long) radius * radius;
        }

        boolean overlaps(int ax, int az, int bx, int bz) {
            return bx >= x0 && ax < x1 && bz >= z0 && az < z1;
        }
    }

    /**
     * building: Abschnitt ist ein Gebäude (bzw. Gebäudeteil), kein Boden. Auf Gelände braucht ein Haus EINE Basis –
     * folgte es wie der Boden jeder Säule, stünden seine Wände am Hang in Stufen versetzt (CityJob, TerrainFit).
     *
     * basis: weitere Säulen, deren Gelände für die Basis zählt – der ganze Grundriss der Gebäudegruppe (Kirche samt
     * Turm und Helm als building:part). Vorher (bis 2026-10-28) bestimmte jeder Teil seine Basis allein aus dem eigenen
     * Grundriss: Am Hang stand der Turm auf anderer Höhe als das Schiff, der Helm (kleinerer Grundriss) steckte im
     * Turm oder schwebte darüber. Leer = nur die eigenen Zellen.
     */
    public record Section(String label, int cx, int cz, List<Cell> cells, boolean building, List<P> basis) {

        /** Bodenabschnitt. */
        public Section(String label, int cx, int cz, List<Cell> cells) {
            this(label, cx, cz, cells, false, List.of());
        }

        /** Abschnitt ohne Gebäudegruppe. */
        public Section(String label, int cx, int cz, List<Cell> cells, boolean building) {
            this(label, cx, cz, cells, building, List.of());
        }
    }

    /**
     * signs: Schilder dieser Etappe mit Text. Der Text ist Blockzustand (Schild-Blockentität), kein BlockData – CityJob
     * setzt nur BlockData; der Schild-Block selbst steht wie jeder andere in den Zellen, den Text schreibt danach
     * SignWriter auf dem Hauptthread.
     */
    public record Plan(List<String> palette, List<Section> sections, int buildings, int roads, long blocks,
            int minX, int minZ, int maxX, int maxZ, List<Sign> signs) {
        // Bewusst kein Konstruktor ohne signs mehr: StagePrep baute den Plan darüber neu und verlor die Schilder
        // (2026-10-29, Straßenschilder ohne Text). Wer einen Plan neu baut, muss die Schilder ausdrücklich weitergeben.
    }

    /**
     * Ein Schild mit Text: Lage wie eine Zelle (relativ zum Bauplatz, auf Gelände nach TerrainFit absolut), block =
     * BlockData des Schilds (zum Wiedererkennen), lines = höchstens 4 Zeilen, auf beiden Seiten gleich.
     */
    public record Sign(int x, int y, int z, String block, List<String> lines) {
    }

    private static final int TILE = 32;
    private static final int LEVEL = 3;
    private static final String[] WALLS = {"white_terracotta", "light_gray_terracotta", "bricks", "smooth_sandstone",
        "white_concrete", "yellow_terracotta", "stone_bricks", "terracotta"};
    private static final String[] ROOFS = {"deepslate_tiles", "red_nether_bricks", "brown_terracotta",
        "dark_oak_planks", "polished_blackstone_bricks"};

    /** pavedBy: kein Weg. */
    static final long NONE = Long.MIN_VALUE;
    /** pavedBy: mehrere Wege (Kreuzung). */
    static final long MULTI = Long.MIN_VALUE + 1;
    /**
     * Rand um den Ausschnitt, in dem Häuser, Wege, Wasser und Brücken vollständig bekannt sind (Laternen, Schilder,
     * Bäume prüfen dort). 24: Ein Schilderpfosten liegt höchstens 11 Blöcke vom Kreuzungspunkt, beide Etappen einer
     * Grenze müssen alle Kandidaten gleich bewerten (StreetFurniture).
     */
    static final int MARGIN = 24;

    /**
     * Küste für den Weltbau (shore(true), 2026-11-01): Marker statt Blöcken, weil Meer am MEERESSPIEGEL liegt und nicht
     * relativ zur Geländeoberkante wie jede andere Bodenzelle. TerrainFit löst sie mit Höhenfeld und Meeresspiegel auf:
     * SEA_MARK − Tiefe → Meeresboden aus Sand, Wasser bis zum Meeresspiegel, Land darüber weg; BEACH_MARK → Sand, wenn
     * die Säule höchstens BEACH_RISE über dem Meer liegt. Spätere Bodenzellen (Parks, Straßen, OSM-Wasser) überschreiben
     * die Marker wie jede andere Fläche. /bm stadt (Flachwelt) bekommt keine Marker – dort gibt es kein Meer.
     */
    public static final int BEACH_MARK = -2;
    public static final int SEA_MARK = -100;
    public static final int MAX_SEA_DEPTH = 12;
    /** Strand: Landzellen bis zu so vielen Zellen vom Meer. */
    static final int BEACH_WIDTH = 3;
    /**
     * Grube am Ufer (eine Lage unter dem Boden, y = −1, damit Flächen und Wege den Marker nicht überschreiben): Liegt
     * eine Landsäule so nah am Meer tiefer als BEACH_RISE unter dem Meeresspiegel, schüttet TerrainFit sie auf und
     * setzt die übrigen Zellen der Säule auf die neue Höhe. Gemessen 2026-11-01, Kachel 15067/5258 (Sydney,
     * RegionRenderProbe): 23 Landproben an der Uferlinie in Gruben bis y −200 – kaputte Tiefenpixel am Wasserrand, die
     * der Generator trocken ließ, mit Gras, Sand und Straßen ganz unten.
     */
    public static final int FILL_MARK = -3;
    static final int PIT_WIDTH = 4;

    public static boolean isSeaMark(int m) {
        return m < SEA_MARK && m >= SEA_MARK - MAX_SEA_DEPTH;
    }

    public static int seaDepth(int m) {
        return SEA_MARK - m;
    }

    /** Tiefe aus dem Abstand zum Land: am Ufer 1, alle zwei Zellen einen Block tiefer, höchstens MAX_SEA_DEPTH. */
    static int depthFor(int toLand) {
        return Math.min(MAX_SEA_DEPTH, 1 + Math.max(0, toLand - 1) / 2);
    }

    private boolean shore;

    /** Küste aus natural=coastline planen (nur Weltbau der Erdwelt). */
    public void shore(boolean on) {
        this.shore = on;
    }

    private final List<String> palette = new ArrayList<>();
    private final Map<String, Integer> index = new HashMap<>();
    private Clip clip = Clip.all();
    private Clip margin = Clip.all();
    private final Set<Long> covered = new HashSet<>();
    private final Map<Long, List<P[]>> roadGrid = new HashMap<>();
    /** Wege mit tunnel=building_passage – alle, nicht nur die im Ausschnitt: Ein Haus ragt über die Etappengrenze. */
    private final List<Road> passages = new ArrayList<>();
    /** Zelle → id des Wegs bzw. Gleises darauf (MULTI bei mehreren), im Rand. */
    private final Map<Long, Long> paved = new HashMap<>();
    /** Wasser (Flächen und Gewässerlinien), im Rand. */
    private final Set<Long> wet = new HashSet<>();
    /** Fußabdruck von Brücken (Fahrbahn und Geländer), im Rand. */
    private final Set<Long> elevated = new HashSet<>();
    /** Zäune, Hecken, Mauern – dort wächst kein Baum. */
    private final Set<Long> fenced = new HashSet<>();
    /** Lichte Höhe je Brücke (Weg-id), über alle Etappen gemerkt – siehe Bridges. */
    private final Map<Long, Integer> bridgeHeights = new HashMap<>();
    private Map<Long, Map<Long, Cell>> canvas = new LinkedHashMap<>();
    /** Gebäudegruppen (Teil → Gebäude, Gebäude → Mitglieder samt sich selbst) des Modells groupsOf – siehe groups(). */
    private CityModel groupsOf;
    private Map<Building, Building> partRoot = new IdentityHashMap<>();
    private Map<Building, List<Building>> groupMembers = new IdentityHashMap<>();

    int mat(String block) {
        return index.computeIfAbsent(block, b -> {
            palette.add(b);
            return palette.size() - 1;
        });
    }

    public static Plan plan(CityModel city, long maxCells) {
        return new CityPlanner().plan(city, Clip.all(), maxCells);
    }

    /** Eine Etappe: nur der Ausschnitt clip. Die Instanz behält die Palette für die nächsten Etappen. */
    public Plan plan(CityModel city, Clip clip, long maxCells) {
        this.clip = clip;
        this.margin = new Clip(clip.x0() - MARGIN, clip.z0() - MARGIN, clip.x1() + MARGIN, clip.z1() + MARGIN,
                clip.radius() + MARGIN);
        clearScratch();
        try {
            return build(city, maxCells);
        } finally {
            clearScratch();
        }
    }

    /** Alles, was nur während einer Etappe gilt (Palette und Brückenhöhen bleiben). */
    private void clearScratch() {
        covered.clear();
        roadGrid.clear();
        passages.clear();
        paved.clear();
        wet.clear();
        elevated.clear();
        fenced.clear();
        canvas = new LinkedHashMap<>();
    }

    private Plan build(CityModel city, long maxCells) {
        // Grundflächen aller Gebäude, die in den Ausschnitt ragen – dort kein Boden (siehe Klassenkommentar). covered
        // auch für den Rand: Laternen und Schilder prüfen dort (StreetFurniture), ground() setzt ohnehin nur im
        // Ausschnitt.
        List<Building> own = new ArrayList<>();
        Map<Building, Set<P>> masks = new HashMap<>();
        for (Building b : city.buildings()) {
            int[] bb = bounds(b.outline());
            if (!margin.overlaps(bb[0], bb[1], bb[2], bb[3])) {
                continue;
            }
            Set<P> mask = fill(b.outline(), b.holes(), Clip.all());
            masks.put(b, mask);
            for (P p : mask) {
                covered.add(key(p.x(), p.z()));
            }
            P c = centroid(b.outline());
            if (clip.contains(c.x(), c.z())) {
                own.add(b);
            }
        }
        indexRoads(city.roads());
        for (Road r : city.roads()) {
            if (r.passage()) {
                passages.add(r);
            }
        }
        // Boden: Zellen je Kachel sammeln; spätere Einträge (Straßen nach Flächen) überschreiben frühere.
        Map<Long, Map<Long, Cell>> tiles = canvas;
        if (shore) {
            shoreline(tiles, city);  // zuerst: Flächen und Wege danach überschreiben Meer- und Strandmarker
        }
        List<Tree> forest = new ArrayList<>();
        for (Area a : city.areas()) {
            if (shore && a.kind().equals("sea")) {
                continue;  // Meeresfläche: shoreline() hat sie schon als Meer am Meeresspiegel geplant
            }
            String top = switch (a.kind()) {
                case "water", "sea" -> "water";
                case "sand" -> "sand";
                case "farmland" -> "coarse_dirt";
                case "pitch" -> "lime_concrete";
                case "plaza" -> "polished_andesite";
                case "parking" -> "gray_concrete";
                default -> "grass_block";
            };
            int m = mat(top);
            boolean water = a.kind().equals("water") || a.kind().equals("sea");
            boolean woods = a.kind().equals("forest") || a.kind().equals("forest_needle");
            // Platz: Fugen aus Steinziegeln im 4er-Raster, sonst wirkt eine große Fläche wie eine graue Wüste.
            int seam = a.kind().equals("plaza") ? mat("stone_bricks") : m;
            // Rand 3: Bäume knapp jenseits der Etappengrenze bringen ihr Laub in diese Etappe mit. Wasser im ganzen
            // Rand – Laternen und Schilder müssen dort wissen, wo es nass ist.
            // Mit Löchern: Inseln einer Wasser-Relation bleiben trocken.
            Set<P> cells = fill(a.outline(), a.holes(), water ? margin : new Clip(clip.x0() - 3, clip.z0() - 3,
                    clip.x1() + 3, clip.z1() + 3, clip.radius() + 3));
            if (a.kind().equals("parking")) {
                parking(tiles, cells, m);
                continue;
            }
            for (P p : cells) {
                ground(tiles, p.x(), 0, p.z(),
                        Math.floorMod(p.x(), 4) == 0 || Math.floorMod(p.z(), 4) == 0 ? seam : m);
                if (water) {
                    ground(tiles, p.x(), -1, p.z(), m);
                    wet.add(key(p.x(), p.z()));
                }
                if (woods) {
                    Tree t = forestTree(p.x(), p.z(), a.kind().equals("forest_needle"));
                    if (t != null) {
                        forest.add(t);
                    }
                }
            }
        }
        // Gewässer vor den Straßen: Eine Straße ohne Brückenkennzeichen über einem Bach ist eine Furt bzw. ein
        // Durchlass.
        for (Road r : city.roads()) {
            if (r.kind().equals("waterway")) {
                road(tiles, r);
            }
        }
        for (Road r : city.roads()) {
            if (!r.kind().equals("waterway") && !r.bridge()) {
                road(tiles, r);
            }
        }
        // Reihenfolge der Ausstattung: erst belegen (Gleisbett, Brückenfußabdruck), dann setzen; Bäume nach Wegen und
        // Zäunen – vorher wuchsen Waldbäume VOR den Straßen, die Straße räumte nur y=1..2 frei, und über ihr hing der
        // Rest des Stamms mit Krone in der Luft. Brücken nach den Bäumen (die Fahrbahn räumt ihr Laub), Laternen und
        // Schilder zuletzt (sie brauchen alles andere als belegt).
        LineFeatures.reserveRails(this, city.lines());
        Bridges.reserve(this, city.roads());
        LineFeatures.rails(this, city.lines());
        LineFeatures.barriers(this, city.lines());
        for (Tree t : forest) {
            if (treeSpot(t.x(), t.z())) {
                tree(tiles, t.x(), t.z(), t.kind());
            }
        }
        for (Tree t : city.trees()) {
            if (treeSpot(t.x(), t.z())) {
                tree(tiles, t.x(), t.z(), t.kind());
            }
        }
        Bridges.build(this, city, bridgeHeights);
        Set<Long> reserved = new HashSet<>();
        StreetFurniture.lamps(this, city.roads(), reserved);
        List<Sign> signs = StreetFurniture.signs(this, city.roads(), reserved);
        Map<Building, List<P>> basis = groupBasis(city, own, masks);
        List<Section> sections = new ArrayList<>();
        List<Map.Entry<Long, Map<Long, Cell>>> tileList = new ArrayList<>(tiles.entrySet());
        tileList.sort(Comparator.comparingDouble(e -> dist(tileX(e.getKey()), tileZ(e.getKey()))));
        long total = 0;
        for (Map.Entry<Long, Map<Long, Cell>> e : tileList) {
            List<Cell> cells = new ArrayList<>(e.getValue().values());
            cells.sort(Comparator.comparingInt(Cell::y));
            int tx = tileX(e.getKey());
            int tz = tileZ(e.getKey());
            sections.add(new Section("Boden " + tx + "/" + tz, tx, tz, List.copyOf(cells)));
            total += cells.size();
            if (total > maxCells) {
                throw new IllegalStateException("zu groß: mehr als " + maxCells + " Blöcke");
            }
        }
        List<Building> buildings = own;
        // Gebäudeteile (Türme, Seitenschiffe) nach allen Gebäuden: Sie überschreiben das Grundgebäude an ihrer Stelle.
        buildings.sort(Comparator.<Building, Boolean>comparing(Building::part)
                .thenComparingDouble(b -> dist(centroid(b.outline()).x(), centroid(b.outline()).z())));
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Building b : buildings) {
            List<Cell> cells = building(b, masks.get(b));
            if (cells.isEmpty()) {
                continue;
            }
            P c = centroid(b.outline());
            String label = b.name().isBlank() ? kindLabel(b.kind()) : b.name();
            sections.add(new Section(label, c.x(), c.z(), cells, true, basis.getOrDefault(b, List.of())));
            total += cells.size();
            if (total > maxCells) {
                throw new IllegalStateException("zu groß: mehr als " + maxCells + " Blöcke");
            }
        }
        for (Section s : sections) {
            for (Cell c : s.cells()) {
                minX = Math.min(minX, c.x());
                maxX = Math.max(maxX, c.x());
                minZ = Math.min(minZ, c.z());
                maxZ = Math.max(maxZ, c.z());
            }
        }
        if (sections.isEmpty()) {
            minX = maxX = minZ = maxZ = 0;
        }
        return new Plan(List.copyOf(palette), List.copyOf(sections), own.size(), city.roads().size(), total,
                minX, minZ, maxX, maxZ, List.copyOf(signs));
    }

    /**
     * Gemeinsame Basis-Säulen je Gebäudegruppe (siehe Section.basis): Ein building:part gehört zum Gebäude (kein Teil),
     * das seinen Schwerpunkt enthält – bei Verschachtelung zum kleinsten. Nur Gruppen mit mindestens einem Teil; ein
     * Teil ohne Gebäude behält seinen eigenen Grundriss.
     */
    private Map<Building, List<P>> groupBasis(CityModel city, List<Building> own, Map<Building, Set<P>> masks) {
        Map<Building, List<P>> out = new IdentityHashMap<>();
        Map<Building, Building> roots = groups(city);
        if (roots.isEmpty()) {
            return out;
        }
        Map<Building, List<P>> byRoot = new IdentityHashMap<>();
        for (Building b : own) {
            Building root = b.part() ? roots.get(b) : b;
            List<Building> members = root == null ? null : groupMembers.get(root);
            if (members == null) {
                continue;  // Teil ohne Gebäude oder Gebäude ohne Teile
            }
            List<P> union = byRoot.computeIfAbsent(root, r -> {
                Set<P> all = new HashSet<>();
                for (Building m : members) {
                    Set<P> mask = masks.get(m);
                    all.addAll(mask != null ? mask : fill(m.outline(), m.holes(), Clip.all()));
                }
                return List.copyOf(all);
            });
            out.put(b, union);
        }
        return out;
    }

    /**
     * Teil → Gebäude, einmal je Modell (Etappen einer Kachel teilen sich das Modell, wie bei den Brückenhöhen).
     * Vorher (bis zur Nachbesserung 2026-10-29) suchte jede Etappe für jedes eigene Gebäude über alle Teile deren
     * Gebäude, und jede Suche lief über alle Gebäude – Gebäude × Teile × Gebäude je Etappe. Gemessen am Münchner Cache
     * mit 300 künstlichen Teilen: 57–86 s statt 11–14 s für 25 Etappen, mit 1 000 Teilen nach 5 min nicht fertig
     * (BuildingGroupLoadTest). Jetzt: je Teil eine Suche, je Modell einmal. Wer das wieder je Etappe oder je Gebäude
     * rechnet, legt Weltbau und /bm stadt in 3D-kartierten Großstädten still.
     */
    private Map<Building, Building> groups(CityModel city) {
        if (groupsOf == city) {
            return partRoot;
        }
        partRoot = new IdentityHashMap<>();
        groupMembers = new IdentityHashMap<>();
        for (Building b : city.buildings()) {
            if (!b.part()) {
                continue;
            }
            Building root = parentOf(b, city);
            if (root != null) {
                partRoot.put(b, root);
                groupMembers.computeIfAbsent(root, r -> new ArrayList<>(List.of(r))).add(b);
            }
        }
        groupsOf = city;
        return partRoot;
    }

    /** Kleinstes Gebäude (kein Teil), das den Schwerpunkt des Teils enthält; null ohne. */
    private static Building parentOf(Building part, CityModel city) {
        P c = centroid(part.outline());
        Building best = null;
        double bestArea = Double.MAX_VALUE;
        for (Building b : city.buildings()) {
            if (b.part()) {
                continue;
            }
            int[] bb = bounds(b.outline());
            if (c.x() < bb[0] || c.x() > bb[2] || c.z() < bb[1] || c.z() > bb[3]) {
                continue;
            }
            double a = OsmImport.area(b.outline());
            if (a < bestArea && OsmImport.contains(b.outline(), c)) {
                best = b;
                bestArea = a;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ Zeichenfläche für die Ausstattung

    Clip clip() {
        return clip;
    }

    Clip marginClip() {
        return margin;
    }

    boolean inClip(int x, int z) {
        return clip.contains(x, z);
    }

    boolean inMargin(int x, int z) {
        return margin.contains(x, z);
    }

    /** Ragt die Linie (um pad erweitert) in den Rand? Vorfilter, damit nicht jede Etappe jede Linie rechnet. */
    boolean nearClip(List<P> line, int pad) {
        if (line.isEmpty()) {
            return false;
        }
        int[] b = bounds(line);
        return margin.overlaps(b[0] - pad, b[1] - pad, b[2] + pad, b[3] + pad);
    }

    /** Bodenzelle setzen (nur im Ausschnitt, nie unter einem Gebäude – wie ground()). */
    void put(int x, int y, int z, int m) {
        ground(canvas, x, y, z, m, 0);
    }

    void put(int x, int y, int z, int m, int anchor) {
        ground(canvas, x, y, z, m, anchor);
    }

    /** Frei für Laterne, Pfosten oder Baum: im Rand, kein Haus, kein Wasser, keine Brücke. */
    boolean open(int x, int z) {
        long k = key(x, z);
        return margin.contains(x, z) && !covered.contains(k) && !wet.contains(k) && !elevated.contains(k);
    }

    long pavedBy(int x, int z) {
        return paved.getOrDefault(key(x, z), NONE);
    }

    void pave(int x, int z, long id) {
        paved.merge(key(x, z), id, (a, b) -> a.equals(b) ? a : MULTI);
    }

    boolean wet(int x, int z) {
        return wet.contains(key(x, z));
    }

    boolean elevated(int x, int z) {
        return elevated.contains(key(x, z));
    }

    void markElevated(int x, int z) {
        elevated.add(key(x, z));
    }

    void markFenced(int x, int z) {
        fenced.add(key(x, z));
    }

    /** Baum nur auf freiem Boden: nicht im Weg, Gleis, Wasser, auf einer Brücke oder in einem Zaun. */
    private boolean treeSpot(int x, int z) {
        long k = key(x, z);
        return pavedBy(x, z) == NONE && !wet.contains(k) && !elevated.contains(k) && !fenced.contains(k);
    }

    /**
     * Baum im Wald: ein Kandidat je 6 × 6-Feld an einer Zufallsstelle (aus dem Feld gerechnet, also gleich in jeder
     * Etappe), 70 % davon wachsen. Vorher ein festes Gitter (x·7 + z·13 mod 37) – sah aus wie eine Plantage in
     * Schrägreihen. Stämme liegen so mindestens 3 Blöcke auseinander, die Kronen (±2) berühren sich gerade.
     */
    static Tree forestTree(int x, int z, boolean needle) {
        int gx = Math.floorDiv(x, 6);
        int gz = Math.floorDiv(z, 6);
        int h = mix(gx, gz);
        if (x != gx * 6 + 1 + (h & 3) || z != gz * 6 + 1 + ((h >>> 2) & 3) || (h >>> 4) % 10 >= 7) {
            return null;
        }
        int species = (h >>> 12) % 10;
        String kind = needle ? "spruce" : species < 6 ? "oak" : species < 9 ? "birch" : "spruce";
        return new Tree(x, z, kind);
    }

    /** Fester Streuwert für ein Rasterfeld (nicht negativ). */
    private static int mix(int a, int b) {
        int h = a * 0x9E3779B1 ^ b * 0x85EBCA77;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return h & 0x7fffffff;
    }

    /**
     * Parkplatz: Asphalt (grauer Beton), am Rand Bordstein, Stellplätze 3 Blöcke breit und 5 tief in zwei Reihen mit
     * 6 Blöcken Fahrgasse (Raster 16 in z), Markierung weiß. Darüber zwei Blöcke Luft wie auf Straßen.
     */
    private void parking(Map<Long, Map<Long, Cell>> tiles, Set<P> cells, int asphalt) {
        int curb = mat("smooth_stone");
        int line = mat("white_concrete");
        int air = mat("air");
        for (P p : cells) {
            boolean edge = !cells.contains(new P(p.x() + 1, p.z())) || !cells.contains(new P(p.x() - 1, p.z()))
                    || !cells.contains(new P(p.x(), p.z() + 1)) || !cells.contains(new P(p.x(), p.z() - 1));
            int row = Math.floorMod(p.z(), 16);
            boolean marking = Math.floorMod(p.x(), 3) == 0 && (row < 5 || row >= 11);
            ground(tiles, p.x(), 0, p.z(), edge ? curb : marking ? line : asphalt);
            ground(tiles, p.x(), 1, p.z(), air);
            ground(tiles, p.x(), 2, p.z(), air);
        }
    }

    private static String kindLabel(String kind) {
        return switch (kind) {
            case "church", "chapel", "cathedral" -> "Kirche";
            case "garage", "garages" -> "Garage";
            case "house", "detached", "semidetached_house" -> "Wohnhaus";
            case "apartments" -> "Mehrfamilienhaus";
            case "industrial", "warehouse" -> "Halle";
            default -> "Gebäude";
        };
    }

    private static double dist(int x, int z) {
        return Math.sqrt((double) x * x + (double) z * z);
    }

    static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static long tileKey(int x, int z) {
        return key(Math.floorDiv(x, TILE), Math.floorDiv(z, TILE));
    }

    private static int tileX(long k) {
        return (int) (k >> 32) * TILE + TILE / 2;
    }

    private static int tileZ(long k) {
        return (int) k * TILE + TILE / 2;
    }

    private void ground(Map<Long, Map<Long, Cell>> tiles, int x, int y, int z, int m) {
        ground(tiles, x, y, z, m, 0);
    }

    /** Meer- und Strandmarker aus den Küstenlinien (siehe SEA_MARK). Ohne Küstenlinie: nichts, das Gelände bleibt. */
    private void shoreline(Map<Long, Map<Long, Cell>> tiles, CityModel city) {
        // Nur für begrenzte Ausschnitte (Etappen des Weltbaus); ein unbegrenzter hätte ein Raster ohne Ende.
        if ((long) (margin.x1() - margin.x0()) * (margin.z1() - margin.z0()) > 4096L * 4096L) {
            return;
        }
        List<List<P>> coasts = new ArrayList<>();
        for (Line l : city.lines()) {
            if ("coastline".equals(l.kind())) {
                coasts.add(l.points());
            }
        }
        // Meeresflächen (Hafen, Bucht als Wasser-Relation) – in Sydney die einzige Quelle, dort gibt es keine
        // Küstenlinie (Overpass 2026-11-01: an Circular Quay nur „Port Jackson“, natural=water, water=harbour).
        Set<P> marine = new HashSet<>();
        for (Area a : city.areas()) {
            if (a.kind().equals("sea")) {
                int[] bb = bounds(a.outline());
                if (margin.overlaps(bb[0], bb[1], bb[2], bb[3])) {
                    marine.addAll(fill(a.outline(), a.holes(), margin));
                }
            }
        }
        // Maske über Ausschnitt und Rand: Tiefe und Strand reißen so an der Etappengrenze nicht ab.
        SeaMask sea = SeaMask.of(coasts, marine, margin.x0(), margin.z0(), margin.x1(), margin.z1());
        if (sea == null) {
            return;
        }
        mat("water");  // TerrainFit.Shore braucht ihre Indizes in der Palette
        mat("sand");
        mat("air");
        mat("stone");
        for (int x = clip.x0(); x < clip.x1(); x++) {
            for (int z = clip.z0(); z < clip.z1(); z++) {
                if (!clip.contains(x, z)) {
                    continue;
                }
                if (sea.sea(x, z)) {
                    ground(tiles, x, 0, z, SEA_MARK - depthFor(sea.toLand(x, z)));
                    wet.add(key(x, z));
                } else {
                    int d = sea.toSea(x, z);
                    if (d <= BEACH_WIDTH) {
                        ground(tiles, x, 0, z, BEACH_MARK);
                    }
                    if (d <= PIT_WIDTH) {
                        ground(tiles, x, -1, z, FILL_MARK);
                    }
                }
            }
        }
    }

    private void ground(Map<Long, Map<Long, Cell>> tiles, int x, int y, int z, int m, int anchor) {
        if (!clip.contains(x, z) || covered.contains(key(x, z))) {
            return;
        }
        tiles.computeIfAbsent(tileKey(x, z), k -> new LinkedHashMap<>())
                .put(key(x, z) * 512 + y + 256, new Cell(x, y, z, m, anchor));
    }

    /**
     * Baum nach Art: oak (Stamm 4, runde Krone), birch (wie oak, eine Lage höher), spruce (Stamm 6, schmaler Kegel).
     * Laub persistent, sonst zerfiele es – ohne Physik gesetzt prüft niemand den Abstand zum Stamm.
     */
    private void tree(Map<Long, Map<Long, Cell>> tiles, int x, int z, String kind) {
        if (kind.equals("spruce")) {
            int log = mat("spruce_log");
            int leaves = mat("spruce_leaves[persistent=true]");
            for (int y = 2; y <= 7; y++) {
                int reach = y <= 3 ? 2 : y <= 6 ? 1 : 0;
                for (int dx = -reach; dx <= reach; dx++) {
                    for (int dz = -reach; dz <= reach; dz++) {
                        if (Math.abs(dx) + Math.abs(dz) <= reach && !(dx == 0 && dz == 0 && y <= 6)) {
                            ground(tiles, x + dx, y, z + dz, leaves, Cell.anchor(-dx, -dz));
                        }
                    }
                }
            }
            for (int y = 1; y <= 6; y++) {
                ground(tiles, x, y, z, log);
            }
            return;
        }
        boolean birch = kind.equals("birch");
        int log = mat(birch ? "birch_log" : "oak_log");
        int leaves = mat(birch ? "birch_leaves[persistent=true]" : "oak_leaves[persistent=true]");
        int lift = birch ? 1 : 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int y = 3; y <= 5; y++) {
                    int r = Math.abs(dx) + Math.abs(dz);
                    if (r <= (y == 5 ? 1 : 3) && !(dx == 0 && dz == 0 && y < 5)) {
                        // Anker zurück zum Stamm: Auf Gelände hängt die Krone an dessen Höhe (siehe Cell).
                        ground(tiles, x + dx, y + lift, z + dz, leaves, Cell.anchor(-dx, -dz));
                    }
                }
            }
        }
        for (int y = 1; y <= 4 + lift; y++) {
            ground(tiles, x, y, z, log);
        }
    }

    static String surface(String kind) {
        return switch (kind) {
            case "waterway" -> "water";
            case "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
                    "living_street", "service", "motorway_link", "primary_link", "secondary_link" -> "gray_concrete";
            case "pedestrian" -> "polished_andesite";
            case "track", "path", "bridleway" -> "dirt_path";
            case "cycleway" -> "red_concrete";
            default -> "andesite";  // footway, steps …
        };
    }

    private void road(Map<Long, Map<Long, Cell>> tiles, Road r) {
        String surface = surface(r.kind());
        int m = mat(surface);
        int curb = mat("smooth_stone");
        int line = mat("white_concrete");
        int air = mat("air");
        boolean car = surface.equals("gray_concrete");
        boolean water = surface.equals("water");
        double half = r.width() / 2.0;
        Set<Long> done = new HashSet<>();
        for (int i = 0; i + 1 < r.line().size(); i++) {
            P a = r.line().get(i);
            P b = r.line().get(i + 1);
            double len = Math.max(1e-9, Math.hypot(b.x() - a.x(), b.z() - a.z()));
            int x0 = (int) Math.floor(Math.min(a.x(), b.x()) - half - 1);
            int x1 = (int) Math.ceil(Math.max(a.x(), b.x()) + half + 1);
            int z0 = (int) Math.floor(Math.min(a.z(), b.z()) - half - 1);
            int z1 = (int) Math.ceil(Math.max(a.z(), b.z()) + half + 1);
            // Bis in den Rand: Dort wird nichts gesetzt (ground prüft den Ausschnitt), aber belegt eingetragen –
            // Laternen und Schilder an der Etappengrenze müssen die Wege der Nachbaretappe kennen.
            if (!margin.overlaps(x0, z0, x1, z1)) {
                continue;
            }
            x0 = Math.max(x0, margin.x0());
            x1 = Math.min(x1, margin.x1() - 1);
            z0 = Math.max(z0, margin.z0());
            z1 = Math.min(z1, margin.z1() - 1);
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    // Abstand der Zelle zur Strecke a–b
                    double t = ((x - a.x()) * (b.x() - a.x()) + (z - a.z()) * (b.z() - a.z())) / (len * len);
                    t = Math.max(0, Math.min(1, t));
                    double px = a.x() + t * (b.x() - a.x());
                    double pz = a.z() + t * (b.z() - a.z());
                    double d = Math.hypot(x - px, z - pz);
                    if (d > half) {
                        continue;
                    }
                    long k = key(x, z);
                    if (water) {
                        wet.add(k);
                    } else {
                        pave(x, z, r.id());
                    }
                    if (!clip.contains(x, z)) {
                        continue;
                    }
                    int top = m;
                    if (car && r.width() >= 6 && d > half - 1) {
                        top = curb;  // Bordstein/Gehweg am Rand breiter Straßen
                    } else if (car && r.width() >= 6 && d < 0.5 && Math.floorMod((int) Math.round(t * len), 6) < 3) {
                        top = line;  // gestrichelte Mittellinie
                    }
                    if (!done.add(k) && top == m) {
                        continue;  // Kreuzung: vorhandene Markierung nicht mit Belag überschreiben
                    }
                    ground(tiles, x, 0, z, top);
                    if (water) {
                        ground(tiles, x, -1, z, m);
                    } else {
                        ground(tiles, x, 1, z, air);  // Weg frei: Gras/Blumen auf der Straße weg
                        ground(tiles, x, 2, z, air);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ Gebäude

    private List<Cell> building(Building b, Set<P> mask) {
        if (mask.size() < 4) {
            return List.of();
        }
        Set<P> outline = new HashSet<>();
        for (P p : mask) {
            if (!mask.contains(new P(p.x() + 1, p.z())) || !mask.contains(new P(p.x() - 1, p.z()))
                    || !mask.contains(new P(p.x(), p.z() + 1)) || !mask.contains(new P(p.x(), p.z() - 1))) {
                outline.add(p);
            }
        }
        String kind = b.kind();
        boolean tower = kind.equals("tower") || kind.equals("bell_tower") || kind.equals("steeple");
        boolean church = kind.equals("church") || kind.equals("chapel") || kind.equals("cathedral") || tower;
        boolean shed = kind.startsWith("garage") || kind.equals("shed") || kind.equals("carport");
        boolean hall = kind.equals("industrial") || kind.equals("warehouse") || kind.equals("power");
        int pick = (int) Math.floorMod(b.id() * 2654435761L, 1_000_003L);
        String wallBlock = church ? "stone_bricks" : shed ? "smooth_stone" : hall ? "light_gray_concrete"
                : WALLS[pick % WALLS.length];
        String roofBlock = church ? "deepslate_tiles" : shed ? "smooth_stone_slab" : hall ? "gray_concrete"
                : ROOFS[(pick / 7) % ROOFS.length];
        // Was OSM über Farbe und Baustoff weiß, schlägt die Vorgabe (OsmMaterials).
        int wall = mat(b.wall().isBlank() ? wallBlock : b.wall());
        int roof = mat(b.roofBlock().isBlank() ? roofBlock : b.roofBlock());
        int floor = mat(church ? "polished_andesite" : "spruce_planks");
        int glass = mat(church ? "light_blue_stained_glass" : "glass");
        int air = mat("air");
        int h = b.height();
        int base = b.minHeight();  // > 0: Teil beginnt erst in der Höhe (Turmhelm über dem Schiff)
        Map<Long, Cell> cells = new LinkedHashMap<>();
        java.util.function.BiConsumer<P, int[]> put = (p, ym) -> cells.put(key(p.x(), p.z()) * 512 + ym[0] + 256,
                new Cell(p.x(), ym[0], p.z(), ym[1]));
        // Boden und Geschossdecken, Innenraum frei
        for (P p : mask) {
            put.accept(p, new int[] {base, floor});
            for (int y = base + 1; y < h; y++) {
                put.accept(p, new int[] {y, (y - base) % LEVEL == 0 ? floor : air});
            }
        }
        // Wände mit Fensterreihen je Stockwerk (jede zweite Stelle, nicht an Ecken)
        for (P p : outline) {
            int neighbours = 0;
            neighbours += outline.contains(new P(p.x() + 1, p.z())) ? 1 : 0;
            neighbours += outline.contains(new P(p.x() - 1, p.z())) ? 1 : 0;
            neighbours += outline.contains(new P(p.x(), p.z() + 1)) ? 1 : 0;
            neighbours += outline.contains(new P(p.x(), p.z() - 1)) ? 1 : 0;
            boolean straight = neighbours == 2 && (outline.contains(new P(p.x() + 1, p.z()))
                    == outline.contains(new P(p.x() - 1, p.z())));
            for (int y = base + 1; y <= h; y++) {
                // Türme: schmale Schallöffnungen alle 6 Blöcke statt Fensterreihen.
                boolean window = !shed && straight && Math.floorMod(p.x() + p.z(), 2) == 0
                        && (tower ? (y - base) % 6 == 4 : church ? y >= base + 2 && y <= h - 2 : (y - base) % LEVEL == 2)
                        && y < h;
                put.accept(p, new int[] {y, window ? glass : wall});
            }
        }
        // Eingang: zwei Blöcke Luft in der Außenwand, die der nächsten Straße am nächsten liegt
        P door = base == 0 ? nearestToRoads(outline) : null;
        if (door != null && h >= 3) {
            put.accept(door, new int[] {1, air});
            put.accept(door, new int[] {2, air});
        }
        if (base == 0 && h >= 4) {
            passage(mask, air, put);
        }
        roof(b, mask, h, roof, wall, put);
        List<Cell> out = new ArrayList<>(cells.values());
        out.sort(Comparator.comparingInt(Cell::y));
        return out;
    }

    /**
     * Durchgang (tunnel=building_passage): Zellen der Grundfläche mit Abstand zur Wegachse ≤ Breite/2 bekommen unten den
     * Wegbelag und darüber drei Blöcke Luft (Wände und die Decke in y=3 fallen dort weg). Vorher lief der Weg bis an die
     * Fassade und endete an der Wand – ein Durchgang zum Hof war nicht begehbar. Nur bei Unterkante 0 und mindestens 4
     * Blöcken Höhe, sonst bliebe vom Haus über dem Durchgang nichts. Mindestens 1 statt Breite/2: Bei width=1 und
     * schräger Achse blieben sonst nur diagonal verbundene Zellen frei, durch die kein Spieler kommt.
     */
    private void passage(Set<P> mask, int air, java.util.function.BiConsumer<P, int[]> put) {
        if (passages.isEmpty()) {
            return;
        }
        int[] bb = bounds(List.copyOf(mask));
        for (Road r : passages) {
            double half = Math.max(1.0, r.width() / 2.0);
            int surface = mat(surface(r.kind()));
            for (int i = 0; i + 1 < r.line().size(); i++) {
                P a = r.line().get(i);
                P c = r.line().get(i + 1);
                if (Math.max(a.x(), c.x()) + half < bb[0] || Math.min(a.x(), c.x()) - half > bb[2]
                        || Math.max(a.z(), c.z()) + half < bb[1] || Math.min(a.z(), c.z()) - half > bb[3]) {
                    continue;
                }
                for (P p : mask) {
                    if (segmentDistance(p.x(), p.z(), a, c) <= half) {
                        put.accept(p, new int[] {0, surface});
                        for (int y = 1; y <= 3; y++) {
                            put.accept(p, new int[] {y, air});
                        }
                    }
                }
            }
        }
    }

    private void roof(Building b, Set<P> mask, int h, int roof, int wall, java.util.function.BiConsumer<P, int[]> put) {
        String shape = b.roof();
        if (shape.equals("flat") || shape.isBlank()) {
            for (P p : mask) {
                put.accept(p, new int[] {h, roof});
            }
            return;
        }
        // Walm-/Zelt-/Kuppeldach: Grundriss Schicht für Schicht nach innen schrumpfen. Satteldach: nur quer zur
        // längeren Seite schrumpfen, die Giebelseiten werden Wand.
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (P p : mask) {
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minZ = Math.min(minZ, p.z());
            maxZ = Math.max(maxZ, p.z());
        }
        boolean gabled = shape.equals("gabled") || shape.equals("saltbox") || shape.equals("gambrel");
        boolean alongX = (maxX - minX) >= (maxZ - minZ);  // First parallel zur längeren Seite
        Set<P> layer = mask;
        int y = h;
        int maxLayers = Math.min(16, OsmImport.MAX_HEIGHT + 8 - h);
        // Turmhelm: auf kleiner Grundfläche wäre eine Pyramide nach wenigen Schichten fertig und sähe flach aus –
        // jede Schicht dreimal, das ergibt die spitze Silhouette von Kirchtürmen.
        String kind = b.kind();
        boolean pointed = shape.equals("pyramidal") || shape.equals("cone") || shape.equals("spire")
                || shape.equals("onion") || shape.equals("conical");
        int rep = pointed && (kind.equals("tower") || kind.equals("bell_tower") || kind.equals("steeple")
                || mask.size() <= 200) ? 3 : 1;
        for (int k = 0; k < maxLayers && !layer.isEmpty(); k++, y += rep) {
            Set<P> next = new HashSet<>();
            for (P p : layer) {
                boolean keep = gabled
                        ? (alongX ? layer.contains(new P(p.x(), p.z() + 1)) && layer.contains(new P(p.x(), p.z() - 1))
                                : layer.contains(new P(p.x() + 1, p.z())) && layer.contains(new P(p.x() - 1, p.z())))
                        : layer.contains(new P(p.x() + 1, p.z())) && layer.contains(new P(p.x() - 1, p.z()))
                                && layer.contains(new P(p.x(), p.z() + 1)) && layer.contains(new P(p.x(), p.z() - 1));
                if (keep) {
                    next.add(p);
                }
            }
            for (P p : layer) {
                if (!next.contains(p) || next.isEmpty()) {
                    for (int r = 0; r < rep; r++) {
                        put.accept(p, new int[] {y + r, roof});
                    }
                } else if (gabled && (alongX ? !layer.contains(new P(p.x() + 1, p.z()))
                        || !layer.contains(new P(p.x() - 1, p.z()))
                        : !layer.contains(new P(p.x(), p.z() + 1)) || !layer.contains(new P(p.x(), p.z() - 1)))) {
                    put.accept(p, new int[] {y, wall});  // Giebeldreieck
                }
            }
            layer = next;
        }
    }

    private static final int GRID = 32;
    /** Weiter als so weit sucht der Eingang keine Straße (Hof, Feld) – dann irgendeine Außenwand. */
    private static final int DOOR_SEARCH = 96;

    /**
     * Straßenabschnitte in ein 32-m-Raster. Vorher lief die Eingangssuche über jeden Punkt jeder Straße für jeden
     * Randpunkt jedes Hauses – quadratisch, bei einer Großstadt-Etappe Milliarden Vergleiche. Jetzt nur die Abschnitte
     * aus den Rasterzellen um das Haus, gemessen wird zur Strecke statt zu den Stützpunkten (lange gerade Straßen
     * haben oft nur zwei Punkte, 200 m auseinander).
     */
    private void indexRoads(List<Road> roads) {
        for (Road r : roads) {
            if (r.kind().equals("waterway")) {
                continue;
            }
            for (int i = 0; i + 1 < r.line().size(); i++) {
                P a = r.line().get(i);
                P b = r.line().get(i + 1);
                int ax = Math.min(a.x(), b.x());
                int az = Math.min(a.z(), b.z());
                int bx = Math.max(a.x(), b.x());
                int bz = Math.max(a.z(), b.z());
                if (!clip.overlaps(ax - DOOR_SEARCH * 2, az - DOOR_SEARCH * 2, bx + DOOR_SEARCH * 2,
                        bz + DOOR_SEARCH * 2)) {
                    continue;
                }
                P[] seg = {a, b};
                for (int gx = Math.floorDiv(ax, GRID); gx <= Math.floorDiv(bx, GRID); gx++) {
                    for (int gz = Math.floorDiv(az, GRID); gz <= Math.floorDiv(bz, GRID); gz++) {
                        roadGrid.computeIfAbsent(key(gx, gz), k -> new ArrayList<>()).add(seg);
                    }
                }
            }
        }
    }

    private P nearestToRoads(Set<P> outline) {
        if (outline.isEmpty()) {
            return null;
        }
        int[] bb = bounds(List.copyOf(outline));
        List<P[]> near = new ArrayList<>();
        for (int gx = Math.floorDiv(bb[0] - DOOR_SEARCH, GRID); gx <= Math.floorDiv(bb[2] + DOOR_SEARCH, GRID); gx++) {
            for (int gz = Math.floorDiv(bb[1] - DOOR_SEARCH, GRID); gz <= Math.floorDiv(bb[3] + DOOR_SEARCH, GRID);
                    gz++) {
                near.addAll(roadGrid.getOrDefault(key(gx, gz), List.of()));
            }
        }
        P best = null;
        double bestD = Double.MAX_VALUE;
        for (P p : outline) {
            for (P[] s : near) {
                double d = segmentDistance(p.x(), p.z(), s[0], s[1]);
                if (d < bestD || d == bestD && best != null && (p.x() < best.x() || p.x() == best.x() && p.z() < best.z())) {
                    bestD = d;
                    best = p;
                }
            }
        }
        if (best == null) {
            // Keine Straße in der Nähe: deterministisch die südlichste Wandstelle (gleiche Daten → gleicher Eingang).
            for (P p : outline) {
                if (best == null || p.z() > best.z() || p.z() == best.z() && p.x() < best.x()) {
                    best = p;
                }
            }
        }
        return best;
    }

    private static double segmentDistance(int x, int z, P a, P b) {
        double dx = b.x() - a.x();
        double dz = b.z() - a.z();
        double len2 = dx * dx + dz * dz;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - a.x()) * dx + (z - a.z()) * dz) / len2));
        return Math.hypot(x - (a.x() + t * dx), z - (a.z() + t * dz));
    }

    /** {minX, minZ, maxX, maxZ}. */
    private static int[] bounds(List<P> pts) {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (P p : pts) {
            b[0] = Math.min(b[0], p.x());
            b[1] = Math.min(b[1], p.z());
            b[2] = Math.max(b[2], p.x());
            b[3] = Math.max(b[3], p.z());
        }
        return b;
    }

    // ------------------------------------------------------------------ Geometrie

    static P centroid(List<P> ring) {
        long sx = 0;
        long sz = 0;
        for (P p : ring) {
            sx += p.x();
            sz += p.z();
        }
        return new P((int) (sx / ring.size()), (int) (sz / ring.size()));
    }

    /** Alle Zellen innerhalb des Polygons (Zellmitte, gerade-ungerade-Regel) plus die Randlinie selbst. */
    static Set<P> fill(List<P> ring) {
        return fill(ring, List.of(), Clip.all());
    }

    /** Wie {@link #fill(List)}, aber nur das Rechteck des Ausschnitts – ein Wald über Kilometer kostet sonst je Etappe. */
    static Set<P> fill(List<P> ring, Clip clip) {
        return fill(ring, List.of(), clip);
    }

    /**
     * Wie {@link #fill(List, Clip)}, mit Innenringen (Höfen): Die gerade-ungerade-Regel läuft über die Kanten ALLER
     * Ringe, ein Hof ist damit von selbst ausgespart; die Randlinie (Bresenham) auch der Innenringe gehört zur Fläche –
     * das wird die Hofwand. Wer die Innenringe wieder weglässt, deckt jeden Blockrand-Hof mit Boden und Dach zu.
     */
    static Set<P> fill(List<P> ring, List<List<P>> holes, Clip clip) {
        Set<P> out = new HashSet<>();
        if (ring.size() < 3) {
            return out;
        }
        List<List<P>> rings = new ArrayList<>(1 + holes.size());
        rings.add(ring);
        for (List<P> hole : holes) {
            if (hole.size() >= 3) {
                rings.add(hole);
            }
        }
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (P p : ring) {
            minZ = Math.min(minZ, p.z());
            maxZ = Math.max(maxZ, p.z());
        }
        minZ = Math.max(minZ, clip.z0());
        maxZ = Math.min(maxZ, clip.z1() - 1);
        for (int z = minZ; z <= maxZ; z++) {
            double zc = z + 0.5;
            List<Double> xs = new ArrayList<>();
            for (List<P> r : rings) {
                int n = r.size();
                for (int i = 0; i < n; i++) {
                    P a = r.get(i);
                    P b = r.get((i + 1) % n);
                    if ((a.z() <= zc && b.z() > zc) || (b.z() <= zc && a.z() > zc)) {
                        xs.add(a.x() + (zc - a.z()) * (b.x() - a.x()) / (double) (b.z() - a.z()));
                    }
                }
            }
            xs.sort(Double::compare);
            for (int i = 0; i + 1 < xs.size(); i += 2) {
                int from = Math.max(clip.x0(), (int) Math.ceil(xs.get(i) - 0.5));
                int to = Math.min(clip.x1() - 1, (int) Math.floor(xs.get(i + 1) - 0.5));
                for (int x = from; x <= to; x++) {
                    out.add(new P(x, z));
                }
            }
        }
        // Randlinie (Bresenham), damit schmale Teile und Kanten nicht fehlen – auch die der Innenringe (Hofwand)
        for (List<P> r : rings) {
            int n = r.size();
            for (int i = 0; i < n; i++) {
                P a = r.get(i);
                P b = r.get((i + 1) % n);
                int dx = Math.abs(b.x() - a.x());
                int dz = -Math.abs(b.z() - a.z());
                int sx = a.x() < b.x() ? 1 : -1;
                int sz = a.z() < b.z() ? 1 : -1;
                int err = dx + dz;
                int x = a.x();
                int z = a.z();
                while (true) {
                    if (x >= clip.x0() && x < clip.x1() && z >= clip.z0() && z < clip.z1()) {
                        out.add(new P(x, z));
                    }
                    if (x == b.x() && z == b.z()) {
                        break;
                    }
                    int e2 = 2 * err;
                    if (e2 >= dz) {
                        err += dz;
                        x += sx;
                    }
                    if (e2 <= dx) {
                        err += dx;
                        z += sz;
                    }
                }
            }
        }
        return out;
    }
}
