package com.blackn0va.baumeister.osm;

import com.blackn0va.baumeister.osm.CityModel.Area;
import com.blackn0va.baumeister.osm.CityModel.Building;
import com.blackn0va.baumeister.osm.CityModel.P;
import com.blackn0va.baumeister.osm.CityModel.Road;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Messprobe Multipolygon und Oberfläche (2026-10-28): liest zwischengespeicherte Overpass-Kacheln NUR LESEND (kein
 * Server, kein Netz) und zählt vorher/nachher Gebäude, verworfene Ringreste, Hofzellen, Wege und Platzzellen. „Vorher“
 * ist nachgerechnet nach den Regeln bis 2026-10-28: jeder outer-Way einer Gebäude-Relation mit ≥ 4 Punkten war ein
 * eigenes Gebäude, inner wurde ignoriert, jeder highway/waterway kam an die Oberfläche, Plätze gab es nicht.
 *
 * Aufruf (alle Argumente optional): MultipolygonProbe [kartenordner] [glob] [lat0] [lon0]. Vorgabe: München-Kacheln
 * im Kartencache des Testservers, Mitte München Mitte. Die Kacheln überlappen (Rand) – Elemente zählen je type/id
 * einmal.
 */
public final class MultipolygonProbe {

    private MultipolygonProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "C:\\swb\\baumeister-testserver\\plugins\\Baumeister\\karten");
        String glob = args.length > 1 ? args[1] : "48.1[34]*.json";
        double lat0 = args.length > 2 ? Double.parseDouble(args[2]) : 48.13711;
        double lon0 = args.length > 3 ? Double.parseDouble(args[3]) : 11.57539;
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, glob)) {
            ds.forEach(files::add);
        }
        files.sort(null);
        if (files.isEmpty()) {
            throw new IllegalStateException("Keine Kacheln " + glob + " in " + dir);
        }
        Map<String, JsonElement> unique = new LinkedHashMap<>();
        for (Path f : files) {
            JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            int n = 0;
            for (JsonElement e : root.getAsJsonArray("elements")) {
                JsonObject o = e.getAsJsonObject();
                if (unique.putIfAbsent(o.get("type").getAsString() + "/" + o.get("id").getAsLong(), e) == null) {
                    n++;
                }
            }
            System.out.printf("kachel %s: %d neue Elemente%n", f.getFileName(), n);
        }

        // ---------------------------------------------------------------- vorher (alte Regeln nachgerechnet)
        double cos = Math.cos(Math.toRadians(lat0));
        int oldBuildings = 0;
        int oldRoads = 0;
        int relations = 0;
        int relationsWithInner = 0;
        int openOuterMembers = 0;
        int innerMembers = 0;
        Map<String, Integer> removed = new HashMap<>();
        int plazaWays = 0;
        int passageWays = 0;
        int layerMinusKept = 0;
        Set<String> plazaNames = new TreeSet<>();
        for (JsonElement el : unique.values()) {
            JsonObject o = el.getAsJsonObject();
            Map<String, String> tags = tags(o);
            String type = o.get("type").getAsString();
            boolean building = tags.containsKey("building") || tags.containsKey("building:part");
            if (type.equals("way") && o.has("geometry")) {
                List<P> pts = points(o.getAsJsonArray("geometry"), lat0, lon0, cos);
                if (building) {
                    oldBuildings += pts.size() >= 4 ? 1 : 0;
                } else if ((tags.containsKey("highway") || tags.containsKey("waterway")) && pts.size() >= 2) {
                    oldRoads++;
                    if (!OsmImport.atSurface(tags)) {
                        removed.merge(reason(tags), 1, Integer::sum);
                    } else if (tags.containsKey("highway") && OsmImport.plaza(tags, pts)) {
                        plazaWays++;
                        plazaNames.add(tags.getOrDefault("name", "(ohne Namen)"));
                    } else {
                        passageWays += "building_passage".equals(tags.get("tunnel")) ? 1 : 0;
                        layerMinusKept += tags.getOrDefault("layer", "").trim().startsWith("-") ? 1 : 0;
                    }
                }
            } else if (type.equals("relation") && building && o.has("members")) {
                relations++;
                boolean inner = false;
                for (JsonElement m : o.getAsJsonArray("members")) {
                    JsonObject mo = m.getAsJsonObject();
                    String role = mo.has("role") ? mo.get("role").getAsString() : "";
                    if (!mo.has("geometry")) {
                        continue;
                    }
                    List<P> pts = points(mo.getAsJsonArray("geometry"), lat0, lon0, cos);
                    if (role.equals("outer")) {
                        oldBuildings += pts.size() >= 4 ? 1 : 0;
                        openOuterMembers += pts.size() >= 2 && !pts.getFirst().equals(pts.getLast()) ? 1 : 0;
                    } else if (role.equals("inner")) {
                        innerMembers++;
                        inner = true;
                    }
                }
                relationsWithInner += inner ? 1 : 0;
            }
        }

        // ---------------------------------------------------------------- nachher (neuer Import)
        JsonArray all = new JsonArray();
        unique.values().forEach(all::add);
        JsonObject merged = new JsonObject();
        merged.add("elements", all);
        int[] dropped = new int[1];
        long t0 = System.nanoTime();
        CityModel city = OsmImport.parse(merged.toString(), "München", lat0, lon0, 1000, dropped);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        int holes = 0;
        int withHoles = 0;
        long courtCells = 0;
        for (Building b : city.buildings()) {
            if (b.holes().isEmpty()) {
                continue;
            }
            withHoles++;
            holes += b.holes().size();
            courtCells += CityPlanner.fill(b.outline()).size()
                    - CityPlanner.fill(b.outline(), b.holes(), CityPlanner.Clip.all()).size();
        }
        int plazas = 0;
        long plazaCells = 0;
        for (Area a : city.areas()) {
            if (a.kind().equals("plaza")) {
                plazas++;
                plazaCells += CityPlanner.fill(a.outline()).size();
            }
        }
        long passages = city.roads().stream().filter(Road::passage).count();

        System.out.printf("%nElemente gesamt (eindeutig): %d aus %d Kacheln, Import %d ms%n", unique.size(),
                files.size(), ms);
        System.out.printf("Gebäude-Relationen: %d, davon mit inner: %d (inner-Members %d), offene outer-Members %d%n",
                relations, relationsWithInner, innerMembers, openOuterMembers);
        System.out.printf("| Größe | vorher | nachher |%n|---|---|---|%n");
        System.out.printf("| Gebäude | %d | %d |%n", oldBuildings, city.buildings().size());
        System.out.printf("| verworfene Ringreste | – | %d |%n", dropped[0]);
        System.out.printf("| Gebäude mit Hof / Höfe | 0 / 0 | %d / %d |%n", withHoles, holes);
        System.out.printf("| Hofzellen (vorher überdacht) | 0 | %d |%n", courtCells);
        System.out.printf("| Wege (highway+waterway) | %d | %d |%n", oldRoads, city.roads().size());
        System.out.printf("| Platzflächen / Platzzellen | 0 / 0 | %d / %d |%n", plazas, plazaCells);
        System.out.printf("Weggefallen nach Grund: %s%n", removed);
        System.out.printf("Plätze (Ways): %d – %s%n", plazaWays, plazaNames);
        System.out.printf("Durchgänge (building_passage): %d, Wege mit layer<0 behalten: %d%n", passageWays,
                layerMinusKept);
        if (passages != passageWays) {
            System.out.printf("ABWEICHUNG Durchgänge: Import %d, Zählung %d%n", passages, passageWays);
        }
    }

    private static String reason(Map<String, String> tags) {
        String tunnel = tags.getOrDefault("tunnel", "");
        if (!tunnel.isEmpty() && !tunnel.equals("no") && !tunnel.equals("building_passage")) {
            return "tunnel=" + tunnel;
        }
        if ("yes".equals(tags.get("indoor"))) {
            return "indoor=yes";
        }
        if (tags.getOrDefault("level", "").trim().startsWith("-")) {
            return "level<0";
        }
        return "highway=" + tags.getOrDefault("highway", "?");
    }

    private static List<P> points(JsonArray geometry, double lat0, double lon0, double cos) {
        List<P> out = new ArrayList<>();
        for (JsonElement g : geometry) {
            if (!g.isJsonObject()) {
                continue;
            }
            JsonObject o = g.getAsJsonObject();
            P p = OsmImport.project(o.get("lat").getAsDouble(), o.get("lon").getAsDouble(), lat0, lon0, cos);
            if (out.isEmpty() || !out.getLast().equals(p)) {
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
}
