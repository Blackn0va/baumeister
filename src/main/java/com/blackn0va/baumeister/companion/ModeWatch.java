package com.blackn0va.baumeister.companion;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Merkt je Spieler, ob er zuletzt „spielte“ (Überleben/Abenteuer) oder nicht (Kreativ/Zuschauer), und meldet nur
 * echte Wechsel.
 *
 * Vorher lag der Zustand im flüchtigen Watch-Objekt von CompanionAdventure: angelegt mit „spielt“, egal in welchem
 * Modus der Spieler gerade war, gelöscht bei Start und Ende eines Abenteuers, und geprüft nur im Takt des Begleiters
 * (also nur mit gerufener Vera in einer Abenteuerwelt). Gemessen im Log von dev01 am 2026-11-05: Pause um 19:07:09,
 * beim Zurückschalten um 19:08:45 kein „weiter“, um 19:43:58 und 20:06:27 gar keine Pause (Wunsch).
 *
 * Jetzt: Der erste Blick auf einen Spieler legt nur den Stand fest (kein Wechsel). Danach zählt jede Änderung, egal
 * wer fragt – das Ereignis PlayerGameModeChangeEvent oder der Takt als Rückfallebene. Wer den ersten Stand wieder fest
 * auf „spielt“ setzt, bekommt die verschluckten Wechsel zurück.
 *
 * Threads: Hauptthread. Fehler: keine.
 */
public final class ModeWatch {

    public enum Change {
        NONE, PAUSED, RESUMED
    }

    private final Map<UUID, Boolean> playing = new HashMap<>();

    /** playingNow = Überleben oder Abenteuer. */
    public Change observe(UUID player, boolean playingNow) {
        Boolean before = playing.put(player, playingNow);
        if (before == null || before == playingNow) {
            return Change.NONE;
        }
        return playingNow ? Change.RESUMED : Change.PAUSED;
    }

    public void forget(UUID player) {
        playing.remove(player);
    }
}
