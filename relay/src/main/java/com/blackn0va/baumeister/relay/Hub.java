package com.blackn0va.baumeister.relay;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Verteilt Plugin-Anfragen auf angemeldete Apps (docs/kanal-v1.md, Abschnitt 4).
 *
 * - Je Pin höchstens eine Sitzung; eine neue mit demselben Pin ersetzt die alte (Close 1001 „ersetzt“).
 * - Je Sitzung höchstens max Anfragen gleichzeitig (aus dem hallo der App, ≤ 6); weitere warten in einer Schlange
 *   (≤ 64, in Ankunftsreihenfolge). Die Frist von 30 s je wartender Anfrage überwacht LocalExchange selbst.
 * - Mehrere Apps: Die zuerst angemeldete mit freiem Platz bekommt die nächste Anfrage. Feste Reihenfolge statt
 *   Lastverteilung – mit Vera-Sätzen (gehört/sagen) zählt, welche App antwortet (X-Baumeister-App).
 * - Byte-Budget: Ist es voll, wird die größte offene Antwort abgebrochen (abbruch an die App, RST zum Plugin).
 *
 * Sperrreihenfolge: Hub-Sperre nie während Ein-/Ausgabe und nie beim Aufruf von Session oder LocalExchange halten –
 * Zuteilungen werden unter der Sperre gesammelt und danach zugestellt.
 *
 * Threads: alle Methoden threadsicher. Fehler: keine.
 */
final class Hub {

    private final Limits limits;
    private final ByteBudget budget;
    private final StatusFile status;
    private final ReentrantLock lock = new ReentrantLock();
    private final List<Session> sessions = new ArrayList<>();
    private final ArrayDeque<LocalExchange> waiting = new ArrayDeque<>();
    private final Set<LocalExchange> exchanges = ConcurrentHashMap.newKeySet();
    private volatile int sessionCount;

    private record Assignment(LocalExchange exchange, Session session) {
    }

    Hub(Limits limits, ByteBudget budget, StatusFile status) {
        this.limits = limits;
        this.budget = budget;
        this.status = status;
    }

    boolean hasApp() {
        return sessionCount > 0;
    }

    List<Session> sessions() {
        lock.lock();
        try {
            return List.copyOf(sessions);
        } finally {
            lock.unlock();
        }
    }

    int waitingCount() {
        lock.lock();
        try {
            return waiting.size();
        } finally {
            lock.unlock();
        }
    }

    /** Neue Sitzung nach gelungenem hallo; eine alte mit demselben Pin-Namen geht mit 1001. */
    void attach(Session s) {
        Session old = null;
        List<Assignment> out = new ArrayList<>();
        lock.lock();
        try {
            for (Session x : sessions) {
                if (x.app.equals(s.app)) {
                    old = x;
                    break;
                }
            }
            if (old != null) {
                sessions.remove(old);
            }
            sessions.add(s);
            sessionCount = sessions.size();
            dispatch(out);
        } finally {
            lock.unlock();
        }
        if (old != null) {
            old.close(1001, "ersetzt", "ersetzt");
        }
        writeStatus();
        deliver(out);
    }

    /** Sitzung ist zu (von Session.close). */
    void detach(Session s) {
        boolean removed;
        lock.lock();
        try {
            removed = sessions.remove(s);
            sessionCount = sessions.size();
        } finally {
            lock.unlock();
        }
        if (removed) {
            writeStatus();
        }
    }

    /** Anfrage einreihen; false: Schlange voll (dann RST). */
    boolean enqueue(LocalExchange e) {
        List<Assignment> out = new ArrayList<>();
        lock.lock();
        try {
            if (waiting.size() >= limits.maxQueue()) {
                return false;
            }
            waiting.add(e);
            dispatch(out);
        } finally {
            lock.unlock();
        }
        deliver(out);
        return true;
    }

    /** Aus der Schlange nehmen; false: Sie war schon zugeteilt (die Zuteilung kommt gleich an). */
    boolean withdraw(LocalExchange e) {
        lock.lock();
        try {
            return waiting.remove(e);
        } finally {
            lock.unlock();
        }
    }

    /** Ein Platz der Sitzung ist frei (Antwort komplett, Abbruch, Zuteilung nicht genutzt). */
    void release(Session s) {
        List<Assignment> out = new ArrayList<>();
        lock.lock();
        try {
            if (s.slots > 0) {
                s.slots--;
            }
            dispatch(out);
        } finally {
            lock.unlock();
        }
        deliver(out);
    }

    void register(LocalExchange e) {
        exchanges.add(e);
    }

    void unregister(LocalExchange e) {
        exchanges.remove(e);
    }

    Set<LocalExchange> exchanges() {
        return exchanges;
    }

    /**
     * Antwortstück reservieren. Budget voll: die größte offene Antwort abbrechen (Abschnitt 4) und noch einmal
     * versuchen. false: Die größte war diese hier – sie ist schon abgebrochen, das Stück verfällt.
     */
    boolean reserveResponse(LocalExchange ex, int n) {
        for (int attempt = 0; attempt < 8; attempt++) {
            if (budget.tryReserve(n)) {
                return true;
            }
            LocalExchange victim = largestOpenResponse();
            if (victim == null || victim == ex) {
                break;
            }
            victim.abortForBudget();
        }
        ex.abortForBudget();
        return false;
    }

    /** Anfragestück reservieren; Budget voll: größte offene Antwort abbrechen. false: kein Platz. */
    boolean reserveRequest(int n) {
        for (int attempt = 0; attempt < 8; attempt++) {
            if (budget.tryReserve(n)) {
                return true;
            }
            LocalExchange victim = largestOpenResponse();
            if (victim == null) {
                return false;
            }
            victim.abortForBudget();
        }
        return false;
    }

    private LocalExchange largestOpenResponse() {
        LocalExchange best = null;
        long bestBytes = 0;
        for (LocalExchange e : exchanges) {
            long b = e.buffered();
            if (e.responseOpen() && b > bestBytes) {
                best = e;
                bestBytes = b;
            }
        }
        return best;
    }

    /** Unter der Sperre: wartende Anfragen auf freie Plätze verteilen. */
    private void dispatch(List<Assignment> out) {
        while (!waiting.isEmpty()) {
            Session target = null;
            for (Session s : sessions) {
                if (!s.isClosing() && s.slots < s.max) {
                    target = s;
                    break;
                }
            }
            if (target == null) {
                return;
            }
            target.slots++;
            out.add(new Assignment(waiting.poll(), target));
        }
    }

    private void deliver(List<Assignment> out) {
        for (Assignment a : out) {
            if (!a.exchange().assign(a.session())) {
                release(a.session());
            }
        }
    }

    /**
     * Stand erst unter der Sperre der Statusdatei abfragen: Sonst könnten zwei Threads (Anmelden, Trennen) ihre Stände
     * in vertauschter Reihenfolge schreiben, und die Datei zeigte eine App, die längst weg ist.
     */
    void writeStatus() {
        synchronized (status) {
            List<StatusFile.Entry> apps = new ArrayList<>();
            for (Session s : sessions()) {
                apps.add(new StatusFile.Entry(s.app, s.since));
            }
            status.write(apps);
        }
    }
}
