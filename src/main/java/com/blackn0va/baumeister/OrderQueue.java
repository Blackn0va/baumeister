package com.blackn0va.baumeister;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Warteschlange für Bauaufträge, die während eines Baus kommen.
 *
 * Vorher wies BuildService jeden zweiten Auftrag ab („Ich baue gerade noch.“ / „Ich plane gerade schon etwas“) – wer
 * zu dritt baute, musste abpassen, wann der NPC frei ist, und tippte seinen Auftrag mehrmals. Jetzt wird er eingereiht
 * und kommt dran, sobald der Baumeister frei ist. Bewusst gespeichert wird nur der Auftrag selbst, keine Freigabe: Die
 * Rechteprüfung (BuildJob, BlockPlace-/BlockBreakEvents im Namen des Spielers) läuft erst, wenn er dran ist, vor dem
 * ersten Block – wie bei jedem Auftrag fail-closed. Wer hier Prüfergebnisse zwischenspeichert, baut mit einer
 * Freigabe, die inzwischen entzogen sein kann.
 *
 * Grenzen: höchstens max Aufträge insgesamt und perOwner je Auftraggeber (Konsole zählt als ein Auftraggeber) – ein
 * Spieler soll den NPC nicht für den Rest des Abends belegen.
 *
 * Threads: nur Hauptthread. Fehler: keine Ausnahmen; Ablehnung über die Rückgabewerte FULL und TOO_MANY.
 *
 * @param <T> der eigentliche Auftrag (in BuildService ein privater Record)
 */
final class OrderQueue<T> {

    /** Warteschlange voll. */
    static final int FULL = -1;
    /** Dieser Auftraggeber hat schon perOwner Aufträge eingereiht. */
    static final int TOO_MANY = -2;

    /** Ein eingereihter Auftrag. id ist fortlaufend und dient nur Log und Tests. */
    record Entry<T>(long id, UUID owner, String ownerName, String label, T order) {
    }

    private final LinkedList<Entry<T>> entries = new LinkedList<>();
    private int max;
    private int perOwner;
    private long nextId = 1;

    OrderQueue(int max, int perOwner) {
        limits(max, perOwner);
    }

    /** Grenzen neu (Config); schon eingereihte Aufträge bleiben, auch wenn es jetzt mehr sind als erlaubt. */
    void limits(int max, int perOwner) {
        this.max = Math.max(0, max);
        this.perOwner = Math.max(1, perOwner);
    }

    int max() {
        return max;
    }

    int perOwner() {
        return perOwner;
    }

    boolean enabled() {
        return max > 0;
    }

    /** Einreihen: Platz ab 1, sonst FULL oder TOO_MANY. */
    int offer(UUID owner, String ownerName, String label, T order) {
        if (entries.size() >= max) {
            return FULL;
        }
        if (countOf(owner) >= perOwner) {
            return TOO_MANY;
        }
        entries.addLast(new Entry<>(nextId++, owner, ownerName, label, order));
        return entries.size();
    }

    /** Nächster Auftrag oder null. */
    Entry<T> poll() {
        return entries.pollFirst();
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    int size() {
        return entries.size();
    }

    int countOf(UUID owner) {
        int n = 0;
        for (Entry<T> e : entries) {
            if (Objects.equals(e.owner(), owner)) {
                n++;
            }
        }
        return n;
    }

    /** Erster Platz (ab 1) dieses Auftraggebers, 0 ohne Eintrag. */
    int positionOf(UUID owner) {
        int i = 1;
        for (Entry<T> e : entries) {
            if (Objects.equals(e.owner(), owner)) {
                return i;
            }
            i++;
        }
        return 0;
    }

    /** Alle Aufträge dieses Auftraggebers heraus; zurück kommen die entfernten. */
    List<Entry<T>> cancelAll(UUID owner) {
        List<Entry<T>> removed = new ArrayList<>();
        for (Iterator<Entry<T>> it = entries.iterator(); it.hasNext();) {
            Entry<T> e = it.next();
            if (Objects.equals(e.owner(), owner)) {
                removed.add(e);
                it.remove();
            }
        }
        return removed;
    }

    /**
     * Auftrag auf Platz position (ab 1) heraus – nur, wenn er owner gehört oder admin. null, wenn es den Platz nicht
     * gibt oder er fremd ist. Fremde Aufträge abbrechen darf nur ein Admin (wie mayTouch bei Stop und Rückgängig).
     */
    Entry<T> cancelAt(int position, UUID owner, boolean admin) {
        if (position < 1 || position > entries.size()) {
            return null;
        }
        Entry<T> e = entries.get(position - 1);
        if (!admin && !Objects.equals(e.owner(), owner)) {
            return null;
        }
        entries.remove(position - 1);
        return e;
    }

    /** Alles heraus (Admin, Plugin-Ende). */
    List<Entry<T>> clear() {
        List<Entry<T>> all = new ArrayList<>(entries);
        entries.clear();
        return all;
    }

    /**
     * Zeilen für /bm warteschlange: Platz, Auftraggeber (eigene mit „(du)“), Auftrag; höchstens max Zeilen, danach
     * „… und n weitere“.
     */
    List<String> describe(UUID me, int max) {
        if (entries.isEmpty()) {
            return List.of("Die Warteschlange ist leer.");
        }
        List<String> out = new ArrayList<>();
        int i = 1;
        for (Entry<T> e : entries) {
            if (i > max) {
                out.add("… und " + (entries.size() - max) + " weitere.");
                break;
            }
            out.add(i + ". " + e.ownerName() + (Objects.equals(e.owner(), me) ? " (du)" : "") + ": " + e.label());
            i++;
        }
        out.add("Eigene abbrechen: /bm warteschlange abbrechen [platz]");
        return out;
    }

    /** Kopie in Reihenfolge – zum Anzeigen. */
    List<Entry<T>> entries() {
        return List.copyOf(entries);
    }

    /** Übernahme nach /bm neuladen: Einträge des alten Dienstes hinten anhängen, Reihenfolge bleibt. */
    void takeOver(OrderQueue<T> old) {
        entries.addAll(old.entries);
        old.entries.clear();
        nextId = Math.max(nextId, old.nextId);
    }
}
