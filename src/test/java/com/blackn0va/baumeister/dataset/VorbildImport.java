package com.blackn0va.baumeister.dataset;

import com.blackn0va.baumeister.dataset.DatasetGenerator.KindStats;
import com.blackn0va.baumeister.dataset.DatasetGenerator.Sample;
import com.blackn0va.baumeister.plan.BlockSpec;
import com.blackn0va.baumeister.plan.Orientation.Cardinal;
import com.blackn0va.baumeister.plan.Pos;
import com.blackn0va.baumeister.plan.RequestHints;
import com.blackn0va.baumeister.structure.PlanDecompiler;
import com.blackn0va.baumeister.structure.StructureFile;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Echte Bauwerke als Vorbilder ins Lehrset: Bauwerk-Datei (.schem, .litematic, .nbt) → StructureFile → PlanDecompiler
 * mit höchstens {@link #BUDGET} Schritten → Rückbau-Vergleich → PlanJudge.
 *
 * Der Ordner braucht eine vorbilder.json – ohne Auftragstext gibt es kein Lehrpaar, und den Text rät der Baumeister
 * nicht aus dem Dateinamen:
 * <pre>
 * [{"datei": "turm.schem", "auftrag": "ein runder Turm aus Steinziegeln", "vorne": "s"}]
 * </pre>
 * vorne = Himmelsrichtung, in die die Vorderseite beim Speichern zeigte (wie in bauteile.yml), Vorgabe s.
 *
 * Ins Lehrset nur, wenn der vereinfachte Bau mindestens {@link #MIN_SHARE} des Originals trifft und die strenge
 * Prüfung besteht. Nur Dateien direkt im Ordner (kein ../ aus einer bearbeiteten vorbilder.json, ASVS V12.3.1).
 *
 * Threads: zustandslos. Fehler: IOException, wenn Ordner oder vorbilder.json nicht lesbar sind; eine kaputte
 * Bauwerk-Datei wird gezählt, nicht geworfen.
 */
final class VorbildImport {

    /** Wie im System-Prompt („Use 5 to 40 steps“). */
    static final int BUDGET = 40;
    /** Darunter ist der Plan eher eine Skizze als das Vorbild – das Modell lernte, Teile wegzulassen. */
    static final double MIN_SHARE = 0.85;

    record Result(List<Sample> samples, KindStats stats) {
    }

    private VorbildImport() {
    }

    static Result collect(Path dir, PlanJudge judge, Set<String> exclude) throws IOException {
        KindStats ks = new KindStats();
        List<Sample> out = new ArrayList<>();
        Set<String> seen = new HashSet<>(exclude);
        Path manifest = dir.resolve("vorbilder.json");
        if (!Files.isRegularFile(manifest)) {
            throw new IOException("vorbilder.json fehlt in " + dir);
        }
        JsonArray entries = JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8))
                .getAsJsonArray();
        Path root = dir.toRealPath();
        for (JsonElement el : entries) {
            ks.tried++;
            JsonObject e = el.getAsJsonObject();
            String order = e.has("auftrag") ? e.get("auftrag").getAsString().strip() : "";
            String name = e.has("datei") ? e.get("datei").getAsString() : "";
            Path file = root.resolve(name).normalize();
            if (order.isEmpty() || name.isEmpty() || !root.equals(file.getParent())) {
                ks.rejected.merge("eintrag_ungültig (datei/auftrag fehlt oder liegt außerhalb)", 1, Integer::sum);
                continue;
            }
            if (!seen.add(RequestHints.normalize(order))) {
                ks.duplicate++;
                continue;
            }
            try {
                Cardinal front = Cardinal.parse(e.has("vorne") ? e.get("vorne").getAsString() : "s");
                Map<Pos, BlockSpec> local = StructureFile.toLocal(StructureFile.read(file), front);
                Optional<String> why = check(judge, order, local, name);
                if (why.isPresent()) {
                    seen.remove(RequestHints.normalize(order));
                    ks.rejected.merge(DatasetGenerator.shortReason(why.get()), 1, Integer::sum);
                    continue;
                }
                ks.accepted++;
                out.add(new Sample("vorbild", order, plan(local, order, name).json()));
            } catch (IOException | IllegalArgumentException ex) {
                seen.remove(RequestHints.normalize(order));
                ks.rejected.merge("datei: " + ex.getMessage(), 1, Integer::sum);
            }
        }
        return new Result(out, ks);
    }

    static PlanDecompiler.Result plan(Map<Pos, BlockSpec> local, String order, String name) {
        return PlanDecompiler.decompile(local, design(local, name), "Ich baue dir " + reply(order) + " nach!",
                BUDGET);
    }

    /** Leer = ins Lehrset; sonst der Grund. */
    static Optional<String> check(PlanJudge judge, String order, Map<Pos, BlockSpec> local, String name) {
        PlanDecompiler.Result r = plan(local, order, name);
        PlanDecompiler.Verify v = PlanDecompiler.verify(r.json(), local, judge.compiler(), BUDGET);
        if (v.error() != null) {
            return Optional.of("rückbau: " + v.error());
        }
        if (v.share() < MIN_SHARE) {
            return Optional.of(String.format(Locale.ROOT, "nur %.0f %% in %d Schritten", 100 * v.share(), BUDGET));
        }
        return judge.rejection(order, r.json());
    }

    private static String design(Map<Pos, BlockSpec> local, String name) {
        int mx = 0;
        int my = 0;
        int mz = 0;
        for (Pos p : local.keySet()) {
            mx = Math.max(mx, p.x());
            my = Math.max(my, p.y());
            mz = Math.max(mz, p.z());
        }
        return "rebuilt from a player build, " + (mx + 1) + "x" + (my + 1) + "x" + (mz + 1) + " (w x h x d), x0-" + mx
                + " y0-" + my + " z0-" + mz + ", front at z0; big shapes first, then rows and details.";
    }

    /** „bau mir einen Turm“ → „einen Turm“, sonst der Auftrag selbst. */
    private static String reply(String order) {
        String o = order.strip().replaceAll("[.!?]+$", "");
        return o.replaceFirst("(?i)^(bitte\\s+)?(bau|baue)\\s+(mir|uns)?\\s*", "");
    }

    // ---------------------------------------------------------------- Bericht

    /**
     * Bericht über alle Bauwerk-Dateien eines Ordners, ohne vorbilder.json (Vorderseite s): verlustfrei nötige
     * Schritte, Anteil in 40 Schritten, Urteil des Richters mit einem neutralen Auftrag.
     *
     * Aufruf: java -cp "target/classes;target/test-classes;lib/*" com.blackn0va.baumeister.dataset.VorbildImport <ordner>
     */
    public static void main(String[] args) throws IOException {
        PlanJudge judge = new PlanJudge();
        List<Path> files;
        try (Stream<Path> s = Files.list(Path.of(args[0]))) {
            files = s.filter(p -> p.toString().matches("(?i).*\\.(schem|schematic|litematic|nbt)$")).sorted()
                    .toList();
        }
        System.out.println("| Datei | Format | Blöcke | verlustfrei Schritte | Anteil in 40 | Richter |");
        System.out.println("|---|---|---:|---:|---:|---|");
        for (Path f : files) {
            String name = f.getFileName().toString();
            try {
                StructureFile.Structure st = StructureFile.read(f);
                Map<Pos, BlockSpec> local = StructureFile.toLocal(st, Cardinal.SOUTH);
                PlanDecompiler.Result full = PlanDecompiler.decompile(local, "x", "x");
                PlanDecompiler.Result r = plan(local, "ein Bauwerk", name);
                PlanDecompiler.Verify v = PlanDecompiler.verify(r.json(), local, judge.compiler(), BUDGET);
                String verdict = judge.rejection("bau mir dieses Bauwerk nach", r.json()).map(DatasetGenerator::shortReason)
                        .orElse("sauber");
                System.out.printf(Locale.ROOT, "| %s | %s | %d | %d | %.0f %% | %s |%n", name, st.format(), full.cells(),
                        full.steps(), 100 * v.share(), verdict);
            } catch (IOException | IllegalArgumentException e) {
                System.out.printf("| %s | – | – | – | – | %s |%n", name, e.getMessage());
            }
        }
    }
}
