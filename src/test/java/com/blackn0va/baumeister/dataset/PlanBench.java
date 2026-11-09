package com.blackn0va.baumeister.dataset;

import com.blackn0va.baumeister.plan.LlmProtocol;
import com.blackn0va.baumeister.plan.PlanReview.Accept;
import com.blackn0va.baumeister.plan.PlanReview.GiveUp;
import com.blackn0va.baumeister.plan.PlanReview.Repair;
import com.blackn0va.baumeister.plan.PlanReview.Verdict;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Messgerät: Annahmequote eines Modells über die festen Testaufträge (BenchOrders), mit genau dem Gespräch des
 * Plugins – System-Prompt, PlanPrompt-Nachricht, Schema als response_format (der Host baut daraus die Grammatik),
 * Temperatur 0,4, max. 3000 Token, eine Reparaturrunde wie llm.reparatur-versuche 1 – und PlanReview als Richter.
 *
 * Warum: „Das neue Modell plant besser“ war bisher ein Gefühl. dev01 verwarf 2026-10-31 55 von 136 LLM-Plänen; ob ein
 * eigenes Modell das senkt, zeigt nur dieselbe Messung vorher und nachher.
 *
 * Gezählt je Auftrag:
 * - sofort: im ersten Versuch angenommen (so baut das Plugin ohne Wartezeit für eine Nachbesserung)
 * - nachgebessert: erst nach der Reparaturrunde angenommen
 * - verworfen: auch danach nicht (oder Antwort unbrauchbar)
 * - sauber: die erste Antwort besteht sogar die Lehrset-Prüfung (PlanJudge.rejection) – das Ziel des Feintunings
 *
 * Aufruf (Host-App oder llama-server läuft, nach mvn test-compile):
 *   java -cp "target/classes;target/test-classes;lib/*" com.blackn0va.baumeister.dataset.PlanBench
 *        --url http://127.0.0.1:8080/v1/chat/completions --modell qwen25-7b [--nur 5] [--ziel target/bench]
 *
 * Threads: ein Auftrag nach dem anderen (die Host-App rechnet ohnehin nacheinander, parallel verfälschte die
 * Zeiten). Fehler: Ist der Server nicht erreichbar, bricht der Lauf mit Exitcode 1 ab, statt Aufträge als verworfen
 * zu zählen – sonst sähe ein abgestürzter Server wie ein schlechtes Modell aus.
 */
public final class PlanBench {

    static final double TEMPERATURE = 0.4;
    static final int MAX_TOKENS = 3000;
    static final int REPAIR_ATTEMPTS = 1;

    /** Ergebnis eines Auftrags. outcome: sofort | nachgebessert | verworfen. */
    record Outcome(String order, String outcome, boolean clean, String reason, long millis, int tokens,
            double tokensPerSecond, List<String> answers) {
    }

    private final PlanJudge judge;
    private final HttpClient http;
    private final URI url;
    private final JsonObject schema;

    PlanBench(PlanJudge judge, URI url) {
        this.judge = judge;
        this.url = url;
        this.schema = JsonParser.parseString(judge.schema()).getAsJsonObject();
        // Wie LlmClient: keine Umleitungen, HTTP/1.1 (der Host spricht kein h2c), kurzer Verbindungsaufbau.
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build();
    }

    LlmProtocol.Reply ask(List<JsonObject> messages) throws IOException, InterruptedException {
        JsonObject body = LlmProtocol.body(messages, schema, TEMPERATURE, MAX_TOKENS);
        HttpRequest req = HttpRequest.newBuilder(url).timeout(Duration.ofMinutes(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        long t0 = System.nanoTime();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + ": " + resp.body().substring(0,
                    Math.min(300, resp.body().length())));
        }
        return LlmProtocol.parse(resp.body(), ms);
    }

    Outcome run(String order) throws IOException, InterruptedException {
        List<JsonObject> messages = new ArrayList<>(List.of(
                LlmProtocol.message("system", judge.systemPrompt()),
                LlmProtocol.message("user", judge.userMessage(order))));
        List<String> answers = new ArrayList<>();
        long millis = 0;
        int tokens = 0;
        boolean clean = false;
        for (int attempt = 0; attempt <= REPAIR_ATTEMPTS; attempt++) {
            LlmProtocol.Reply reply = ask(messages);
            millis += reply.millis();
            tokens += reply.tokens();
            answers.add(reply.content());
            if (attempt == 0) {
                clean = judge.rejection(order, reply.content()).isEmpty();
            }
            Verdict v = judge.judge(order, reply.content(), attempt, REPAIR_ATTEMPTS);
            double tps = millis > 0 ? tokens * 1000.0 / millis : 0;
            if (v instanceof Accept) {
                return new Outcome(order, attempt == 0 ? "sofort" : "nachgebessert", clean, "", millis, tokens,
                        tps, answers);
            }
            if (v instanceof GiveUp g) {
                return new Outcome(order, "verworfen", clean, g.reason(), millis, tokens, tps, answers);
            }
            Repair rep = (Repair) v;
            messages.add(LlmProtocol.message("assistant", rep.assistant()));
            messages.add(LlmProtocol.message("user", rep.llmRequest()));
        }
        throw new IllegalStateException("bench_ohne_urteil");
    }

    public static void main(String[] args) throws Exception {
        String url = "http://127.0.0.1:8080/v1/chat/completions";
        String model = "unbenannt";
        int only = Integer.MAX_VALUE;
        Path out = Path.of("target", "bench");
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--url" -> url = args[i + 1];
                case "--modell" -> model = args[i + 1];
                case "--nur" -> only = Integer.parseInt(args[i + 1]);
                case "--ziel" -> out = Path.of(args[i + 1]);
                default -> {
                    System.err.println("Unbekannter Schalter: " + args[i]);
                    System.exit(1);
                }
            }
        }
        PlanJudge judge = new PlanJudge();
        PlanBench bench = new PlanBench(judge, URI.create(url));
        List<String> orders = BenchOrders.load();
        orders = orders.subList(0, Math.min(only, orders.size()));
        Files.createDirectories(out);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String safe = model.replaceAll("[^A-Za-z0-9._-]", "_");
        Path jsonl = out.resolve(safe + "-" + stamp + ".jsonl");
        Gson gson = new GsonBuilder().disableHtmlEscaping().create();
        List<Outcome> results = new ArrayList<>();
        for (String order : orders) {
            Outcome o;
            try {
                o = bench.run(order);
            } catch (java.net.ConnectException e) {
                System.err.println("Server nicht erreichbar (" + url + "): " + e.getMessage());
                System.exit(1);
                return;
            } catch (IOException | IllegalStateException e) {
                // Zeitüberschreitung, HTTP-Fehler oder kaputte Antwort: zählt als verworfen, mit Grund.
                o = new Outcome(order, "verworfen", false, "fehler: " + e.getMessage(), 0, 0, 0, List.of());
            }
            results.add(o);
            System.out.printf(Locale.ROOT, "%-11s %s %6.1f s %5d tok  %s%s%n", o.outcome(), o.clean() ? "sauber"
                    : "      ", o.millis() / 1000.0, o.tokens(), order, o.reason().isEmpty() ? "" : "  – "
                    + DatasetGenerator.shortReason(o.reason()));
            Files.writeString(jsonl, gson.toJson(o) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        }
        String summary = summary(model, url, results);
        System.out.print(summary);
        Files.writeString(out.resolve(safe + "-" + stamp + ".md"), summary, StandardCharsets.UTF_8);
    }

    static String summary(String model, String url, List<Outcome> results) {
        int n = results.size();
        long now = results.stream().filter(o -> o.outcome().equals("sofort")).count();
        long fixed = results.stream().filter(o -> o.outcome().equals("nachgebessert")).count();
        long clean = results.stream().filter(Outcome::clean).count();
        long ms = results.stream().mapToLong(Outcome::millis).sum();
        long tok = results.stream().mapToLong(Outcome::tokens).sum();
        Optional<Double> tps = ms > 0 ? Optional.of(tok * 1000.0 / ms) : Optional.empty();
        return String.format(Locale.ROOT, "%n# Messung %s%n%n%s, %d Aufträge, Temperatur %.1f, %d Reparaturrunde(n)."
                + "%n%n| angenommen sofort | nach Reparatur | verworfen | sauber (Lehrset-Maßstab) | Zeit gesamt | "
                + "Token | Token/s |%n|---:|---:|---:|---:|---:|---:|---:|%n| %d/%d (%.0f %%) | %d | %d | %d/%d | "
                + "%.0f s | %d | %s |%n", model, url, n, TEMPERATURE, REPAIR_ATTEMPTS, now, n, n == 0 ? 0 : 100.0
                * now / n, fixed, n - now - fixed, clean, n, ms / 1000.0, tok, tps.map(t -> String.format(Locale.ROOT,
                "%.1f", t)).orElse("–"));
    }
}
