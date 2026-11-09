package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verzeichnis gebauter Städte für /bm ort &lt;Stadt&gt; &lt;Straße&gt; (Nutzer 2026-10-30). */
class CityIndexTest {

    private static CityResume.State city(String name, int x) {
        return new CityResume.State(name, 48.88, 8.96, 2000, 12_000_000L, "stadtwelt", x, -61, 2476, 40, 225, true,
                null, "Blackkn0va", 7);
    }

    @Test
    void postcodeTownIsFoundWithAndWithoutTheNumber(@TempDir Path dir) {
        CityIndex idx = new CityIndex(dir.resolve("staedte.json"));
        idx.put(city("71735 Eberdingen", 100));
        CityIndex.Match a = idx.prefix(List.of("Eberdingen", "kepplerstrasse")).orElseThrow();
        assertEquals("71735 Eberdingen", a.city().place());
        assertEquals(1, a.rest());
        CityIndex.Match b = idx.prefix(List.of("71735", "Eberdingen", "Keplerstraße", "5")).orElseThrow();
        assertEquals(2, b.rest());
    }

    @Test
    void exactNameWinsAndOnlyACityNameIsNoStreet(@TempDir Path dir) {
        CityIndex idx = new CityIndex(dir.resolve("staedte.json"));
        idx.put(city("71735 Eberdingen", 100));
        idx.put(city("Eberdingen", 200));
        assertEquals(200, idx.prefix(List.of("eberdingen", "keplerstr")).orElseThrow().city().x());
        assertTrue(idx.prefix(List.of("Eberdingen")).isEmpty(), "ohne Straße bleibt es ein Reisepunkt");
        assertTrue(idx.prefix(List.of("Sindelfingen", "Hauptstraße")).isEmpty());
    }

    /**
     * dev01 2026-10-30: Nach dem Zurücksetzen der Stadtwelt gewann der tote Eintrag „Eberdingen“ vor dem neuen Bau
     * „71735 Eberdingen“. Nach dem Löschen führt „Eberdingen &lt;Straße&gt;“ zum neuen Bau – auch nach einem Neustart.
     */
    @Test
    void removedCityLetsThePostcodeTownTakeOver(@TempDir Path dir) {
        Path f = dir.resolve("staedte.json");
        CityIndex idx = new CityIndex(f);
        idx.put(city("Eberdingen", -468));
        idx.put(city("71735 Eberdingen", 19));
        assertEquals(-468, idx.prefix(List.of("Eberdingen", "kepplerstrasse")).orElseThrow().city().x());
        assertTrue(idx.remove("eberdingen"));
        assertEquals(19, idx.prefix(List.of("Eberdingen", "kepplerstrasse")).orElseThrow().city().x());
        assertEquals(19, new CityIndex(f).prefix(List.of("Eberdingen", "kepplerstrasse")).orElseThrow().city().x());
        assertTrue(!idx.remove("Eberdingen"), "zweimal löschen: nichts mehr da");
    }

    @Test
    void survivesARestartAndANewBuildReplacesTheOldOne(@TempDir Path dir) {
        Path f = dir.resolve("staedte.json");
        CityIndex idx = new CityIndex(f);
        idx.put(city("Eberdingen", 100));
        idx.put(city("Eberdingen", 300));
        CityIndex again = new CityIndex(f);
        assertEquals(1, again.all().size());
        assertEquals(300, again.all().getFirst().x());
        assertEquals(48.88, again.all().getFirst().lat());
    }
}
