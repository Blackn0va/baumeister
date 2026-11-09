package com.blackn0va.baumeister;

import com.blackn0va.baumeister.osm.CityPlanner.Section;

import java.util.List;
import java.util.Optional;

/**
 * Wohin /bm ort &lt;Stadt&gt; führt: zur Etappe mit den meisten Gebäuden, nicht zum Mittelpunkt laut Nominatim.
 *
 * Vorher (bis 2026-10-30) war der Reisepunkt die Mitte des Bauplatzes = der Punkt, den Nominatim für den Ort liefert.
 * Für eine Gemeinde aus mehreren Dörfern ist das der Schwerpunkt der Gemeindefläche: Eberdingen (dev01, 2 km Radius)
 * – am Reisepunkt standen in 9 × 9 Chunks nur Acker und Feldwege, die Etappen 1–15 rund um die Mitte hatten laut
 * Planung 0 Gebäude, die drei Dörfer lagen 1,4–1,8 km entfernt („Eberdingen 51 %, aber ich sehe nichts“).
 *
 * Jetzt meldet CityJob jede Etappe beim Baubeginn; hat sie mehr Gebäudeabschnitte als jede vorige, wandert der
 * Reisepunkt in den Schwerpunkt ihrer Gebäude. Gezählt werden Abschnitte mit building=true (ein Gebäude oder eine
 * Gebäudegruppe), nicht Blöcke – ein großes Hallendach soll kein Dorf schlagen. Der Bestwert reist im Fortsetzen-Stand
 * mit (CityResume.State.travel), sonst würfe nach einem Neustart die erste Etappe mit einem Schuppen den Ortskern
 * wieder hinaus.
 *
 * Threads: nur Hauptthread. Fehler: keine.
 */
final class TravelSpot {

    /** Neuer Reisepunkt relativ zur Mitte des Bauplatzes und wie viele Gebäudeabschnitte ihn tragen. */
    record Spot(int x, int z, int buildings) {
    }

    private int best;

    /** best: Gebäude des bisherigen Reisepunkts (0 = noch keiner, beim Fortsetzen aus dem Stand). */
    TravelSpot(int best) {
        this.best = Math.max(0, best);
    }

    /** Etappe bewerten; ein Spot nur, wenn sie mehr Gebäude hat als jede vorige. */
    Optional<Spot> offer(List<Section> sections) {
        int n = 0;
        long sx = 0;
        long sz = 0;
        for (Section s : sections) {
            if (s.building()) {
                n++;
                sx += s.cx();
                sz += s.cz();
            }
        }
        if (n <= best) {
            return Optional.empty();
        }
        best = n;
        return Optional.of(new Spot((int) Math.round((double) sx / n), (int) Math.round((double) sz / n), n));
    }

    int best() {
        return best;
    }
}
