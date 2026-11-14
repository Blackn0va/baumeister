package com.blackn0va.baumeister.client;

import com.blackn0va.baumeister.plan.BlockCatalog;
import com.blackn0va.baumeister.plan.CompiledBuild;
import com.blackn0va.baumeister.plan.LlmProtocol;
import com.blackn0va.baumeister.plan.Machines;
import com.blackn0va.baumeister.plan.ModuleLibrary;
import com.blackn0va.baumeister.plan.PlanCompiler;
import com.blackn0va.baumeister.plan.PlanException;
import com.blackn0va.baumeister.plan.PlanParser;
import com.blackn0va.baumeister.plan.PlanPrompt;
import com.blackn0va.baumeister.plan.PlanReview;
import com.blackn0va.baumeister.plan.QuickOrders;
import com.blackn0va.baumeister.plan.RequestHints;
import com.blackn0va.baumeister.plan.SiteSurvey;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Vom Auftrag zum fertigen Bau (CompiledBuild) – dieselbe Kette wie im Plugin: erst die Schnellaufträge (QuickOrders,
 * ohne LLM), sonst LLM im Baumeister-Host mit derselben Nachricht (PlanPrompt), derselben Grammatik (LlmProtocol) und
 * demselben Prüfer samt Nachbesserung (PlanReview). Vorher gab es nur den Weg über das Server-Plugin; Wunsch
 * 2026-10-31.
 *
 * Nicht dabei (Server-Sachen): Recherche im Internet, Gedächtnis für Folgeaufträge, Plot-Modus.
 *
 * Threads: quick() auf dem Client-Thread; llm() rechnet im HttpClient-Pool und vervollständigt dort – der Aufrufer
 * springt selbst zurück auf den Client-Thread. Fehler: PlanException mit deutschem Satz.
 */
final class ClientPlanner {

    /**
     * Fertig geprüfter Bau. reply = Antwortsatz des Plans für den Chat. fixes = Nachbesserungen ohne LLM
     * (PlanAutoFix wie im Plugin), rounds = LLM-Antworten (0 = Schnellauftrag) – beides für Chat und App.
     */
    record Planned(CompiledBuild build, String reply, List<String> warnings, List<String> fixes, int rounds) {

        Planned(CompiledBuild build, String reply, List<String> warnings) {
            this(build, reply, warnings, List.of(), 0);
        }
    }

    private static final Gson GSON = new Gson();

    private final ClientSettings settings;
    private final PlanCompiler compiler;
    private final ModuleLibrary modules = new Machines();
    private final String systemPrompt;
    private final JsonObject schema;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    ClientPlanner(ClientSettings settings, BlockCatalog catalog) throws IOException {
        this.settings = settings;
        this.compiler = new PlanCompiler(catalog, "stone",
                new PlanCompiler.Limits(settings.maxBlocks, settings.maxExtent), modules);
        this.systemPrompt = resource("/system-prompt.txt");
        this.schema = JsonParser.parseString(resource("/plan-schema.json")).getAsJsonObject();
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = ClientPlanner.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("Ressource fehlt in der Mod: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    ModuleLibrary modules() {
        return modules;
    }

    /** Schnellauftrag (Hochhaus, Burg, Bunker, Taschenrechner …); leer = der Auftrag gehört ans LLM. */
    Optional<Planned> quick(String prompt, SiteSurvey survey) throws PlanException {
        Optional<String> json = QuickOrders.match(prompt, modules, settings.maxExtent);
        if (json.isEmpty()) {
            return Optional.empty();
        }
        var plan = PlanParser.parse(json.get());
        CompiledBuild build = compiler.compile(plan);
        PlanReview.checkOverbuild(survey, prompt, build, settings.maxOverbuild);
        return Optional.of(new Planned(build, plan.reply(), build.warnings()));
    }

    /**
     * LLM mit Prüfer und höchstens repairAttempts Nachbesserungen. say: Zwischenstand für den Chat (beliebiger Thread).
     * request: über ihn bricht /kbau stop die laufende Anfrage samt weiterer Runden ab (PlanRequest).
     */
    CompletableFuture<Planned> llm(String prompt, SiteSurvey survey, Consumer<String> say, PlanRequest request) {
        List<String> names = modules.all().stream().map(ModuleLibrary.Module::name).toList();
        PlanReview review = new PlanReview(compiler, modules, new PlanReview.Context(prompt,
                RequestHints.parse(prompt, names), survey, false, settings.maxOverbuild, settings.repairAttempts,
                PlanReview.LintMode.STRICT));
        List<JsonObject> messages = new ArrayList<>();
        messages.add(LlmProtocol.message("system", systemPrompt));
        messages.add(LlmProtocol.message("user", PlanPrompt.userMessage(prompt, survey, settings.distanceAhead,
                modules, null, null, 0, null)));
        CompletableFuture<Planned> result = new CompletableFuture<>();
        ask(review, messages, 0, say, result, request);
        return result;
    }

    private void ask(PlanReview review, List<JsonObject> messages, int attempt, Consumer<String> say,
            CompletableFuture<Planned> result, PlanRequest request) {
        JsonObject body = LlmProtocol.body(messages, schema, settings.temperature, settings.maxTokens);
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(settings.llmUrl))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();
        long start = System.nanoTime();
        CompletableFuture<HttpResponse<String>> call = http.sendAsync(httpRequest,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (!request.track(call)) {
            result.completeExceptionally(new PlanException("Planung abgebrochen."));
            return;
        }
        call.whenComplete((r, err) -> {
            if (request.cancelled()) {
                // Abgebrochen: keine Meldung mehr, keine Nachbesserungsrunde (ClientBuilder hat schon gemeldet).
                result.completeExceptionally(new PlanException("Planung abgebrochen."));
                return;
            }
            if (err != null) {
                result.completeExceptionally(new PlanException("Der Baumeister-Host ist nicht erreichbar ("
                        + settings.llmUrl + "): " + err.getClass().getSimpleName()
                        + ". Läuft die App und ist das Modell geladen?"));
                return;
            }
            if (r.statusCode() != 200) {
                result.completeExceptionally(new PlanException("Der Baumeister-Host antwortet mit HTTP "
                        + r.statusCode() + (r.statusCode() == 503 ? " (Modell nicht geladen oder beschäftigt)" : "")
                        + "."));
                return;
            }
            LlmProtocol.Reply reply;
            try {
                reply = LlmProtocol.parse(r.body(), (System.nanoTime() - start) / 1_000_000);
            } catch (IllegalStateException e) {
                result.completeExceptionally(new PlanException("Antwort des Hosts unbrauchbar: " + e.getMessage()));
                return;
            }
            PlanReview.Verdict v = review.judge(reply.content(), attempt);
            if (v instanceof PlanReview.Repair rep) {
                say.accept(rep.playerText());
                // Korrekturrunde ohne Scan und Modulbeschreibungen, nur der letzte Plan (PlanPrompt.repairConversation).
                List<JsonObject> next = PlanPrompt.repairConversation(messages, review.prompt(),
                        modules.all().stream().map(ModuleLibrary.Module::name).toList(), rep.assistant(),
                        rep.llmRequest());
                ask(review, next, attempt + 1, say, result, request);
            } else if (v instanceof PlanReview.GiveUp g) {
                result.completeExceptionally(new PlanException("Kein brauchbarer Plan: " + g.playerText()));
            } else {
                PlanReview.Accept a = (PlanReview.Accept) v;
                result.complete(new Planned(a.build(), a.plan().reply(), a.build().warnings(), a.repairs(),
                        attempt + 1));
            }
        });
    }
}
