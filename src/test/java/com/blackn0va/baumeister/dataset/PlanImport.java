package com.blackn0va.baumeister.dataset;

import com.blackn0va.baumeister.dataset.DatasetGenerator.KindStats;
import com.blackn0va.baumeister.dataset.DatasetGenerator.Sample;
import com.blackn0va.baumeister.plan.AttachPlanner;
import com.blackn0va.baumeister.plan.PlanParser;
import com.blackn0va.baumeister.plan.RequestHints;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Echte LLM-Pläne aus plugins/Baumeister/pläne/ (bau.pläne-speichern, BuildService.savePlan: Felder auftrag, versuch,
 * antwort – die letzten 50) ins Lehrset, wenn sie dieselbe strenge Prüfung bestehen wie die erzeugten.
 *
 * Warum nur mit Prüfung: Gespeichert wird JEDE Rohantwort, auch die verworfenen (dev01 2026-10-31: 55 von 136). Eine
 * Antwort, die PlanReview erst zurechtbiegen musste, käme sonst als Vorbild ins Lehrset.
 *
 * Ausgelassen: Anbau-Aufträge („an das Haus anbauen“) und Plot-Aufträge – geprüft wird hier auf der freien Wiese, und
 * ein frei stehender Plan zu „bau an das Haus an“ lehrte, den Anschluss zu übergehen. Folgeaufträge (mode update)
 * ebenso: Ohne das Gedächtnis aus der Nachricht passt die Antwort nicht zum Auftrag.
 *
 * Threads: zustandslos. Fehler: IOException nur, wenn der Ordner nicht lesbar ist; eine kaputte Datei wird gezählt.
 */
final class PlanImport {

    record Result(List<Sample> samples, KindStats stats) {
    }

    private static final Pattern PLOT = Pattern.compile("plot|grundst|parzelle", Pattern.CASE_INSENSITIVE);

    private PlanImport() {
    }

    static Result collect(Path dir, PlanJudge judge, Set<String> exclude) throws IOException {
        KindStats ks = new KindStats();
        List<Sample> out = new ArrayList<>();
        Set<String> seen = new HashSet<>(exclude);
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        for (Path f : files) {
            ks.tried++;
            String order;
            String answer;
            try {
                JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                order = o.get("auftrag").getAsString();
                answer = o.get("antwort").getAsString();
            } catch (RuntimeException e) {
                ks.rejected.merge("datei_unlesbar", 1, Integer::sum);
                continue;
            }
            if (AttachPlanner.WORDS.matcher(order).find() || PLOT.matcher(order).find()) {
                ks.noCandidate++;
                continue;
            }
            if (!seen.add(RequestHints.normalize(order))) {
                ks.duplicate++;
                continue;
            }
            Optional<String> why = updateMode(answer).or(() -> judge.rejection(order, answer));
            if (why.isPresent()) {
                seen.remove(RequestHints.normalize(order));
                ks.rejected.merge(DatasetGenerator.shortReason(why.get()), 1, Integer::sum);
                continue;
            }
            ks.accepted++;
            out.add(new Sample("llm_angenommen", order, answer.strip()));
        }
        return new Result(out, ks);
    }

    private static Optional<String> updateMode(String answer) {
        try {
            return PlanParser.parse(answer).isUpdate() ? Optional.of("folgeauftrag (mode update)")
                    : Optional.empty();
        } catch (com.blackn0va.baumeister.plan.PlanException | RuntimeException e) {
            return Optional.empty();  // Urteil fällt danach in judge.rejection, mit Grund
        }
    }
}
