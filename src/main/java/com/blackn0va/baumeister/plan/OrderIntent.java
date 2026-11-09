package com.blackn0va.baumeister.plan;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ob ein Bauauftrag ein NEUES eigenständiges Bauwerk meint oder den letzten Bau ändern will – ohne LLM.
 *
 * Vorfall 2026-11-04 13:49 (dev01, 14B Q3_K_M): „baue mir ein Haus“ und gleich danach „ein neues Haus“ plante das Modell
 * beide Male als "mode":"update" des Hauses von 13:22 – Gedächtnis (eine Stunde, 48 Blöcke) hatte den alten Rahmen
 * geliefert. Ergebnis: 12 bzw. 10 neue Blöcke am alten Platz, 20 Blöcke vom Spieler entfernt. Der System-Prompt sagt
 * „update NUR bei Änderung“, das Modell hielt sich nicht daran. Deshalb hier fest: Nennt der Auftrag mit unbestimmtem
 * Artikel ein eigenständiges Bauwerk und nichts, das sich auf den alten Bau bezieht, gibt es kein Gedächtnis – der
 * Baumeister baut frisch am Standort des Spielers. Im Zweifel (Bezug, unbekanntes Wort) bleibt es beim Gedächtnis,
 * also beim alten Verhalten.
 *
 * Threads: zustandslos. Fehler: keine.
 */
public final class OrderIntent {

    /** Wortenden eigenständiger Bauwerke („holzhaus“ endet auf „haus“). Teile wie Stockwerk, Dach, Fenster fehlen. */
    static final List<String> STRUCTURES = List.of("haus", "häuschen", "hütte", "villa", "burg", "schloss", "palast",
            "turm", "brücke", "brunnen", "kirche", "kapelle", "kathedrale", "tempel", "pyramide", "statue", "mühle",
            "scheune", "stall", "farm", "garten", "bunker", "schiff", "boot", "zelt", "laden", "shop", "bahnhof",
            "arena", "computer", "rechner", "maschine", "addierer", "fahrstuhl", "aufzug", "schaltung", "ampel",
            "garage", "festung", "hafen", "lager", "dorf", "pool", "tunnel", "bibliothek", "schmiede", "bäckerei",
            "taverne", "iglu", "wolkenkratzer", "hochhaus", "kraftwerk", "fabrik", "anlage", "sortierer", "falle",
            "schloß");

    /** Wörter, die auf den vorhandenen Bau zeigen oder ihn ändern wollen. */
    static final Set<String> RELATION = Set.of("daneben", "dazu", "dran", "daran", "darauf", "darüber", "darunter",
            "drauf", "drinnen", "drin", "innen", "anbau", "anbauen", "erweitere", "erweitern", "ändere", "ändern",
            "umbauen", "baue um", "dach", "fenster", "tür", "stockwerk", "etage", "wand", "wände", "boden", "färbe",
            "größer", "kleiner", "höher", "breiter", "entferne", "reiß", "repariere", "reparier", "diesem", "deinem",
            "meinem", "das", "dieses", "diesen", "neben");

    private static final Set<String> ARTICLES = Set.of("ein", "eine", "einen", "eins");

    private OrderIntent() {
    }

    public static boolean isNewStructure(String order) {
        if (order == null || order.isBlank()) {
            return false;
        }
        String t = order.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}\\s-]", " ");
        String[] words = t.trim().split("[\\s-]+");
        for (String w : words) {
            if (RELATION.contains(w)) {
                return false;
            }
        }
        for (int i = 0; i < words.length; i++) {
            if (!ARTICLES.contains(words[i])) {
                continue;
            }
            // Bis zu drei Wörter weiter: „ein kleines gemütliches Holzhaus“, „einen Redstone Computer“.
            for (int j = i + 1; j < Math.min(words.length, i + 5); j++) {
                if (isStructure(words[j])) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isStructure(String word) {
        for (String s : STRUCTURES) {
            if (word.endsWith(s)) {
                return true;
            }
        }
        return false;
    }
}
