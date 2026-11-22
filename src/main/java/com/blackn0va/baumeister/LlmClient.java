package com.blackn0va.baumeister;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
import java.util.logging.Level;
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

    /**
     * Höchste Token-Grenze einer Nachfrage: die Obergrenze von llm.max-tokens (Settings.from, ConfigCheck.RANGES). Eine
     * eigene Einstellung gibt es bewusst nicht – der Host kappt max_tokens ohnehin auf den freien Kontext
     * (host/src/llm/engine.hpp, answer_budget), mehr als 8192 kann kein Plan je bekommen.
     */
    static final int MAX_TOKENS_CAP = 8192;

    private final HttpClient http;
    private final Settings settings;
    private final JsonObject schema;
    /** response_format.json_schema.name – wählt im Host die Grammatik (bauplan oder begleiter). */
    private final String schemaName;
    private final int maxTokens;
    private final long[] retryDelaysMs;
    /** Token-Grenze der einen Nachfrage nach einer abgeschnittenen Antwort; ≤ maxTokens = keine Nachfrage. */
    private final int lengthRetryTokens;
    /**
     * Host weg? Gleiche Regel wie HostReporter und CompanionVoiceLink: eine Zeile beim Ausfall, Erinnerung alle 15 min,
     * eine bei Rückkehr. Zusätzlich entscheidet der Zustand, ob ein Auftrag die volle Wiederholungskette bekommt.
     */
    private final OutageLog outage = new OutageLog(System::nanoTime);
    /** llm.modell – für Ollama der echte Name (qwen2.5:7b), für llama-server und die App egal („lokal“). */
    private String model = "lokal";

    LlmClient(Settings settings, JsonObject schema) {
        this(settings, schema, RETRY_DELAYS_MS);
    }

    /** retryDelaysMs: für Tests kurz; leer = ein Versuch. */
    LlmClient(Settings settings, JsonObject schema, long[] retryDelaysMs) {
        this(settings, schema, "bauplan", settings.maxTokens(), retryDelaysMs,
                Math.min(2 * settings.maxTokens(), MAX_TOKENS_CAP));
    }

    /**
     * Für den Begleiter: eigenes Schema samt Namen und kürzere Antworten. Ohne Nachfrage bei abgeschnittener Antwort –
     * der Begleiter hat eine feste Antwortfrist (CompanionService.ANSWER_DEADLINE_MS), eine zweite, längere Rechnung
     * fiele fast immer hinein.
     */
    LlmClient(Settings settings, JsonObject schema, String schemaName, int maxTokens, long[] retryDelaysMs) {
        this(settings, schema, schemaName, maxTokens, retryDelaysMs, 0);
    }

    /** lengthRetryTokens: Grenze der einen Nachfrage nach einer abgeschnittenen Antwort; ≤ maxTokens = keine. */
    LlmClient(Settings settings, JsonObject schema, String schemaName, int maxTokens, long[] retryDelaysMs,
            int lengthRetryTokens) {
        this.settings = settings;
        this.schema = schema;
        this.schemaName = schemaName;
        this.maxTokens = maxTokens;
        this.retryDelaysMs = retryDelaysMs.clone();
        this.lengthRetryTokens = lengthRetryTokens;
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

    /** HTTP-Frist je Anfrage (TownLifeTest prüft sie am Bewohner-Client). */
    java.time.Duration timeout() {
        return settings.llmTimeout();
    }

    static JsonObject message(String role, String content) {
        return com.blackn0va.baumeister.plan.LlmProtocol.message(role, content);
    }

    /** Antwort samt Befund, ob sie an der Token-Grenze abgeschnitten wurde. */
    private record Answer(Reply reply, boolean truncated) {
    }

    /**
     * Eine Anfrage samt Wiederholungen und höchstens einer Nachfrage mit mehr Platz.
     *
     * Host-Ausfall (dev01 bis 2026-11-21: 41× „llm | wiederholung … grund=ConnectException“, 30× „llm | fehler“): Vorher
     * lief JEDER Auftrag bei ausgeschaltetem PC die volle Kette – 3 Versuche, 13,0–13,1 s (gemessen 2026-11-21 gegen
     * einen geschlossenen Port, auch beim zweiten Auftrag), dann „nicht erreichbar“ ohne Ausweg. Jetzt: Ist der Host aus
     * einer vorigen Kette schon als weg bekannt (OutageLog), gibt es genau einen Versuch; scheitert er wieder an der
     * Verbindung, sagt der Baumeister sofort ab und nennt den Schnellbau. Ein einzelner Wackler bei bekanntem Host
     * bekommt weiter die volle Kette. Wer die Prüfung auf outage.down() entfernt, lässt den Spieler wieder bei jedem
     * Auftrag 13 s auf dieselbe Absage warten.
     */
    CompletableFuture<Reply> complete(List<JsonObject> messages) {
        boolean known = outage.down();
        return tracked(send(request(messages, maxTokens), 0, known ? 0 : retryDelaysMs.length), known)
                .thenCompose(a -> {
                    if (!askAgain(a.truncated(), a.reply().tokens(), maxTokens, lengthRetryTokens)) {
                        return CompletableFuture.completedFuture(a.reply());
                    }
                    // Abgeschnitten (dev01 bis 2026-11-21: 3× Plan verworfen mit „JSON fehlerhaft: EOFException … path
                    // $.steps[N]“). Vorher ging die halbe Antwort direkt in die Prüfung: JsonRepair rettet das
                    // Vollständige, ein Plan ohne Dach oder Hälfte fiel trotzdem durch. Jetzt genau EINE Nachfrage
                    // mit mehr Platz – keine Kette, ein zweiter Lauf kostet auf dem PC Minuten. Scheitert sie, gilt
                    // die erste Antwort wie bisher.
                    LOG.info(() -> "llm | nachfrage | grund=abgeschnitten schema=" + schemaName + " max_tokens="
                            + maxTokens + " neu=" + lengthRetryTokens + " tokens=" + a.reply().tokens());
                    boolean nowKnown = outage.down();
                    return tracked(send(request(messages, lengthRetryTokens), 0,
                            nowKnown ? 0 : retryDelaysMs.length), nowKnown)
                            .handle((b, err) -> {
                                if (err != null) {
                                    LOG.log(Level.WARNING, "llm | nachfrage_fehlgeschlagen | schema=" + schemaName
                                            + " – nehme die abgeschnittene Antwort", err);
                                    return a.reply();
                                }
                                Reply r = b.reply();
                                return new Reply(r.content(), a.reply().millis() + r.millis(), r.tokens(),
                                        r.tokensPerSecond());
                            });
                });
    }

    /**
     * Lohnt die Nachfrage? Nur wenn abgeschnitten, die neue Grenze wirklich höher ist und die Antwort die alte Grenze
     * ausgeschöpft hat. Hat der Host weniger Token erzeugt (unter 90 % der Grenze), hat ihn der Kontext gekappt
     * (answer_budget) – dann brächte dieselbe Anfrage mit mehr max_tokens dasselbe Ergebnis nach Minuten Rechnen.
     * Die 10 % Spielraum sind nicht gemessen: Sie fangen ab, dass Server Token unterschiedlich zählen. tokens ≤ 0 =
     * Server meldet keine Zahl, dann fragen. Rein, testbar.
     */
    static boolean askAgain(boolean truncated, int tokens, int asked, int raised) {
        if (!truncated || raised <= asked) {
            return false;
        }
        return tokens <= 0 || tokens >= asked * 9L / 10;
    }

    private HttpRequest request(List<JsonObject> messages, int tokens) {
        // Körper und Auswertung seit 2026-10-31 in der Plan-Logik (LlmProtocol), gleich für die Client-Mod.
        JsonObject body = com.blackn0va.baumeister.plan.LlmProtocol.body(model, messages, schema, schemaName,
                settings.temperature(), tokens);
        return HttpRequest.newBuilder(settings.llmUrl())
                .timeout(settings.llmTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();
    }

    /**
     * Führt OutageLog nach: Endet die Kette an einer verweigerten Verbindung, gilt der Host als weg. War er das schon
     * vor dem Auftrag (known), wird daraus HostDownException – describe nennt dann den Schnellbau statt „nach drei
     * Versuchen“. Jede HTTP-Antwort (auch 503) zählt in send() als „Host da“.
     */
    private CompletableFuture<Answer> tracked(CompletableFuture<Answer> f, boolean known) {
        return f.handle((a, err) -> {
            if (err == null) {
                return CompletableFuture.completedFuture(a);
            }
            Throwable cause = root(err);
            if (cause instanceof java.net.ConnectException) {
                long minutes = outage.failure();
                if (minutes != OutageLog.UP) {
                    LOG.warning(() -> "llm | host_weg | schema=" + schemaName + " seit_min=" + minutes
                            + " – weitere Aufträge ohne Wiederholung, bis er wieder antwortet");
                }
                if (known) {
                    return CompletableFuture.<Answer>failedFuture(new HostDownException(cause));
                }
            }
            return CompletableFuture.<Answer>failedFuture(err);
        }).thenCompose(x -> x);
    }

    private void hostAnswered() {
        long minutes = outage.success();
        if (minutes != OutageLog.UP) {
            LOG.info(() -> "llm | host_zurück | schema=" + schemaName + " ausfall_min=" + minutes);
        }
    }

    private CompletableFuture<Answer> send(HttpRequest request, int retry, int retries) {
        long start = System.nanoTime();
        CompletableFuture<Answer> first = http.sendAsync(request, HttpResponse.BodyHandlers.ofString(
                StandardCharsets.UTF_8))
                .thenApply(response -> {
                    long millis = (System.nanoTime() - start) / 1_000_000;
                    hostAnswered();
                    if (response.statusCode() != 200) {
                        throw new LlmException("llm_http_" + response.statusCode());
                    }
                    Reply reply = parse(response.body(), millis);
                    return new Answer(reply, truncated(response.body(), reply.content()));
                });
        if (retry >= retries) {
            return first;
        }
        return first.handle((answer, err) -> {
            if (err == null) {
                return CompletableFuture.completedFuture(answer);
            }
            if (!retryable(err)) {
                return CompletableFuture.<Answer>failedFuture(err);
            }
            long delay = retryDelaysMs[retry];
            LOG.info(() -> "llm | wiederholung | versuch=" + (retry + 2) + " in_ms=" + delay + " grund="
                    + root(err).getClass().getSimpleName() + (root(err) instanceof LlmException ? ":"
                    + root(err).getMessage() : ""));
            return CompletableFuture.supplyAsync(() -> request,
                    CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS))
                    .thenCompose(r -> send(r, retry + 1, retries));
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
     * Abgeschnitten? finish_reason „length“ (llama-server, Host-App: chat_api.cpp, Ollama), sonst am Inhalt: endet das
     * JSON mit offenen Klammern (JsonRepair.truncated, dieselbe Erkennung, mit der PlanParser später rettet) – für
     * Endpunkte, die finish_reason nicht mitschicken. Rein, testbar; wirft nicht (parse hat den Körper schon gelesen).
     */
    static boolean truncated(String body, String content) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (root.isJsonObject() && root.getAsJsonObject().get("choices") instanceof JsonArray choices
                    && !choices.isEmpty() && choices.get(0).isJsonObject()
                    && choices.get(0).getAsJsonObject().get("finish_reason") instanceof JsonElement reason
                    && reason.isJsonPrimitive() && "length".equals(reason.getAsString())) {
                return true;
            }
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "llm | finish_reason_unlesbar", e);
        }
        com.blackn0va.baumeister.plan.JsonRepair.Result r = com.blackn0va.baumeister.plan.JsonRepair.repair(content);
        return r != null && r.truncated();
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

    /**
     * Host war schon vor dem Auftrag als weg bekannt und verweigert die Verbindung weiter: ein Versuch, keine Kette.
     * Eigene Klasse, damit describe den Spieler nicht „nach drei Versuchen“ warten lässt, die es nicht gab.
     */
    static final class HostDownException extends IOException {

        private static final long serialVersionUID = 1L;

        HostDownException(Throwable cause) {
            super("llm_host_weg", cause);
        }
    }

    /**
     * Hinweis bei Host-Ausfall: QuickForms bauen ohne Modell (HelpBook, Kapitel „Schnellbau“). Vorher endete die
     * Meldung bei „läuft BaumeisterHost?“ – der Spieler wusste nicht, dass Bauen trotzdem geht.
     */
    static final String QUICK_HINT = "Schnellbau geht auch ohne KI: Pyramide, Burg, Hochhaus …";

    /** Macht aus Netzwerkfehlern einen kurzen deutschen Satz für den Chat. */
    static String describe(Throwable t) {
        Throwable cause = root(t);
        if (cause instanceof HostDownException) {
            return "Der PC mit der KI ist aus (BaumeisterHost antwortet nicht). " + QUICK_HINT;
        }
        if (cause instanceof java.net.ConnectException) {
            // Vorher „läuft start-llm.ps1“ – das Skript ist seit der Host-App (2026-10-28) nicht mehr der Weg.
            return "Das LLM ist nicht erreichbar, auch nach drei Versuchen (läuft BaumeisterHost auf dem PC?). "
                    + QUICK_HINT;
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
