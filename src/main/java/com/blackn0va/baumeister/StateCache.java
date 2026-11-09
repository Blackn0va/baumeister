package com.blackn0va.baumeister;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Blockzustände aus Text (BlockData.getAsString) einmal erzeugen und merken – mit Platz für Zustände, die diese
 * Serverfassung nicht kennt.
 *
 * Wozu: Rückgängig-Einträge überleben seit dem Komfort-Paket einen Neustart, bis zu rückgängig-max-tage, also auch ein
 * Spigot- oder Minecraft-Update mit umbenannten Blöcken oder entfallenen Eigenschaften. Vorher rief restoreSpool
 * Bukkit.createBlockData ungeschützt im runTaskTimer auf. Ein unbekannter Zustand warf IllegalArgumentException an
 * derselben Stelle, Tick für Tick (der Zähler lief nicht weiter), und das Rückgängig blieb halb stehen. Jetzt liefert
 * get() für einen unbekannten Zustand null: Der Block bleibt, wie er ist, und wird gezählt (skipped). Gemeldet wird je
 * Zustand einmal. Wer den Fang zurückdreht, hat den Endlosfehler im Timer wieder.
 *
 * Threads: wie der Aufrufer (Bukkit: Hauptthread). Fehler: IllegalArgumentException der Fabrik wird zu null; andere
 * Ausnahmen laufen durch.
 *
 * @param <T> der erzeugte Zustand (BlockData)
 */
final class StateCache<T> {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    private final Function<String, T> factory;
    private final Map<String, T> known = new HashMap<>();
    private final Set<String> unknown = new HashSet<>();
    private long skipped;

    StateCache(Function<String, T> factory) {
        this.factory = factory;
    }

    /** Zustand oder null, wenn diese Serverfassung ihn nicht kennt (zählt als übersprungen). */
    T get(String state) {
        T t = resolve(state);
        if (t == null) {
            skipped++;
        }
        return t;
    }

    /** Kennt diese Serverfassung den Zustand? Zählt nicht als übersprungen – für die Prüfung beim Laden. */
    boolean knows(String state) {
        return resolve(state) != null;
    }

    /** Wie viele get()-Aufrufe null lieferten. */
    long skipped() {
        return skipped;
    }

    /** Verschiedene unbekannte Zustände. */
    int unknownStates() {
        return unknown.size();
    }

    private T resolve(String state) {
        T t = known.get(state);
        if (t != null) {
            return t;
        }
        if (unknown.contains(state)) {
            return null;
        }
        try {
            t = factory.apply(state);
        } catch (IllegalArgumentException e) {
            unknown.add(state);
            String shown = state.length() > 80 ? state.substring(0, 80) + "…" : state;
            LOG.warning(() -> "rückgängig | zustand_unbekannt | zustand=" + shown + " grund=" + e.getMessage());
            return null;
        }
        if (t == null) {
            unknown.add(state);
            return null;
        }
        known.put(state, t);
        return t;
    }
}
