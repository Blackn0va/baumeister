package com.blackn0va.baumeister.dataset;

import com.blackn0va.baumeister.plan.BuildPlan;
import com.blackn0va.baumeister.plan.CompiledBuild;
import com.blackn0va.baumeister.plan.PlanCompiler;
import com.blackn0va.baumeister.plan.PlanException;
import com.blackn0va.baumeister.plan.PlanParser;
import com.blackn0va.baumeister.plan.PlanReview.Accept;
import com.blackn0va.baumeister.plan.PlanReview.Verdict;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Messprobe, kein Unit-Test: gibt je echter LLM-Antwort die Zellen aus, die das Plugin baut – als Gegenstück zur
 * Plan-Vorschau der Host-App (host/src/gui/plan_view, Probe swbh_probe_plan_preview). Ein Python-Vergleich zählt
 * danach die Abweichungen je Art (Auftrag T-017, AK-19).
 *
 * Zwei Fassungen je Plan, weil sie verschiedene Fragen beantworten:
 * - „compiler“: PlanCompiler direkt auf der Antwort (nach PlanParser.normalize, wie PlanReview sie liest), mit den
 *   eingebauten Maschinen und dem Blockkatalog aus tiers/block-ids.txt (Datengenerator 1.21.11/26.2), seit T-019
 *   derselbe wie in PlanJudge (PlanJudge.SERVER_BLOCKS). Das ist, was die Vorschau nachrechnet – jede Abweichung hier
 *   liegt an der Vorschau. Vorher kannte der Katalog von PlanJudge jeden Material-Namen, auch Gegenstände wie
 *   „redstone“, die der Server (BukkitBlockCatalog, isBlock) über den Alias zu redstone_wire macht – gemessen
 *   2 198 Zellen Scheinabweichung in einem Plan (2026-11-21); diese Probe hatte deshalb eine eigene Liste.
 * - „review“: der Bau nach PlanReview (PlanJudge, dieselbe Prüfkette wie im Plugin). Was hier zusätzlich abweicht,
 *   hat das Plugin am Plan nachgebessert (Hohlmachen, Stützen, weggelassene Blöcke) – das kann die Vorschau aus der
 *   Antwort allein nicht wissen.
 *
 * Die Daten liegen NIE im Repo (öffentlich, Spielertexte) – Eingabe- und Ausgabeordner kommen als Argumente.
 * Ausgabe je Plan: <name>.compiler.txt und <name>.review.txt, je Zeile „x y z block“ (Block ohne Zustände, Luft
 * ausgelassen), erste Zeile „# status …“.
 *
 * Aufruf (nach mvn test-compile):
 *   java -cp "target/classes;target/test-classes;lib/*" com.blackn0va.baumeister.dataset.PreviewCellsProbe <ein> <aus>
 *
 * Threads: ein Lauf, ein Thread. Fehler: eine unlesbare Datei bekommt „# status unlesbar“, der Lauf geht weiter.
 */
public final class PreviewCellsProbe {

    private PreviewCellsProbe() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Aufruf: PreviewCellsProbe <ordner mit *.json> <ausgabeordner>");
            System.exit(1);
        }
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        PlanJudge judge = new PlanJudge();
        PlanCompiler compiler = judge.compiler();
        List<Path> files;
        try (Stream<Path> s = Files.list(Path.of(args[0]))) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        TreeMap<String, Integer> counts = new TreeMap<>();
        for (Path f : files) {
            String name = f.getFileName().toString().replaceFirst("\\.json$", "");
            String order = "";
            String answer = "";
            int attempt = 0;
            try {
                JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                order = o.get("auftrag").getAsString();
                answer = o.get("antwort").getAsString();
                attempt = Integer.parseInt(o.get("versuch").getAsString().trim());
            } catch (RuntimeException e) {
                // Grenze je Datei: eine kaputte Aufzeichnung soll die übrigen nicht verhindern.
                write(out.resolve(name + ".compiler.txt"), "unlesbar " + e, null);
                write(out.resolve(name + ".review.txt"), "unlesbar " + e, null);
                counts.merge("unlesbar", 1, Integer::sum);
                continue;
            }
            String compilerStatus;
            CompiledBuild direct = null;
            try {
                BuildPlan plan = PlanParser.parse(PlanParser.normalize(answer).json());
                direct = compiler.compile(plan);
                compilerStatus = "gebaut warnungen=" + direct.warnings().size();
            } catch (PlanException | RuntimeException e) {
                compilerStatus = "abgelehnt " + e.getMessage();
            }
            write(out.resolve(name + ".compiler.txt"), compilerStatus, direct);
            Verdict v = judge.judge(order, answer, attempt, 1);
            if (v instanceof Accept a) {
                write(out.resolve(name + ".review.txt"), "gebaut nachbesserungen=" + a.repairs().size(), a.build());
            } else {
                write(out.resolve(name + ".review.txt"), "nicht_gebaut " + v.getClass().getSimpleName(), null);
            }
            counts.merge(compilerStatus.split(" ")[0] + "/" + v.getClass().getSimpleName(), 1, Integer::sum);
        }
        System.out.println("Pläne: " + files.size() + " " + counts);
    }

    private static void write(Path file, String status, CompiledBuild build) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# status " + status.replace('\n', ' '));
        if (build != null) {
            for (CompiledBuild.Action a : build.actions()) {
                for (CompiledBuild.Change c : a.changes()) {
                    if (c.block().isAir()) {
                        continue;
                    }
                    lines.add(c.pos().x() + " " + c.pos().y() + " " + c.pos().z() + " " + c.block().id());
                }
            }
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
    }
}
