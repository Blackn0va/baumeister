package com.blackn0va.baumeister.module;

/**
 * Ein Modul zu einem Zeitpunkt: Beschreibung, Schalter laut config.yml (on), Zustand und Grund. reason ist leer,
 * wenn es nichts zu sagen gibt.
 *
 * Threads: beliebig (unveränderlich). Fehler: keine.
 */
public record ModuleEntry(ModuleDef def, boolean on, ModuleState state, String reason) {

    public ModuleEntry {
        reason = reason == null ? "" : reason;
    }
}
