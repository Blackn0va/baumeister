package com.blackn0va.baumeister.client;

import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;

/**
 * Ergebnis eines Client-Baus an den Baumeister-Host – dasselbe Format wie ResultReporter im Plugin
 * (host/src/server/result_api.cpp), zusätzlich "quelle":"client", damit die App Client- und Server-Bauten
 * unterscheidet. Reine Anzeige: fail-open, höchstens eine Logzeile je Minute.
 *
 * Threads: Aufruf vom Client-Thread, Abschluss im HttpClient-Pool. Fehler: wirft nicht.
 */
final class ResultSender {

    private static final Logger LOG = Logger.getLogger("Baumeister-Client");

    private final ClientSettings settings;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private volatile long lastError = Long.MIN_VALUE / 2;

    ResultSender(ClientSettings settings) {
        this.settings = settings;
    }

    void send(String order, String outcome, String reason, long blocks) {
        send(order, outcome, reason, blocks, List.of(), 0);
    }

    /** Wie ResultReporter im Plugin: korrekturen und runden seit 2026-10-31. */
    void send(String order, String outcome, String reason, long blocks, List<String> fixes, int rounds) {
        if (!settings.reportResults) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("auftrag", order == null ? "" : order);
        o.addProperty("ergebnis", outcome);
        o.addProperty("grund", reason == null ? "" : reason);
        o.addProperty("bloecke", Math.max(0, blocks));  // umlaut-ok: Schlüssel ASCII wie im Host
        o.addProperty("plot", "");
        o.addProperty("quelle", "client");
        o.addProperty("korrekturen", fixes == null ? "" : String.join("; ", fixes));
        o.addProperty("runden", Math.max(0, rounds));
        try {
            URI target = URI.create(settings.llmUrl).resolve("/baumeister/v1/ergebnis");
            http.sendAsync(HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(o.toString(), StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.discarding()).whenComplete((r, err) -> {
                        if (err != null || r.statusCode() != 200) {
                            note(err != null ? err.getClass().getSimpleName() : "http_" + r.statusCode());
                        }
                    });
        } catch (IllegalArgumentException e) {
            // Bewusst fail-open: reine Anzeige (ungültige llmUrl meldet schon der Planer).
            note(e.getClass().getSimpleName());
        }
    }

    private void note(String why) {
        long now = System.nanoTime();
        if (now - lastError > 60_000_000_000L) {
            lastError = now;
            LOG.info(() -> "gui | ergebnis_nicht_gemeldet | grund=" + why + " – reine Anzeige");
        }
    }
}
