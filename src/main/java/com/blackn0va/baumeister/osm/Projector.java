package com.blackn0va.baumeister.osm;

/**
 * Erdpunkt → Blockkoordinate für {@link OsmImport}. Vorher stand die Projektion fest im Import (gleichabständig um den
 * Mittelpunkt der Abfrage) – richtig für /bm stadt, wo der Ort frei auf eine Fläche gesetzt wird. Der Weltbau setzt
 * OSM dagegen auf das Gelände der Erdwelt, dort muss jedes Haus genau an der Stelle stehen, die der Weltgenerator für
 * diesen Punkt der Erde benutzt – also dieselbe Projektion (EarthProjection.toWorld minus Kachelursprung). Wer hier
 * wieder die eigene Näherung nimmt, verschiebt die Häuser gegen Hügel und Flüsse.
 *
 * Threads: wird nur im Planungs-Thread des Imports aufgerufen. Fehler: keine Ausnahmen – ein Punkt, den es auf der
 * Karte nicht gibt (Netzlücke der Erdwelt), liefert null und fällt weg.
 */
@FunctionalInterface
public interface Projector {

    /** Blockkoordinate des Punkts, oder null, wenn er nicht auf der Karte liegt – dann lässt der Import ihn weg. */
    CityModel.P project(double lat, double lon);
}
