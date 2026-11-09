package com.blackn0va.baumeister;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Drossel für den Kartendienst im Weltbau: höchstens eine Abfrage gleichzeitig und zwischen dem Ende der einen und dem
 * Beginn der nächsten mindestens gapMillis.
 *
 * Warum: Der Automatikbetrieb fragt ohne Zutun eines Menschen an, solange Spieler laufen – ohne Bremse wären das
 * Dutzende Kacheln am Stück gegen öffentliche Overpass-Instanzen („einzelne Orte ja, Dauerabfragen nein“, README).
 * Eine Großstadtkachel braucht beim Dienst 33–44 s (gemessen München 2026-10-28); mit 20 s Abstand sind es also
 * höchstens rund eine Abfrage je Minute – im Betrieb weit weniger, weil der Weltbau erst nach dem Bau einer Kachel die
 * nächste lädt. Gezählt wird ab dem ENDE einer Abfrage, nicht ab ihrem Beginn: Eine Abfrage, die 140 s lief, war für
 * den Dienst teuer – direkt danach die nächste wäre die falsche Antwort. Wer das auf „ab Beginn“ umstellt, schickt
 * nach langen Abfragen sofort die nächste.
 *
 * Uhr injizierbar (Tests). Threads: nur Hauptthread. Fehler: begin() bei laufender Abfrage → IllegalStateException
 * (Programmierfehler, nie still zwei Abfragen).
 */
final class Throttle {

    private final LongSupplier clockMillis;
    private long gapMillis;
    private boolean inFlight;
    /** Frühester Beginn der nächsten Abfrage; 0 = sofort. */
    private long nextAllowed;

    Throttle(LongSupplier clockMillis, long gapMillis) {
        this.clockMillis = clockMillis;
        this.gapMillis = gapMillis;
    }

    /** Abstand neu (Config neu geladen); wirkt ab dem nächsten end(). */
    void gap(long millis) {
        gapMillis = millis;
    }

    boolean ready() {
        return !inFlight && clockMillis.getAsLong() >= nextAllowed;
    }

    boolean inFlight() {
        return inFlight;
    }

    /** Millisekunden bis zur nächsten erlaubten Abfrage (0 = jetzt; während einer Abfrage unbestimmt: −1). */
    long waitMillis() {
        return inFlight ? -1 : Math.max(0, nextAllowed - clockMillis.getAsLong());
    }

    void begin() {
        if (!ready()) {
            throw new IllegalStateException("Kartendienst: Abfrage vor Ablauf der Drossel");
        }
        inFlight = true;
    }

    /**
     * begin(), dann die Abfrage anstoßen. Wirft der Anstoß selbst (synchron, bevor eine Future existiert), gibt die
     * Drossel sofort wieder frei und reicht die Ausnahme weiter; end() nach der Antwort bleibt Sache des Aufrufers.
     *
     * Prüfbefund 2026-10-28: Vorher standen begin() und osm.fetchBox() nebeneinander im WorldBuildService. Eine
     * Overpass-Adresse ohne Schema in der config lässt HttpRequest.newBuilder synchron werfen („URI with undefined
     * scheme“, nachgestellt in SyncThrowProbe) – end() kam nie, inFlight blieb bis zum Neustart true: kein Weltbau
     * mehr, und /bm stadt verweigerte dauerhaft („Der Weltbau lädt gerade Kartendaten“). Wer begin() wieder vom Anstoß
     * trennt, holt genau das zurück.
     */
    <T> CompletableFuture<T> start(Supplier<CompletableFuture<T>> query) {
        begin();
        try {
            return query.get();
        } catch (RuntimeException e) {
            end();
            throw e;
        }
    }

    /**
     * Wie start(), aber die Drossel gibt sich nach der Antwort selbst frei – Erfolg wie Fehler –, über main (der
     * Hauptthread, wie jeder Drossel-Aufruf). Für Abfragen ohne eigenen end()-Pfad, z. B. das Vorausladen.
     *
     * Vorfall 2026-10-31 (dev01): Das Vorausladen im Weltbau rief start(), aber nie end(). Nach seiner ersten Abfrage
     * (dort HTTP 502) blieb inFlight für immer true; die nächste Kachel wartete ewig auf die Drossel, die Automatik
     * wählte nichts mehr – „Auto an“ in München tat nichts – und /bm stadt hätte bis zum Neustart „Der Weltbau lädt
     * gerade Kartendaten“ gemeldet. Wer das Vorausladen wieder auf start() umstellt, holt genau das zurück.
     */
    <T> CompletableFuture<T> startReleasing(Supplier<CompletableFuture<T>> query, Executor main) {
        CompletableFuture<T> answer = start(query);
        answer.whenComplete((r, e) -> main.execute(this::end));
        return answer;
    }

    /** Abfrage beendet – egal ob mit Erfolg oder Fehler, der Dienst hat gearbeitet. */
    void end() {
        inFlight = false;
        nextAllowed = clockMillis.getAsLong() + gapMillis;
    }
}
