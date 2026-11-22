package com.blackn0va.baumeister.town.presence;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Einstellungen des Spieler-Auftritts der Bewohner (config.yml, Abschnitt stadtleben).
 *
 * announce = gelbe Zeile „Lotte hat das Spiel betreten/verlassen“ beim Kommen und Gehen, wie bei echten Spielern
 * (Nutzer 2026-11-22, Vertrag v013 AK-40: „schaltbar“). Vorgabe an: Wer das Stadtleben einschaltet, will Bewohner, die
 * wie Spieler wirken; wem die Zeilen zu viel sind, schaltet sie hier ab.
 *
 * Threads: unveränderlich. Fehler: keine – unlesbare Werte gelten als Vorgabe (ConfigCheck meldet sie).
 */
public record PresenceSettings(boolean announce) {

    public static final String KEY_ANNOUNCE = "stadtleben.kommen-gehen-melden";
    public static final boolean DEFAULT_ANNOUNCE = true;

    public static PresenceSettings defaults() {
        return new PresenceSettings(DEFAULT_ANNOUNCE);
    }

    public static PresenceSettings from(ConfigurationSection root) {
        return root == null ? defaults() : new PresenceSettings(root.getBoolean(KEY_ANNOUNCE, DEFAULT_ANNOUNCE));
    }
}
