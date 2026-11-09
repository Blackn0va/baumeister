package com.blackn0va.baumeister;

/**
 * Ein fertiger KI-Plan, der auf das Ende eines Stadt- oder Weltbaus wartet.
 *
 * Warum (Nutzerwunsch 2026-11-01: Stadt und Welt bauen ohne KI): Stadt- und Weltbau planen aus OpenStreetMap, nie mit
 * dem LLM. Trotzdem warteten sie, solange der Baumeister auf einen LLM-Plan wartete (BuildService.busy() enthielt
 * „thinking“, bis zum Timeout von 900 s). Jetzt dürfen sie während der KI-Planung starten – und der Plan, der danach
 * fertig wird, wird hier vorgemerkt statt verworfen und startet, sobald die Baustelle frei ist.
 *
 * Genau ein Platz: Ein zweiter fertiger Plan kommt praktisch nicht vor (neue Aufträge lehnt BuildService.request ab,
 * solange Stadt- oder Weltbau läuft); kommt er doch, sagt offer() false, und der Aufrufer meldet das.
 *
 * Threads: nur Hauptthread. Fehler: keine.
 */
final class DeferredStart {

    private Runnable pending;
    private String owner = "";

    /** false, wenn schon ein Plan wartet. */
    boolean offer(String ownerName, Runnable start) {
        if (pending != null) {
            return false;
        }
        pending = start;
        owner = ownerName;
        return true;
    }

    boolean waiting() {
        return pending != null;
    }

    String owner() {
        return owner;
    }

    /** Startet den wartenden Plan, wenn free; true, wenn gestartet. Der Platz ist danach leer, auch wenn start wirft. */
    boolean runIfFree(boolean free) {
        if (pending == null || !free) {
            return false;
        }
        Runnable r = pending;
        pending = null;
        owner = "";
        r.run();
        return true;
    }

    /** Verwirft den wartenden Plan; true, wenn einer da war. */
    boolean cancel() {
        boolean had = pending != null;
        pending = null;
        owner = "";
        return had;
    }
}
