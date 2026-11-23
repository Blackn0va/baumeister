package com.blackn0va.baumeister.town.work;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Schalter und Grenzen für das Bauen der Bewohner in Baufeldern (config.yml, stadtleben.bauen; Vertrag v013, AK-45).
 *
 * Vorgabe aus, und zusätzlich gilt stadtleben.weltänderung: Bauen ist die größte Weltänderung der Bewohner, ein Admin
 * soll sie zweimal ausdrücklich wollen (Schalter hier, Freigabe je Feld mit /bm stadtleben feld bauen). Eigener
 * Datensatz neben WorkSettings aus demselben Grund wie dort: ein neues Feld in WorkSettings änderte jeden
 * Konstruktoraufruf der Feldarbeit. Die Grenzen stehen gleich in ConfigCheck.RANGES (BuildSettingsTest gleicht ab).
 *
 * Threads: unveränderlich. Fehler: keine – unlesbare Werte gelten als Vorgabe, Zahlen werden gekappt.
 */
public record BuildSettings(boolean enabled, int perField, int pauseMinutes) {

    public static final String KEY_ENABLED = "stadtleben.bauen.an";
    public static final String KEY_PER_FIELD = "stadtleben.bauen.je-feld";
    public static final String KEY_PAUSE = "stadtleben.bauen.pause-minuten";
    public static final boolean DEFAULT_ENABLED = false;
    /** Vier kleine Bauten je Feld: genug für „ein Dorf wächst“, zu wenig, um ein Feld zuzubauen. */
    public static final int DEFAULT_PER_FIELD = 4;
    public static final int MIN_PER_FIELD = 1;
    /** Mehr als 16 Kleinbauten passen in kein Feld mit Radius 24 samt Abstand – darüber wäre die Grenze keine. */
    public static final int MAX_PER_FIELD = 16;
    /** Pause zwischen zwei Bauten desselben Feldes: „nach und nach“ statt alles in einer Mittagspause. */
    public static final int DEFAULT_PAUSE = 3;
    public static final int MIN_PAUSE = 0;
    public static final int MAX_PAUSE = 120;

    public static BuildSettings defaults() {
        return new BuildSettings(DEFAULT_ENABLED, DEFAULT_PER_FIELD, DEFAULT_PAUSE);
    }

    public static BuildSettings from(ConfigurationSection root) {
        if (root == null) {
            return defaults();
        }
        return new BuildSettings(root.getBoolean(KEY_ENABLED, DEFAULT_ENABLED),
                Math.clamp(root.getInt(KEY_PER_FIELD, DEFAULT_PER_FIELD), MIN_PER_FIELD, MAX_PER_FIELD),
                Math.clamp(root.getInt(KEY_PAUSE, DEFAULT_PAUSE), MIN_PAUSE, MAX_PAUSE));
    }

    public long pauseMillis() {
        return pauseMinutes * 60_000L;
    }
}
