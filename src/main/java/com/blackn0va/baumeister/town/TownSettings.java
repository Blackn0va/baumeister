package com.blackn0va.baumeister.town;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Einstellungen des Stadtlebens (config.yml, Abschnitt stadtleben).
 *
 * Vorgabe aus (Vertrag v008, AK-22): Bewohner kosten Takt und KI-Zeit auf einem Host, den sich Bau und Vera teilen –
 * eingeschaltet wird nur bewusst per /bm stadtleben an. Die Grenzen stehen gleich in ConfigCheck.RANGES
 * (TownSettingsTest gleicht beide ab); wer hier eine Grenze ändert, zieht sie dort nach.
 *
 * Threads: unveränderlich. Fehler: keine – unlesbare Werte gelten als Vorgabe, Zahlen werden gekappt.
 */
public record TownSettings(boolean enabled, int count, long talkGapMillis) {

    public static final boolean DEFAULT_ENABLED = false;
    public static final int DEFAULT_COUNT = 4;
    public static final int MIN_COUNT = 1;
    /** Obergrenze 12: Tickkosten mit 12 gemessen (AK-25), mehr ist nicht belegt. */
    public static final int MAX_COUNT = 12;
    public static final int DEFAULT_GAP_SECONDS = 45;
    /** Unter 15 s würde ein Gespräch nach dem anderen den Host belegen (ein 7B-Satz braucht auf dem PC 1–3 s). */
    public static final int MIN_GAP_SECONDS = 15;
    public static final int MAX_GAP_SECONDS = 600;

    public static final String KEY_ENABLED = "stadtleben.an";
    public static final String KEY_COUNT = "stadtleben.anzahl";
    public static final String KEY_GAP = "stadtleben.ki-abstand-sekunden";

    public static TownSettings defaults() {
        return new TownSettings(DEFAULT_ENABLED, DEFAULT_COUNT, DEFAULT_GAP_SECONDS * 1000L);
    }

    public static TownSettings from(ConfigurationSection root) {
        if (root == null) {
            return defaults();
        }
        return new TownSettings(root.getBoolean(KEY_ENABLED, DEFAULT_ENABLED),
                Math.clamp(root.getInt(KEY_COUNT, DEFAULT_COUNT), MIN_COUNT, MAX_COUNT),
                Math.clamp(root.getLong(KEY_GAP, DEFAULT_GAP_SECONDS), MIN_GAP_SECONDS, MAX_GAP_SECONDS) * 1000L);
    }

    public TownSettings withEnabled(boolean on) {
        return new TownSettings(on, count, talkGapMillis);
    }
}
