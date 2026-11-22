package com.blackn0va.baumeister.module;

import org.bukkit.ChatColor;

/**
 * Zustand eines Moduls, wie ihn Befehl, Kistenmenü, Host-App und Mod zeigen (Vertrag v012, modul_json: „aktiv|aus|
 * wartet|fehler“). Der Draht-Name ist fest – Host (T-031) und Mod (T-032) lesen genau diese vier Wörter.
 *
 * Threads: beliebig. Fehler: keine.
 */
public enum ModuleState {
    AKTIV("aktiv", ChatColor.GREEN),
    AUS("aus", ChatColor.GRAY),
    WARTET("wartet", ChatColor.YELLOW),
    FEHLER("fehler", ChatColor.RED);

    private final String wire;
    private final ChatColor color;

    ModuleState(String wire, ChatColor color) {
        this.wire = wire;
        this.color = color;
    }

    /** So steht der Zustand im JSON und im Chat. */
    public String wire() {
        return wire;
    }

    /** Farbe je Zustand im Chat und im Menü (grün läuft, grau aus, gelb wartet, rot kaputt). */
    public ChatColor color() {
        return color;
    }
}
