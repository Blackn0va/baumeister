package com.blackn0va.baumeister.redstone;

import com.blackn0va.baumeister.logic.LogicMinimizer;

import java.util.ArrayList;
import java.util.List;

/**
 * Was eine Schaltung tut, durch Ausprobieren: alle Hebel-Kombinationen schalten (Gray-Code, je Schritt nur ein Hebel),
 * Ausgänge ablesen, daraus je Ausgang die kürzeste Formel (LogicMinimizer). Am Ende noch einmal die Anfangsstellung –
 * zeigt ein Ausgang dann etwas anderes als am Anfang, merkt sich die Schaltung etwas (Speicher, Flipflop, Zähler).
 *
 * Warum ausprobieren statt nachrechnen: Der Server rechnet Redstone exakt (Quasi-Konnektivität, Verzögerungen,
 * Komparatoren); ein eigener Simulator läge irgendwo daneben. RedstoneReader sieht nur den Augenblick.
 *
 * Threads: zustandslos. Fehler: keine; zu viele Eingänge lehnt der Aufrufer ab (MAX_INPUTS).
 */
public final class RedstoneExperiment {

    /** 5 Hebel = 32 Kombinationen; wie der Logik-Compiler (LogicExpr.MAX_INPUTS). */
    public static final int MAX_INPUTS = 5;
    public static final int MAX_OUTPUTS = 8;

    private RedstoneExperiment() {
    }

    /** Reihenfolge der Kombinationen: Gray-Code ab 0, dann noch einmal 0 (Gedächtnisprobe). */
    public static int[] plan(int inputs) {
        int n = 1 << inputs;
        int[] out = new int[n + 1];
        for (int i = 0; i < n; i++) {
            out[i] = i ^ (i >> 1);
        }
        out[n] = 0;
        return out;
    }

    /** Ergebnis je Ausgang: Formel als Text, und ob er sich etwas merkt. */
    public record Output(String name, String formula, boolean memory, List<Integer> minterms) {
    }

    /**
     * @param readings readings[schritt][ausgang] in der Reihenfolge von plan(inputs)
     * @param inputNames Namen der Eingänge (A, B, …) in Bit-Reihenfolge: Bit i = Eingang i
     */
    public static List<Output> analyse(boolean[][] readings, int inputs, List<String> inputNames,
            List<String> outputNames) {
        int[] plan = plan(inputs);
        List<Output> out = new ArrayList<>();
        for (int o = 0; o < outputNames.size(); o++) {
            List<Integer> on = new ArrayList<>();
            for (int s = 0; s < plan.length - 1; s++) {
                if (readings[s][o]) {
                    on.add(plan[s]);
                }
            }
            boolean memory = readings[0][o] != readings[plan.length - 1][o];
            out.add(new Output(outputNames.get(o), formula(on, inputs, inputNames), memory, List.copyOf(on)));
        }
        return out;
    }

    /** „A und nicht B oder C“ – deutsch, wie der Logik-Compiler es liest (/bm logik). */
    public static String formula(List<Integer> minterms, int inputs, List<String> names) {
        if (minterms.isEmpty()) {
            return "immer aus";
        }
        if (minterms.size() == 1 << inputs) {
            return "immer an";
        }
        List<String> terms = new ArrayList<>();
        for (LogicMinimizer.Term t : LogicMinimizer.minimize(minterms, inputs)) {
            List<String> lits = new ArrayList<>();
            for (int i = 0; i < inputs; i++) {
                int l = t.literal(i);
                if (l == 1) {
                    lits.add(names.get(i));
                } else if (l == 0) {
                    lits.add("nicht " + names.get(i));
                }
            }
            terms.add(String.join(" und ", lits));
        }
        return terms.size() == 1 ? terms.get(0)
                : String.join(" oder ", terms.stream().map(t -> t.contains(" und ") ? "(" + t + ")" : t).toList());
    }

    /** Eingänge, die auf keinen Ausgang wirken (in keiner Formel vorkommen). */
    public static List<String> unused(List<Output> outputs, List<String> names) {
        List<String> out = new ArrayList<>();
        for (String n : names) {
            boolean used = outputs.stream().anyMatch(o -> (" " + o.formula().replace("(", " ").replace(")", " ")
                    + " ").contains(" " + n + " "));
            if (!used) {
                out.add(n);
            }
        }
        return out;
    }
}
