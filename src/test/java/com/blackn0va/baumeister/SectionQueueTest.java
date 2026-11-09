package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Warteschlange der Abschnitte in CityJob: nur über take(), Rest eines Helfers vorn mit Rechteck. */
class SectionQueueTest {

    private record S(String name, int[] box) {
    }

    private static final S A = new S("A", new int[] {0, 0, 10, 10});
    private static final S B = new S("B", new int[] {10, 0, 20, 10});   // teilt die Wand x = 10 mit A
    private static final S C = new S("C", new int[] {100, 100, 110, 110});

    private static SectionQueue<S> queueOf(S... sections) {
        SectionQueue<S> q = new SectionQueue<>(S::box);
        for (S s : sections) {
            q.add(s);
        }
        return q;
    }

    @Test
    void restOfARemovedHelperStaysAheadOfItsLaterNeighbour() {
        // Wie CityJob.adjustHelpers: Ein Helfer baut A und fällt weg (/bm helfer weniger), sein Rest geht zurück.
        // Hinten eingereiht (Gegenprobe) nähme der nächste Arbeiter B – B setzte die gemeinsame Wand vor A.
        SectionQueue<S> q = queueOf(A, B, C);
        assertEquals(A, q.take(List.of()));
        S rest = new S("A-Rest", A.box());
        q.putBack(rest);
        assertEquals(rest, q.take(List.of()), "der Rest von A kommt vor B");
        assertEquals(C, q.take(List.of(rest.box())), "B wartet, solange der Rest von A läuft; C darf vor");
        assertNull(q.take(List.of(rest.box())), "nur noch B übrig, und das wartet");
        assertEquals(B, q.take(List.of()));
        assertEquals(0, q.size());
    }

    @Test
    void takeRespectsWhatOthersAreBuilding() {
        // Ein FIFO (pollFirst) gäbe B heraus, während ein anderer Arbeiter noch an A baut.
        SectionQueue<S> q = queueOf(B, C);
        assertEquals(C, q.take(List.of(A.box())));
        assertNull(q.take(List.of(A.box())));
        assertEquals(1, q.size());
    }
}
