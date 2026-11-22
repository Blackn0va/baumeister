package com.blackn0va.baumeister.module;

import java.util.List;

/**
 * Ein Modul des Verzeichnisses: was es heißt, was es tut, welcher bestehende Konfigschlüssel es schaltet und ob der
 * Schalter sofort greift.
 *
 * key null = fester Teil ohne eigenen Schalter (Bau-KI, Städte, Erde …): Diese Funktionen hatten nie einen an/aus-
 * Schlüssel, und der Vertrag v012 verbietet neue Schalter nur der Ordnung halber („bestehende Konfigschlüssel bleiben
 * die Quelle“). Sie stehen trotzdem im Verzeichnis, damit man sieht, warum eine wartet (KI fehlt, Welt fehlt).
 *
 * Threads: beliebig (unveränderlich). Fehler: keine.
 */
public record ModuleDef(String id, String name, String description, String key, Effect effect, List<String> needs) {

    /**
     * Wann ein Schalter greift. Gelesen im Code (2026-11-22), nicht geraten: Nur Stadtleben (TownLife.setEnabled) und
     * Sprache (BaumeisterPlugin.reopenVoice) übernehmen den Wert im Lauf. Karte, Rucksack, Aufgaben, Warps und
     * Mods-Download halten ihre Settings in final-Feldern aus onEnable; Vera und die App-Anzeige werden nur in load()
     * gebaut, und /bm neuladen ruft load() nur, wenn sich Bau-Einstellungen ändern (ReloadPlan.replaceBuildService).
     * Deshalb heißt der ehrliche Hinweis für diese „nach einem Neustart“, nicht „nach /bm neuladen“.
     */
    public enum Effect {
        SOFORT,
        NEUSTART
    }

    public ModuleDef {
        needs = List.copyOf(needs);
    }

    /** Hat einen eigenen Schalter in config.yml. */
    public boolean switchable() {
        return key != null;
    }
}
