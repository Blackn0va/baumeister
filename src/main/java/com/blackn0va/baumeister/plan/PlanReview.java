package com.blackn0va.baumeister.plan;

import com.blackn0va.baumeister.plan.PlanLinter.Issue;
import com.blackn0va.baumeister.plan.PlanLinter.Rule;
import com.blackn0va.baumeister.plan.PlanLinter.Severity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Prüfkette für eine LLM-Antwort, ohne Bukkit: lesen, übersetzen, gegen den Auftrag halten, an die Anbauwand rücken,
 * Bauqualität prüfen (PlanLinter), Überbau-Schutz. Das Ergebnis sagt, ob gebaut, nachgebessert oder abgesagt wird.
 * BuildService führt nur noch aus (Chat, NPC, nächster LLM-Aufruf).
 *
 * Warum eine eigene Klasse: Der PlanLinter war geprüft, aber nicht eingebunden – die Reparaturrunde lag mitten in
 * BuildService.handleReply und war ohne laufenden Server nicht testbar. Hier lässt sich mit einer Attrappe für das
 * LLM zeigen, dass ein sauberer Plan keinen zweiten Aufruf kostet und ein kaputter genau eine Nachbesserung bekommt
 * (PlanReviewTest).
 *
 * Reihenfolge wie bisher: Alles bis einschließlich Linter betrifft den Entwurf (siteCheck = false, beim Hochhaus
 * greift danach der Rückfall), der Überbau-Schutz den Bauplatz. Der Linter läuft nach der Anbau-Ausrichtung, weil
 * erst dann die Altbauwand neben der neuen Tür steht.
 */
public final class PlanReview {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    /**
     * So viele schwere Befunde gehen höchstens an das LLM. Jeder Satz kostet Prompt-Token (CPU-Zeit), und ein
     * 7B-Modell bessert drei klare Punkte eher aus als zwölf; der Rest kommt nach der Reparatur ggf. wieder.
     */
    static final int MAX_FEEDBACK = 3;

    /**
     * Was nach einer Reparaturrunde mit verbliebenen schweren Linter-Befunden passiert (llm.plan-prüfer).
     */
    public enum LintMode {
        /** Nachbessern, danach Rückfall (Hochhaus) oder Absage – wie beim Überbau-Schutz. Vorgabe. */
        STRICT,
        /** Nachbessern, danach trotzdem bauen und die Befunde nur loggen. */
        REPAIR,
        /** Linter aus, wie vor seiner Einbindung. */
        OFF;

        /**
         * Aus der Config; Unbekanntes wird zur Vorgabe STRICT (mit Warnung), nicht zu OFF – ein Tippfehler soll die
         * Prüfung nicht still abschalten.
         */
        public static LintMode parse(String raw) {
            String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            return switch (v) {
                case "streng", "" -> STRICT;
                case "reparieren" -> REPAIR;
                case "aus", "false", "off" -> OFF;
                default -> {
                    LOG.warning(() -> "config | plan_prüfer_unbekannt | wert=" + v + " – nehme streng");
                    yield STRICT;
                }
            };
        }
    }

    /**
     * @param survey         Bauplatz-Scan oder null (Direktbau ohne Scan)
     * @param hasMemory      der Spieler hat einen letzten Bau im Gedächtnis (mode update ersetzt ihn)
     * @param maxOverbuild   bau.max-überbauen
     * @param repairAttempts llm.reparatur-versuche: so viele Nachbesserungen insgesamt, für alle Fehlerarten
     */
    public record Context(String prompt, RequestHints.Hints hints, SiteSurvey survey, boolean hasMemory,
            int maxOverbuild, int repairAttempts, LintMode lintMode, int targetHeight, int plotWidth, int plotDepth) {

        /** Ohne Recherche (targetHeight 0 = keine Vorgabe). */
        public Context(String prompt, RequestHints.Hints hints, SiteSurvey survey, boolean hasMemory,
                int maxOverbuild, int repairAttempts, LintMode lintMode) {
            this(prompt, hints, survey, hasMemory, maxOverbuild, repairAttempts, lintMode, 0);
        }

        /** Ohne Plot-Modus (plotWidth/plotDepth 0 = keine Grenze). */
        public Context(String prompt, RequestHints.Hints hints, SiteSurvey survey, boolean hasMemory,
                int maxOverbuild, int repairAttempts, LintMode lintMode, int targetHeight) {
            this(prompt, hints, survey, hasMemory, maxOverbuild, repairAttempts, lintMode, targetHeight, 0, 0);
        }
    }

    /**
     * Höhe gegen die Recherche (ReferenceLookup): mehr als 20 % daneben (und mehr als 3 Blöcke) geht zur
     * Nachbesserung. Ohne diese Prüfung stand die Vorgabe nur im Prompt, und ein kleines Modell baut „den Kölner
     * Dom“ gern 30 Blöcke hoch. Rein, testbar.
     */
    /** Im letzten Versuch wird gebaut, wenn der Plan mindestens so viel der Vorbildhöhe erreicht. */
    static final double HEIGHT_KEEP = 0.3;

    static Optional<String> heightProblem(CompiledBuild build, int target) {
        if (target <= 0 || build.actions().isEmpty()) {
            return Optional.empty();
        }
        int h = build.max().y() - build.min().y() + 1;
        int off = Math.abs(h - target);
        if (off <= 3 || off <= target * 0.2) {
            return Optional.empty();
        }
        return Optional.of("The real building is about " + target + " blocks tall (from Wikipedia/Wikidata, scaled "
                + "to the build limit), but the plan is " + h + " blocks tall. Build it about " + target
                + " blocks tall with the real proportions.");
    }

    public sealed interface Verdict permits Accept, Repair, GiveUp {
    }

    /**
     * Bauen.
     *
     * @param json      der gebaute Plan (nach der Anbau-Ausrichtung verschoben) – fürs Gedächtnis
     * @param remaining Linter-Befunde, die trotzdem gebaut werden (leichte; bei REPAIR auch schwere)
     * @param fit       Anbau-Ausrichtung oder null
     */
    public record Accept(BuildPlan plan, CompiledBuild build, String json, List<Issue> remaining,
            AttachPlanner.Fit fit, List<String> repairs) implements Verdict {
    }

    /**
     * Nachbessern lassen.
     *
     * @param assistant  der Plan, auf den sich die Rückmeldung bezieht – nach einer Anbau-Verschiebung der
     *                   verschobene, damit die Koordinaten in der Rückmeldung zu dem passen, was das LLM liest
     * @param llmRequest Nutzernachricht der Reparaturrunde (englisch wie der System-Prompt)
     * @param playerText deutscher Satz für den Chat
     */
    public record Repair(String reason, String assistant, String llmRequest, String playerText) implements Verdict {
    }

    /**
     * Aufgeben.
     *
     * @param siteCheck  gescheitert am Bauplatz (Überbau, Anschluss) statt am Entwurf – dort hilft kein Ersatzbau
     * @param playerText Grund für den Chat
     */
    public record GiveUp(String reason, boolean siteCheck, String playerText) implements Verdict {
    }

    private final PlanCompiler compiler;
    private final ModuleLibrary modules;
    private final Context ctx;
    private final Optional<AttachPlanner.Wall> wall;

    public PlanReview(PlanCompiler compiler, ModuleLibrary modules, Context ctx) {
        this.compiler = compiler;
        this.modules = modules;
        this.ctx = ctx;
        this.wall = AttachPlanner.choose(ctx.survey(), ctx.prompt());
    }

    public Optional<AttachPlanner.Wall> wall() {
        return wall;
    }

    /** Der Auftrag dieser Prüfung – für die Korrekturrunde (PlanPrompt.repairConversation). */
    public String prompt() {
        return ctx.prompt();
    }

    /** @param attempt 0 = erste Antwort, 1 = nach der ersten Nachbesserung … */
    public Verdict judge(String content, int attempt) {
        boolean canRepair = attempt < ctx.repairAttempts();
        boolean siteCheck = false;
        String assistant = content;
        try {
            PlanParser.Normalized norm = PlanParser.normalize(content);
            String json = norm.json();
            List<String> repairs = new ArrayList<>(norm.fixes());
            if (!repairs.isEmpty()) {
                // Reparierte Fassung auch für eine Reparaturrunde: kürzer (der halbe letzte Schritt fehlt) und gültig –
                // vorher ging die abgeschnittene Antwort vollständig zurück und sprengte den Kontext.
                assistant = json;
                LOG.info(() -> String.format("plan | syntax_repariert | abgeschnitten=%s änderungen=%s versuch=%d",
                        norm.truncated(), String.join("; ", repairs), attempt));
            }
            // Grundriss in den gültigen Bereich, bevor der Parser ihn ablehnt (PlanAutoFix.footprints).
            PlanAutoFix.Fixed sides = PlanAutoFix.footprints(json);
            if (!sides.notes().isEmpty()) {
                json = sides.json();
                assistant = json;
                repairs.addAll(sides.notes());
                LOG.info(() -> "plan | grundriss_angepasst | " + String.join("; ", sides.notes()) + " versuch="
                        + attempt);
            }
            BuildPlan plan = PlanParser.parse(json);
            CompiledBuild build = compiler.compile(plan);
            // Module ohne erkannten Maschinenwunsch: nie mehr Reparaturrunde oder Absage (PlanAutoFix.MACHINE_SHARE).
            List<Integer> unwanted = RequestHints.unwantedModules(plan, ctx.hints());
            if (!unwanted.isEmpty()) {
                double share = PlanAutoFix.moduleShare(plan, build);
                if (share >= PlanAutoFix.MACHINE_SHARE) {
                    LOG.info(() -> String.format(Locale.ROOT, "plan | maschine_angenommen | module=%d anteil=%.2f "
                            + "versuch=%d", unwanted.size(), share, attempt));
                } else {
                    Optional<PlanAutoFix.Fixed> stripped = PlanAutoFix.withoutSteps(json, unwanted,
                            unwanted.size() + " Bauteil(e) als Verzierung entfernt (keine Maschine gewünscht)");
                    if (stripped.isPresent()) {
                        json = stripped.get().json();
                        assistant = json;
                        plan = PlanParser.parse(json);
                        build = compiler.compile(plan);
                        repairs.addAll(stripped.get().notes());
                        LOG.info(() -> String.format(Locale.ROOT, "plan | module_entfernt | anzahl=%d anteil=%.2f "
                                + "versuch=%d", unwanted.size(), share, attempt));
                    }
                }
            }
            // Nach compile, vor dem Überbau-Schutz: Widerspruch zum Auftrag geht wie jeder Planfehler in die
            // Reparaturrunde (dev01 2026-10-28: Hochhaus ohne skyscraper-Schritt).
            List<String> problems = new ArrayList<>(RequestHints.check(plan, ctx.hints()));
            // Unbekannte Blöcke gehen zuerst zurück ans LLM (2026-11-08, Host-Log des Wunsch) – Elytren sind ein
            // Gegenstand, der Compiler machte daraus den Ersatzblock, und gebaut wurde
            // eine Steinsäule samt Steinlinie, die niemand bestellt hatte. Ist keine Runde mehr frei, bleibt es beim
            // Ersatzblock wie bisher – ein einzelner falscher Block ist kein Absagegrund.
            List<String> unknown = unknownBlocks(build.warnings());
            if (!unknown.isEmpty() && canRepair) {
                problems.add("Unknown block ids " + unknown + ": these are items or do not exist and would be built "
                        + "as a replacement block. Use placeable Minecraft block ids only.");
            }
            Optional<String> height = heightProblem(build, ctx.targetHeight());
            if (height.isPresent()) {
                // Im letzten Versuch ist eine abweichende Höhe kein Absagegrund mehr, solange es mindestens
                // HEIGHT_KEEP der Vorgabe sind: dev01 2026-10-31, World Trade Center viermal mit 130 statt 184
                // Blöcken verworfen – ein 130 hohes Hochhaus ist näher am Wunsch als keins.
                int h = build.max().y() - build.min().y() + 1;
                if (canRepair || h < ctx.targetHeight() * HEIGHT_KEEP) {
                    problems.add(height.get());
                } else {
                    repairs.add("Höhe " + h + " statt etwa " + ctx.targetHeight() + " Blöcke (Vorbild) – so gebaut");
                    LOG.info(() -> "plan | höhe_abweichend_gebaut | höhe=" + h + " vorgabe=" + ctx.targetHeight());
                }
            }
            // Plot-Modus: Was nicht auf den Plot passt, geht wie ein Planfehler in die Reparaturrunde (PlotLayout).
            if (ctx.plotWidth() > 0) {
                PlotLayout.problem(build, ctx.plotWidth(), ctx.plotDepth()).ifPresent(problems::add);
            }
            if (!problems.isEmpty()) {
                throw new PlanException(String.join(" ", problems));
            }
            // update ersetzt den eigenen letzten Bau – dessen Blöcke zu ändern ist dann der Auftrag, kein Überbau,
            // und ein Anbau ist es auch nicht.
            boolean update = plan.isUpdate() && ctx.hasMemory();
            AttachPlanner.Fit fit = null;
            if (!update && wall.isPresent()) {
                fit = AttachPlanner.fit(ctx.survey(), wall.get(), json, plan, build, compiler);
                json = fit.json();
                plan = fit.plan();
                build = fit.build();
                assistant = fit.moved() ? json : content;
                AttachPlanner.Fit f = fit;
                AttachPlanner.Wall w = wall.get();
                LOG.info(() -> String.format("bauplatz | anbau | bauwerk=%d seite=%s ebene=%d frei=%d "
                        + "verschoben=%d/%d/%d geklemmt=%d", w.structure(), w.side().german(), w.plane(), w.freeDepth(), f.dx(), f.dy(),
                        f.dz(), f.clamped()));
            }
            List<Issue> issues = lint(plan, build);
            // Vollklötze macht der Baumeister selbst hohl (PlanAutoFix.hollowSolids) statt einer Reparaturrunde.
            Optional<PlanAutoFix.Fixed> hollow = PlanAutoFix.hollowSolids(json, issues);
            if (hollow.isPresent()) {
                json = hollow.get().json();
                assistant = json;
                plan = PlanParser.parse(json);
                build = compiler.compile(plan);
                repairs.addAll(hollow.get().notes());
                issues = lint(plan, build);
                LOG.info(() -> "plan | hohl_gemacht | " + String.join("; ", hollow.get().notes()) + " versuch="
                        + attempt);
            }
            // Kleinfehler (Fenster außerhalb jeder Wand, einzelne schwebende Blöcke) lässt der Baumeister selbst weg.
            // Vorher ging jeder davon in die Reparaturrunde, und kleine Modelle bessern ihn dort nicht aus: dev01
            // 2026-10-30, Llama 3.1 8B – ein schwebender Block bei y 31 (zweimal 40 s, verworfen), eine Glasscheibe
            // vor der Wand (zweimal 40 s, verworfen). Ein fehlender Einzelblock ist kleiner als 80 s und eine Absage.
            // Größere schwebende Teile (ein Dach, ein Stockwerk) gehen weiter ans LLM – die fehlten sichtbar.
            Set<Pos> minor = minorDefects(issues, build);
            if (!minor.isEmpty()) {
                build = withoutCells(build, minor, minor.size() + " Block/Blöcke weggelassen (Fenster ohne Wand, "
                        + "einzelne schwebende Blöcke) – das LLM hatte sie danebengesetzt.");
                issues = lint(plan, build);
                int n = minor.size();
                LOG.info(() -> "plan | kleinfehler_weggelassen | anzahl=" + n + " versuch=" + attempt);
            }
            // Angehängtes ohne Halt (Fackel, Laterne, Teppich …) lässt der Baumeister selbst weg. Vorher kostete jede
            // schwebende Fackel eine ganze Reparaturrunde: dev01 2026-10-30, 61 s für 642 Token, danach immer noch
            // 12 Fackeln ohne Halt → Plan verworfen. Ein fehlendes Licht ist kleiner als eine Minute Warten oder eine
            // Absage; alle anderen Befunde gehen weiter an das LLM.
            List<Issue> unsupported = issues.stream().filter(i -> i.rule() == Rule.SUPPORT).toList();
            if (!unsupported.isEmpty()) {
                build = withoutCells(build, unsupported);
                issues = lint(plan, build);
                int n = unsupported.size();
                LOG.info(() -> "plan | ohne_halt_weggelassen | anzahl=" + n + " versuch=" + attempt);
            }
            // Größere schwebende Teile bekommen Stützen (PlanAutoFix.supports); was danach noch schwebt, saß falsch.
            if (issues.stream().anyMatch(i -> i.rule() == Rule.FLOATING)) {
                Optional<CompiledBuild> held = PlanAutoFix.supports(build, issues, existing());
                if (held.isPresent()) {
                    build = held.get();
                    issues = lint(plan, build);
                    String note = build.warnings().getLast();
                    repairs.add(note);
                    LOG.info(() -> "plan | stützen_gesetzt | " + note + " versuch=" + attempt);
                }
            }
            List<Issue> severe = issues.stream().filter(PlanReview::severe).toList();
            List<Issue> light = issues.stream().filter(i -> !severe(i)).toList();
            if (!light.isEmpty()) {
                LOG.info(() -> "plan | hinweise | anzahl=" + light.size() + " erster=" + light.getFirst().llmText());
            }
            if (!severe.isEmpty()) {
                String summary = germanSummary(severe);
                LOG.info(() -> String.format("plan | baufehler | anzahl=%d arten=%s versuch=%d", severe.size(), summary,
                        attempt));
                if (canRepair) {
                    String reason = feedback(severe);
                    return new Repair(reason, assistant, "The plan has building errors: " + reason + " Fix them, keep "
                            + "everything else, and return the complete corrected plan as JSON.",
                            "Mein Plan hat Baufehler (" + summary + "), ich bessere nach …");
                }
                if (ctx.lintMode() == LintMode.STRICT) {
                    return new GiveUp(feedback(severe), false, "der Plan hat weiter Baufehler (" + summary + ").");
                }
                LOG.info(() -> "plan | baufehler_gebaut | modus=reparieren anzahl=" + severe.size());
            }
            siteCheck = true;
            if (!update) {
                checkOverbuild(ctx.survey(), ctx.prompt(), build, ctx.maxOverbuild());
            }
            return new Accept(plan, build, json, issues, fit, List.copyOf(repairs));
        } catch (PlanException e) {
            if (canRepair) {
                return new Repair(e.getMessage(), assistant, repairRequest(e.getMessage()),
                        "Mein Plan hat einen Fehler, ich bessere nach …");
            }
            return new GiveUp(e.getMessage(), siteCheck, e.getMessage());
        }
    }

    private static final java.util.regex.Pattern UNKNOWN = java.util.regex.Pattern.compile(
            "^Unbekannter Block (\\S+) → ");

    /** IDs aus den Warnungen „Unbekannter Block X → ersatz“ (BlockRules.resolve), ohne Doppelte, höchstens 8. */
    static List<String> unknownBlocks(List<String> warnings) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String w : warnings) {
            java.util.regex.Matcher m = UNKNOWN.matcher(w);
            if (m.find() && out.size() < 8) {
                out.add(m.group(1));
            }
        }
        return List.copyOf(out);
    }

    /** Wortgleich mit der Reparaturrunde vor dieser Klasse – das LLM kennt die Form aus allen bisherigen Plänen. */
    static String repairRequest(String reason) {
        return "The plan is invalid: " + reason + " Return the complete corrected plan as JSON.";
    }

    /**
     * Schwer = in die Reparaturrunde: alles, was der Linter als ERROR meldet, dazu schwebende Teile – ein schwebender
     * Klotz ist das Sichtbarste, was ein Plan falsch machen kann. Im Linter bleibt Schweben HINT (ohne Scan kennt er
     * das Gelände nicht). Mit Scan zählt dort Gelände und Bestand als Halt (PlanReview.lint gibt survey::solidAt
     * mit); vorher galt eine Plattform auf einem Hügel als schwebend und wurde abgesagt (Prüferbefund 2026-10-29).
     * Wer FLOATING hier schwer lässt, aber den Scan im Linter wieder weglässt, sagt gültige Pläne am Hang ab.
     * Auslaufendes Wasser bleibt leicht (Log): Ein Brunnen mit Überlauf kann gewollt sein.
     */
    static boolean severe(Issue i) {
        return i.severity() == Severity.ERROR || i.rule() == Rule.FLOATING;
    }

    /**
     * Höchstens MAX_FEEDBACK Befunde, zuerst je einer pro Regel, dann je einer pro Schritt und Regel, plus wie viele es
     * insgesamt sind. Vorher nur „je Schritt und Regel“ in Listenreihenfolge: dev01 2026-10-30 belegten drei Fackeln
     * (Schritte 6–9) alle drei Plätze, der Steinklotz über den Modulen kam beim LLM nie an, und die Reparaturrunde
     * besserte nur Fackeln nach.
     */
    static String feedback(List<Issue> severe) {
        List<String> parts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<Rule> rules = new LinkedHashSet<>();
        for (Issue i : severe) {
            if (parts.size() < MAX_FEEDBACK && rules.add(i.rule()) && seen.add(i.step() + "/" + i.rule())) {
                parts.add(i.llmText());
            }
        }
        for (Issue i : severe) {
            if (parts.size() < MAX_FEEDBACK && seen.add(i.step() + "/" + i.rule())) {
                parts.add(i.llmText());
            }
        }
        int more = severe.size() - parts.size();
        return String.join(" ", parts) + (more > 0 ? " (" + more + " more problem(s) of the same kind.)" : "");
    }

    /** Der Bau ohne die Zellen der Befunde; leere Aktionen fallen weg, ein Hinweis geht in den Chat. */
    /** Schwebende Gruppen bis zu dieser Größe gelten als Kleinfehler, zusammen höchstens MINOR_FLOATING_TOTAL. */
    static final int MINOR_FLOATING_PART = 3;
    static final int MINOR_FLOATING_TOTAL = 8;

    /**
     * Zellen, die PlanReview ohne LLM weglässt: jede Glasscheibe/jedes Fenster, das nicht in einer Wand sitzt, und
     * schwebende Gruppen mit höchstens {@link #MINOR_FLOATING_PART} Blöcken – zusammen höchstens
     * {@link #MINOR_FLOATING_TOTAL}, sonst keine (dann schwebt mehr als ein Ausrutscher, und das LLM soll es sehen).
     * Die Gruppe wird hier über alle 26 Nachbarn aus dem fertigen Bau gesucht, wie im Linter.
     */
    static Set<Pos> minorDefects(List<Issue> issues, CompiledBuild build) {
        Set<Pos> out = new java.util.HashSet<>();
        Map<Pos, BlockSpec> cells = new java.util.HashMap<>();
        for (CompiledBuild.Action a : build.actions()) {
            for (CompiledBuild.Change c : a.changes()) {
                cells.put(c.pos(), c.block());
            }
        }
        Set<Pos> floating = new java.util.HashSet<>();
        boolean tooMuch = false;
        for (Issue i : issues) {
            if (i.rule() == Rule.WINDOW) {
                out.add(i.pos());
            } else if (i.rule() == Rule.FLOATING && !tooMuch) {
                Set<Pos> part = component(cells, i.pos(), MINOR_FLOATING_PART + 1);
                if (part.size() > MINOR_FLOATING_PART) {
                    tooMuch = true;
                } else {
                    floating.addAll(part);
                }
            }
        }
        if (!tooMuch && floating.size() <= MINOR_FLOATING_TOTAL) {
            out.addAll(floating);
        }
        return out;
    }

    /** Zusammenhängende feste Zellen um start (26 Nachbarn), abgebrochen nach limit Zellen. */
    private static Set<Pos> component(Map<Pos, BlockSpec> cells, Pos start, int limit) {
        Set<Pos> seen = new java.util.LinkedHashSet<>();
        java.util.ArrayDeque<Pos> queue = new java.util.ArrayDeque<>();
        if (solid(cells.get(start))) {
            seen.add(start);
            queue.add(start);
        }
        while (!queue.isEmpty() && seen.size() <= limit) {
            Pos p = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        Pos n = p.add(dx, dy, dz);
                        if (solid(cells.get(n)) && seen.add(n)) {
                            queue.add(n);
                        }
                    }
                }
            }
        }
        return seen;
    }

    private static boolean solid(BlockSpec b) {
        return b != null && !b.isAir() && !b.id().equals("water") && !b.id().equals("lava");
    }

    static CompiledBuild withoutCells(CompiledBuild build, List<Issue> drop) {
        Set<Pos> gone = new java.util.HashSet<>();
        for (Issue i : drop) {
            gone.add(i.pos());
        }
        return withoutCells(build, gone, gone.size() + " Block/Blöcke ohne Halt (Fackel, Laterne …) weggelassen – "
                + "sie wären abgefallen.");
    }

    static CompiledBuild withoutCells(CompiledBuild build, Set<Pos> gone, String note) {
        List<CompiledBuild.Action> kept = new ArrayList<>(build.actions().size());
        for (CompiledBuild.Action a : build.actions()) {
            List<CompiledBuild.Change> changes = a.changes().stream().filter(c -> !gone.contains(c.pos())).toList();
            if (!changes.isEmpty()) {
                kept.add(changes.size() == a.changes().size() ? a : new CompiledBuild.Action(a.step(), changes));
            }
        }
        List<String> warnings = new ArrayList<>(build.warnings());
        warnings.add(note);
        return new CompiledBuild(List.copyOf(kept), List.copyOf(warnings), build.min(), build.max(),
                build.stepNames());
    }

    static String germanSummary(List<Issue> severe) {
        Set<String> kinds = new LinkedHashSet<>();
        for (Issue i : severe) {
            kinds.add(i.rule().german());
        }
        return String.join(", ", kinds);
    }

    /**
     * Fail-open: Der Linter dient der Bauqualität und ist kein Sicherheitspfad (siehe PlanLinter). Ein Fehler in ihm
     * darf einen sonst gültigen Plan nicht verhindern – dann wird ohne Prüfung gebaut und gewarnt. Was gesetzt werden
     * darf, entscheiden weiter Compiler, Überbau-Schutz und Rechteprüfung, die hier nicht berührt werden.
     */
    private List<Issue> lint(BuildPlan plan, CompiledBuild build) {
        if (ctx.lintMode() == LintMode.OFF) {
            return List.of();
        }
        SiteSurvey survey = ctx.survey();
        boolean free = survey == null || !survey.hasStructures();
        try {
            return PlanLinter.lint(plan, build, modules, free, existing());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "plan | prüfer_fehler | bewusst fail-open, gebaut wird ungeprüft", e);
            return List.of();
        }
    }

    /** Gelände und Bestand aus dem Scan, die einen Block tragen (ohne Scan: nichts). */
    private Predicate<Pos> existing() {
        SiteSurvey survey = ctx.survey();
        return survey == null ? p -> false : survey::solidAt;
    }

    // ---------------------------------------------------------------- Überbau-Schutz

    /**
     * Wortanfänge (normalisiert, siehe RequestHints.normalize), mit denen der Auftraggeber ausdrücklich erlaubt,
     * Bestehendes zu verändern. Vorher ein Regex ohne Wortgrenzen über den Rohtext: „reiß“ traf „dreißig“, „änder“
     * traf „Geländer“, „Länder“, „Rändern“ – und schon „bau ein Hochhaus mit dreißig Stockwerken“ schaltete den
     * Überbau-Schutz ab, der Plan ging ungefragt quer durch das Bestehende. Jetzt zählt nur ein Wort, das mit dem
     * Stamm BEGINNT („einreißen“/„niederreißen“ stehen deshalb eigens in der Liste – vorher trafen sie nur zufällig
     * über den Teilwort-Treffer). Wer das auf Teilwort-Suche zurückdreht, öffnet den Schutz wieder für jedes Geländer
     * (Gegenprobe PlanReviewTest.overbuildGuardIgnoresRebuildStemsInsideOtherWords).
     */
    private static final List<String> REBUILD_STEMS = List.of(
            "umbau", "abreiss", "abriss", "ersetz", "ueberbau", "renovier", // umlaut-ok
            "veraender", "aender", "abaender", "reiss", "einreiss", "niederreiss", "umgestalt"); // umlaut-ok

    /** Ausdrücklicher Umbauwunsch: ein Wort mit einem Stamm aus REBUILD_STEMS oder „neu gestalt…“. */
    private static boolean isRebuildWish(String prompt) {
        List<String> words = RequestHints.tokens(RequestHints.normalize(prompt));
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            for (String stem : REBUILD_STEMS) {
                if (w.startsWith(stem)) {
                    return true;
                }
            }
            if (w.equals("neu") && i + 1 < words.size() && words.get(i + 1).startsWith("gestalt")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Anbau statt Durchbau: Verändert der Plan mehr als max-überbauen Blöcke eines bestehenden
     * Bauwerks, geht er mit der Stelle zurück an das LLM (Reparaturrunde) – außer der Auftrag
     * sagt ausdrücklich „umbauen/abreißen/ersetzen“. Ein Durchgang (2) oder Fenster (1) bleibt erlaubt.
     *
     * Unverändert aus BuildService.checkOverbuild hierher gezogen (fail-closed: jede Ausnahme ist ein Nein), damit
     * der Schutz mit derselben Prüfkette testbar ist. BuildService ruft für Schnellaufträge und Rückfall dieselbe
     * Methode.
     */
    public static void checkOverbuild(SiteSurvey survey, String prompt, CompiledBuild build, int maxOverbuild)
            throws PlanException {
        if (survey == null || !survey.hasStructures()) {
            return;
        }
        // Anschluss-Prüfung: „bau an das Haus an“ mit einem frei stehenden Plan ist kein Anbau.
        if (AttachPlanner.WORDS.matcher(prompt).find() && !survey.touchesStructure(build)) {
            SiteSurvey.Structure s = survey.structures().getFirst();
            LOG.info(() -> "bauplatz | anschluss_fehlt | bauwerk=" + s.number());
            throw new PlanException("The player asked to attach to the existing building, but your plan does not "
                    + "touch structure " + s.number() + " (x " + s.min().x() + ".." + s.max().x() + ", z "
                    + s.min().z() + ".." + s.max().z() + "). " + attachHint(survey, prompt, s));
        }
        if (isRebuildWish(prompt)) {
            return;
        }
        SiteSurvey.Overbuild ob = survey.overbuild(build);
        LOG.info(() -> String.format("bauplatz | überbau | blöcke=%d erlaubt=%d", ob.count(), maxOverbuild));
        if (ob.count() > maxOverbuild) {
            Pos e = ob.example();
            throw new PlanException("The plan changes " + ob.count() + " blocks of existing structure "
                    + ob.structure() + " (e.g. at [" + e.x() + "," + e.y() + "," + e.z() + "] " + ob.before()
                    + " would become " + ob.after() + "). Keep existing structures intact: build next to them, "
                    + "share a wall, match floor height and materials, and connect with at most a door-sized "
                    + "opening. Sag 'umbauen', wenn das Bestehende verändert werden soll.");
        }
    }

    /**
     * Nur der Text der Anschlussprüfung, die Prüfung selbst bleibt oben unverändert. Vorher verwies er auf „the
     * listed attach points … x=&lt;max.x&gt; for the right side“ – die Liste steht seit der festen Anbauwand nicht mehr
     * im Prompt, und bei einer Vorgabe links/hinten/vorne widersprach „right side“ ihr (Prüferbefund 2026-10-29,
     * Gegenprobe PlanReviewTest.missingAttachFeedbackPointsToTheFixedWall). Mit Vorgabe (dieselbe, die das LLM
     * gelesen hat – choose ist deterministisch) zeigt er auf sie, sonst nennt er alle vier Wandebenen.
     */
    static String attachHint(SiteSurvey survey, String prompt, SiteSurvey.Structure s) {
        return AttachPlanner.choose(survey, prompt).map(w -> {
            String axis = w.side().xPlane ? "x" : "z";
            String along = w.side().xPlane ? "z" : "x";
            return "Follow the ATTACH wall: share " + axis + "=" + w.plane() + " (" + along + " " + w.spanMin() + ".."
                    + w.spanMax() + ") as the " + w.side().ownWall + " wall of the new part, floor at y=" + w.floorY()
                    + ", and build on the " + w.side().english + " side of it.";
        }).orElse("Share one of its wall planes (x=" + s.min().x() + " or x=" + s.max().x() + ", z=" + s.min().z()
                + " or z=" + s.max().z() + ") and match its floor height y=" + s.min().y() + ".");
    }
}
