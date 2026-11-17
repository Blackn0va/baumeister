package com.blackn0va.baumeister;

import com.blackn0va.baumeister.BuildJob.UndoRecord;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Regeln für den Stapel von /bm rückgängig (BuildService.undo, neuester Eintrag vorn) – hier statt inline, damit sie
 * ohne Server prüfbar sind.
 *
 * Tiefe 50 statt 5 (2026-11-17, Nutzer: „Ja, auf 50“): Den Hohlraum vom 2026-11-16 (LLM, fill air, 8428 Blöcke) konnte
 * /bm rückgängig nicht mehr zurücknehmen – fünf kleine Treppenstücke um 23:05 hatten seinen Eintrag aus dem Stapel
 * gedrängt, ohne jede Logzeile. Jetzt fällt ein Eintrag erst nach bau.undo-tiefe neueren heraus, und jedes Herausfallen
 * steht im Log (dropLine).
 *
 * Arbeitsspeicher: Ein Eintrag aus dieser Sitzung hält je Block den Ursprung und den gesetzten Typ (Block, BlockData,
 * Material samt Map-Einträgen). Gemessen auf dev01 (2026-11-17): Heap fest 5 GB, nach GC 0,48 GB belegt; 87 Bauten der
 * letzten Tage im Median 1 297 Blöcke, der größte 45 802 – erlaubt sind aber 150 000 je Plan (bau.max-blöcke) und
 * 1 000 000 je Bauteil. 50 solche Einträge im Speicher wären ein Vielfaches des freien Heaps. Deshalb behalten nur die
 * neuesten IN_MEMORY ihre Daten im Speicher; ältere zieht offload auf ihre Kopie auf der Platte um
 * (UndoJournal.persistCopy, ohnehin für den Neustart geschrieben). Mehr Tiefe kostet so Platte
 * (komfort.rückgängig-max-mb), keinen Heap. Ohne fertige Kopie (Rückgängig über Neustart aus, Schreibfehler, Kopie
 * noch unterwegs) bleibt ein Eintrag im Speicher wie bisher.
 *
 * Threads: nur Hauptthread. Fehler: keine.
 */
final class UndoStack {

    /** So viele der neuesten Einträge halten ihre Ursprünge im Speicher – so viele wie vor 2026-11-17 insgesamt. */
    static final int IN_MEMORY = 5;

    private UndoStack() {
    }

    /** Älteste über depth hinaus entfernen; drop bekommt jeden entfernten, ältesten zuerst. */
    static void trim(Deque<UndoRecord> stack, int depth, Consumer<UndoRecord> drop) {
        while (stack.size() > depth) {
            drop.accept(stack.removeLast());
        }
    }

    /**
     * Einträge ab Platz keep (0 = neuester), die ihre Ursprünge noch im Speicher halten, durch toDisk ersetzen. toDisk
     * gibt denselben Eintrag zurück, wenn (noch) keine fertige Kopie da ist. Reihenfolge bleibt. Zurück: wie viele
     * umgezogen sind.
     */
    static int offload(Deque<UndoRecord> stack, int keep, UnaryOperator<UndoRecord> toDisk) {
        if (stack.size() <= keep) {
            return 0;
        }
        List<UndoRecord> all = new ArrayList<>(stack);
        int moved = 0;
        for (int i = keep; i < all.size(); i++) {
            UndoRecord r = all.get(i);
            if (r.spool() != null || r.originals().isEmpty()) {
                continue;
            }
            UndoRecord d = toDisk.apply(r);
            if (d != r) {
                all.set(i, d);
                moved++;
            }
        }
        if (moved > 0) {
            stack.clear();
            stack.addAll(all);
        }
        return moved;
    }

    /**
     * rec aus dem Stapel nehmen – oder den Eintrag, der ihn nach offload von der Platte vertritt (gleicher
     * Auftraggeber, gleiche Zeit). Vorher entfernte die Lag-Wache mit undo.remove(rec): Nach dem Umzug stünde dort ein
     * anderes Objekt, und eine Lag-Maschine bliebe stehen. null, wenn der Eintrag schon weg ist.
     */
    static UndoRecord take(Deque<UndoRecord> stack, UndoRecord rec) {
        for (Iterator<UndoRecord> it = stack.iterator(); it.hasNext();) {
            UndoRecord r = it.next();
            if (r == rec || (Objects.equals(r.owner(), rec.owner()) && r.when().equals(rec.when()))) {
                it.remove();
                return r;
            }
        }
        return null;
    }

    /** Logzeile, wenn ein Eintrag aus dem Stapel fällt (vorher still, siehe oben). */
    static String dropLine(UndoRecord r, int depth, Instant now) {
        long minutes = Math.max(0, Duration.between(r.when(), now).toMinutes());
        return "rückgängig | aus_dem_stapel | besitzer=" + r.ownerName() + " blöcke=" + r.size() + " alter_min="
                + minutes + " tiefe=" + depth;
    }
}
