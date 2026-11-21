package com.blackn0va.baumeister.dataset;

import com.blackn0va.baumeister.dataset.DatasetGenerator.KindStats;
import com.blackn0va.baumeister.dataset.DatasetGenerator.Sample;
import com.blackn0va.baumeister.plan.AttachPlanner;
import com.blackn0va.baumeister.plan.PlanParser;
import com.blackn0va.baumeister.plan.PlanReview;
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
 * antwort – die letzten 50) ins Lehrset, wenn sie dieselbe strenge Prüfung bestehen wie die erzeugten. collect liefert
 * Erstantworten (Nutzer → Plan), collectRepairs Reparaturrunden (verworfener Plan → Rückmeldung → korrigierter Plan).
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

    /** Eine gespeicherte Antwort; versuch fehlt in Dateien vor bau.pläne-speichern mit Versuchszähler → 0. */
    private record Saved(String owner, String order, int attempt, String answer) {
    }

    /**
     * Reparaturrunden: Folgen ein Auftrag und seine Versuche 0, 1 … desselben Besitzers aufeinander, entsteht aus den
     * letzten beiden ein Mehrrunden-Beispiel (verworfener Plan → Rückmeldung → korrigierter Plan) – aber nur, wenn der
     * letzte Versuch dieselbe strenge Prüfung besteht wie jede Erstantwort und der vorletzte hier wirklich in die
     * Reparaturrunde fällt.
     *
     * Warum die Rückmeldung neu aus PlanReview statt aus der Datei: Gespeichert wird nur die Rohantwort, nicht das
     * Urteil (BuildService.savePlan). PlanReview.judge liefert mit Repair.llmRequest genau den Satz, den das Plugin in
     * der Korrekturrunde schickt; ein nachgebauter Text liefe beim nächsten Umbau des Prüfers auseinander. Urteilt der
     * Prüfer auf der freien Wiese anders als auf dem Server (Bauplatz, Gedächtnis), fällt das Paar heraus
     * („kein_reparaturfall“) – lieber eine Runde weniger als eine Rückmeldung, die nicht zum Plan passt.
     *
     * Nur das letzte Paar einer Kette: Das Plugin schickt in jeder Runde nur den letzten Plan mit
     * (PlanPrompt.repairConversation), ein Zwischenschritt mit weiter kaputter Antwort lehrte nichts Richtiges.
     */
    static Result collectRepairs(Path dir, PlanJudge judge, Set<String> exclude) throws IOException {
        KindStats ks = new KindStats();
        List<Sample> out = new ArrayList<>();
        Set<String> seen = new HashSet<>(exclude);
        List<Saved> saved = new ArrayList<>();
        for (Path f : planFiles(dir)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                saved.add(new Saved(o.has("besitzer") ? o.get("besitzer").getAsString() : "",
                        o.get("auftrag").getAsString(), o.has("versuch") ? o.get("versuch").getAsInt() : 0,
                        o.get("antwort").getAsString()));
            } catch (RuntimeException e) {
                ks.rejected.merge("datei_unlesbar", 1, Integer::sum);
            }
        }
        for (int i = 0; i < saved.size(); i++) {
            Saved last = saved.get(i);
            if (last.attempt() == 0 || continues(saved, i)) {
                continue;  // Erstantwort ohne Folge oder nicht das Ende der Kette
            }
            ks.tried++;
            Optional<Saved> broken = previous(saved, i);
            if (broken.isEmpty()) {
                // Vorgänger nicht (mehr) da: savePlan behält nur die letzten 50 (KEEP_PLANS)
                ks.rejected.merge("vorgänger_fehlt", 1, Integer::sum);
                continue;
            }
            String order = last.order();
            if (AttachPlanner.WORDS.matcher(order).find() || PLOT.matcher(order).find()) {
                ks.noCandidate++;
                continue;
            }
            if (seen.contains(RequestHints.normalize(order))) {
                ks.duplicate++;
                continue;
            }
            Optional<String> why = updateMode(last.answer()).or(() -> judge.rejection(order, last.answer()));
            if (why.isPresent()) {
                ks.rejected.merge("korrektur " + DatasetGenerator.shortReason(why.get()), 1, Integer::sum);
                continue;
            }
            if (!(judge.judge(order, broken.get().answer(), 0, 1) instanceof PlanReview.Repair rep)) {
                ks.rejected.merge("kein_reparaturfall", 1, Integer::sum);
                continue;
            }
            seen.add(RequestHints.normalize(order));
            ks.accepted++;
            out.add(new Sample("llm_reparatur", order, last.answer().strip(), rep.assistant(), rep.llmRequest()));
        }
        return new Result(out, ks);
    }

    /** Ist saved[i] nicht das Ende seiner Kette, weil der nächste Versuch desselben Besitzers direkt folgt? */
    private static boolean continues(List<Saved> saved, int i) {
        Saved s = saved.get(i);
        for (int k = i + 1; k < saved.size(); k++) {
            Saved n = saved.get(k);
            if (n.owner().equals(s.owner())) {
                return n.attempt() == s.attempt() + 1 && n.order().equals(s.order());
            }
        }
        return false;
    }

    /** Der direkt vorausgehende Versuch desselben Besitzers, wenn er zu diesem Auftrag und Versuch − 1 gehört. */
    private static Optional<Saved> previous(List<Saved> saved, int i) {
        Saved s = saved.get(i);
        for (int k = i - 1; k >= 0; k--) {
            Saved p = saved.get(k);
            if (p.owner().equals(s.owner())) {
                return p.attempt() == s.attempt() - 1 && p.order().equals(s.order()) ? Optional.of(p)
                        : Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Dateinamen beginnen mit dem Zeitstempel (BuildService.savePlan) – sortiert = zeitliche Folge. */
    private static List<Path> planFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
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
