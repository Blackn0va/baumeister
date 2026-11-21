package com.blackn0va.baumeister.dataset;

import com.blackn0va.baumeister.plan.PlanReview.Accept;
import com.blackn0va.baumeister.plan.PlanReview.GiveUp;
import com.blackn0va.baumeister.plan.PlanReview.Repair;
import com.blackn0va.baumeister.plan.PlanReview.Verdict;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Messprobe, kein Unit-Test: spielt echte LLM-Antworten noch einmal durch Parser und Prüfer (PlanJudge, dieselbe
 * Prüfkette wie im Plugin, flacher Bauplatz, llm.reparatur-versuche 1) und zählt, was daraus würde – gebaut,
 * gebaut nach Selbstkorrektur, Reparaturrunde (kostet eine weitere KI-Runde) oder verworfen.
 *
 * Warum: Auf dev01 endeten 72 von 223 KI-Aufträgen mit verworfenem Plan (Logs bis 2026-11-21). Ob eine Korrektur
 * im Plugin daran etwas ändert, zeigen erst die echten Antworten, nicht nachgebaute. Die Daten liegen NIE im Repo
 * (das Repo ist öffentlich, die Aufträge sind Spielertexte) – deshalb nimmt die Probe den Ordner als Argument.
 *
 * Eingabe: je Datei ein JSON-Objekt mit „auftrag“, „antwort“ und „versuch“ (0 = erste Antwort, 1 = nach der
 * Nachbesserung), wie der Host sie mitschreibt.
 *
 * Aufruf (nach mvn test-compile):
 *   java -cp "target/classes;target/test-classes;lib/*" com.blackn0va.baumeister.dataset.PlanReplayProbe <ordner> [-v]
 *
 * Threads: ein Lauf, ein Thread. Fehler: unlesbare Dateien zählen als „unlesbar“ und brechen den Lauf nicht ab.
 */
public final class PlanReplayProbe {

    private PlanReplayProbe() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Aufruf: PlanReplayProbe <ordner mit *.json> [-v]");
            System.exit(1);
        }
        boolean verbose = args.length > 1 && args[1].equals("-v");
        PlanJudge judge = new PlanJudge();
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, Integer> reasons = new TreeMap<>();
        List<Path> files;
        try (Stream<Path> s = Files.list(Path.of(args[0]))) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        for (Path f : files) {
            String kind;
            String detail;
            try {
                JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                String order = o.get("auftrag").getAsString();
                String answer = o.get("antwort").getAsString();
                int attempt = Integer.parseInt(o.get("versuch").getAsString().trim());
                Verdict v = judge.judge(order, answer, attempt, 1);
                switch (v) {
                    case Accept a -> {
                        kind = a.repairs().isEmpty() ? "gebaut" : "gebaut_gerichtet";
                        detail = String.join("; ", a.repairs());
                    }
                    case Repair r -> {
                        kind = "reparaturrunde";
                        detail = r.reason();
                    }
                    case GiveUp g -> {
                        kind = "verworfen";
                        detail = g.reason();
                    }
                }
            } catch (RuntimeException e) {
                // Grenze je Datei: eine kaputte Aufzeichnung soll die Zählung der übrigen nicht verhindern.
                kind = "unlesbar";
                detail = e.toString();
            }
            counts.merge(kind, 1, Integer::sum);
            if (!kind.equals("gebaut")) {
                reasons.merge(kind + " | " + shorten(detail), 1, Integer::sum);
            }
            if (verbose) {
                System.out.println(f.getFileName() + " | " + kind + " | " + shorten(detail));
            }
        }
        System.out.println("dateien=" + files.size() + " " + counts);
        reasons.forEach((k, n) -> System.out.println(n + "× " + k));
    }

    private static String shorten(String s) {
        String one = s.replace('\n', ' ');
        return one.length() > 160 ? one.substring(0, 160) + "…" : one;
    }
}
