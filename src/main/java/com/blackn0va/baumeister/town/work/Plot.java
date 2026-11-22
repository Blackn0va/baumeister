package com.blackn0va.baumeister.town.work;

/**
 * Lesender Blick auf die Blöcke eines Feldes – im Plugin Bukkit (TownLife), im Test ein Gitter. Typen als
 * Material-Schlüssel ohne Namensraum in Kleinschreibung („wheat“, „oak_log“, „air“).
 *
 * Warum Zeichenketten statt Material/BlockData: BlockData lässt sich ohne Server nicht bauen, und gerade die Regeln
 * „nie Behälter, nie Redstone, nur natürliche Bäume“ sollen ohne Server getestet sein.
 *
 * Threads: Hauptthread. Fehler: keine; ungeladene Chunks liest das Plugin als „unloaded“ (nie ein Ziel).
 */
public interface Plot {

    /** Was hier steht; „unloaded“ in ungeladenen Chunks oder außerhalb der Welthöhe. */
    String type(int x, int y, int z);

    /** Wachstumsstufe (Ageable), −1 ohne. */
    int age(int x, int y, int z);

    /** Höchste Wachstumsstufe, −1 ohne. */
    int maxAge(int x, int y, int z);

    /** Laub: vom Spieler gesetzt (persistent=true)? Für alles andere false. */
    boolean persistent(int x, int y, int z);
}
