package com.blackn0va.baumeister.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Wahrheitstabelle → kurze Summe von Produkten (Quine-McCluskey, dann wesentliche Primimplikanten und gierige
 * Überdeckung). Jeder Term wird im Raster eine eigene Spalte (3 Blöcke breit) – weniger Terme heißen kleinere
 * Schaltung und kürzere Leitungen; das Raster trägt höchstens {@link LogicCircuit#MAX_TERMS}.
 *
 * Für höchstens 5 Eingänge (32 Zeilen) ist das exakte Verfahren in Mikrosekunden fertig; die gierige Überdeckung ist
 * nicht immer minimal, aber immer korrekt (jede wahre Zeile ist von einem Term gedeckt, keine falsche).
 *
 * Threads: zustandslos.
 */
public final class LogicMinimizer {

    private LogicMinimizer() {
    }

    /**
     * Ein Produktterm: lit[i] = 1 (Eingang i), 0 (nicht Eingang i), -1 (kommt nicht vor).
     *
     * @param mask Bits, die im Term vorkommen
     * @param value Werte dieser Bits
     */
    public record Term(int mask, int value, int inputs) {

        public int literal(int i) {
            return (mask >> i & 1) == 0 ? -1 : value >> i & 1;
        }

        boolean covers(int minterm) {
            return (minterm & mask) == value;
        }
    }

    public static List<Term> minimize(List<Integer> minterms, int inputs) {
        if (minterms.isEmpty()) {
            return List.of();
        }
        int full = (1 << inputs) - 1;
        // Primimplikanten: Implikanten (mask, value) so lange paarweise zusammenfassen, wie sich genau ein Bit
        // unterscheidet; was nicht mehr zusammenfassbar ist, ist prim.
        Set<Term> current = new LinkedHashSet<>();
        for (int m : minterms) {
            current.add(new Term(full, m, inputs));
        }
        Set<Term> primes = new LinkedHashSet<>();
        while (!current.isEmpty()) {
            Set<Term> next = new LinkedHashSet<>();
            Set<Term> used = new HashSet<>();
            List<Term> list = new ArrayList<>(current);
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    Term a = list.get(i);
                    Term b = list.get(j);
                    if (a.mask() != b.mask()) {
                        continue;
                    }
                    int diff = a.value() ^ b.value();
                    if (Integer.bitCount(diff) == 1) {
                        next.add(new Term(a.mask() & ~diff, a.value() & ~diff, inputs));
                        used.add(a);
                        used.add(b);
                    }
                }
            }
            for (Term t : list) {
                if (!used.contains(t)) {
                    primes.add(t);
                }
            }
            current = next;
        }
        // Überdeckung: erst wesentliche Primimplikanten (decken eine Zeile als einzige), dann gierig den, der die
        // meisten offenen Zeilen deckt (bei Gleichstand der mit weniger Literalen – kürzere Spalte).
        Set<Integer> open = new LinkedHashSet<>(minterms);
        List<Term> chosen = new ArrayList<>();
        for (int m : minterms) {
            List<Term> covering = primes.stream().filter(t -> t.covers(m)).toList();
            if (covering.size() == 1 && !chosen.contains(covering.getFirst())) {
                chosen.add(covering.getFirst());
            }
        }
        chosen.forEach(t -> open.removeIf(t::covers));
        while (!open.isEmpty()) {
            Term best = primes.stream()
                    .filter(t -> !chosen.contains(t))
                    .max(Comparator.<Term>comparingLong(t -> open.stream().filter(t::covers).count())
                            .thenComparingInt(t -> -Integer.bitCount(t.mask())))
                    .orElseThrow();
            chosen.add(best);
            open.removeIf(best::covers);
        }
        return List.copyOf(chosen);
    }
}
