package com.blackn0va.baumeister;

import com.blackn0va.baumeister.plan.BuildPlan;
import com.blackn0va.baumeister.plan.CompiledBuild;
import com.blackn0va.baumeister.plan.Machines;
import com.blackn0va.baumeister.plan.PlanCompiler;
import com.blackn0va.baumeister.plan.PlanParser;
import com.blackn0va.baumeister.plan.Pos;
import com.blackn0va.baumeister.plan.RequestHints;
import com.blackn0va.baumeister.plan.SiteSurvey;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Messprobe, kein Unit-Test: schickt Aufträge an einen laufenden llama-server und zeigt, was
 * daraus würde (Dauer, Tokens, Warnungen, Grundriss je Schicht). Gleicher Prompt, gleiches
 * Schema, gleicher Compiler wie im Plugin.
 *
 * Aufruf (nach mvn test-compile):
 *   java -cp "target/classes;target/test-classes;lib/*" com.blackn0va.baumeister.LlmProbe [url] "auftrag" …
 */
public final class LlmProbe {

    public static void main(String[] args) throws Exception {
        String url = "http://127.0.0.1:8080/v1/chat/completions";
        int first = 0;
        if (args.length > 0 && args[0].startsWith("http")) {
            url = args[0];
            first = 1;
        }
        // Limits wie die Config-Vorgaben (150 000 / 192): Mit den alten 6000 / 48 lehnte die Probe jedes Hochhaus ab,
        // das im Plugin durchgegangen wäre – gemessen wurde dann der Compiler, nicht das Modell.
        Settings s = new Settings("Baumeister", URI.create(url), Duration.ofMinutes(10), 3000, 0.4, 1, 3, 150_000,
                192, 3, "stone", false, 5, false, 12, 300, 700, 120,
                com.blackn0va.baumeister.plan.PlanReview.LintMode.STRICT);
        JsonObject schema = JsonParser.parseString(resource("/plan-schema.json")).getAsJsonObject();
        // -Dmodell=qwen2.5:7b für Ollama (llm.modell im Plugin); ohne bleibt es „lokal“ wie für llama-server.
        LlmClient llm = new LlmClient(s, schema).withModel(System.getProperty("modell", "lokal"));
        Machines machines = new Machines();
        // Material.isBlock() braucht ab 1.21 die Server-Registry; ohne Server reicht hier der Enum-Name.
        PlanCompiler compiler = new PlanCompiler(
                id -> org.bukkit.Material.getMaterial(id.toUpperCase(java.util.Locale.ROOT)) != null,
                "stone", new PlanCompiler.Limits(150_000, 192), machines);
        // Leerer Bauplatz (nur Luft) und dieselbe Nachricht wie im Plugin (BuildService.userMessage samt
        // Maschinenbibliothek und REQUEST FACTS). Vorher schickte die Probe nur „Bauauftrag: …“ und maß damit ein
        // anderes Gespräch als das, das der Server führt.
        SiteSurvey empty = SiteSurvey.scan(SiteFrame.SCAN_MIN, SiteFrame.SCAN_MAX, p -> "air");
        // -Drecherche=<ordner>: wie im Plugin vor dem LLM Wikipedia/Wikidata fragen (Zwischenspeicher im Ordner).
        String researchDir = System.getProperty("recherche");
        ReferenceLookup research = researchDir == null ? null : new ReferenceLookup("https://de.wikipedia.org/w/api.php",
                "https://www.wikidata.org/w/api.php", java.nio.file.Path.of(researchDir),
                "https://github.com/Blackn0va/baumeister");
        for (int i = first; i < args.length; i++) {
            String prompt = args[i];
            System.out.println("==== " + prompt);
            java.util.Optional<ReferenceLookup.Reference> ref = research == null ? java.util.Optional.empty()
                    : research.lookup(prompt).join();
            int target = ref.map(x -> x.targetHeight(s.maxExtent())).orElse(0);
            System.out.println("referenz: " + ref.map(x -> x.title() + ", zielhöhe " + target).orElse("-"));
            LlmClient.Reply r = llm.complete(List.of(
                    LlmClient.message("system", resource("/system-prompt.txt")),
                    LlmClient.message("user", BuildService.userMessage(prompt, empty, s.distanceAhead(),
                            machines, null, null, 0, ref.map(x -> x.prompt(s.maxExtent())).orElse(null))))).join();
            System.out.printf("dauer %.1f s, %d tokens, %.1f t/s%n", r.millis() / 1000.0, r.tokens(),
                    r.tokensPerSecond());
            try {
                BuildPlan plan = PlanParser.parse(r.content());
                System.out.println("design: " + plan.design());
                System.out.println("reply:  " + plan.reply());
                System.out.println("schritte: " + plan.steps().size());
                // Was handleReply im Plugin als Planfehler in die Reparaturrunde schicken würde.
                System.out.println("auftragsprüfung: " + RequestHints.check(plan, RequestHints.parse(prompt,
                        machines.all().stream().map(m -> m.name()).toList())));
                CompiledBuild b = compiler.compile(plan);
                System.out.println("blöcke: " + b.blockCount() + ", aktionen: " + b.actions().size()
                        + ", größe " + (b.max().x() - b.min().x() + 1) + "x" + (b.max().y() - b.min().y() + 1)
                        + "x" + (b.max().z() - b.min().z() + 1));
                System.out.println("warnungen: " + b.warnings());
                // Urteil wie im Plugin (Plan-Prüfer streng, leerer Bauplatz): bauen, nachbessern oder absagen.
                com.blackn0va.baumeister.plan.PlanReview.Verdict v = new com.blackn0va.baumeister.plan.PlanReview(
                        compiler, machines, new com.blackn0va.baumeister.plan.PlanReview.Context(prompt,
                                RequestHints.parse(prompt, machines.all().stream().map(m -> m.name()).toList()),
                                empty, false, 12, 1, s.lintMode(), target)).judge(r.content(), 0);
                System.out.println("prüfer: " + (v instanceof com.blackn0va.baumeister.plan.PlanReview.Repair rep
                        ? "NACHBESSERN – " + rep.reason() + System.lineSeparator() + r.content()
                        : v.getClass().getSimpleName()));
                System.out.println("module: " + plan.steps().stream().filter(st -> st.name() != null)
                        .map(st -> st.name()).toList());
                draw(b);
            } catch (Exception e) {
                System.out.println("FEHLER: " + e.getMessage());
                System.out.println(r.content());
            }
        }
    }

    /** Jede Schicht von oben gesehen, vorne (z klein, Spielerseite) unten. Zeichen = Legende. */
    private static void draw(CompiledBuild b) {
        Map<Pos, String> state = new HashMap<>();
        b.actions().forEach(a -> a.changes().forEach(c -> state.put(c.pos(), c.block().id())));
        Map<String, Character> legend = new LinkedHashMap<>();
        String symbols = "#=%@&*+oOxX$~^!?abcdefghijklmnpqrstuvwyz";
        for (int y = b.max().y(); y >= b.min().y(); y--) {
            StringBuilder sb = new StringBuilder("y=" + y + "\n");
            for (int z = b.max().z(); z >= b.min().z(); z--) {
                for (int x = b.min().x(); x <= b.max().x(); x++) {
                    String id = state.get(new Pos(x, y, z));
                    if (id == null) {
                        sb.append(' ');
                    } else if (id.equals("air")) {
                        sb.append('.');
                    } else {
                        sb.append(legend.computeIfAbsent(id, k -> symbols.charAt(legend.size() % symbols.length())));
                    }
                }
                sb.append('\n');
            }
            System.out.print(sb);
        }
        System.out.println("legende: " + legend);
    }

    private static String resource(String name) throws Exception {
        try (InputStream in = LlmProbe.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
