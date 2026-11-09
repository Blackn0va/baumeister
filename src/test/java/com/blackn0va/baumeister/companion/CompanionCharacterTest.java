package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.companion.CompanionCharacter.Kind;
import com.blackn0va.baumeister.companion.CompanionCharacter.Line;
import com.blackn0va.baumeister.companion.CompanionCharacter.Tone;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** Charakter: Wunsch erkennen, Ton mitlesen, frech nur mit Recht, Sätze ohne echte Beleidigungen. */
class CompanionCharacterTest {

    @Test
    void recognisesWishes() {
        assertEquals(Kind.FRECH, CompanionCharacter.wish("sei frech"));
        assertEquals(Kind.FRECH, CompanionCharacter.wish("du darfst ruhig frech sein"));
        assertEquals(Kind.FRECH, CompanionCharacter.wish("sei mal ein Arsch"));
        assertEquals(Kind.KUMPEL, CompanionCharacter.wish("sei wieder nett"));
        assertEquals(Kind.KUMPEL, CompanionCharacter.wish("hör auf so frech zu sein"));
        assertEquals(Kind.ANPASSEN, CompanionCharacter.wish("pass dich mir an"));
        assertNull(CompanionCharacter.wish("bau mir ein Haus"));
        assertNull(CompanionCharacter.wish("das ist frech"));
    }

    @Test
    void adaptingTurnsCheekyOnlyWhenThePlayerIsRough() {
        Tone t = new Tone();
        t.heard("hallo Vera");
        t.heard("du bist so lahm");
        assertFalse(CompanionCharacter.cheeky(Kind.ANPASSEN, t, true), "einmal ruppig reicht nicht");
        t.heard("halt die Klappe");
        assertTrue(CompanionCharacter.cheeky(Kind.ANPASSEN, t, true));
        t.heard("danke dir");
        t.heard("bitte gib mir Brot");
        t.heard("super gemacht");
        assertFalse(CompanionCharacter.cheeky(Kind.ANPASSEN, t, true), "mehr freundlich als ruppig");
        assertFalse(CompanionCharacter.cheeky(Kind.KUMPEL, t, true));
    }

    @Test
    void cheekyNeedsTheRight() {
        assertTrue(CompanionCharacter.cheeky(Kind.FRECH, null, true));
        assertFalse(CompanionCharacter.cheeky(Kind.FRECH, null, false), "Admin hat Frech abgestellt");
    }

    @Test
    void everyLineExistsInBothTonesAndCheekyDiffers() {
        for (Line l : Line.values()) {
            assertFalse(CompanionCharacter.line(l, false, 0).isBlank(), l.name());
            assertFalse(CompanionCharacter.line(l, true, 7).isBlank(), l.name());
        }
        assertNotEquals(CompanionCharacter.line(Line.DEATH, false, 0), CompanionCharacter.line(Line.DEATH, true, 0));
    }

    @Test
    void cheekyStaysWithinLimits() {
        // Frech, aber ohne Schimpfwörter über „Mist“ hinaus und ohne Sexuelles (Kopfkommentar der Klasse).
        Pattern bad = Pattern.compile("(?iu)\\b(scheiß|scheiss|fick|arschloch|hure|wichser|idiot|dumm|behindert|fett)");
        for (Line l : Line.values()) {
            for (int i = 0; i < 5; i++) {
                String s = CompanionCharacter.line(l, true, i);
                assertFalse(bad.matcher(s).find(), s);
            }
        }
        assertTrue(CompanionCharacter.prompt(true).contains("still help"));
        assertTrue(CompanionCharacter.prompt(false).contains("never mean"));
    }

    @Test
    void offlineChatUsesTheTone() {
        assertEquals("Gern geschehen!", CompanionIntent.chat("danke").orElseThrow());
        assertNotEquals("Gern geschehen!", CompanionIntent.chat("danke", true, 0).orElseThrow());
        assertTrue(CompanionIntent.chat("fick dich", true, 0).isPresent());
    }
}
