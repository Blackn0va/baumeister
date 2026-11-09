package com.blackn0va.baumeister;

import com.blackn0va.baumeister.osm.CityPlanner;

import java.util.concurrent.CompletableFuture;

/**
 * Woher CityJob seine Etappen bekommt. Vorher hing CityJob fest an CityStages (ein Ort um einen Mittelpunkt, Kacheln
 * vom Kartendienst). Der Weltbau baut dagegen eine feste Kachel der Erdwelt, deren Karte schon geladen ist – mit
 * derselben Bau-Maschinerie (Helfer, Lastregelung, Rückgängig, Kistenschutz), aber einer anderen Etappenquelle.
 *
 * Threads: alle Methoden vom Hauptthread; plan() liefert asynchron. Fehler: als fehlgeschlagene CompletableFuture.
 */
interface StageSource {

    /** Name für Meldungen und Log. */
    String place();

    /** Anzahl der Etappen. */
    int count();

    /** Etappe k planen (0 ≤ k &lt; count()). */
    CompletableFuture<CityPlanner.Plan> plan(int k);

    /** Speicher und Threads freigeben; danach wird nicht mehr geplant. */
    void close();

    /**
     * true: Die Zellen der Pläne tragen schon absolute Welthöhen (Gelände im Planungs-Thread gerechnet, TerrainFit),
     * CityJob setzt sie unverändert. false: y relativ zur Basis wie bisher.
     */
    default boolean absolute() {
        return false;
    }

    /** Zusatz für den Fortschritt, solange die Karte vorab lädt (CityStages.downloadAll: „, Karte 3/12“), sonst leer. */
    default String mapProgress() {
        return "";
    }

    /** Befehl, der diesen Bau zurücksetzt – für die Schlussmeldung von CityJob. */
    default String undoCommand() {
        return "/bm rückgängig";
    }
}
