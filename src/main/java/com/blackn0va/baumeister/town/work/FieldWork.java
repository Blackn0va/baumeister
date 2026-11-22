package com.blackn0va.baumeister.town.work;

/**
 * Führt eine Arbeit (Task) Block für Block aus – je Aufruf höchstens eine Änderung (AK-43).
 *
 * Reihenfolge je Änderung, jede Stufe fail-closed:
 * 1. Welt neu lesen: Steht dort nicht mehr, was der Planer sah (Spieler hat geerntet, Kiste hingestellt), STALE.
 * 2. Erlaubnisliste und Sperre (Safety) noch einmal – zwischen Planen und Ausführen kann ein Hebel dazugekommen sein.
 * 3. Rate (Platz nehmen, sonst WAIT).
 * 4. Hands: Ereignis an die Schutz-Plugins und nur bei Erlaubnis die Änderung. Verboten → DENIED, nichts geändert.
 *
 * Threads: Hauptthread. Fehler: keine; was Hands wirft, geht an den Aufrufer (TownLife fängt es an der Takt-Grenze).
 */
public final class FieldWork {

    /** Was TownLife mit der Welt tun darf – im Plugin über Bukkit-Ereignisse, im Test ein Gitter. */
    public interface Hands {
        /**
         * Abbauen über ein BlockBreakEvent im Namen des Spielers, für den der Bewohner arbeitet. true = abgebaut (Ernte
         * eingesammelt oder liegen gelassen); false = Ereignis abgesagt, Block unverändert.
         */
        boolean breakBlock(Task.Change c);

        /** Setzen, dann BlockPlaceEvent; abgesagt → alter Zustand zurück und false. */
        boolean place(Task.Change c);
    }

    public enum Result {
        /** Eine Änderung geschehen, weitere folgen. */
        APPLIED,
        /** Letzte Änderung geschehen – Arbeit fertig. */
        DONE,
        /** Rate in dieser Minute erschöpft – später weiter. */
        WAIT,
        /** Schutz-Plugin hat abgelehnt – Arbeit abbrechen, Block sperren. */
        DENIED,
        /** Welt hat sich geändert oder die Änderung ist nicht (mehr) erlaubt – Arbeit verwerfen. */
        STALE
    }

    private FieldWork() {
    }

    /** Folgt auf Änderung i ein Setzen an derselben Stelle (Ernte → Saat)? TownLife führt beides im selben Takt aus. */
    public static boolean pairedWithNext(Task t, int i) {
        if (i < 0 || i + 1 >= t.changes().size()) {
            return false;
        }
        Task.Change a = t.changes().get(i);
        Task.Change b = t.changes().get(i + 1);
        return a.kind() == Task.Kind.BREAK && b.kind() == Task.Kind.PLACE && a.x() == b.x() && a.y() == b.y()
                && a.z() == b.z();
    }

    /** index[0] = nächste Änderung der Arbeit; wird bei APPLIED/DONE weitergezählt. */
    public static Result step(Task t, int[] index, Plot p, Hands hands, Rate rate, long now) {
        if (index[0] >= t.changes().size()) {
            return Result.DONE;
        }
        Task.Change c = t.changes().get(index[0]);
        if (!p.type(c.x(), c.y(), c.z()).equals(c.expect())) {
            return Result.STALE;
        }
        if (!Safety.allowed(c) || Safety.nearForbidden(p, c.x(), c.y(), c.z())) {
            return Result.STALE;
        }
        if (c.kind() == Task.Kind.PLACE && c.type().endsWith("_sapling")
                && !Safety.GROUND.contains(p.type(c.x(), c.y() - 1, c.z()))) {
            return Result.STALE;
        }
        // Ernte und Saat derselben Stelle nur zusammen: Reicht die Minute nur noch für den Abbau, wartet er. Live
        // gemessen (T-035, testserver): Fiel der Abbau auf den letzten Platz der Minute und ging der Bauer dann in die
        // Mittagspause, blieb die Stelle leer – 1 von 49 Weizenfeldern ohne Saat.
        if (pairedWithNext(t, index[0]) && rate.free(now) < 2) {
            return Result.WAIT;
        }
        if (!rate.tryTake(now)) {
            return Result.WAIT;
        }
        boolean ok = c.kind() == Task.Kind.BREAK ? hands.breakBlock(c) : hands.place(c);
        if (!ok) {
            return Result.DENIED;
        }
        index[0]++;
        return index[0] >= t.changes().size() ? Result.DONE : Result.APPLIED;
    }
}
