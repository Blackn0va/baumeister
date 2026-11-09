package com.blackn0va.baumeister.rucksack;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Teamname landet im Dateinamen: kein Weg aus dem Ordner heraus. */
class RucksackTeamsTest {

    @Test
    void namesCannotEscapeTheFolder() {
        for (String ok : new String[] {"bau-team", "farm_2", "ab"}) {
            assertTrue(RucksackTeams.validName(ok), ok);
        }
        for (String bad : new String[] {"../x", "a/b", "a\\b", "a", "Großbuchstaben", "sehr-langer-teamname-x", "",
                "a b", "..", null}) {
            assertFalse(RucksackTeams.validName(bad), String.valueOf(bad));
        }
    }
}
