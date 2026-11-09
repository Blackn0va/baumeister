package com.blackn0va.baumeister;

import com.blackn0va.baumeister.BuildJob.UndoRecord;
import com.blackn0va.baumeister.WorldTiles.Tile;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Eigener Rückgängig-Speicher des Weltbaus: je Erdkachel höchstens ein Eintrag, die ältesten fallen über depth heraus
 * (samt Dateien auf der Platte).
 *
 * Prüfbefund 2026-10-28: Vorher legte der Weltbau jede Kachel auf den allgemeinen Stapel von BuildService (Vorgabe
 * bau.undo-tiefe 5). Im Automatikbetrieb verdrängten die Kacheln so die Rückgängig-Einträge der Spielerbauten, und
 * ein Admin, der nach seinem eigenen Bau „/bm rückgängig“ tippte, räumte stattdessen die zuletzt gebaute Erdkachel weg
 * (mayTouch erlaubt Admins jeden Eintrag, undo nimmt den obersten) – Hunderttausende Blöcke, und die Kachel blieb in
 * weltbau.txt als fertig gemerkt, also nie wieder gebaut. Jetzt: Kacheln nur hier, zurück nur über
 * „/bm weltbau rückgängig [tx tz]“, das die Kachel auch aus der Merkliste nimmt. Wer die Kacheln wieder auf den
 * allgemeinen Stapel legt, holt beides zurück.
 *
 * Threads: nur Hauptthread. Fehler: keine Ausnahmen; leere Einträge werden gar nicht erst gemerkt.
 */
final class TileUndo {

    /** Ein Eintrag: welche Kachel, und womit sie sich zurücksetzen lässt. */
    record Entry(Tile tile, UndoRecord record) {
    }

    private final Map<Long, Entry> entries = new LinkedHashMap<>();
    private int depth;

    TileUndo(int depth) {
        this.depth = Math.max(0, depth);
    }

    /** Tiefe neu (Config neu geladen); zu viele Einträge fallen sofort heraus, älteste zuerst. */
    void depth(int d) {
        depth = Math.max(0, d);
        trim();
    }

    /**
     * Kachel merken. Leere Einträge (nichts gesetzt, Rückgängig aus) werden verworfen, ein älterer Eintrag derselben
     * Kachel ersetzt (seine Dateien gelöscht). false, wenn nichts gemerkt wurde.
     */
    boolean add(Tile tile, UndoRecord rec) {
        if (rec.empty()) {
            delete(rec);
            return false;
        }
        Entry old = entries.remove(tile.key());
        if (old != null) {
            delete(old.record());
        }
        entries.put(tile.key(), new Entry(tile, rec));
        trim();
        return entries.containsKey(tile.key());
    }

    /** Eintrag der Kachel herausnehmen (der Aufrufer setzt zurück und löscht danach), null ohne Eintrag. */
    Entry take(Tile tile) {
        return entries.remove(tile.key());
    }

    /** Zuletzt gemerkte Kachel (ohne sie herauszunehmen), null ohne Eintrag. */
    Tile latest() {
        Tile last = null;
        for (Entry e : entries.values()) {
            last = e.tile();
        }
        return last;
    }

    int size() {
        return entries.size();
    }

    private void trim() {
        Iterator<Entry> it = entries.values().iterator();
        while (entries.size() > depth && it.hasNext()) {
            Entry oldest = it.next();
            it.remove();
            delete(oldest.record());
        }
    }

    private static void delete(UndoRecord rec) {
        if (rec.spool() != null) {
            rec.spool().delete();
        }
    }
}
