package com.blackn0va.baumeister;

import com.google.gson.JsonObject;

import java.net.URI;
import java.util.List;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Meldet dem Baumeister-Host, was aus einem Auftrag im Spiel wurde: gebaut (Blöcke, Plot), verworfen, abgesagt,
 * abgebrochen. Wunsch 2026-10-31 – vorher sah die App nur die Antwort des Modells; ob daraus
 * ein Bau wurde, stand allein im Serverlog auf dev01. Die App zeigt es in Karte, Konsole und Reiter „Verlauf“.
 *
 * Schnittstelle Version 1, Gegenstück im Host: host/src/server/result_api.cpp – beide Seiten gleich halten:
 *   POST &lt;Adresse wie HostReporter, Pfad PATH&gt;, JSON {auftrag, ergebnis, grund, bloecke, plot,  // umlaut-ok
 *   korrekturen, runden}. korrekturen und runden seit 2026-10-31 (was der Baumeister ohne LLM nachgebessert hat,
 *   wie viele LLM-Antworten der Auftrag brauchte); ein älterer Host übergeht sie.
 *
 * Reine Anzeige, bewusst fail-open wie HostReporter: Ist der Host nicht da, ändert das am Bau nichts; geloggt wird
 * höchstens einmal je Minute. Nie blockierend (sendAsync), keine Warteschlange – ein verlorenes Ergebnis fehlt nur in
 * der Anzeige.
 *
 * Threads: Aufrufe aus dem Hauptthread, Abschluss im HttpClient-Pool. Fehler: wirft nicht.
 */
final class ResultReporter {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    static final String PATH = "/baumeister/v1/ergebnis";
    private static final long ERROR_LOG_NANOS = 60_000_000_000L;

    private final URI uri;
    private final HostReporter.Transport transport;
    private final LongSupplier clock;
    private volatile long lastErrorLog = Long.MIN_VALUE / 2;

    ResultReporter(URI uri, HostReporter.Transport transport, LongSupplier clock) {
        this.uri = uri;
        this.transport = transport;
        this.clock = clock;
    }

    /** null, wenn abgeschaltet (gui.ergebnis-melden: false) oder keine brauchbare Adresse. */
    static ResultReporter from(FileConfiguration c, URI llmUrl) {
        if (!c.getBoolean("gui.ergebnis-melden", true)) {
            return null;
        }
        URI base = HostReporter.target(c.getString("gui.url", ""), llmUrl);
        if (base == null) {
            return null;
        }
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        HostReporter.Transport t = (u, json) -> http.sendAsync(HttpRequest.newBuilder(u)
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.discarding()).thenApply(HttpResponse::statusCode);
        return new ResultReporter(base.resolve(PATH), t, System::nanoTime);
    }

    /** outcome: gebaut | verworfen | abgesagt | abgebrochen (die App lehnt anderes mit 400 ab). */
    void report(String order, String outcome, String reason, long blocks, String plot) {
        report(order, outcome, reason, blocks, plot, List.of(), 0);
    }

    /**
     * fixes: Reparaturen ohne LLM (PlanAutoFix, PlanParser) – „Schritt 2: house-Grundriss 32×1 auf 24×5 angepasst“.
     * rounds: LLM-Antworten für diesen Auftrag (0 = Schnellauftrag, 1 = ohne Nachbesserung).
     */
    void report(String order, String outcome, String reason, long blocks, String plot, List<String> fixes,
            int rounds) {
        JsonObject o = new JsonObject();
        o.addProperty("auftrag", order == null ? "" : order);
        o.addProperty("ergebnis", outcome);
        o.addProperty("grund", reason == null ? "" : reason);
        o.addProperty("bloecke", Math.max(0, blocks));  // umlaut-ok: Schlüssel ASCII wie im Host
        o.addProperty("plot", plot == null ? "" : plot);
        o.addProperty("korrekturen", fixes == null ? "" : String.join("; ", fixes));
        o.addProperty("runden", Math.max(0, rounds));
        try {
            transport.post(uri, o.toString()).whenComplete((status, err) -> {
                if (err != null) {
                    noteError(err.getClass().getSimpleName());
                } else if (status != 200) {
                    noteError("http_" + status);
                }
            });
        } catch (RuntimeException e) {
            // Bewusst fail-open (siehe Kopf): reine Anzeige, der Bau ist davon nie betroffen.
            noteError(e.getClass().getSimpleName());
        }
    }

    private void noteError(String why) {
        long now = clock.getAsLong();
        if (now - lastErrorLog < ERROR_LOG_NANOS) {
            return;
        }
        lastErrorLog = now;
        LOG.info(() -> "gui | ergebnis_nicht_gemeldet | ziel=" + uri + " grund=" + why + " – reine Anzeige");
    }
}
