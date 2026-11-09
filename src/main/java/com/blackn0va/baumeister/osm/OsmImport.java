package com.blackn0va.baumeister.osm;

import com.blackn0va.baumeister.osm.CityModel.Area;
import com.blackn0va.baumeister.osm.CityModel.Building;
import com.blackn0va.baumeister.osm.CityModel.Line;
import com.blackn0va.baumeister.osm.CityModel.P;
import com.blackn0va.baumeister.osm.CityModel.Road;
import com.blackn0va.baumeister.osm.CityModel.Tree;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Overpass-Antwort ({@code out geom}) → {@link CityModel}. Rein, ohne Netz – testbar mit gespeicherten Antworten.
 *
 * Projektion: gleichabständig um den Mittelpunkt (x = Δlon·cos(lat₀)·111 320 m, z = −Δlat·110 540 m). Für einen Ort
 * mit wenigen Kilometern Ausdehnung ist der Fehler weit unter einem Block je 100 m; 1 Block = 1 m. Das ist die
 * Vorgabe ({@link #around}); der Weltbau reicht über {@link Projector} die Projektion der Erdwelt herein.
 *
 * Höhen: OSM height (Meter) bzw. building:levels × 3 + 1; fehlt beides (meist – im Testort 0 von 68 Gebäuden), eine
 * Vorgabe je Gebäudetyp. Dachform aus roof:shape, sonst Satteldach für kleine Wohnhäuser, flach für große Bauten.
 */
public final class OsmImport {

    private static final double M_PER_DEG_LAT = 110_540.0;
    private static final double M_PER_DEG_LON = 111_320.0;
    /** Vorher 80 – zu niedrig für Kirchtürme (Frauenkirche München 99 m, Ulmer Münster 162 m). */
    static final int MAX_HEIGHT = 170;

    private OsmImport() {
    }

    public static CityModel parse(String json, String place, double lat0, double lon0, int radius) {
        return parse(json, place, lat0, lon0, radius, around(lat0, lon0), new int[1]);
    }

    /**
     * Wie {@link #parse(String, String, double, double, int)}, aber mit fremder Projektion – der Weltbau setzt OSM so
     * genau auf die Stellen, die der Generator der Erdwelt für dieselben Erdpunkte benutzt (siehe {@link Projector}).
     * lat0/lon0/radius landen nur als Angaben im CityModel.
     */
    public static CityModel parse(String json, String place, double lat0, double lon0, int radius,
            Projector projector) {
        return parse(json, place, lat0, lon0, radius, projector, new int[1]);
    }

    /** Die bisherige Projektion: gleichabständig um (lat0, lon0), siehe Klassenkommentar. Vorgabe von parse. */
    public static Projector around(double lat0, double lon0) {
        double cos = Math.cos(Math.toRadians(lat0));
        return (lat, lon) -> project(lat, lon, lat0, lon0, cos);
    }

    /**
     * Wie {@link #parse(String, String, double, double, int)}; {@code dropped[0]} zählt die Multipolygon-Reste, die sich
     * nicht zu einem Ring schließen ließen oder keinem Außenring zuzuordnen waren (für die Messprobe).
     */
    static CityModel parse(String json, String place, double lat0, double lon0, int radius, int[] dropped) {
        return parse(json, place, lat0, lon0, radius, around(lat0, lon0), dropped);
    }

    private static CityModel parse(String json, String place, double lat0, double lon0, int radius, Projector proj,
            int[] dropped) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray elements = root.has("elements") ? root.getAsJsonArray("elements") : new JsonArray();
        List<Building> buildings = new ArrayList<>();
        List<Road> roads = new ArrayList<>();
        List<Area> areas = new ArrayList<>();
        List<Tree> trees = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        // Wege bzw. Gleise, die nur über layer>0 als Brücke gelten (ohne bridge-Angabe) – siehe settleLayerBridges.
        Set<Object> layerOnly = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean mirrored = mirrored(proj, lat0, lon0);
        for (JsonElement el : elements) {
            JsonObject e = el.getAsJsonObject();
            Map<String, String> tags = tags(e);
            long id = e.has("id") ? e.get("id").getAsLong() : 0;
            String type = e.get("type").getAsString();
            if (type.equals("node")) {
                if ("tree".equals(tags.get("natural")) && e.has("lat")) {
                    P p = proj.project(e.get("lat").getAsDouble(), e.get("lon").getAsDouble());
                    if (p != null) {
                        trees.add(new Tree(p.x(), p.z(), treeKind(tags)));
                    }
                }
                continue;
            }
            boolean isBuilding = tags.containsKey("building") || tags.containsKey("building:part");
            if (type.equals("relation") && !isBuilding && "water".equals(tags.get("natural"))) {
                if (e.has("members")) {
                    areas.addAll(waterPolygons(id, e.getAsJsonArray("members"), tags, proj, dropped));
                }
                continue;
            }
            if (type.equals("relation") && isBuilding) {
                if (e.has("members")) {
                    buildings.addAll(multipolygon(id, e.getAsJsonArray("members"), tags, proj, dropped));
                }
                continue;
            }
            if (!isBuilding && (tags.containsKey("highway") || tags.containsKey("waterway")
                    || tags.containsKey("railway")) && !atSurface(tags)) {
                continue;
            }
            String lineKind = isBuilding || tags.containsKey("highway") ? null : lineKind(tags);
            List<List<P>> rings = new ArrayList<>();
            if (type.equals("way") && e.has("geometry")) {
                rings.add(line(e.getAsJsonArray("geometry"), proj));
            } else if (type.equals("relation") && e.has("members")) {
                for (JsonElement m : e.getAsJsonArray("members")) {
                    JsonObject mo = m.getAsJsonObject();
                    if (mo.has("geometry") && "outer".equals(mo.has("role") ? mo.get("role").getAsString() : "")) {
                        rings.add(line(mo.getAsJsonArray("geometry"), proj));
                    }
                }
            }
            for (List<P> ring : rings) {
                if (ring.size() < 2) {
                    continue;
                }
                if ("coastline".equals(tags.get("natural")) && !isBuilding) {
                    // Küstenlinie für SeaMask: Land links der Laufrichtung, auch in Kachelkoordinaten (siehe mirrored).
                    lines.add(new Line(id, mirrored ? reversed(ring) : ring, "coastline", false));
                    continue;
                }
                if (isBuilding) {
                    if (ring.size() >= 4) {
                        buildings.add(building(id, ring, tags, List.of()));
                    }
                } else if (tags.containsKey("highway")) {
                    if (plaza(tags, ring)) {
                        areas.add(new Area(id, ring, "plaza"));
                    } else {
                        Road road = new Road(id, ring, roadWidth(tags), tags.get("highway"),
                                tags.getOrDefault("name", ""), "building_passage".equals(tags.get("tunnel")),
                                bridge(tags), layer(tags));
                        roads.add(road);
                        if (road.bridge() && !tags.containsKey("bridge")) {
                            layerOnly.add(road);
                        }
                    }
                } else if (tags.containsKey("waterway")) {
                    // Nur fließende Gewässer als Linie. Vorher wurde jeder waterway=* zu Wasser – auch Wehre, Dämme
                    // und Schleusentore (Querbauwerke, oft als Linie quer über den Fluss) sowie die alte
                    // waterway=riverbank als 3 m breiter Wasserrand um das Flussbett.
                    String w = tags.get("waterway");
                    if (w.equals("riverbank")) {
                        if (ring.size() >= 4) {
                            areas.add(new Area(id, ring, "water"));
                        }
                    } else if (FLOWING.contains(w)) {
                        roads.add(new Road(id, ring, waterwayWidth(tags), "waterway", tags.getOrDefault("name", "")));
                    }
                } else if (lineKind != null) {
                    Line line = new Line(id, ring, lineKind, bridge(tags));
                    lines.add(line);
                    if (line.bridge() && !tags.containsKey("bridge")) {
                        layerOnly.add(line);
                    }
                    // Zaun um eine Fläche (Garten, Sportplatz, Parkplatz, Wald mit barrier=fence auf demselben Way):
                    // Vorher kam nur der Zaun, die Fläche fiel weg (Prüfbefund 2026-10-29). Beides aus demselben Ring.
                    String kind = areaKind(tags);
                    if (kind != null && ring.size() >= 4) {
                        areas.add(new Area(id, ring, kind));
                    }
                } else {
                    String kind = areaKind(tags);
                    if (kind != null && ring.size() >= 4) {
                        areas.add(new Area(id, ring, kind));
                    }
                }
            }
        }
        settleLayerBridges(roads, lines, areas, layerOnly);
        return new CityModel(place, lat0, lon0, radius, List.copyOf(buildings), List.copyOf(roads), List.copyOf(areas),
                List.copyOf(trees), List.copyOf(lines));
    }

    /**
     * layer&gt;0 ohne bridge-Angabe ist nur dann eine Brücke, wenn der Weg bzw. das Gleis etwas kreuzt (Weg, Gleis,
     * Gewässer) oder über Wasser liegt. Laut OSM-Wiki heißt layer allein nicht Brücke: Rampen, Wege über Tunneln (der
     * Tunnel fehlt im Modell, atSurface), Dachparkdecks, Wege auf Dämmen tragen oft layer=1. Vorher (bis zur
     * Nachbesserung 2026-10-29) wurden sie mit Rampe und Geländer gehoben, ohne Laternen und Schilder – über einem
     * Tunnel als 1-Block-Brücke mitten in der Straße. Jetzt: ohne Querung zurück an den Boden (layer bleibt). Explizites
     * bridge=* bleibt immer Brücke.
     */
    private static void settleLayerBridges(List<Road> roads, List<Line> lines, List<Area> areas,
            Set<Object> layerOnly) {
        if (layerOnly.isEmpty()) {
            return;
        }
        for (int i = 0; i < roads.size(); i++) {
            Road r = roads.get(i);
            if (layerOnly.contains(r) && Bridges.over(r.line(), r, roads, lines, areas) == 0) {
                roads.set(i, new Road(r.id(), r.line(), r.width(), r.kind(), r.name(), r.passage(), false, r.layer()));
            }
        }
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            if (layerOnly.contains(l) && Bridges.over(l.points(), l, roads, lines, areas) == 0) {
                lines.set(i, new Line(l.id(), l.points(), l.kind(), false));
            }
        }
    }

    /** Fließgewässer, die als Linie mit Breite entstehen; alles andere unter waterway=* ist Bauwerk oder Fläche. */
    private static final Set<String> FLOWING = Set.of("river", "stream", "canal", "ditch", "drain", "brook");
    /**
     * Spiegelt die Projektion um den Mittelpunkt (Ost × Nord negativ)? In OSM liegt Land links einer Küstenlinie; in
     * Blockkoordinaten der Erdwelt zeigt Nord nach −z – „links“ wird dort zu „rechts“. SeaMask rechnet immer mit
     * „Land links“, also wird jede Küstenlinie bei Spiegelung umgedreht. Wer das weglässt, flutet die Städte und legt
     * die Häfen trocken (SeaMaskTest, OsmImportCoastTest). Fehlt ein Punkt (Netzlücke), gilt „nicht gespiegelt“.
     */
    static boolean mirrored(Projector proj, double lat, double lon) {
        P a = proj.project(lat, lon);
        P e = proj.project(lat, lon + 0.001);
        P n = proj.project(lat + 0.001, lon);
        if (a == null || e == null || n == null) {
            return false;
        }
        long det = (long) (e.x() - a.x()) * (n.z() - a.z()) - (long) (e.z() - a.z()) * (n.x() - a.x());
        return det < 0;
    }

    private static List<P> reversed(List<P> ring) {
        List<P> out = new ArrayList<>(ring);
        Collections.reverse(out);
        return out;
    }

    /** Gleisarten, die gebaut werden; abandoned/disused/razed/platform/construction nicht. */
    private static final Set<String> RAILS = Set.of("rail", "tram", "light_rail", "narrow_gauge", "subway");
    /** Begrenzungen; guard_rail, kerb, bollard u. a. sind zu fein oder kein Bauwerk im Maßstab 1:1. */
    private static final Set<String> BARRIERS = Set.of("fence", "hedge", "wall", "retaining_wall", "city_wall");

    /** Gleis- bzw. Begrenzungsart für {@link Line}, sonst null. */
    static String lineKind(Map<String, String> tags) {
        String rail = tags.get("railway");
        if (rail != null) {
            return RAILS.contains(rail) ? rail : null;
        }
        String barrier = tags.get("barrier");
        return barrier != null && BARRIERS.contains(barrier) ? barrier : null;
    }

    /**
     * Brücke? bridge=* außer „no“ (yes, viaduct, aqueduct, cantilever …), dazu layer&gt;0 ohne bridge-Angabe – im
     * Bestand oft eine Brücke ohne Kennzeichen; ob sie wirklich etwas überquert, prüft danach settleLayerBridges. Ein Durchgang durch ein Haus oder ein Tunnel ist nie eine Brücke.
     */
    static boolean bridge(Map<String, String> tags) {
        if (tags.containsKey("tunnel") && !"no".equals(tags.get("tunnel"))) {
            return false;
        }
        String b = tags.get("bridge");
        if (b != null) {
            return !b.equals("no");
        }
        return layer(tags) > 0;
    }

    /** OSM layer als ganze Zahl (−5..5), 0 ohne oder bei unlesbarer Angabe. */
    static int layer(Map<String, String> tags) {
        Double l = number(tags.get("layer"));
        return l == null ? 0 : (int) Math.max(-5, Math.min(5, Math.round(l)));
    }

    /**
     * Baumart aus leaf_type, genus bzw. species. Nadelbäume werden Fichten, Birken Birken, alles andere Eichen – mehr
     * Arten lohnen sich im Maßstab 1:1 nicht, ein Baum aus OSM steht meist allein am Straßenrand.
     */
    static String treeKind(Map<String, String> tags) {
        String genus = (tags.getOrDefault("genus", "") + " " + tags.getOrDefault("species", ""))
                .toLowerCase(Locale.ROOT);
        if (genus.contains("betula")) {
            return "birch";
        }
        if ("needleleaved".equals(tags.get("leaf_type")) || genus.contains("picea") || genus.contains("pinus")
                || genus.contains("abies") || genus.contains("larix")) {
            return "spruce";
        }
        return "oak";
    }

    /** Keine begehbare Oberfläche: Planung, Baustelle, Gänge und Aufzüge in Gebäuden, Haltestelle. */
    private static final Set<String> NOT_A_WAY = Set.of("proposed", "construction", "corridor", "elevator", "bus_stop");
    /** tunnel-Werte unter der Erde bzw. unter einem Deckel; building_passage bleibt (Durchgang, siehe Road#passage). */
    private static final Set<String> BURIED = Set.of("yes", "culvert", "covered");

    /**
     * Liegt der Weg bzw. das Gewässer an der Oberfläche? Vorher kam jeder highway/waterway an die Oberfläche:
     * Fußgängertunnel und Gänge in Gebäuden wurden zu Andesit-Strichen über Wiesen und Plätze, verdolte Bäche
     * (culvert) zu Wasser quer über Straßen. layer&lt;0 allein reicht bewusst NICHT zum Verwerfen – damit sind oft nur
     * Wege unter einer Brücke markiert, die sehr wohl an der Oberfläche liegen. Gemessen München Mitte (Kartencache
     * 2026-10-28, MultipolygonProbe): 690 von 3 658 Wegen fallen weg, davon 604 tunnel=yes; 45 mit layer&lt;0 bleiben.
     */
    static boolean atSurface(Map<String, String> tags) {
        if (BURIED.contains(tags.getOrDefault("tunnel", "")) || "yes".equals(tags.get("indoor"))
                || tags.getOrDefault("level", "").trim().startsWith("-")) {
            return false;
        }
        return !NOT_A_WAY.contains(tags.getOrDefault("highway", ""));
    }

    /**
     * Platzfläche statt Weglinie: highway=platform als geschlossener Ring, pedestrian/footway/service mit area=yes.
     * Vorher wurde der Umriss solcher Flächen als schmaler Weg gezogen – vom Platz blieb ein Rahmen, innen nichts.
     * FALLE: Flächen, die OSM nur als Relation führt, kommen gar nicht an – die Abfrage holt nur Gebäude-Relationen. Im
     * Münchner Kartencache (2026-10-28) ist der Marienplatz nur als Weglinien da, keine area=yes-Fläche.
     */
    static boolean plaza(Map<String, String> tags, List<P> ring) {
        boolean closed = ring.size() >= 4 && ring.getFirst().equals(ring.getLast());
        if (!closed) {
            return false;
        }
        String highway = tags.getOrDefault("highway", "");
        return highway.equals("platform") || ("yes".equals(tags.get("area"))
                && (highway.equals("pedestrian") || highway.equals("footway") || highway.equals("service")));
    }

    /**
     * Gebäude-Relation (Multipolygon): outer und inner getrennt zu Ringen verketten, jeden Innenring dem Außenring
     * zuordnen, der seinen ersten Punkt enthält (bei Verschachtelung dem kleinsten). Ein Gebäude je Außenring, alle mit
     * der Relations-id. Vorher wurde jeder outer-Way einzeln zum Gebäude – offene Teilstücke schloss fill() mit einer
     * Sehne quer durchs Haus, inner wurde ignoriert und der Hof bekam ein Dach. Gemessen München Mitte (2026-10-28):
     * 47 von 53 Gebäude-Relationen haben Höfe, 49 895 Zellen waren vorher überdacht.
     */
    /**
     * water=*, die auf Meereshöhe liegen: Hafen, Bucht, Meer … Sie werden im Weltbau Meer (SeaMask) statt einer
     * Wasserfläche auf Geländehöhe. Ein See oder Fluss (water=lake/river/pond …) bleibt „water“.
     */
    static final Set<String> MARINE = Set.of("harbour", "bay", "sea", "ocean", "strait", "cove", "fjord", "sound",
            "inlet", "estuary");

    /** Wasser-Relation als Flächen mit Löchern (Inseln); Ringe aus Teilstücken wie bei Gebäuden (assembleRings). */
    static List<Area> waterPolygons(long id, JsonArray members, Map<String, String> tags, Projector proj,
            int[] dropped) {
        List<List<P>> outerParts = new ArrayList<>();
        List<List<P>> innerParts = new ArrayList<>();
        for (JsonElement m : members) {
            JsonObject mo = m.getAsJsonObject();
            if (!mo.has("geometry")) {
                continue;
            }
            String role = mo.has("role") ? mo.get("role").getAsString() : "";
            (role.equals("inner") ? innerParts : outerParts).add(line(mo.getAsJsonArray("geometry"), proj));
        }
        List<List<P>> outers = assembleRings(outerParts, dropped);
        List<List<P>> inners = assembleRings(innerParts, dropped);
        List<List<List<P>>> holes = new ArrayList<>();
        for (int i = 0; i < outers.size(); i++) {
            holes.add(new ArrayList<>());
        }
        for (List<P> inner : inners) {
            for (int i = 0; i < outers.size(); i++) {
                if (contains(outers.get(i), inner.getFirst())) {
                    holes.get(i).add(inner);
                    break;
                }
            }
        }
        String kind = MARINE.contains(tags.getOrDefault("water", "")) ? "sea" : "water";
        List<Area> out = new ArrayList<>();
        for (int i = 0; i < outers.size(); i++) {
            if (outers.get(i).size() >= 4) {
                out.add(new Area(id, outers.get(i), kind, List.copyOf(holes.get(i))));
            }
        }
        return out;
    }

    private static List<Building> multipolygon(long id, JsonArray members, Map<String, String> tags, Projector proj,
            int[] dropped) {
        List<List<P>> outerParts = new ArrayList<>();
        List<List<P>> innerParts = new ArrayList<>();
        for (JsonElement m : members) {
            JsonObject mo = m.getAsJsonObject();
            if (!mo.has("geometry")) {
                continue;
            }
            String role = mo.has("role") ? mo.get("role").getAsString() : "";
            if (role.equals("outer")) {
                outerParts.add(line(mo.getAsJsonArray("geometry"), proj));
            } else if (role.equals("inner")) {
                innerParts.add(line(mo.getAsJsonArray("geometry"), proj));
            }
        }
        List<List<P>> outers = assembleRings(outerParts, dropped);
        List<List<P>> inners = assembleRings(innerParts, dropped);
        List<List<List<P>>> holes = new ArrayList<>();
        for (int i = 0; i < outers.size(); i++) {
            holes.add(new ArrayList<>());
        }
        for (List<P> inner : inners) {
            int best = -1;
            double bestArea = Double.MAX_VALUE;
            for (int i = 0; i < outers.size(); i++) {
                double a = area(outers.get(i));
                if (a < bestArea && contains(outers.get(i), inner.getFirst())) {
                    best = i;
                    bestArea = a;
                }
            }
            if (best < 0) {
                dropped[0]++;  // Innenring ohne Außenring: kein Hof ohne Haus
            } else {
                holes.get(best).add(inner);
            }
        }
        List<Building> out = new ArrayList<>();
        for (int i = 0; i < outers.size(); i++) {
            out.add(building(id, outers.get(i), tags, List.copyOf(holes.get(i))));
        }
        return out;
    }

    static List<List<P>> assembleRings(List<List<P>> parts) {
        return assembleRings(parts, new int[1]);
    }

    /**
     * Offene Ways über gemeinsame Endpunkte zu geschlossenen Ringen verketten, bei Bedarf umgedreht (OSM legt die
     * Richtung der Teilstücke nicht fest). Was sich nicht schließt, wird verworfen und in {@code dropped[0]} gezählt –
     * lieber ein fehlendes Haus als eine Sehne quer durch den Block. Ergebnis: Ringe mit erstem == letztem Punkt und
     * mindestens 4 Punkten.
     */
    static List<List<P>> assembleRings(List<List<P>> parts, int[] dropped) {
        List<List<P>> rings = new ArrayList<>();
        List<List<P>> open = new ArrayList<>();
        for (List<P> part : parts) {
            if (part.size() < 2) {
                dropped[0]++;
            } else if (part.getFirst().equals(part.getLast())) {
                if (part.size() >= 4) {
                    rings.add(List.copyOf(part));
                } else {
                    dropped[0]++;
                }
            } else {
                open.add(part);
            }
        }
        boolean[] used = new boolean[open.size()];
        for (int i = 0; i < open.size(); i++) {
            if (used[i]) {
                continue;
            }
            used[i] = true;
            List<P> chain = new ArrayList<>(open.get(i));
            boolean extended = true;
            while (extended && !chain.getFirst().equals(chain.getLast())) {
                extended = false;
                P end = chain.getLast();
                for (int j = 0; j < open.size() && !extended; j++) {
                    if (used[j]) {
                        continue;
                    }
                    List<P> next = open.get(j);
                    if (next.getFirst().equals(end)) {
                        chain.addAll(next.subList(1, next.size()));
                    } else if (next.getLast().equals(end)) {
                        List<P> rev = new ArrayList<>(next);
                        Collections.reverse(rev);
                        chain.addAll(rev.subList(1, rev.size()));
                    } else {
                        continue;
                    }
                    used[j] = true;
                    extended = true;
                }
            }
            if (chain.size() >= 4 && chain.getFirst().equals(chain.getLast())) {
                rings.add(List.copyOf(chain));
            } else {
                dropped[0]++;
            }
        }
        return rings;
    }

    /** Punkt im Ring (gerade-ungerade-Regel, Strahl nach +x). */
    static boolean contains(List<P> ring, P p) {
        boolean in = false;
        int n = ring.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            P a = ring.get(i);
            P b = ring.get(j);
            if ((a.z() > p.z()) != (b.z() > p.z())
                    && p.x() < (double) (b.x() - a.x()) * (p.z() - a.z()) / (b.z() - a.z()) + a.x()) {
                in = !in;
            }
        }
        return in;
    }

    static P project(double lat, double lon, double lat0, double lon0, double cos) {
        int x = (int) Math.round((lon - lon0) * cos * M_PER_DEG_LON);
        int z = (int) Math.round(-(lat - lat0) * M_PER_DEG_LAT);
        return new P(x, z);
    }

    /** Punkte ohne Stelle auf der Karte (Projector liefert null) fallen weg, doppelte Nachbarn ebenso. */
    private static List<P> line(JsonArray geometry, Projector proj) {
        List<P> out = new ArrayList<>();
        for (JsonElement g : geometry) {
            if (!g.isJsonObject()) {
                continue;
            }
            JsonObject o = g.getAsJsonObject();
            P p = proj.project(o.get("lat").getAsDouble(), o.get("lon").getAsDouble());
            if (p != null && (out.isEmpty() || !out.getLast().equals(p))) {
                out.add(p);
            }
        }
        return out;
    }

    private static Map<String, String> tags(JsonObject e) {
        Map<String, String> m = new HashMap<>();
        if (e.has("tags")) {
            for (Map.Entry<String, JsonElement> t : e.getAsJsonObject("tags").entrySet()) {
                m.put(t.getKey(), t.getValue().getAsString());
            }
        }
        return m;
    }

    private static Building building(long id, List<P> ring, Map<String, String> tags, List<List<P>> holes) {
        boolean part = !tags.containsKey("building") && tags.containsKey("building:part");
        String kind = (part ? tags.get("building:part") : tags.getOrDefault("building", "yes")).toLowerCase(Locale.ROOT);
        if (part && (kind.equals("yes") || kind.equals("part"))) {
            kind = tags.containsKey("tower:type") || "tower".equals(tags.get("man_made")) ? "tower" : "part";
        }
        int height = height(tags, kind);
        String roof = tags.getOrDefault("roof:shape", "").toLowerCase(Locale.ROOT);
        if (roof.isEmpty()) {
            roof = defaultRoof(kind, ring);
        }
        String name = tags.getOrDefault("name", "");
        int minHeight = minHeight(tags, height);
        return new Building(id, ring, height, roof, kind, name, OsmMaterials.wall(tags), OsmMaterials.roof(tags),
                minHeight, part, holes);
    }

    /** OSM min_height bzw. building:min_level × 3; nie über der Oberkante. */
    static int minHeight(Map<String, String> tags, int height) {
        Double m = number(tags.get("min_height"));
        if (m == null) {
            Double levels = number(tags.get("building:min_level"));
            m = levels == null ? 0.0 : levels * 3;
        }
        return (int) Math.max(0, Math.min(height - 1, Math.round(m)));
    }

    static int height(Map<String, String> tags, String kind) {
        Double h = number(tags.get("height"));
        if (h == null) {
            Double levels = number(tags.get("building:levels"));
            if (levels != null) {
                h = levels * 3 + 1;
            }
        }
        if (h == null) {
            h = switch (kind) {
                case "garage", "garages", "shed", "carport", "hut", "kiosk", "power", "transformer_tower" -> 3.0;
                case "house", "detached", "semidetached_house", "bungalow", "farm", "barn", "stable" -> 7.0;
                case "church", "cathedral", "chapel" -> 14.0;
                case "tower", "bell_tower", "steeple" -> 30.0;
                case "apartments", "commercial", "office", "retail", "hotel", "public", "civic", "school",
                        "hospital", "university" -> 13.0;
                case "industrial", "warehouse" -> 9.0;
                default -> 10.0;  // Innenstadt: „yes“ ist meist ein Wohn-/Geschäftshaus mit 3 Stockwerken
            };
        }
        return (int) Math.max(3, Math.min(MAX_HEIGHT, Math.round(h)));
    }

    private static String defaultRoof(String kind, List<P> ring) {
        if (kind.equals("church") || kind.equals("chapel") || kind.equals("cathedral")) {
            return "gabled";
        }
        if (kind.startsWith("garage") || kind.equals("industrial") || kind.equals("warehouse") || kind.equals("retail")
                || kind.equals("commercial") || kind.equals("power")) {
            return "flat";
        }
        // Große Grundflächen (> 600 m²) eher flach – ein Walmdach über einem Kaufhaus sähe falsch aus.
        return area(ring) > 600 ? "flat" : "hipped";
    }

    static double area(List<P> ring) {
        double a = 0;
        for (int i = 0; i < ring.size(); i++) {
            P p = ring.get(i);
            P q = ring.get((i + 1) % ring.size());
            a += (double) p.x() * q.z() - (double) q.x() * p.z();
        }
        return Math.abs(a) / 2;
    }

    private static int roadWidth(Map<String, String> tags) {
        Double w = number(tags.get("width"));
        if (w != null) {
            return (int) Math.max(1, Math.min(30, Math.round(w)));
        }
        Double lanes = number(tags.get("lanes"));
        if (lanes != null) {
            return (int) Math.max(2, Math.min(30, Math.round(lanes * 3)));
        }
        return switch (tags.get("highway")) {
            case "motorway", "trunk" -> 12;
            case "primary", "secondary" -> 8;
            case "tertiary", "unclassified", "residential" -> 6;
            case "living_street", "service", "pedestrian" -> 4;
            case "track" -> 3;
            default -> 2;  // footway, path, cycleway, steps
        };
    }

    private static int waterwayWidth(Map<String, String> tags) {
        Double w = number(tags.get("width"));
        if (w != null) {
            return (int) Math.max(1, Math.min(60, Math.round(w)));
        }
        return switch (tags.get("waterway")) {
            case "river" -> 20;
            case "canal" -> 10;
            case "ditch", "drain" -> 2;
            default -> 3;
        };
    }

    private static String areaKind(Map<String, String> tags) {
        String natural = tags.get("natural");
        String landuse = tags.get("landuse");
        String leisure = tags.get("leisure");
        if ("water".equals(natural) || "reservoir".equals(landuse) || "basin".equals(landuse)) {
            return "water";
        }
        if ("wood".equals(natural) || "forest".equals(landuse)) {
            return "needleleaved".equals(tags.get("leaf_type")) ? "forest_needle" : "forest";
        }
        if ("parking".equals(tags.get("amenity"))) {
            // Tiefgarage und Parkhaus sind keine Fläche am Boden – das Parkhaus kommt als Gebäude, wenn es eins ist.
            String parking = tags.getOrDefault("parking", "surface");
            return parking.equals("underground") || parking.equals("multi-storey") || parking.equals("rooftop")
                    ? null : "parking";
        }
        if ("sand".equals(natural) || "beach".equals(natural)) {
            return "sand";
        }
        if ("farmland".equals(landuse)) {
            return "farmland";
        }
        if ("park".equals(leisure) || "garden".equals(leisure) || "grass".equals(landuse) || "meadow".equals(landuse)
                || "village_green".equals(landuse) || "recreation_ground".equals(landuse)) {
            return "grass";
        }
        if ("pitch".equals(leisure)) {
            return "pitch";
        }
        return null;  // residential/commercial/… Flächen: nichts bauen, die Häuser kommen einzeln
    }

    private static Double number(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Double.parseDouble(s.trim().replace(',', '.').replaceAll("[^0-9.]+$", "").replace(" m", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
