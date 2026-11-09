package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;

/**
 * Jeder Blockzustand beider Server-Fassungen kommt unverändert durch BlockSpec: parse(zustand).asString() nennt
 * dieselbe ID mit denselben Eigenschaften (Reihenfolge egal, verglichen als Map).
 *
 * Warum (Plan B9, Wunsch 2026-11-08): Was
 * BlockSpec nicht zurückgibt, wie es hineinkam, baut das Plugin anders, als der Plan es sagt – und Bukkit verwirft dann
 * im schlimmsten Fall still alle Zustände. Die Listen kommen aus dem Datengenerator der Vanilla-Jars
 * (probes/blockstates, README dort), nicht aus dem Gedächtnis: Abgeschrieben fehlten immer Blöcke.
 *
 * Bewusst ohne Daten ({…}): Der Test prüft nur Zustände, damit er gilt, egal wie BlockSpec NBT ablegt (Paket P3).
 * Ein Fehlschlag listet die ersten Zustände samt Grund – als Befund, nicht als Rätsel.
 */
class BlockStatesRoundTripTest {

    /** Beim Erzeugen gezählt (probes/blockstates/README.md) – eine abgeschnittene Datei fiele sonst nicht auf. */
    private static final Map<String, Integer> COUNTS = Map.of("26.2", 32_366, "1.21.11", 29_671);

    private static final Pattern CANONICAL = Pattern.compile(
            "minecraft:[a-z0-9_]+(\\[[a-z0-9_]+=[a-z0-9_]+(,[a-z0-9_]+=[a-z0-9_]+)*])?");

    static List<String> states(String version) throws IOException {
        String resource = "/blockstates/" + version + ".txt.gz";
        List<String> out = new ArrayList<>();
        try (InputStream raw = BlockStatesRoundTripTest.class.getResourceAsStream(resource)) {
            assertNotNull(raw, resource + " fehlt – python -I probes/blockstates/blockstates.py (README dort)");
            try (BufferedReader in = new BufferedReader(new InputStreamReader(new GZIPInputStream(raw),
                    StandardCharsets.UTF_8))) {
                for (String line = in.readLine(); line != null; line = in.readLine()) {
                    out.add(line);
                }
            }
        }
        return out;
    }

    /** Eigene Zerlegung, unabhängig von BlockSpec: „id“ → Eigenschaften in Lesereihenfolge. */
    static Map<String, Map<String, String>> split(String state) {
        int open = state.indexOf('[');
        String id = open < 0 ? state : state.substring(0, open);
        Map<String, String> props = new LinkedHashMap<>();
        if (open >= 0) {
            for (String pair : state.substring(open + 1, state.length() - 1).split(",")) {
                int eq = pair.indexOf('=');
                props.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return Map.of(id, props);
    }

    /** Zustände, die BlockSpec ablehnt oder verändert zurückgibt, mit Grund. */
    static List<String> roundTripFailures(List<String> states) {
        List<String> failures = new ArrayList<>();
        for (String state : states) {
            try {
                String back = BlockSpec.parse(state).asString();
                if (!split(back).equals(split(state))) {
                    failures.add(state + " → " + back);
                }
            } catch (PlanException e) {
                failures.add(state + " → abgelehnt: " + e.getMessage());
            }
        }
        return failures;
    }

    private static void assertEveryStateRoundTrips(String version) throws IOException {
        List<String> states = states(version);
        assertEquals(COUNTS.get(version), states.size(), "Zustände in " + version + ".txt.gz");
        assertEquals(states.size(), new HashSet<>(states).size(), "doppelte Zustände in " + version);
        for (String s : states) {
            assertTrue(CANONICAL.matcher(s).matches(), "unerwartete Form: " + s);
        }
        List<String> failures = roundTripFailures(states);
        assertTrue(failures.isEmpty(), failures.size() + " von " + states.size() + " Zuständen (" + version
                + ") kommen nicht unverändert durch BlockSpec, die ersten: "
                + failures.subList(0, Math.min(20, failures.size())));
    }

    @Test
    void everyStateOfMinecraft26Point2SurvivesParseAndAsString() throws IOException {
        assertEveryStateRoundTrips("26.2");
    }

    @Test
    void everyStateOfMinecraft1Point21Point11SurvivesParseAndAsString() throws IOException {
        assertEveryStateRoundTrips("1.21.11");
    }

    /** Der Vergleich selbst: andere Reihenfolge zählt nicht, ein fehlender oder anderer Wert schon. */
    @Test
    void comparisonIgnoresOrderButNotValues() {
        assertEquals(split("minecraft:chest[type=right,facing=east,waterlogged=false]"),
                split("minecraft:chest[facing=east,type=right,waterlogged=false]"));
        assertEquals(List.of(),
                roundTripFailures(List.of("minecraft:chest[type=right,facing=east,waterlogged=false]")));
        assertNotEquals(split("minecraft:chest[facing=east,type=right]"),
                split("minecraft:chest[facing=east,type=right,waterlogged=false]"));
        assertNotEquals(split("minecraft:chest[facing=east]"), split("minecraft:chest[facing=west]"));
        assertNotEquals(split("minecraft:chest[facing=east]"), split("minecraft:trapped_chest[facing=east]"));
    }
}
