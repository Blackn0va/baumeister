package com.blackn0va.baumeister;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Spricht mit llama-server über die OpenAI-kompatible Schnittstelle.
 *
 * response_format mit json_schema lässt llama-server eine Grammatik bauen: Das Modell kann
 * dann gar kein ungültiges JSON und keine unbekannte op mehr erzeugen. Ohne das liefert ein
 * 7B-Modell gern Erklärtext oder Markdown – der Parser fängt das ab, aber jede verworfene
 * Antwort kostet hier eine bis drei Minuten Rechenzeit auf dem PC.
 */
final class LlmClient {

    record Reply(String content, long millis, int tokens, double tokensPerSecond) {
    }

    private static final Gson GSON = new Gson();
    private static final Logger LOG = Logger.getLogger("Baumeister");

    /**
     * Wartezeit vor dem 2. und 3. Versuch. Vorher genau ein Versuch: In den dev01-Logs (alle bis 2026-10-31) standen
     * 13× „llm | fehler“ – 7× ConnectException (Tunnel/Host kurz weg), 3× HTTP 503 (Modell lädt oder rechnet schon),
     * 2× Verbindung ohne Antwort abgerissen, 1× HTTP 400. Jeder davon war ein verlorener Auftrag, obwohl der Host
     * Sekunden später wieder antwortete. Wer das auf einen Versuch zurückdreht, lässt den Spieler bei jedem
     * Tunnel-Neustart neu tippen.
     */
    static final long[] RETRY_DELAYS_MS = {3_000, 10_000};

    private final HttpClient http;
    private final Settings settings;
    private final JsonObject schema;
    /** response_format.json_schema.name – wählt im Host die Grammatik (bauplan oder begleiter). */
    private final String schemaName;
    private final int maxTokens;
    private final long[] retryDelaysMs;
    /** llm.modell – für Ollama der echte Name (qwen2.5:7b), für llama-server und die App egal („lokal“). */
    private String model = "lokal";

    LlmClient(Settings settings, JsonObject schema) {
        this(settings, schema, RETRY_DELAYS_MS);
    }

    /** retryDelaysMs: für Tests kurz; leer = ein Versuch. */
    LlmClient(Settings settings, JsonObject schema, long[] retryDelaysMs) {
        this(settings, schema, "bauplan", settings.maxTokens(), retryDelaysMs);
    }

    /** Für den Begleiter: eigenes Schema samt Namen und kürzere Antworten. */
    LlmClient(Settings settings, JsonObject schema, String schemaName, int maxTokens, long[] retryDelaysMs) {
        this.settings = settings;
        this.schema = schema;
        this.schemaName = schemaName;
        this.maxTokens = maxTokens;
        this.retryDelaysMs = retryDelaysMs.clone();
        // Redirect.NEVER: Der Endpunkt ist ein fester Tunnel; ein Redirect wäre ein Fehler, kein Ziel.
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** Modellname setzen (llm.modell); leer = „lokal“. Vor der ersten Anfrage, Hauptthread. */
    LlmClient withModel(String name) {
        this.model = name == null || name.isBlank() ? "lokal" : name.strip();
        return this;
    }

    String model() {
        return model;
    }

    static JsonObject message(String role, String content) {
        return com.blackn0va.baumeister.plan.LlmProtocol.message(role, content);
    }

    CompletableFuture<Reply> complete(List<JsonObject> messages) {
        // Körper und Auswertung seit 2026-10-31 in der Plan-Logik (LlmProtocol), gleich für die Client-Mod.
        JsonObject body = com.blackn0va.baumeister.plan.LlmProtocol.body(model, messages, schema, schemaName,
                settings.temperature(), maxTokens);
        HttpRequest request = HttpRequest.newBuilder(settings.llmUrl())
                .timeout(settings.llmTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();
        return send(request, 0);
    }

    private CompletableFuture<Reply> send(HttpRequest request, int retry) {
        long start = System.nanoTime();
        CompletableFuture<Reply> first = http.sendAsync(request, HttpResponse.BodyHandlers.ofString(
                StandardCharsets.UTF_8))
                .thenApply(response -> {
                    long millis = (System.nanoTime() - start) / 1_000_000;
                    if (response.statusCode() != 200) {
                        throw new LlmException("llm_http_" + response.statusCode());
                    }
                    return parse(response.body(), millis);
                });
        if (retry >= retryDelaysMs.length) {
            return first;
        }
        return first.handle((reply, err) -> {
            if (err == null) {
                return CompletableFuture.completedFuture(reply);
            }
            if (!retryable(err)) {
                return CompletableFuture.<Reply>failedFuture(err);
            }
            long delay = retryDelaysMs[retry];
            LOG.info(() -> "llm | wiederholung | versuch=" + (retry + 2) + " in_ms=" + delay + " grund="
                    + root(err).getClass().getSimpleName() + (root(err) instanceof LlmException ? ":"
                    + root(err).getMessage() : ""));
            return CompletableFuture.supplyAsync(() -> request,
                    CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS))
                    .thenCompose(r -> send(r, retry + 1));
        }).thenCompose(f -> f);
    }

    /**
     * Lohnt ein zweiter Versuch? Ja bei Verbindungsabbruch, verweigerter Verbindung und 502/503/504 (Host lädt das
     * Modell, rechnet gerade oder der Tunnel baut sich neu auf). Nein bei Zeitüberschreitung (das Modell hat bis zur
     * Grenze gerechnet – ein zweiter Lauf verdoppelt nur das Warten) und bei jeder anderen Antwort wie 400 (dieselbe
     * Anfrage scheitert wieder gleich). Rein, testbar.
     */
    static boolean retryable(Throwable t) {
        Throwable c = root(t);
        if (c instanceof java.net.http.HttpTimeoutException) {
            return false;
        }
        if (c instanceof LlmException e) {
            return e.getMessage().equals("llm_http_502") || e.getMessage().equals("llm_http_503")
                    || e.getMessage().equals("llm_http_504");
        }
        return c instanceof IOException;
    }

    private static Throwable root(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && (cause instanceof CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static Reply parse(String body, long millis) {
        try {
            com.blackn0va.baumeister.plan.LlmProtocol.Reply r = com.blackn0va.baumeister.plan.LlmProtocol.parse(body,
                    millis);
            return new Reply(r.content(), r.millis(), r.tokens(), r.tokensPerSecond());
        } catch (IllegalStateException e) {
            throw new LlmException(e.getMessage());
        }
    }

    /**
     * Erreichbar? GET /health (llama-server, Baumeister-App), sonst GET /v1/models. Ollama hat kein /health
     * (gemessen 2026-11-08: 404), aber /v1/models (200) – vorher stand bei Ollama in /bm status „NICHT erreichbar“,
     * obwohl Bauaufträge durchgingen.
     */
    CompletableFuture<Boolean> health() {
        return status(settings.llmUrl().resolve("/health")).thenCompose(ok -> ok
                ? CompletableFuture.completedFuture(true)
                : status(settings.llmUrl().resolve("/v1/models")));
    }

    private CompletableFuture<Boolean> status(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenApply(r -> r.statusCode() == 200)
                .exceptionally(e -> false);
    }

    /** Fehlercode im snake_case-Stil; die Meldung ist für Log und Chat bestimmt. */
    static final class LlmException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        LlmException(String code) {
            super(code);
        }
    }

    /** Macht aus Netzwerkfehlern einen kurzen deutschen Satz für den Chat. */
    static String describe(Throwable t) {
        Throwable cause = root(t);
        if (cause instanceof java.net.ConnectException) {
            // Vorher „läuft start-llm.ps1“ – das Skript ist seit der Host-App (2026-10-28) nicht mehr der Weg.
            return "Das LLM ist nicht erreichbar, auch nach drei Versuchen (läuft BaumeisterHost auf dem PC?).";
        }
        if (cause instanceof java.net.http.HttpTimeoutException) {
            return "Das LLM hat zu lange gebraucht.";
        }
        if (cause instanceof LlmException) {
            return "Das LLM hat einen Fehler gemeldet (" + cause.getMessage() + ").";
        }
        if (cause instanceof IOException) {
            // Vorher „Verbindung zum LLM abgebrochen (IOException)“ – seit dem Relay (2026-11-08) der Normalfall, wenn
            // keine App verbunden ist: Das Relay nimmt an und setzt sofort zurück (RST), Java meldet das nicht als
            // ConnectException. Im Spiel sah der Nutzer nur den Klassennamen. Den gibt es weiter im Log
            // („llm | wiederholung | grund=…“), der Chat bekommt einen Satz, mit dem ein Spieler etwas anfangen kann.
            return "Der PC mit der KI ist gerade nicht verbunden, auch nach drei Versuchen (läuft BaumeisterHost "
                    + "dort?). Versuch es gleich noch einmal.";
        }
        return "Unerwarteter Fehler: " + cause.getClass().getSimpleName();
    }
}
