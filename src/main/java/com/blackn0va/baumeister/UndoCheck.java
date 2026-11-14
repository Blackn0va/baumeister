package com.blackn0va.baumeister;

import java.util.Set;

/**
 * Darf das Rückgängig eine Stelle zurücksetzen? Nur, wenn dort noch steht, was der Bau gesetzt hat.
 *
 * Vorher setzte /bm rückgängig jede Zelle blind auf den Ursprung (Sicherheitsprüfung 1, Befund 6): Hatte ein anderer
 * Spieler nach dem Bau dort gebaut, war sein Block weg – auch eine Truhe samt Inhalt, wenn der Bau an der Stelle eine
 * Truhe gesetzt hatte und sie danach gefüllt wurde. Jetzt merkt sich jeder Eintrag den gesetzten Blocktyp
 * (UndoSpool Format 2, BuildJob.UndoRecord.placed), und eine Zelle wird nur zurückgesetzt, wenn
 *   - dort derselbe Typ steht (Zustände wie Redstone-Strom, Treppenform oder Wachstum zählen nicht: die ändert
 *     Minecraft selbst), bis auf Familien, die von allein wechseln (Gras ↔ Erde, Wasser ↔ Blasensäule, Kupfer altert,
 *     Trockenbeton härtet), oder
 *   - dort jetzt nichts steht, was ein Spieler gesetzt haben kann: Luft, Flüssigkeit, Feuer, Schnee, Gras. Wer den
 *     gesetzten Block abgebaut hat, verliert nichts, wenn der Ursprung zurückkommt.
 * Ohne Angabe (Format 1, Einträge von vor 2026-11-14) bleibt es beim alten Verhalten.
 *
 * Wer das zurückdreht, lässt das Rückgängig wieder fremde Bauten und Truhen überschreiben (OWASP A01, ASVS V4.2.1:
 * eine Aktion wirkt nur auf das, was dem Auftraggeber gehört).
 *
 * Threads: zustandslos. Fehler: keine – unbekannte Namen sind einfach verschieden.
 */
final class UndoCheck {

    private static final Set<String> FREE = Set.of("air", "cave_air", "void_air", "water", "lava", "bubble_column",
            "fire", "soul_fire", "snow", "short_grass", "tall_grass", "fern", "large_fern");
    private static final Set<String> SOIL = Set.of("grass_block", "dirt", "dirt_path", "farmland", "podzol",
            "mycelium", "coarse_dirt", "rooted_dirt");

    private UndoCheck() {
    }

    /**
     * current: Blocktyp, der jetzt dort steht (z. B. „minecraft:stone“ oder „stone“); placed: was der Bau gesetzt hat,
     * null = unbekannt (dann wie früher zurücksetzen).
     */
    static boolean restorable(String current, String placed) {
        if (placed == null) {
            return true;
        }
        String now = kind(current);
        return FREE.contains(now) || now.equals(kind(placed));
    }

    /** Familie eines Blocktyps: ohne Namensraum, Wechsel, die Minecraft von allein macht, zusammengelegt. */
    static String kind(String key) {
        String k = key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
        if (SOIL.contains(k)) {
            return "dirt";
        }
        if (k.equals("bubble_column")) {
            return "water";
        }
        if (k.endsWith("_concrete_powder")) {
            return k.substring(0, k.length() - "_powder".length());
        }
        if (k.contains("copper")) {
            for (String p : new String[] {"waxed_", "exposed_", "weathered_", "oxidized_"}) {
                k = k.replace(p, "");
            }
        }
        return k;
    }
}
