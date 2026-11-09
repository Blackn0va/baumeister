package com.blackn0va.baumeister.osm;

import java.util.List;

/**
 * Ein Ort aus OpenStreetMap in Blockkoordinaten: 1 Block = 1 Meter, x nach Osten, z nach Süden (wie Minecraft),
 * (0,0) = Mittelpunkt der Abfrage.
 *
 * Kartendaten © OpenStreetMap-Mitwirkende, ODbL – im Spiel nennt /bm stadt die Quelle.
 */
public record CityModel(String place, double lat, double lon, int radius, List<Building> buildings, List<Road> roads,
        List<Area> areas, List<Tree> trees, List<Line> lines) {

    /** Ohne Linien (Gleise, Zäune) – Tests und Aufrufer aus der Zeit vor der Stadtausstattung (2026-10-29). */
    public CityModel(String place, double lat, double lon, int radius, List<Building> buildings, List<Road> roads,
            List<Area> areas, List<Tree> trees) {
        this(place, lat, lon, radius, buildings, roads, areas, trees, List.of());
    }

    /** Punkt in Blockkoordinaten. */
    public record P(int x, int z) {
    }

    /**
     * @param height    Oberkante der Wände in Blöcken über dem Boden (ohne Dach)
     * @param roof      "flat", "gabled", "hipped", "pyramidal", "dome" … (OSM roof:shape oder abgeleitet)
     * @param kind      OSM building=… bzw. building:part=… (yes, house, church, tower …)
     * @param wall      Fassadenblock aus Farbe/Baustoff, "" = Vorgabe des Planers
     * @param roofBlock Dachblock aus Farbe/Baustoff, "" = Vorgabe des Planers
     * @param minHeight Unterkante (OSM min_height) – ein Turmhelm beginnt erst über dem Kirchenschiff
     * @param part      OSM building:part (Turm, Seitenschiff …) – wird nach den Gebäuden gebaut und überschreibt sie
     * @param holes     Innenringe (Multipolygon-Rolle inner): Innenhöfe, dort entsteht weder Boden noch Dach des
     *                  Gebäudes. Vorher (bis 2026-10-28) las der Import nur outer – Blockrandbebauung bekam ein Dach
     *                  über den ganzen Hof.
     */
    public record Building(long id, List<P> outline, int height, String roof, String kind, String name, String wall,
            String roofBlock, int minHeight, boolean part, List<List<P>> holes) {

        /** Ohne Farben und Teile – für Tests und alte Aufrufer. */
        public Building(long id, List<P> outline, int height, String roof, String kind, String name) {
            this(id, outline, height, roof, kind, name, "", "", 0, false, List.of());
        }

        /** Ohne Innenhöfe – einfache Gebäude (ein geschlossener Way). */
        public Building(long id, List<P> outline, int height, String roof, String kind, String name, String wall,
                String roofBlock, int minHeight, boolean part) {
            this(id, outline, height, roof, kind, name, wall, roofBlock, minHeight, part, List.of());
        }
    }

    /**
     * @param kind    OSM highway=…
     * @param passage OSM tunnel=building_passage: Der Weg führt durch ein Gebäude, der Planer schneidet dort einen
     *                Durchgang (Boden bleibt Weg, darüber drei Blöcke Luft). Ohne das Kennzeichen stünde die Hauswand
     *                quer über dem Weg – der Weg endete an der Fassade.
     * @param bridge  OSM bridge=* (außer „no“) oder layer&gt;0 über einer Querung: Der Planer hebt den Weg als
     *                Brücke mit Geländer über das, was darunter liegt (Bridges). Vorher lag jede Brücke als Straße am
     *                Boden – quer durch den Fluss bzw. als Kreuzung auf der unterquerten Straße.
     * @param layer   OSM layer (0 ohne Angabe); eine Brücke über einer Brücke steht höher.
     */
    public record Road(long id, List<P> line, int width, String kind, String name, boolean passage, boolean bridge,
            int layer) {

        /** Gewöhnlicher Weg an der Oberfläche. */
        public Road(long id, List<P> line, int width, String kind, String name) {
            this(id, line, width, kind, name, false, false, 0);
        }

        /** Weg an der Oberfläche, ggf. durch ein Gebäude (passage). */
        public Road(long id, List<P> line, int width, String kind, String name, boolean passage) {
            this(id, line, width, kind, name, passage, false, 0);
        }
    }

    /**
     * @param kind sea (Meeresfläche: water=harbour/bay/sea …, im Weltbau am Meeresspiegel), water, grass, forest, forest_needle (Nadelwald), sand, farmland, park, plaza, parking …
     */
    public record Area(long id, List<P> outline, String kind, List<List<P>> holes) {

        /** Ohne Löcher. holes gibt es für Wasser-Multipolygone (Inseln im Hafen, 2026-11-01). */
        public Area(long id, List<P> outline, String kind) {
            this(id, outline, kind, List.of());
        }
    }

    /** @param kind oak, birch oder spruce – aus leaf_type/genus des Baums, sonst oak. */
    public record Tree(int x, int z, String kind) {

        /** Laubbaum ohne Angabe. */
        public Tree(int x, int z) {
            this(x, z, "oak");
        }
    }

    /**
     * Linie ohne Fahrbahn: Gleise (kind rail, tram, light_rail, narrow_gauge, subway) und Begrenzungen (fence, hedge,
     * wall, retaining_wall, city_wall). Eigene Liste statt Road: Ein Gleis ist keine Straße – an ihm sucht kein Haus
     * seinen Eingang, und es bekommt keine Laternen.
     *
     * @param bridge Gleis auf einer Brücke (bridge=*, layer&gt;0)
     */
    public record Line(long id, List<P> points, String kind, boolean bridge) {
    }
}
