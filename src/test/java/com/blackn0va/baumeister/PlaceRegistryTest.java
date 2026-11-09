package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Stream;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reisepunkte: Speichern/Laden, Namensschlüssel, Aktualisieren, Seiten, sichere Ankunftshöhe – ohne Server. */
class PlaceRegistryTest {

    private static final Instant T0 = Instant.parse("2026-10-28T20:00:00Z");

    private static PlaceRegistry registry(Path dir, Instant now) {
        return new PlaceRegistry(dir.resolve("orte.json"), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void savedPlacesSurviveARestart(@TempDir Path dir) throws IOException {
        PlaceRegistry a = registry(dir, T0);
        a.put("Bad Kreuznach", PlaceRegistry.Kind.CITY, "stadtwelt", 0.5, -60, 0.5, 90f, 10f);
        a.put("Grand Canyon", PlaceRegistry.Kind.TERRAIN, "world_baumeister_gelaende", 65_536.5, 777, 0.5, 0f, 0f);

        PlaceRegistry b = registry(dir, T0.plusSeconds(3600));
        assertEquals(2, b.load());
        PlaceRegistry.Place p = b.find("bad kreuznach");
        assertNotNull(p);
        assertEquals(new PlaceRegistry.Place("Bad Kreuznach", PlaceRegistry.Kind.CITY, "stadtwelt", 0.5, -60, 0.5, 90f,
                10f, T0), p);
        assertEquals(PlaceRegistry.Kind.TERRAIN, b.find("Grand Canyon").kind());
        // Atomar geschrieben: keine Nachbardatei bleibt liegen.
        assertFalse(Files.exists(dir.resolve("orte.json.tmp")));
        String json = Files.readString(dir.resolve("orte.json"), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"art\": \"gelaende\""), json);
    }

    @Test
    void missingFileMeansEmptyList(@TempDir Path dir) {
        assertEquals(0, registry(dir, T0).load());
    }

    @Test
    void corruptFileIsSetAsideInsteadOfOverwritten(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("orte.json"), "{ kaputt", StandardCharsets.UTF_8);
        PlaceRegistry r = registry(dir, T0);
        assertEquals(0, r.load());
        r.put("Neu", PlaceRegistry.Kind.CUSTOM, "world", 1, 2, 3, 0f, 0f);
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> aside = files.filter(f -> f.getFileName().toString().startsWith("orte.json.defekt-")).toList();
            assertEquals(1, aside.size());
            assertEquals("{ kaputt", Files.readString(aside.getFirst(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void brokenEntryCostsOnlyItself(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("orte.json"), "{\"version\":1,\"orte\":["
                + "{\"name\":\"Gut\",\"art\":\"stadt\",\"welt\":\"w\",\"x\":1,\"y\":2,\"z\":3,"
                + "\"angelegt\":\"2026-10-28T20:00:00Z\"},"
                + "{\"name\":\"Ohne Welt\",\"art\":\"stadt\",\"x\":1,\"y\":2,\"z\":3,\"angelegt\":\"2026-10-28T20:00:00Z\"},"
                + "{\"name\":\"Falsche Art\",\"art\":\"burg\",\"welt\":\"w\",\"x\":1,\"y\":2,\"z\":3,"
                + "\"angelegt\":\"2026-10-28T20:00:00Z\"}]}", StandardCharsets.UTF_8);
        PlaceRegistry r = registry(dir, T0);
        assertEquals(1, r.load());
        assertEquals("Gut", r.find("gut").name());
    }

    @Test
    void umlautsAndCaseMapToOneKey() {
        assertEquals("muenchen", PlaceRegistry.key("München")); // umlaut-ok
        assertEquals("muenchen", PlaceRegistry.key("muenchen")); // umlaut-ok
        assertEquals("muenchen", PlaceRegistry.key("MÜNCHEN")); // umlaut-ok
        assertEquals("strasse", PlaceRegistry.key("Straße"));
        assertEquals("koeln", PlaceRegistry.key("  KÖLN ")); // umlaut-ok
        assertEquals("bad kreuznach", PlaceRegistry.key("Bad   Kreuznach"));
        assertEquals("sevres", PlaceRegistry.key("Sèvres"));
    }

    @Test
    void findIgnoresCaseAndUmlautSpellingButKeepsTheDisplayName(@TempDir Path dir) throws IOException {
        PlaceRegistry r = registry(dir, T0);
        r.put("München", PlaceRegistry.Kind.CITY, "stadtwelt", 0, 0, 0, 0f, 0f);
        for (String typed : List.of("München", "muenchen", "MÜNCHEN", "MUENCHEN")) { // umlaut-ok
            assertEquals("München", r.find(typed).name(), typed);
        }
        assertEquals(List.of("München"), r.names());
    }

    @Test
    void sameNameUpdatesInsteadOfDuplicating(@TempDir Path dir) throws IOException {
        PlaceRegistry r = registry(dir, T0);
        r.put("München", PlaceRegistry.Kind.CITY, "stadtwelt", 0, 0, 0, 0f, 0f);
        PlaceRegistry later = registry(dir, T0.plusSeconds(86_400));
        later.load();
        String typed = "MUENCHEN"; // umlaut-ok
        PlaceRegistry.Place p = later.put(typed, PlaceRegistry.Kind.CITY, "stadtwelt", 500, 10, -20, 45f, 0f);
        assertEquals(1, later.all().size());
        assertEquals("München", p.name(), "Anzeige wie angelegt");
        assertEquals(T0, p.created(), "Anlagedatum bleibt");
        assertEquals(500, p.x());
        PlaceRegistry reread = registry(dir, T0);
        assertEquals(1, reread.load());
        assertEquals(500, reread.find("münchen").x());
    }

    @Test
    void automaticEntryOfAnotherKindGetsItsOwnName(@TempDir Path dir) throws IOException {
        PlaceRegistry r = registry(dir, T0);
        r.rememberAuto("München", PlaceRegistry.Kind.CITY, "stadtwelt", 0, 0, 0, 0f, 0f);
        r.rememberAuto("München", PlaceRegistry.Kind.TERRAIN, "gelaende", 65_536, 520, 0, 0f, 0f);
        r.rememberAuto("München", PlaceRegistry.Kind.CITY, "stadtwelt", 7, 0, 7, 0f, 0f);
        assertEquals(List.of("München", "München (Gelände)"), r.names());
        assertEquals(7, r.find("München").x(), "gleiche Art aktualisiert");
        assertEquals(PlaceRegistry.Kind.TERRAIN, r.find("muenchen (gelaende)").kind()); // umlaut-ok
    }

    @Test
    void automaticEntryWithALongNameKeepsBothPlaces(@TempDir Path dir) throws IOException {
        String longName = "Sankt " + "Irgendwo".repeat(10);
        PlaceRegistry r = registry(dir, T0);
        String city = r.rememberAuto(longName, PlaceRegistry.Kind.CITY, "stadtwelt", 1, 2, 3, 0f, 0f).name();
        PlaceRegistry.Place terrain = r.rememberAuto(longName, PlaceRegistry.Kind.TERRAIN, "gelaende", 65_536, 520, 0,
                0f, 0f);
        assertEquals(2, r.all().size(), r.all().toString());
        assertTrue(terrain.name().endsWith(" (Gelände)"), terrain.name());
        assertTrue(terrain.name().length() <= PlaceRegistry.MAX_NAME, terrain.name());
        assertEquals(PlaceRegistry.Kind.CITY, r.find(city).kind(), "Stadt bleibt");
        assertEquals(1, r.find(city).x());
        PlaceRegistry reread = registry(dir, T0);
        assertEquals(2, reread.load());
        // Zweiter Geländelauf aktualisiert den Eintrag mit Zusatz, statt einen dritten anzulegen.
        r.rememberAuto(longName, PlaceRegistry.Kind.TERRAIN, "gelaende", 9, 9, 9, 0f, 0f);
        assertEquals(2, r.all().size());
        assertEquals(9, r.find(terrain.name()).x());
    }

    @Test
    void automaticEntryNeverOverwritesAnotherKindUnderTheSuffixedName(@TempDir Path dir) throws IOException {
        PlaceRegistry r = registry(dir, T0);
        r.put("München (Gelände)", PlaceRegistry.Kind.CUSTOM, "world", 5, 6, 7, 0f, 0f);
        r.rememberAuto("München", PlaceRegistry.Kind.CITY, "stadtwelt", 0, 0, 0, 0f, 0f);
        assertThrows(IllegalArgumentException.class, () -> r.rememberAuto("München", PlaceRegistry.Kind.TERRAIN,
                "gelaende", 65_536, 520, 0, 0f, 0f));
        assertEquals(PlaceRegistry.Kind.CUSTOM, r.find("München (Gelände)").kind(), "gemerkter Ort bleibt");
        assertEquals(PlaceRegistry.Kind.CITY, r.find("München").kind());
        assertEquals(2, r.all().size());
    }

    @Test
    void nonFiniteCoordinatesAreRejectedBeforeAnythingIsSaved(@TempDir Path dir) {
        PlaceRegistry r = registry(dir, T0);
        PlaceRegistry.Kind k = PlaceRegistry.Kind.EARTH;
        assertThrows(IllegalArgumentException.class, () -> r.put("Kaputt", k, "w", Double.NaN, 64, 0, 0f, 0f));
        assertThrows(IllegalArgumentException.class, () -> r.put("Kaputt", k, "w", 0, Double.POSITIVE_INFINITY, 0,
                0f, 0f));
        assertThrows(IllegalArgumentException.class, () -> r.put("Kaputt", k, "w", 0, 64, Double.NEGATIVE_INFINITY,
                0f, 0f));
        assertThrows(IllegalArgumentException.class, () -> r.put("Kaputt", k, "w", 0, 64, 0, Float.NaN, 0f));
        assertThrows(IllegalArgumentException.class, () -> r.rememberAuto("Kaputt", k, "w", 0, 64, 0, 0f,
                Float.POSITIVE_INFINITY));
        assertTrue(r.all().isEmpty());
        assertFalse(Files.exists(dir.resolve("orte.json")), "nichts geschrieben");
    }

    @Test
    void nonFiniteCoordinatesInTheFileCostOnlyThatEntry(@TempDir Path dir) throws IOException {
        // Gson liest NaN/Infinity nachsichtig ein – genau so stand es nach dem alten put() in orte.json.
        Files.writeString(dir.resolve("orte.json"), "{\"version\":1,\"orte\":["
                + "{\"name\":\"Gut\",\"art\":\"erde\",\"welt\":\"w\",\"x\":1,\"y\":2,\"z\":3,"
                + "\"angelegt\":\"2026-10-28T20:00:00Z\"},"
                + "{\"name\":\"NaN-Ort\",\"art\":\"erde\",\"welt\":\"w\",\"x\":NaN,\"y\":2,\"z\":3,"
                + "\"angelegt\":\"2026-10-28T20:00:00Z\"},"
                + "{\"name\":\"Unendlich\",\"art\":\"erde\",\"welt\":\"w\",\"x\":1,\"y\":Infinity,\"z\":3,"
                + "\"angelegt\":\"2026-10-28T20:00:00Z\"}]}", StandardCharsets.UTF_8);
        PlaceRegistry r = registry(dir, T0);
        assertEquals(1, r.load());
        assertEquals(List.of("Gut"), r.names());
    }

    @Test
    void decomposedUmlautsMapToTheSameKey(@TempDir Path dir) throws IOException {
        String decomposed = Normalizer.normalize("München", Normalizer.Form.NFD);
        assertEquals("muenchen", PlaceRegistry.key(decomposed)); // umlaut-ok
        assertEquals("muenchen", PlaceRegistry.key(Normalizer.normalize("MÜNCHEN", Normalizer.Form.NFD))); // umlaut-ok
        assertEquals("sevres", PlaceRegistry.key(Normalizer.normalize("Sèvres", Normalizer.Form.NFD)));
        PlaceRegistry r = registry(dir, T0);
        r.put("München", PlaceRegistry.Kind.CITY, "stadtwelt", 0, 0, 0, 0f, 0f);
        assertNotNull(r.find(decomposed));
        r.put(decomposed, PlaceRegistry.Kind.CITY, "stadtwelt", 4, 0, 0, 0f, 0f);
        assertEquals(1, r.all().size(), "zerlegt und zusammengesetzt sind derselbe Ort");
    }

    @Test
    void rememberWithoutWorldStoresNothing(@TempDir Path dir) {
        PlaceRegistry r = registry(dir, T0);
        assertNull(r.remember("Irgendwo", new Location(null, 1, 2, 3), PlaceRegistry.Kind.EARTH));
        assertNull(r.remember("Irgendwo", new Location(null, 1, 2, 3), "unbekannt"));
        assertTrue(r.all().isEmpty());
    }

    @Test
    void namesAreCleanedAndEmptyNamesRejected(@TempDir Path dir) throws IOException {
        PlaceRegistry r = registry(dir, T0);
        assertEquals("Rot", r.put("§cRot", PlaceRegistry.Kind.CUSTOM, "w", 0, 0, 0, 0f, 0f).name());
        assertThrows(IllegalArgumentException.class, () -> r.put("  ", PlaceRegistry.Kind.CUSTOM, "w", 0, 0, 0, 0f, 0f));
        assertThrows(IllegalArgumentException.class, () -> r.put("x", PlaceRegistry.Kind.CUSTOM, "", 0, 0, 0, 0f, 0f));
        assertTrue(r.put("A".repeat(100), PlaceRegistry.Kind.CUSTOM, "w", 0, 0, 0, 0f, 0f).name().length()
                <= PlaceRegistry.MAX_NAME);
    }

    @Test
    void removeDeletesOnlyTheNamedPlace(@TempDir Path dir) throws IOException {
        PlaceRegistry r = registry(dir, T0);
        r.put("Köln", PlaceRegistry.Kind.CITY, "w", 0, 0, 0, 0f, 0f);
        r.put("Mainz", PlaceRegistry.Kind.CITY, "w", 0, 0, 0, 0f, 0f);
        assertTrue(r.remove("KOELN")); // umlaut-ok
        assertFalse(r.remove("Köln"));
        PlaceRegistry reread = registry(dir, T0);
        reread.load();
        assertEquals(List.of("Mainz"), reread.names());
    }

    @Test
    void searchMatchesPartsOfTheKey(@TempDir Path dir) throws IOException {
        PlaceRegistry r = registry(dir, T0);
        for (String n : List.of("München", "Münster", "Bad Kreuznach", "Grand Canyon")) {
            r.put(n, PlaceRegistry.Kind.CITY, "w", 0, 0, 0, 0f, 0f);
        }
        String part = "muen"; // umlaut-ok
        assertEquals(List.of("München", "Münster"), r.search(part).stream().map(PlaceRegistry.Place::name).toList());
        assertEquals(List.of("Bad Kreuznach"), r.search("KREUZ").stream().map(PlaceRegistry.Place::name).toList());
        assertEquals(List.of("Bad Kreuznach", "Grand Canyon", "München", "Münster"), r.names());
    }

    @Test
    void pagesSplitTheListAndClampOutOfRangeNumbers() {
        List<PlaceRegistry.Place> all = Stream.iterate(1, i -> i + 1).limit(23)
                .map(i -> new PlaceRegistry.Place("Ort " + i, PlaceRegistry.Kind.CUSTOM, "w", 0, 0, 0, 0f, 0f, T0))
                .toList();
        PlaceRegistry.Page first = PlaceRegistry.page(all, 1, 10);
        assertEquals(10, first.items().size());
        assertEquals(3, first.pages());
        assertEquals(23, first.total());
        PlaceRegistry.Page last = PlaceRegistry.page(all, 3, 10);
        assertEquals(List.of("Ort 21", "Ort 22", "Ort 23"), last.items().stream().map(PlaceRegistry.Place::name)
                .toList());
        assertEquals(3, PlaceRegistry.page(all, 99, 10).page());
        assertEquals(1, PlaceRegistry.page(all, 0, 10).page());
        PlaceRegistry.Page empty = PlaceRegistry.page(List.of(), 1, 10);
        assertEquals(1, empty.pages());
        assertTrue(empty.items().isEmpty());
    }

    @Test
    void safeHeightClimbsUntilFeetAndHeadAreFree() {
        // Säule bei (5, z=7): fest bis y 69, Luft ab 70 – dazwischen eine Lücke bei 66 (nur ein Block hoch).
        Set<Integer> solid = Set.of(60, 61, 62, 63, 64, 65, 67, 68, 69);
        PlaceRegistry.BlockProbe probe = (x, y, z) -> !solid.contains(y);
        assertEquals(OptionalInt.of(70), PlaceRegistry.safeY(5, 62, 7, -64, 320, probe));
        assertEquals(OptionalInt.of(70), PlaceRegistry.safeY(5, 66, 7, -64, 320, probe), "Lücke zu niedrig");
        assertEquals(OptionalInt.of(75), PlaceRegistry.safeY(5, 75, 7, -64, 320, probe), "frei bleibt, wo es ist");
        assertEquals(OptionalInt.of(58), PlaceRegistry.safeY(5, 58, 7, -64, 320, probe), "nicht nach unten suchen");
    }

    @Test
    void safeHeightGivesUpAtTheCeilingAndClampsIntoTheWorld() {
        assertEquals(OptionalInt.empty(), PlaceRegistry.safeY(0, 0, 0, -64, 320, (x, y, z) -> false));
        assertEquals(OptionalInt.of(318), PlaceRegistry.safeY(0, 400, 0, -64, 320, (x, y, z) -> true));
        assertEquals(OptionalInt.of(-64), PlaceRegistry.safeY(0, -100, 0, -64, 320, (x, y, z) -> true));
    }

    @Test
    void listLineRunsTheTravelCommandAndShowsWhereOnHover() {
        BaseComponent[] line = Texts.linkLine("Bad Kreuznach", "/bm ort Bad Kreuznach", "Welt: stadtwelt\n0 -60 0",
                " · Stadt");
        assertEquals(3, line.length);
        ClickEvent click = line[1].getClickEvent();
        assertEquals(ClickEvent.Action.RUN_COMMAND, click.getAction());
        assertEquals("/bm ort Bad Kreuznach", click.getValue());
        assertEquals("Welt: stadtwelt\n0 -60 0", ((Text) line[1].getHoverEvent().getContents().getFirst()).getValue());
        assertNull(line[0].getClickEvent(), "nur der Name ist anklickbar");
        assertNull(line[2].getClickEvent());
    }
}
