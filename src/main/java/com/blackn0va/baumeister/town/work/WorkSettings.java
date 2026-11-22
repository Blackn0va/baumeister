package com.blackn0va.baumeister.town.work;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Schalter und Grenze der Feldarbeit (config.yml, Abschnitt stadtleben; Vertrag v013, AK-43).
 *
 * Vorgabe aus: Bewohner ändern die Welt nur, wenn ein Admin es ausdrücklich will – erst weltänderung an, dann Felder
 * setzen. Ohne den Schalter (oder ohne Felder) arbeiten sie wie in v1, nur mit Werkzeug und Armschwung.
 * Eigener Datensatz statt neuer Felder in TownSettings: Den teilen sich die Stadtleben-Aufgaben, ein neues Feld dort
 * änderte jeden Konstruktoraufruf. Die Grenzen stehen gleich in ConfigCheck.RANGES (WorkSettingsTest gleicht ab).
 *
 * Threads: unveränderlich. Fehler: keine – unlesbare Werte gelten als Vorgabe, Zahlen werden gekappt.
 */
public record WorkSettings(boolean worldChange, int blocksPerMinute) {

    public static final String KEY_WORLD_CHANGE = "stadtleben.weltänderung";
    public static final String KEY_RATE = "stadtleben.feld-blöcke-je-minute";
    public static final boolean DEFAULT_WORLD_CHANGE = false;
    public static final int DEFAULT_RATE = 20;
    public static final int MIN_RATE = 1;
    /** 120 je Minute = 2 je Sekunde: sichtbar Arbeit, kein Abräumen ganzer Wälder in einer Mittagspause. */
    public static final int MAX_RATE = 120;

    public static WorkSettings defaults() {
        return new WorkSettings(DEFAULT_WORLD_CHANGE, DEFAULT_RATE);
    }

    public static WorkSettings from(ConfigurationSection root) {
        if (root == null) {
            return defaults();
        }
        return new WorkSettings(root.getBoolean(KEY_WORLD_CHANGE, DEFAULT_WORLD_CHANGE),
                Math.clamp(root.getInt(KEY_RATE, DEFAULT_RATE), MIN_RATE, MAX_RATE));
    }
}
