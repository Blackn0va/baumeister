package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Abenteuer-Tagebuch: Zeiten je Etappe, Titelseite, Überstehen eines Neustarts. */
class AdventureDiaryTest {

    private static final long MIN = 60_000;

    @Test
    void eachStageShowsHowLongItTook() {
        AdventureBook book = new AdventureBook();
        UUID a = UUID.randomUUID();
        book.start(a, Adventure.Mode.NORMAL, 0, Set.of("holz"));            // beim Start schon Holz
        book.observe(a, Set.of("holzspitzhacke"), 4 * MIN);                  // 4 Minuten später
        book.observe(a, Set.of("stein"), 84 * MIN);                          // 1 Std. 20 Min. danach
        AdventureBook.Run r = book.run(a).orElseThrow();
        assertEquals(3, r.stage());
        List<String> pages = AdventureDiary.pages(r, "Blackkn0va", 90 * MIN);
        assertTrue(pages.getFirst().contains("von Blackkn0va"), pages.getFirst());
        assertTrue(pages.getFirst().contains("Unterwegs: 1 Std. 30 Min."), pages.getFirst());
        assertTrue(pages.getFirst().contains("Tode: 0"));
        String first = pages.get(1);
        assertTrue(first.contains("✔ Holz sammeln\n§8   schon dabei"), first);
        assertTrue(first.contains("✔ Werkbank und Holzspitzhacke\n§8   nach 4 Min."), first);
        assertTrue(first.contains("✔ Steinzeit\n§8   nach 1 Std. 20 Min."), first);
        assertTrue(first.contains("➜ §lEin sicherer Schlafplatz"), first);
        // 14 Etappen à 5 je Seite = 3 Etappenseiten + Titelseite
        assertEquals(4, pages.size());
    }

    @Test
    void timesSurviveARestart() {
        AdventureBook book = new AdventureBook();
        UUID a = UUID.randomUUID();
        book.start(a, Adventure.Mode.HARDCORE, 1_000, Set.of());
        book.observe(a, Set.of("holz"), 7 * MIN);
        AdventureBook.Run back = AdventureBook.fromJson(book.toJson()).run(a).orElseThrow();
        assertEquals(7 * MIN, back.doneAt().get(0));
        assertTrue(AdventureDiary.pages(back, "x", 8 * MIN).getFirst().contains("Hardcore"));
    }

    @Test
    void durationsReadNaturally() {
        assertEquals("unter einer Minute", AdventureDiary.duration(30_000));
        assertEquals("5 Min.", AdventureDiary.duration(5 * MIN));
        assertEquals("2 Std.", AdventureDiary.duration(120 * MIN));
    }
}
