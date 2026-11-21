package com.blackn0va.baumeister.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Freigabeliste der Pfade über die Liste der Testvektoren hinaus (die prüft VectorTest): Länge, Abfrage, kodierte
 * Steuerzeichen, Groß/klein, Form der Segmente.
 */
class PathRulesTest {

    @Test
    void totalLengthIsAtMost2048() {
        String head = "/v1/";
        assertTrue(PathRules.allowed(head + "a".repeat(PathRules.MAX_LENGTH - head.length())));
        assertFalse(PathRules.allowed(head + "a".repeat(PathRules.MAX_LENGTH - head.length() + 1)));
    }

    @Test
    void queryNeedsAtLeastOneCharacterFromItsAlphabet() {
        assertTrue(PathRules.allowed("/v1/a?x"));
        assertTrue(PathRules.allowed("/v1/a?x=1&y=a+b.c~d_e-f"));
        assertFalse(PathRules.allowed("/v1/a?"));
        assertFalse(PathRules.allowed("/v1/a??x"));
        assertFalse(PathRules.allowed("/v1/a?x=1#f"));
        assertFalse(PathRules.allowed("/v1/a?x=/"));
        assertFalse(PathRules.allowed("/health?x=1"));
    }

    /**
     * Wie die Host-App: jedes „%“ ein vollständiges %XX, nie %2e/%2E (die App schlösse sonst die ganze Verbindung).
     * Dazu nie ein kodiertes Steuerzeichen – ein NUL könnte im C++-Server der App einen String abschneiden.
     */
    @Test
    void percentEncodingMustBeCompleteNeverADotAndNoControlCharacter() {
        for (String ok : List.of("%41", "%7e", "%7E", "%C3%BC", "%20", "%ff", "%2d", "%2F", "%2f")) {
            assertTrue(PathRules.allowed("/v1/a?x=" + ok), ok);
        }
        for (String bad : List.of("%2e", "%2E", "%2e%2e", "a%2Eb", "%00", "%0a", "%0D", "%1f", "%7f", "%7F", "%", "%4",
                "%g1", "%%41")) {
            assertFalse(PathRules.allowed("/v1/a?x=" + bad), bad);
        }
    }

    @Test
    void segmentsAreNonEmptyAndCaseMatters() {
        for (String bad : List.of("/v1/a/", "/v1//a", "/V1/a", "/v1/a.b", "/v1/a%41", "/baumeister/V1/x",
                "/baumeister/v1/x/", "/v1/" + (char) 0xE4, "v1/a", "", "/v1/a b", "/v1/a\tb", "/v2/a")) {
            assertFalse(PathRules.allowed(bad), bad);
        }
        assertFalse(PathRules.allowed(null));
        assertTrue(PathRules.allowed("/v1/A-b_9/c"));
    }

    /**
     * Das Plugin hängt „?imspiel=Name1,Name2“ an den Long-Poll (CompanionVoiceLink.heardUri), sobald zwei Spieler im
     * Voice-Chat sind. Die erste Fassung von Abschnitt 5 verbot das Komma – dann bekam der Long-Poll 404. Seit
     * 2026-11-08 erlauben Spezifikation, Testvektoren, App und Relay es gleich. Wer es hier wieder verbietet, schaltet
     * Veras Gehör ab, sobald zwei Spieler sprechen.
     */
    @Test
    void commaInTheQueryCarriesThePlayerList() {
        assertTrue(PathRules.allowed("/baumeister/v1/begleiter/gehoert?imspiel=Alex,Steve"));  // umlaut-ok: URL
        assertTrue(PathRules.allowed("/baumeister/v1/begleiter/gehoert?imspiel=Alex"));  // umlaut-ok: URL
        assertFalse(PathRules.allowed("/baumeister/v1/begleiter,x"), "im Pfad bleibt das Komma verboten");
    }

    /**
     * Seit 2026-11-20 hängt das Plugin den Begleiternamen an („&amp;begleiter=Lotte“, URLEncoder: Leerzeichen „+“,
     * Umlaute %C3%xx), damit die App ihn im Vortext der Erkennung nennt. Das Relay reicht ihn ohne eigene Regel durch –
     * dieser Test hält fest, dass das so bleibt. Wer „+“ oder „&amp;“ aus der Abfrage nimmt, beendet jeden Long-Poll
     * mit 404 und damit Veras Gehör.
     */
    @Test
    void companionNameRidesAlongInTheQuery() {
        assertTrue(PathRules.allowed("/baumeister/v1/begleiter/gehoert?imspiel=Alex&begleiter=Lotte"));  // umlaut-ok
        assertTrue(PathRules.allowed("/baumeister/v1/begleiter/gehoert?begleiter=J%C3%BCrgen+Maria"));  // umlaut-ok
        assertFalse(PathRules.allowed("/baumeister/v1/begleiter/gehoert?begleiter=Lo%0Atte"),  // umlaut-ok: URL
                "kodierter Zeilenumbruch bleibt verboten");
    }

    @Test
    void logFormDropsTheQueryAndOddCharacters() {
        assertEquals("/baumeister/v1/begleiter/hoeren", LocalExchange.loggable(  // umlaut-ok: URL-Pfad
                "/baumeister/v1/begleiter/hoeren?spieler=Alex"));  // umlaut-ok: URL-Pfad
        assertEquals("/v1/a?b", LocalExchange.loggable("/v1/a\nb"));
        assertEquals(80, LocalExchange.loggable("/" + "x".repeat(500)).length());
    }
}
