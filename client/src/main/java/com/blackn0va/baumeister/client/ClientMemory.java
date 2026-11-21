package com.blackn0va.baumeister.client;

import com.blackn0va.baumeister.plan.OrderIntent;
import com.blackn0va.baumeister.plan.Orientation.Cardinal;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Gedächtnis für Folgeaufträge im Client: der letzte Bau des Spielers samt Plan, damit „mach das Dach rot“ weiß,
 * welches Dach gemeint ist. Vorher gab es das nur im Plugin (BuildService.Memory, Wunsch 2026-10-28); im Client begann
 * jeder Auftrag bei null und ClientPlanner schickte hasMemory=false (AK-5, 2026-11-21).
 *
 * Regeln wie im Plugin (Festgelegte Entscheidung „Gedächtnis je Spieler“): eine Stunde, 48 Blöcke Umkreis um die Füße
 * beim ersten Auftrag, nur im Speicher – nie auf Platte. Ein neues eigenständiges Bauwerk (OrderIntent) plant frisch.
 * Dazu, was das Plugin über die Welt der Location bekommt: Der Eintrag gehört zu genau einer Welt (ClientLevel, nur
 * als Identität verglichen) – nach einem Welt-, Dimensions- oder Serverwechsel ist er nicht mehr zu sehen. Wer diese
 * Prüfung entfernt, plant im Nether „auf“ einem Bau, der in der Oberwelt steht.
 *
 * Ein Eintrag statt einer Tabelle je Spieler: Im Client gibt es genau einen Spieler; die Welt-Bindung trennt Server.
 *
 * Koordinaten: Statt den Plan wie BuildService.translate in den Bauplatz-Rahmen zu verschieben, merkt sich der
 * Eintrag die Verschiebung (shiftX/shiftZ), mit der der Bau stand. Der Folgeauftrag scannt und baut mit derselben
 * Verschiebung – alter Plan, Scan und neuer Plan liegen damit in denselben Koordinaten, ohne eine zweite Fassung der
 * Plan-Umrechnung im Client.
 *
 * @param <C> gesetzte Blöcke des Baus (im Spiel Map&lt;BlockPos, BlockState&gt;; Tests kommen ohne Minecraft aus)
 *
 * Threads: nur Client-Thread. Fehler: keine.
 */
final class ClientMemory<C> {

    /** Wie BuildService.MEMORY_TTL: Wer später wiederkommt, meint einen neuen Bau. */
    static final Duration TTL = Duration.ofHours(1);
    /** Wie BuildService.MEMORY_RADIUS: Wer weggeht, meint einen neuen Bau. */
    static final int RADIUS = 48;

    /**
     * @param world   Welt des Baus (Identität)
     * @param x       Füße beim ersten Auftrag – Ursprung des Rahmens, wie Memory.feet im Plugin
     * @param shiftZ  Verschiebung nach vorn samt Abstand vor den Füßen (distanceAhead) – ändert jemand den Abstand
     *                zwischen zwei Aufträgen, bleibt der Nachtrag trotzdem am alten Bau
     * @param planJson Plan in den Koordinaten des Rahmens ohne Verschiebung (so, wie das LLM ihn schrieb)
     * @param cells   gesetzte Blöcke: weggeräumt wird bei einem Umbau nur, was noch so dasteht
     */
    record Entry<C>(Object world, int x, int y, int z, Cardinal facing, int shiftX, int shiftZ, String planJson,
            String prompt, Instant when, C cells) {

        long minutesAgo(Instant now) {
            return Math.max(0, Duration.between(when, now).toMinutes());
        }
    }

    private final Supplier<Instant> clock;
    private Entry<C> last;

    ClientMemory() {
        this(Instant::now);
    }

    /** Für Tests: eigene Uhr. */
    ClientMemory(Supplier<Instant> clock) {
        this.clock = clock;
    }

    Instant now() {
        return clock.get();
    }

    /** @return was vorher im Gedächtnis stand (für Rückgängig, restore) oder null */
    Entry<C> remember(Entry<C> e) {
        Entry<C> before = last;
        last = e;
        return before;
    }

    /** Rückgängig: Stand vor dem zurückgebauten Bau wiederherstellen; null = vergessen. */
    void restore(Entry<C> before) {
        last = before;
    }

    /** „vergiss“: der nächste Auftrag beginnt frisch. */
    boolean forget() {
        boolean had = last != null;
        last = null;
        return had;
    }

    /**
     * Letzter Bau, wenn der Auftrag ihn fortschreiben darf: gleiche Welt, binnen der Frist, im Umkreis, und kein neues
     * eigenständiges Bauwerk („bau mir ein Haus“ – Vorfall 2026-11-04 13:49 im Plugin, siehe OrderIntent).
     */
    Optional<Entry<C>> recall(Object world, int x, int y, int z, String prompt) {
        Entry<C> m = last;
        if (m == null || world == null || m.world() != world) {
            return Optional.empty();
        }
        if (m.when().plus(TTL).isBefore(clock.get())) {
            last = null;  // abgelaufen bleibt abgelaufen – nichts mehr im Speicher halten
            return Optional.empty();
        }
        double dx = m.x() - x;
        double dy = m.y() - y;
        double dz = m.z() - z;
        if (Math.sqrt(dx * dx + dy * dy + dz * dz) > RADIUS) {
            return Optional.empty();
        }
        if (OrderIntent.isNewStructure(prompt)) {
            return Optional.empty();
        }
        return Optional.of(m);
    }
}
