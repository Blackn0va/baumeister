package com.blackn0va.baumeister.town.work;

import java.util.ArrayDeque;

/**
 * Höchstens limit Blockänderungen aller Bewohner zusammen je Minute (stadtleben.feld-blöcke-je-minute, AK-43).
 *
 * Gleitendes Fenster statt fester Minute: Bei einer festen Minute gingen am Minutenwechsel 2·limit Änderungen in
 * wenigen Sekunden durch. Auch eine verbotene Änderung kostet einen Platz – sonst ließe ein Feld unter WorldGuard-
 * Schutz die Bewohner jeden Takt ein neues Ereignis werfen.
 *
 * Threads: Hauptthread. Fehler: keine.
 */
public final class Rate {

    static final long WINDOW_MS = 60_000L;

    private final ArrayDeque<Long> taken = new ArrayDeque<>();
    private int limit;

    public Rate(int limit) {
        this.limit = Math.max(1, limit);
    }

    public void limit(int perMinute) {
        this.limit = Math.max(1, perMinute);
    }

    /** Einen Platz nehmen; false = in dieser Minute ist schon alles verbraucht. */
    public boolean tryTake(long now) {
        while (!taken.isEmpty() && now - taken.peekFirst() >= WINDOW_MS) {
            taken.removeFirst();
        }
        if (taken.size() >= limit) {
            return false;
        }
        taken.addLast(now);
        return true;
    }

    /** Noch freie Plätze im laufenden Fenster. */
    public int free(long now) {
        return Math.max(0, limit - used(now));
    }

    /** Verbrauchte Plätze im laufenden Fenster (Status). */
    public int used(long now) {
        while (!taken.isEmpty() && now - taken.peekFirst() >= WINDOW_MS) {
            taken.removeFirst();
        }
        return taken.size();
    }
}
