package com.blackn0va.baumeister.dataset;

import com.blackn0va.baumeister.plan.LlmProtocol;
import com.blackn0va.baumeister.plan.Machines;
import com.blackn0va.baumeister.plan.PlanCompiler;
import com.blackn0va.baumeister.plan.PlanPrompt;
import com.blackn0va.baumeister.plan.PlanReview;
import com.blackn0va.baumeister.plan.PlanReview.Accept;
import com.blackn0va.baumeister.plan.PlanReview.LintMode;
import com.blackn0va.baumeister.plan.PlanReview.Verdict;
import com.blackn0va.baumeister.plan.Pos;
import com.blackn0va.baumeister.plan.RequestHints;
import com.blackn0va.baumeister.plan.SiteSurvey;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Eine Prüfkette für Lehrsatz, Import und Messgerät: dieselbe Nachricht an das LLM wie im Plugin (PlanPrompt mit
 * System-Prompt, Maschinenbibliothek und REQUEST FACTS) und dasselbe Urteil (PlanReview, Plan-Prüfer streng).
 *
 * Warum eine gemeinsame Klasse: Wenn der Generator einen Plan mit einem anderen Bauplatz oder anderen Limits prüft
 * als das Messgerät, lernt das Modell auf eine Prüfung hin, an der es dann nicht gemessen wird – und „besser“ wäre
 * wieder ein Gefühl. Limits und Bauplatz entsprechen den Config-Vorgaben (bau.max-blöcke 150 000, max-ausdehnung
 * 192, abstand-vor-spieler 3, max-überbauen 12) und einer freien Wiese.
 *
 * Bauplatz: eine flache Wiese (grass_block bei y −1, Erde darunter, darüber Luft) im Scanfenster von SiteFrame.
 * Vorher (LlmProbe) war der Probe-Bauplatz reine Luft – dann trägt nichts unter y 0, und ein Plan mit Fundament bei
 * y −1 (Maschinenhalle) hätte anders geurteilt als auf dem Server. Dass die Fenstergrenzen zu SiteFrame passen,
 * prüft DatasetSiteTest.
 *
 * Threads: nach dem Bau nur lesend, mehrere Threads dürfen judge rufen (PlanReview je Aufruf neu). Fehler: der
 * Konstruktor wirft UncheckedIOException, wenn eine Ressource fehlt.
 */
public final class PlanJudge {

    /** = SiteFrame.SCAN_MIN/SCAN_MAX (paketprivat dort; Gleichheit belegt DatasetSiteTest). */
    public static final Pos SCAN_MIN = new Pos(-16, -4, -6);
    public static final Pos SCAN_MAX = new Pos(32, 20, 36);
    static final int MAX_BLOCKS = 150_000;
    static final int MAX_EXTENT = 192;
    static final int AHEAD = 3;
    static final int MAX_OVERBUILD = 12;

    private final Machines modules = new Machines();
    private final List<String> moduleNames = modules.all().stream().map(m -> m.name()).toList();
    // Wie LlmProbe: Material.isBlock() braucht ab 1.21 die Server-Registry, ohne Server reicht der Enum-Name.
    private final PlanCompiler compiler = new PlanCompiler(
            id -> org.bukkit.Material.getMaterial(id.toUpperCase(Locale.ROOT)) != null, "stone",
            new PlanCompiler.Limits(MAX_BLOCKS, MAX_EXTENT), modules);
    private final SiteSurvey site = SiteSurvey.scan(SCAN_MIN, SCAN_MAX,
            p -> p.y() >= 0 ? "air" : p.y() == -1 ? "grass_block" : "dirt");
    private final String systemPrompt = resource("/system-prompt.txt");
    private final String schema = resource("/plan-schema.json");
    private final com.google.gson.JsonObject schemaJson = com.google.gson.JsonParser.parseString(schema)
            .getAsJsonObject();

    public Machines modules() {
        return modules;
    }

    /** Derselbe Compiler wie im Urteil – für den Rückbau-Vergleich der Vorbilder. */
    public PlanCompiler compiler() {
        return compiler;
    }

    public List<String> moduleNames() {
        return moduleNames;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public String schema() {
        return schema;
    }

    /** Nutzernachricht genau wie BuildService ohne Gedächtnis und ohne Recherche. */
    public String userMessage(String order) {
        return PlanPrompt.userMessage(order, site, AHEAD, modules, null, null, 0, null);
    }

    /**
     * Nachrichten der Korrekturrunde ohne System-Prompt (der steht im Lehrset als eigene Datei): über
     * PlanPrompt.repairConversation, dieselbe Stelle, die BuildService und ClientPlanner rufen – damit die gekürzte
     * Nutzernachricht und die Rechte-Zeile wortgleich bleiben, wenn sich der Prompt dort ändert.
     */
    public List<com.google.gson.JsonObject> repairConversation(String order, String broken, String request) {
        List<com.google.gson.JsonObject> first = List.of(LlmProtocol.message("system", systemPrompt),
                LlmProtocol.message("user", userMessage(order)));
        List<com.google.gson.JsonObject> round = PlanPrompt.repairConversation(first, order, moduleNames, broken,
                request);
        return new java.util.ArrayList<>(round.subList(1, round.size()));
    }

    /** Urteil wie im Plugin; repairAttempts = llm.reparatur-versuche (Vorgabe 1). */
    public Verdict judge(String order, String json, int attempt, int repairAttempts) {
        PlanReview.Context ctx = new PlanReview.Context(order, RequestHints.parse(order, moduleNames), site, false,
                MAX_OVERBUILD, repairAttempts, LintMode.STRICT);
        return new PlanReview(compiler, modules, ctx).judge(json, attempt);
    }

    /**
     * Leer, wenn der Plan ins Lehrset darf: im ersten Versuch angenommen, ohne dass PlanReview etwas nachbessern
     * musste (keine Syntaxreparatur, kein Hohlmachen, keine weggelassenen Blöcke, keine Stützen), ohne
     * Compiler-Warnung (Kappung, Maßanpassung) und ohne einen einzigen Linter-Hinweis, auch keinen leichten.
     *
     * Strenger als das Plugin mit Absicht: Ein Plan, den der Baumeister erst zurechtbiegen muss, würde gebaut, aber
     * das Modell lernte daraus genau die Fehler, die er biegt. Wer hier auf „Accept genügt“ lockert, bringt dem Modell
     * schwebende Fackeln und Vollklötze bei.
     */
    public Optional<String> rejection(String order, String json) {
        // Zuerst: Kann die Grammatik des Hosts diesen Plan überhaupt erzeugen? Sonst lernte das Modell ein Format, das
        // es beim Planen nicht schreiben darf (SchemaCheck, 2026-11-01: 214 Hochhaus-Pläne mit falscher Feldfolge).
        Optional<String> grammar = SchemaCheck.problem(json, schemaJson);
        if (grammar.isPresent()) {
            return Optional.of("grammatik: " + grammar.get());
        }
        Verdict v = judge(order, json, 0, 0);
        if (!(v instanceof Accept a)) {
            return Optional.of(v instanceof PlanReview.GiveUp g ? "abgesagt: " + g.reason() : "nicht angenommen");
        }
        if (!a.repairs().isEmpty()) {
            return Optional.of("nachgebessert: " + a.repairs().getFirst());
        }
        if (!a.build().warnings().isEmpty()) {
            return Optional.of("warnung: " + a.build().warnings().getFirst());
        }
        if (!a.remaining().isEmpty()) {
            return Optional.of("hinweis: " + a.remaining().getFirst().rule().german());
        }
        return Optional.empty();
    }

    /**
     * Ausdehnung der übersetzten Schritte (min, max) – für die Aufstellung mehrerer Teile nebeneinander. Gemessen statt
     * gerechnet: Dachüberstand, Baumkronen und Burgtürme stehen über den Grundriss hinaus, und eine eigene Rechnung
     * dafür liefe neben dem Compiler auseinander.
     */
    public Pos[] extent(List<com.google.gson.JsonObject> steps) {
        try {
            com.blackn0va.baumeister.plan.CompiledBuild b = compiler.compile(
                    com.blackn0va.baumeister.plan.PlanParser.parse(PlanText.render("x", "x", steps)));
            return new Pos[] {b.min(), b.max()};
        } catch (com.blackn0va.baumeister.plan.PlanException e) {
            throw new IllegalArgumentException("Teil lässt sich nicht übersetzen: " + e.getMessage(), e);
        }
    }

    private static String resource(String name) {
        try (InputStream in = PlanJudge.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("Ressource fehlt: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
