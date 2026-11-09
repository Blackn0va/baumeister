package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Plapperton: Frage steigt, Ausruf hüpft, sonst ruhig – nach dem letzten Satzzeichen. */
class CompanionMoodTest {

    @Test
    void moodFollowsTheLastPunctuation() {
        assertEquals(CompanionMood.Mood.QUESTION, CompanionMood.of("Wo bist du?"));
        assertEquals(CompanionMood.Mood.EXCITED, CompanionMood.of("Super gemacht!"));
        assertEquals(CompanionMood.Mood.EXCITED, CompanionMood.of("Echt?!"));
        assertEquals(CompanionMood.Mood.EXCITED, CompanionMood.of("Da steht „Hurra!“"));
        assertEquals(CompanionMood.Mood.CALM, CompanionMood.of("Ich warte hier."));
        assertEquals(CompanionMood.Mood.CALM, CompanionMood.of("Ist das gut? Ja, sehr."));
        assertEquals(CompanionMood.Mood.CALM, CompanionMood.of(null));
    }

    @Test
    void pitchesStayInMinecraftRange() {
        for (CompanionMood.Mood m : CompanionMood.Mood.values()) {
            assertEquals(true, m.pitch() >= 0.5f && m.pitch() <= 2.0f, m.name());
        }
    }
}
