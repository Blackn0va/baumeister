package com.blackn0va.baumeister;

import com.blackn0va.baumeister.companion.WorldWatch;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.bukkit.Location;
import org.bukkit.block.Block;

/**
 * Blick ins Protokoll (CoreProtect), ohne dass CompanionService einen CoreProtect-Typ kennt: Ohne das Plugin bleibt
 * dieser Dienst null, und keine CoreProtect-Klasse wird geladen (CoreProtectBoot).
 *
 * Alle Abfragen laufen abseits des Hauptthreads (CoreProtect verlangt das für Abfragen und Rollbacks); done wird im
 * Hauptthread aufgerufen, bei einem Fehler mit leerem Ergebnis bzw. -1.
 */
interface BlockHistory {

    /** Kisten-Verlauf am Ort für die letzten seconds Sekunden. */
    void chest(Location at, int seconds, Consumer<List<WorldWatch.ChestEntry>> done);

    /** Wer den Block gesetzt hat (letzte Platzierung), leer = unbekannt. */
    void placedBy(Block block, int seconds, Consumer<Optional<String>> done);

    /** Zahl der durch WorldWatch.DAMAGE_CAUSES zerstörten Blöcke im Umkreis. */
    void countDamage(Location at, int radius, int seconds, Consumer<Integer> done);

    /** Setzt genau diese Schäden zurück; done bekommt die Zahl der Änderungen (-1 = Fehler). */
    void repair(Location at, int radius, int seconds, Consumer<Integer> done);
}
