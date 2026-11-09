package com.blackn0va.baumeister.rucksack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Was der Rucksack mit Stapeln macht – ohne Bukkit, damit es getestet werden kann: einlagern (erst auffüllen, dann
 * leere Plätze), sortieren und zusammenfassen, Seiten blättern.
 *
 * Grundregel: Kein Gegenstand entsteht und keiner verschwindet. Jede Operation erhält die Summe je Sorte – die Tests
 * prüfen genau das, weil ein Fehler hier entweder Duplikate erzeugt oder Sachen des Spielers vernichtet.
 *
 * Plätze liegen flach in einer Liste (Seite × 45 + Platz), null = leer.
 *
 * Threads: zustandslos. Fehler: keine (ungültige Mengen werden nicht erzeugt).
 */
public final class RucksackLogic {

    /** Plätze je Seite: 5 Reihen. Die sechste Reihe gehört den Knöpfen. */
    public static final int PER_PAGE = 45;
    public static final int SLOT_PREV = 45;
    public static final int SLOT_DEPOSIT = 47;
    public static final int SLOT_INFO = 49;
    public static final int SLOT_SORT = 51;
    public static final int SLOT_NEXT = 53;
    /** Shift-Klick auf einen Pfeil springt so weit. */
    public static final int JUMP = 10;

    /** Wie ein Stapel gelesen und verändert wird (ItemStack im Plugin, Fakes im Test). Immer Kopien liefern. */
    public interface StackOps<T> {
        boolean similar(T a, T b);

        int amount(T t);

        int max(T t);

        T withAmount(T t, int amount);
    }

    private RucksackLogic() {
    }

    /**
     * Legt item in die Plätze: erst auf gleiche, nicht volle Stapel (über alle Seiten), dann in leere Plätze.
     * Liefert, was nicht mehr passt (0 = alles drin).
     */
    public static <T> int deposit(List<T> slots, T item, StackOps<T> ops) {
        int left = ops.amount(item);
        for (int i = 0; i < slots.size() && left > 0; i++) {
            T s = slots.get(i);
            if (s != null && ops.similar(s, item)) {
                int room = ops.max(s) - ops.amount(s);
                if (room > 0) {
                    int put = Math.min(room, left);
                    slots.set(i, ops.withAmount(s, ops.amount(s) + put));
                    left -= put;
                }
            }
        }
        for (int i = 0; i < slots.size() && left > 0; i++) {
            if (slots.get(i) == null) {
                int put = Math.min(ops.max(item), left);
                slots.set(i, ops.withAmount(item, put));
                left -= put;
            }
        }
        return left;
    }

    /**
     * Fasst gleiche Gegenstände zu vollen Stapeln zusammen, sortiert nach order und schreibt sie von vorn in die
     * Plätze; der Rest wird leer. Es passen immer alle hinein: Zusammenfassen braucht nie mehr Plätze als vorher.
     */
    public static <T> void sort(List<T> slots, StackOps<T> ops, Comparator<T> order) {
        List<T> kinds = new ArrayList<>();
        List<Long> totals = new ArrayList<>();
        // Größter gesehener Stapel je Sorte: Stapel über dem Höchstwert (per Befehl erzeugt) bleiben so groß – sonst
        // bräuchte das Sortieren mehr Plätze als vorher, und der Rest hätte keinen Platz.
        List<Integer> biggest = new ArrayList<>();
        for (T s : slots) {
            if (s == null) {
                continue;
            }
            int k = 0;
            while (k < kinds.size() && !ops.similar(kinds.get(k), s)) {
                k++;
            }
            if (k == kinds.size()) {
                kinds.add(s);
                totals.add(0L);
                biggest.add(0);
            }
            totals.set(k, totals.get(k) + ops.amount(s));
            biggest.set(k, Math.max(biggest.get(k), ops.amount(s)));
        }
        List<Integer> index = new ArrayList<>();
        for (int k = 0; k < kinds.size(); k++) {
            index.add(k);
        }
        index.sort((a, b) -> order.compare(kinds.get(a), kinds.get(b)));
        int at = 0;
        for (int k : index) {
            T kind = kinds.get(k);
            long left = totals.get(k);
            int max = Math.max(Math.max(1, ops.max(kind)), biggest.get(k));
            while (left > 0) {
                int put = (int) Math.min(max, left);
                slots.set(at++, ops.withAmount(kind, put));
                left -= put;
            }
        }
        for (int i = at; i < slots.size(); i++) {
            slots.set(i, null);
        }
    }

    /** Plätze (flacher Index), auf denen match zutrifft – für die Suche. */
    public static <T> List<Integer> find(List<T> slots, java.util.function.Predicate<T> match) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            T s = slots.get(i);
            if (s != null && match.test(s)) {
                out.add(i);
            }
        }
        return out;
    }

    /**
     * Nimmt bis zu count Stück heraus, auf die match zutrifft – erst aus angebrochenen Stapeln (der Rucksack bleibt
     * aufgeräumt), dann von vorn. Liefert die herausgenommenen Stapel (je höchstens ein voller Stapel).
     */
    public static <T> List<T> take(List<T> slots, java.util.function.Predicate<T> match, int count, StackOps<T> ops) {
        List<Integer> hits = find(slots, match);
        hits.sort(Comparator.comparingInt((Integer i) -> ops.amount(slots.get(i)) >= ops.max(slots.get(i)) ? 1 : 0));
        List<T> out = new ArrayList<>();
        int left = count;
        for (int i : hits) {
            if (left <= 0) {
                break;
            }
            T s = slots.get(i);
            int n = Math.min(left, ops.amount(s));
            left -= n;
            slots.set(i, n == ops.amount(s) ? null : ops.withAmount(s, ops.amount(s) - n));
            T got = ops.withAmount(s, n);
            // Gleiches zu vollen Stapeln zusammenlegen, damit die Rückgabe ins Inventar wenig Plätze braucht.
            int at = out.size() - 1;
            if (at >= 0 && ops.similar(out.get(at), got) && ops.amount(out.get(at)) < ops.max(got)) {
                int room = ops.max(got) - ops.amount(out.get(at));
                int put = Math.min(room, n);
                out.set(at, ops.withAmount(got, ops.amount(out.get(at)) + put));
                n -= put;
            }
            if (n > 0) {
                out.add(ops.withAmount(got, n));
            }
        }
        return out;
    }

    /** Belegte Plätze. */
    public static <T> int used(List<T> slots) {
        int n = 0;
        for (T s : slots) {
            if (s != null) {
                n++;
            }
        }
        return n;
    }

    /** Zielseite nach einem Pfeil (0-basiert), am Rand festgehalten. Shift springt um JUMP Seiten. */
    public static int turn(int page, int pages, boolean forward, boolean shift) {
        int step = (shift ? JUMP : 1) * (forward ? 1 : -1);
        return Math.clamp((long) page + step, 0, Math.max(0, pages - 1));
    }

    /** Fenstertitel, z. B. „Rucksack · Seite 3/100“. */
    public static String title(int page, int pages) {
        return "Rucksack · Seite " + (page + 1) + "/" + pages;
    }
}
