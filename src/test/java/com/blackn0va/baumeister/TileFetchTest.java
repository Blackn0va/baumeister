package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.WorldTiles.GeoBox;

import com.google.gson.JsonParser;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Abfragefolge einer Weltbau-Kachel: eine Abfrage, über der Datumsgrenze zwei – Versuche je Teil, Aufgeben, Mischen. */
class TileFetchTest {

    /** Kachel bei Fidschi, entfaltet über +180. */
    private static final GeoBox FIJI = new GeoBox(-16.81, 179.99, -16.79, 180.01);

    private static String answer(long id) {
        return "{\"version\":0.6,\"elements\":[{\"type\":\"node\",\"id\":" + id + ",\"lat\":-16.8,\"lon\":179.99}]}";
    }

    @Test
    void secondPartIsQueriedOnlyAfterTheFirstAnswer() {
        TileFetch f = new TileFetch();
        f.start(FIJI);
        assertEquals(new GeoBox(-16.81, 179.99, -16.79, 180), f.query());
        assertEquals(" teil=1/2", f.partText());
        assertFalse(f.answered(answer(1)), "nach Teil 1 fehlt noch Teil 2 – noch nicht planen");
        assertEquals(-180, f.query().west());
        assertEquals(-179.99, f.query().east(), 1e-9);
        assertEquals(" teil=2/2", f.partText());
        assertTrue(f.answered(answer(2)));
        String merged = TileFetch.merged(f.answers());
        assertEquals(2, JsonParser.parseString(merged).getAsJsonObject().getAsJsonArray("elements").size());
    }

    @Test
    void attemptsStartOverForTheSecondPart() {
        TileFetch f = new TileFetch();
        f.start(FIJI);
        assertTrue(f.failed(true, 3));
        assertTrue(f.failed(true, 3));
        assertEquals(2, f.attempts());
        assertFalse(f.answered(answer(1)));
        assertEquals(0, f.attempts(), "Teil 2 beginnt beim ersten Spiegel");
        assertTrue(f.failed(true, 3));
        assertTrue(f.failed(true, 3));
        assertFalse(f.failed(true, 3), "Teil 2 endgültig gescheitert → aufgeben");
        assertEquals(1, f.answers().size(), "nur Teil 1 da – nichts zum Zusammenführen");
    }

    @Test
    void nonRetryableErrorGivesUpAtOnce() {
        TileFetch f = new TileFetch();
        f.start(FIJI);
        assertFalse(f.failed(false, 3));
    }

    @Test
    void aNewTileForgetsTheOldAnswers() {
        // Stopp mitten in der Folge (neuer Lauf): Teil 1 der alten Kachel darf nicht in die neue Karte geraten.
        TileFetch f = new TileFetch();
        f.start(FIJI);
        assertFalse(f.answered(answer(1)));
        GeoBox plain = new GeoBox(48.7, 11.4, 48.8, 11.5);
        f.start(plain);
        assertEquals(plain, f.query());
        assertEquals("", f.partText());
        assertTrue(f.answered(answer(7)), "eine Kachel ohne Datumsgrenze ist nach einer Antwort fertig");
        assertEquals(List.of(answer(7)), f.answers());
        assertEquals(answer(7), TileFetch.merged(f.answers()), "eine Antwort bleibt unverändert");
    }
}
