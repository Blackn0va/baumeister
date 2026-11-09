package com.blackn0va.baumeister.osm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.blackn0va.baumeister.osm.CityModel.P;

/** Meer und Land aus Küstenlinien (Land links der Laufrichtung), 2026-11-01. */
class SeaMaskTest {

    private static List<P> line(int... xz) {
        P[] out = new P[xz.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = new P(xz[2 * i], xz[2 * i + 1]);
        }
        return List.of(out);
    }

    @Test
    void straightCoastHasLandOnTheLeftAndDeepensAwayFromIt() {
        // Laufrichtung +x: links (Kreuzprodukt > 0) ist +z.
        SeaMask m = SeaMask.of(List.of(line(-50, 50, 250, 50)), 0, 0, 200, 100);
        assertNotNull(m);
        assertFalse(m.sea(100, 80), "Landseite");
        assertTrue(m.sea(100, 20), "Meeresseite");
        assertTrue(m.toLand(100, 48) < m.toLand(100, 10), "Tiefe wächst vom Ufer weg");
        assertEquals(1, m.toLand(100, 49), "direkt am Ufer");
        assertTrue(m.toSea(100, 52) <= 3, "Strandstreifen");
        assertTrue(m.toSea(100, 90) > 3, "Hinterland ist kein Strand");
    }

    @Test
    void islandCornersStayInTheSea() {
        // Gegen den Uhrzeigersinn im Rechenrahmen: Land innen (links). Die Stelle schräg vor der Ecke ist am nächsten
        // an der ECKE (Außenkeil) – auch dort muss Meer herauskommen.
        List<P> island = line(50, 50, 150, 50, 150, 150, 50, 150, 50, 50);
        SeaMask m = SeaMask.of(List.of(island), 0, 0, 200, 200);
        assertNotNull(m);
        assertFalse(m.sea(100, 100), "Inselinneres");
        assertTrue(m.sea(20, 20), "vor der Ecke");
        assertTrue(m.sea(180, 180), "vor der gegenüberliegenden Ecke");
        assertTrue(m.sea(100, 10));
        assertTrue(SeaMask.landSide(List.of(island), 100, 100));
        assertFalse(SeaMask.landSide(List.of(island), 45, 45), "vor der Ecke: Meer");
    }

    @Test
    void bayCornerFromTheSeaSideCountsAsLandOnlyInside() {
        // Rechtskurve (Bucht, Land außen): Punkt in der Bucht ist Meer, Punkt hinter der Ecke Land.
        List<P> bay = line(0, 100, 100, 100, 100, 0);
        assertFalse(SeaMask.landSide(List.of(bay), 50, 50), "in der Bucht");
        assertTrue(SeaMask.landSide(List.of(bay), 150, 150), "hinter der Ecke");
    }

    @Test
    void stageInTheMiddleOfTheHarbourIsSeaWithoutAnyCoastInside() {
        // Ausschnitt weit vom Ufer: keine Uferzelle im Raster, trotzdem Meer (nächste Linie über die ganze Karte).
        SeaMask m = SeaMask.of(List.of(line(-5000, 1000, 5000, 1000)), 0, 0, 256, 256);
        assertNotNull(m);
        assertTrue(m.sea(128, 128));
        assertEquals(SeaMask.CAP, m.toLand(128, 128), "ohne Land im Raster: weit");
    }

    @Test
    void withoutCoastlineTheHeightModelDecides() {
        assertNull(SeaMask.of(List.of(), 0, 0, 10, 10));
    }
}
