package com.blackn0va.baumeister.client;

import java.util.concurrent.CompletableFuture;

/**
 * Eine laufende Planung beim Host, die sich abbrechen lässt – samt Nachbesserungsrunden.
 *
 * Vorher setzte /kbau stop nur das PlanTicket zurück: Das Ergebnis wurde verworfen, aber die HTTP-Anfrage lief weiter
 * bis zur Antwort, und der Host rechnete den verworfenen Plan zu Ende, Nachbesserungsrunden inklusive (Gedächtnis
 * „Offen“). Auf dem GPU-PC neben Minecraft sind das Minuten, in denen ein neuer Auftrag wartet. Jetzt bricht cancel()
 * die laufende Anfrage ab (CompletableFuture.cancel des sendAsync-Ergebnisses schließt die Verbindung, gemessen in
 * PlanRequestTest), und der Host sieht die geschlossene Verbindung und hört auf zu rechnen (chat_api: client_gone).
 * Eine Runde, die erst nach dem Abbruch beginnen würde, startet gar nicht mehr.
 *
 * Threads: track() aus dem HttpClient-Pool oder dem Client-Thread, cancel() vom Client-Thread – deshalb synchronisiert.
 * Fehler: keine; ein schon fertiger Aufruf lässt sich nicht mehr abbrechen, das ist kein Fehler.
 */
final class PlanRequest {

    private CompletableFuture<?> current;
    private boolean cancelled;

    /** Die gerade laufende Anfrage merken. War schon abgebrochen, wird sie sofort mit abgebrochen; dann false. */
    synchronized boolean track(CompletableFuture<?> call) {
        if (cancelled) {
            call.cancel(true);
            return false;
        }
        current = call;
        return true;
    }

    /** Abbrechen: die laufende Anfrage und jede weitere Runde. */
    synchronized void cancel() {
        cancelled = true;
        if (current != null) {
            current.cancel(true);
        }
    }

    synchronized boolean cancelled() {
        return cancelled;
    }
}
