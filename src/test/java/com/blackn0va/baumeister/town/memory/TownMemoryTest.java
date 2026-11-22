package com.blackn0va.baumeister.town.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Gedächtnis der Bewohner (Vertrag v013, AK-41): Grenzen, Ablauf, Speichern, Pseudonyme, keine rohen Chatzeilen. */
class TownMemoryTest {

    private static final long DAY = 24L * 60 * 60 * 1000;

    @Test
    void keepsAtMostTwentyEntriesOldestDropFirst() {
        TownMemory m = TownMemory.inMemory();
        List<String> topics = List.of("Brot", "Fisch", "Holz", "Eisen", "Garten", "Buch", "Weizen", "Malen", "Post",
                "krank", "Musik", "Smaragd", "Haus", "Wetter", "Weg", "Essen", "Arbeit", "geht's");
        int i = 0;
        for (String t : topics) {
            m.remember("Lotte", Fact.heardFrom("Spieler AA", t), 1000 + i++);
        }
        m.remember("Lotte", Fact.heardFrom("Spieler AB", "danke"), 1000 + i++);
        m.remember("Lotte", Fact.passedBy("Spieler AB"), 1000 + i++);
        m.remember("Lotte", Fact.passedBy("Spieler AC"), 1000 + i++);
        m.remember("Lotte", Fact.met("Emil"), 1000 + i++);
        List<TownMemory.Entry> e = m.entries("Lotte", 2000);
        assertEquals(TownMemory.MAX_ENTRIES, e.size());
        // 22 gemerkt, die zwei ältesten (Brot, Fischen) sind weg.
        assertFalse(m.facts("Lotte", 2000).contains("Spieler AA fragte mich nach Brot."), m.facts("Lotte", 2000)
                .toString());
        assertFalse(m.facts("Lotte", 2000).contains("Spieler AA fragte mich nach Fischen."));
        assertEquals("Ich habe Emil getroffen und geplaudert.", e.getLast().fact().text());
        assertEquals(0, m.entries("Emil", 2000).size(), "Gedächtnis gehört dem Bewohner");
    }

    @Test
    void sameFactMovesToTheEndInsteadOfFillingUp() {
        TownMemory m = TownMemory.inMemory();
        m.remember("Lotte", Fact.passedBy("Spieler AA"), 1);
        m.remember("Lotte", Fact.met("Emil"), 2);
        m.remember("Lotte", Fact.passedBy("Spieler AA"), 3);
        List<TownMemory.Entry> e = m.entries("Lotte", 4);
        assertEquals(2, e.size());
        assertEquals("Spieler AA kam bei mir vorbei.", e.getLast().fact().text());
        assertEquals(3, e.getLast().at());
    }

    @Test
    void entriesExpireAfterSevenDays() {
        TownMemory m = TownMemory.inMemory();
        m.remember("Lotte", Fact.passedBy("Spieler AA"), 0);
        m.remember("Lotte", Fact.met("Emil"), 2 * DAY);
        assertEquals(2, m.entries("Lotte", 7 * DAY - 1).size());
        assertEquals(List.of("Ich habe Emil getroffen und geplaudert."), m.facts("Lotte", 7 * DAY));
        assertEquals(0, m.entries("Lotte", 9 * DAY).size());
    }

    @Test
    void savedAndLoadedWithoutExpiredOrForeignLines(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(TownMemory.FILE);
        TownMemory m = new TownMemory(file);
        m.remember("Lotte", Fact.heardFrom("Spieler KM", "Hast du Brot?"), 5 * DAY);
        m.remember("Emil", Fact.met("Lotte"), 5 * DAY);
        assertTrue(m.save(5 * DAY));
        assertFalse(m.save(5 * DAY), "ohne Änderung kein Schreiben");
        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(json.contains("Hast du"), "Chatzeile auf der Platte: " + json);

        // Von Hand eingeschleuste Chatzeile, abgelaufener und kaputter Eintrag: fallen beim Laden heraus.
        String tampered = json.replace("[{", "[{\"t\":\"Spieler KM sagte: mein Passwort ist geheim\",\"at\":"
                + (5 * DAY) + "},{\"t\":\"Spieler KM kam bei mir vorbei.\",\"at\":0},{\"t\":5},{");
        Files.writeString(file, tampered, StandardCharsets.UTF_8);
        TownMemory back = new TownMemory(file);
        back.load(8 * DAY);
        assertEquals(List.of("Spieler KM fragte mich nach Brot."), back.facts("Lotte", 8 * DAY));
        assertEquals(List.of("Ich habe Lotte getroffen und geplaudert."), back.facts("Emil", 8 * DAY));
        assertEquals(2, back.size());
    }

    @Test
    void brokenFileIsAnIoErrorNotACrash(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(TownMemory.FILE);
        Files.writeString(file, "{kaputt", StandardCharsets.UTF_8);
        TownMemory m = new TownMemory(file);
        try {
            m.load(0);
            throw new AssertionError("kaputte Datei ohne Fehler gelesen");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().startsWith("gedächtnis_"), expected.getMessage());
        }
        assertEquals(0, m.size());
    }

    @Test
    void forgetOneOrAll() {
        TownMemory m = TownMemory.inMemory();
        m.remember("Lotte", Fact.met("Emil"), 1);
        m.remember("Lotte", Fact.passedBy("Spieler AA"), 1);
        m.remember("Emil", Fact.met("Lotte"), 1);
        assertEquals(2, m.forget("lotte"));
        assertEquals(0, m.entries("Lotte", 2).size());
        assertEquals(1, m.size());
        assertEquals(1, m.forgetAll());
        assertEquals(0, m.size());
    }

    /** Aus einer Chatzeile wird nur ein Thema – Wortlaut und darin genannte Namen kommen nicht in den Fakt. */
    @Test
    void factNeverCarriesTheChatLine() {
        Fact f = Fact.heardFrom("Spieler QX", "Ich bin Zxqvbnm7 und will Brot, mein Passwort ist hallo123");
        assertEquals("Spieler QX fragte mich nach Brot.", f.text());
        assertEquals("Spieler QX hat mit mir geplaudert.", Fact.heardFrom("Spieler QX", "Zxqvbnm7 hier!").text());
        assertEquals("Spieler QX hat sich bei mir bedankt.", Fact.heardFrom("Spieler QX", "Danke dir!").text());
        assertTrue(Fact.accepts(f.text()));
        assertFalse(Fact.accepts("Spieler QX sagte: Zxqvbnm7"));
        assertFalse(Fact.accepts("Zxqvbnm7 kam bei mir vorbei."));
    }

    @Test
    void pseudonymIsStableAndNotTheName(@TempDir Path dir) throws Exception {
        Pseudonyms a = Pseudonyms.open(dir);
        assertTrue(Files.isRegularFile(dir.resolve(Pseudonyms.KEY_FILE)));
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String alias = a.of(id);
        assertTrue(alias.matches("Spieler [A-Z]{2}"), alias);
        assertEquals(alias, a.of(id));
        // Neu geladen aus demselben Ordner: dasselbe Pseudonym (Neustart).
        assertEquals(alias, Pseudonyms.open(dir).of(id));
        // Anderes Geheimnis: andere Pseudonyme – über 40 Spieler höchstens ein paar zufällige Gleiche.
        Pseudonyms other = Pseudonyms.ephemeral();
        int same = 0;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            UUID u = new UUID(7, i);
            if (a.of(u).equals(other.of(u))) {
                same++;
            }
            seen.add(a.of(u));
        }
        assertTrue(same <= 4, "Pseudonym hängt nicht am Geheimnis: " + same + " von 40 gleich");
        assertTrue(seen.size() >= 35, "zu viele Doppelte: " + seen.size());
        assertNotEquals(a.of(new UUID(7, 0)), a.of(new UUID(7, 1)));
    }

    @Test
    void conversationKeepsTheLastFourFreshLines() {
        Conversation c = new Conversation();
        for (int i = 1; i <= 6; i++) {
            c.add(List.of("Lotte", "Emil"), "Lotte", "Satz " + i, i * 1000L);
        }
        List<Conversation.Line> l = c.recent("emil", 7000);
        assertEquals(Conversation.KEEP, l.size());
        assertEquals("Satz 3", l.getFirst().text());
        assertEquals("Satz 6", l.getLast().text());
        assertEquals(0, c.recent("Lotte", 6000 + Conversation.FRESH_MS + 1).size(), "alt = anderes Gespräch");
        assertEquals(0, c.recent("Greta", 7000).size(), "Greta war nicht dabei");
    }
}
