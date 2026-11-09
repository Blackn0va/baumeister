package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.blackn0va.baumeister.ChatIntent.Kind;
import com.blackn0va.baumeister.ChatIntent.Target;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Steuerwörter im Chat gehen nie ans LLM – auch ohne laufenden Stadtbau (2026-10-28). */
class ChatIntentTest {

    @Test
    void controlWordsNeverReachTheLlm() {
        for (String text : List.of("stopp", "Stopp!", "hör auf", "status", "wie weit bist du?", "mach das rückgängig")) {
            Kind kind = ChatIntent.parse(text);
            assertNotEquals(Kind.BUILD, kind, text);
            for (boolean city : new boolean[] {false, true}) {
                for (boolean busy : new boolean[] {false, true}) {
                    assertNotEquals(Target.LLM, ChatIntent.route(kind, city, busy),
                            text + " bei Stadt=" + city + " Bau=" + busy);
                }
            }
        }
    }

    @Test
    void stopDuringNormalBuildGoesToBuildService() {
        assertEquals(Target.BUILD_SERVICE, ChatIntent.route(ChatIntent.parse("Stopp"), false, true));
        assertEquals(Target.BUILD_SERVICE, ChatIntent.route(ChatIntent.parse("hör auf"), false, true));
        assertEquals(Target.BUILD_SERVICE, ChatIntent.route(ChatIntent.parse("abbrechen"), false, true));
        // Läuft eine Stadt, stoppt „stopp“ die Stadt.
        assertEquals(Target.CITY, ChatIntent.route(Kind.STOP, true, false));
        // Rückgängig geht immer an den Bau; die laufende Stadt stoppt der Listener vorher wie /bm rückgängig.
        assertEquals(Target.BUILD_SERVICE, ChatIntent.route(ChatIntent.parse("weg damit"), true, false));
    }

    @Test
    void idleSpeedWordsSayNothingRuns() {
        for (String text : List.of("schneller", "langsamer", "pause", "weiter")) {
            assertEquals(Target.NOT_RUNNING, ChatIntent.route(ChatIntent.parse(text), false, false), text);
            assertEquals(Target.CITY, ChatIntent.route(ChatIntent.parse(text), true, false), text);
        }
        // „komm“ holt den NPC auch ohne Stadt.
        assertEquals(Target.CITY, ChatIntent.route(ChatIntent.parse("komm her"), false, false));
    }

    @Test
    void helperWordsReachTheNormalBuildWithoutCity() {
        // 2026-10-30: Vorher „Gerade läuft kein Stadtbau.“, auch während ein Haus mit Helfern lief.
        for (String text : List.of("mehr helfer", "Weniger Helfer!")) {
            assertEquals(Target.BUILD_SERVICE, ChatIntent.route(ChatIntent.parse(text), false, true), text);
            assertEquals(Target.BUILD_SERVICE, ChatIntent.route(ChatIntent.parse(text), false, false), text);
            assertEquals(Target.CITY, ChatIntent.route(ChatIntent.parse(text), true, false), text);
        }
    }

    @Test
    void buildOrdersStayWithTheLlm() {
        for (String text : List.of("bau mir ein kleines Haus aus Fichtenholz", "reiß die Mauer ab und bau ein Tor")) {
            assertEquals(Kind.BUILD, ChatIntent.parse(text), text);
            assertEquals(Target.LLM, ChatIntent.route(Kind.BUILD, false, false), text);
            assertEquals(Target.LLM, ChatIntent.route(Kind.BUILD, true, true), text);
        }
    }

    @Test
    void onlyTheWholeMessageCounts() {
        assertEquals(Kind.BUILD, ChatIntent.parse("stopp die Mauer bei der Brücke und bau weiter"));
        assertEquals(Kind.BUILD, ChatIntent.parse("bau ein Haus, status egal"));
        assertEquals(Kind.UNDO, ChatIntent.parse("  Rückgängig! "));
        assertEquals(Kind.UNDO, ChatIntent.parse("rueckgaengig")); // umlaut-ok
        assertEquals(Kind.STATUS, ChatIntent.parse("Wie weit bist du?!"));
    }
}
