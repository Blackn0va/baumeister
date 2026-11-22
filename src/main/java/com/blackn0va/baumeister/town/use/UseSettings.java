package com.blackn0va.baumeister.town.use;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Schalter des Benutzens (config.yml, stadtleben.nutzen.*). an = alles; die Teile einzeln, damit ein Admin z. B. nur
 * die Türen abstellt – sie sind die einzige Weltänderung (Tür-/Torzustand, sofort zurückgestellt).
 *
 * Vorgabe an: Die Rechteprüfung (Interact-Event) entscheidet je Tür, und ohne Bett/Bank/Arbeitsblock bleibt alles wie
 * vorher.
 *
 * Threads: unveränderlich. Fehler: keine.
 */
public record UseSettings(boolean enabled, boolean doors, boolean beds, boolean seats, boolean work) {

    public static final String KEY_ENABLED = "stadtleben.nutzen.an";
    public static final String KEY_DOORS = "stadtleben.nutzen.türen";
    public static final String KEY_BEDS = "stadtleben.nutzen.schlafen";
    public static final String KEY_SEATS = "stadtleben.nutzen.sitzen";
    public static final String KEY_WORK = "stadtleben.nutzen.arbeitsblöcke";

    public static UseSettings defaults() {
        return new UseSettings(true, true, true, true, true);
    }

    public static UseSettings off() {
        return new UseSettings(false, false, false, false, false);
    }

    public static UseSettings from(ConfigurationSection root) {
        if (root == null) {
            return defaults();
        }
        return new UseSettings(root.getBoolean(KEY_ENABLED, true), root.getBoolean(KEY_DOORS, true),
                root.getBoolean(KEY_BEDS, true), root.getBoolean(KEY_SEATS, true), root.getBoolean(KEY_WORK, true));
    }

    public boolean doorsOn() {
        return enabled && doors;
    }

    public boolean bedsOn() {
        return enabled && beds;
    }

    public boolean seatsOn() {
        return enabled && seats;
    }

    public boolean workOn() {
        return enabled && work;
    }
}
