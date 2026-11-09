package com.blackn0va.baumeister.relay;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Eine Anfrage des Plugins auf 127.0.0.1:18080, von der Annahme bis zur letzten Antwortzeile (docs/kanal-v1.md,
 * Abschnitte 4 und 6). Ein Thread je Verbindung, eine Anfrage je Verbindung.
 *
 * Ablauf: Kopf lesen (≤ 16 KiB, 10 s) → prüfen (eigene 4xx) → einreihen (≤ 64) → auf eine App warten (≤ 30 s, sonst
 * RST) → Body stückweise (≤ 64 KiB) an die App → Antwort: Status und Content-Type der App unverändert, dazu
 * Content-Length, X-Baumeister-App und Connection: close → Ende mit FIN. Alles, was ohne Antwort endet, endet mit RST.
 *
 * Auflegen des Plugins: Während die Antwort aussteht, sieht der Thread alle 200 ms nach, ob am Socket EOF anliegt
 * (Lesen mit 1 ms Frist – Daten wären ein Verstoß gegen „eine Anfrage je Verbindung“). EOF → abbruch an die App
 * (Abschnitt 6: deren Server hört dann auf zu rechnen). Solange der Body noch im Socket liegt, wird nicht
 * nachgesehen – das Lesen nähme Body-Bytes weg; wer dann auflegt, fällt beim Lesen des Bodys auf.
 *
 * Nie 502/503/504: Was die App nicht beantwortet, endet ohne Antwort. Der LlmClient des Plugins wiederholt bei 502/503/
 * 504 die ganze Generierung (LlmClient.retryable); bei einem Abriss gilt dasselbe wie heute beim SSH-Tunnel.
 *
 * Threads: der eigene; assign/head/piece/lost kommen aus Hub- bzw. Sitzungs-Threads und reihen nur ein.
 * Fehler: werden geloggt; die Verbindung endet dann mit RST.
 */
final class LocalExchange implements Runnable {

    private static final Logger LOG = RelayLog.LOG;
    private static final long PROBE_NANOS = 200_000_000L;
    /** Höchstens so lange und so viele Bytes nach einer eigenen 4xx abwarten, damit das Plugin die Antwort liest. */
    private static final long DRAIN_NANOS = 2_000_000_000L;
    private static final long DRAIN_BYTES = 4L * 1024 * 1024;

    private sealed interface Event permits Assigned, Head, Piece, Lost {
    }

    private record Assigned(Session session) implements Event {
    }

    private record Head(int status, String contentType, long length, String app) implements Event {
    }

    private record Piece(byte[] buffer, int offset, int length) implements Event {
    }

    private record Lost(String reason) implements Event {
    }

    private final Relay relay;
    private final Hub hub;
    private final ByteBudget budget;
    private final Limits limits;
    private final Socket socket;
    private final Runnable onEnd;
    private final long started = System.nanoTime();

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition arrived = lock.newCondition();
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private boolean closed;
    private String lostReason;

    private final AtomicLong buffered = new AtomicLong();
    private volatile boolean headSeen;
    private volatile Session session;
    private volatile long id;
    private volatile long writingSince;

    /** Nur der eigene Thread: */
    private BufferedInputStream in;
    private OutputStream out;
    private boolean slotHeld;
    private boolean completed;
    private String method = "-";
    private String path = "-";
    private int status;
    private long bytesIn;
    private long bytesOut;

    LocalExchange(Relay relay, Socket socket, Runnable onEnd) {
        this.relay = relay;
        this.hub = relay.hub();
        this.budget = relay.budget();
        this.limits = relay.limits();
        this.socket = socket;
        this.onEnd = onEnd;
    }

    // ------------------------------------------------------------------ von anderen Threads

    /** Hub teilt eine Sitzung zu. false: Austausch schon zu Ende – der Hub gibt den Platz zurück. */
    boolean assign(Session s) {
        return offer(new Assigned(s), 0);
    }

    /** Antwortkopf der App. */
    boolean head(int statusCode, String contentType, long length, String app) {
        boolean ok = offer(new Head(statusCode, contentType, length, app), 0);
        if (ok) {
            headSeen = true;
        }
        return ok;
    }

    /** Antwortstück; die Bytes hat der Aufrufer schon im Budget reserviert (bei false gibt er sie frei). */
    boolean piece(Message.Chunk c) {
        return offer(new Piece(c.buffer(), c.offset(), c.length()), c.length());
    }

    /** Antwort verloren (App bricht ab, Sitzung weg, Budget, Frist): RST zum Plugin, gepufferte Bytes frei. */
    void lost(String reason) {
        long dropped = 0;
        List<Session> unusedSlots = new ArrayList<>();
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            lostReason = reason;
            for (Event e : events) {
                if (e instanceof Piece p) {
                    dropped += p.length();
                } else if (e instanceof Assigned a) {
                    unusedSlots.add(a.session());
                }
            }
            events.clear();
            arrived.signalAll();
        } finally {
            lock.unlock();
        }
        drop(dropped);
        for (Session s : unusedSlots) {
            hub.release(s);
        }
        // Weckt einen blockierten Schreib- oder Lesevorgang des eigenen Threads.
        Io.reset(socket);
    }

    /** Budget voll und diese Antwort ist die größte: abbruch an die App, RST zum Plugin. */
    void abortForBudget() {
        abort("budget");
    }

    /** Das Relay gibt die Anfrage auf (Budget, Plugin liest nicht): abbruch an die App, RST zum Plugin. */
    void abort(String reason) {
        Session s = session;
        long i = id;
        if (s != null && i != 0) {
            s.abort(i);
        }
        relay.throttle().reject("abbruch_" + reason, "relay | anfrage_abgebrochen | grund=" + reason + " pfad="
                + path + " gepuffert_kb=" + buffered.get() / 1024 + " budget_kb=" + budget.limit() / 1024);
        lost(reason);
    }

    /** Gepufferte Antwortbytes (reserviert, noch nicht ans Plugin geschrieben). */
    long buffered() {
        return buffered.get();
    }

    /** Antwort hat begonnen und läuft noch – Kandidat, wenn das Budget voll ist. */
    boolean responseOpen() {
        lock.lock();
        try {
            return headSeen && !closed;
        } finally {
            lock.unlock();
        }
    }

    /** 0 = schreibt gerade nicht; sonst Beginn des laufenden Schreibvorgangs ans Plugin. */
    long writingSince() {
        return writingSince;
    }

    private boolean offer(Event e, long bytes) {
        lock.lock();
        try {
            if (closed) {
                return false;
            }
            events.add(e);
            if (bytes > 0) {
                buffered.addAndGet(bytes);
            }
            arrived.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    private void drop(long bytes) {
        if (bytes > 0) {
            buffered.addAndGet(-bytes);
            budget.release(bytes);
        }
    }

    // ------------------------------------------------------------------ eigener Thread

    @Override
    public void run() {
        String outcome = "fehler";
        hub.register(this);
        try {
            outcome = serve();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome = "unterbrochen";
        } catch (RuntimeException e) {
            // Thread-Grenze: Ein Fehler im Relay darf genau diese eine Anfrage kosten, nicht den Prozess.
            outcome = "fehler_" + e.getClass().getSimpleName();
            LOG.log(Level.SEVERE, "relay | anfrage_fehler | pfad=" + path, e);
        } finally {
            finish();
        }
        report(outcome);
    }

    private String serve() throws InterruptedException {
        LocalRequest req;
        Future<?> headerDeadline = Io.schedule(relay.scheduler(), () -> Io.reset(socket),
                limits.localHeader().toMillis());
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout((int) limits.localHeader().toMillis());
            in = new BufferedInputStream(socket.getInputStream(), 8192);
            out = socket.getOutputStream();
            HttpHead head = HttpHead.read(in, LocalRequest.MAX_HEADER, 431);
            method = head.method();
            path = loggable(head.target());
            req = LocalRequest.check(head, relay.localPort());
        } catch (HttpFailure f) {
            headerDeadline.cancel(false);
            refuse(f);
            return "abgewiesen_" + f.status() + "_" + f.code();
        } catch (IOException e) {
            return "lesen_abgebrochen";
        } finally {
            headerDeadline.cancel(false);
        }
        if (!hub.enqueue(this)) {
            return "warteschlange_voll";
        }
        Session s = awaitAssignment(req);
        if (s == null) {
            return lostReason != null ? lostReason : "keine_app";
        }
        String failed = send(s, req);
        if (failed != null) {
            return failed;
        }
        return receive(s);
    }

    /** Wartet auf eine App. null: Frist (30 s), Plugin hat aufgelegt oder Austausch beendet. */
    private Session awaitAssignment(LocalRequest req) throws InterruptedException {
        long deadline = System.nanoTime() + limits.queueWait().toNanos();
        long nextProbe = System.nanoTime() + PROBE_NANOS;
        boolean giveUp = false;
        while (true) {
            long now = System.nanoTime();
            if (!giveUp && req.length() == 0 && now - nextProbe >= 0) {
                if (hungUp()) {
                    lostReason = "plugin_aufgelegt";
                    giveUp = true;
                }
                nextProbe = now + PROBE_NANOS;
            }
            if (!giveUp && deadline - now <= 0) {
                lostReason = "keine_app_30s";
                giveUp = true;
            }
            if (giveUp && hub.withdraw(this)) {
                return null;
            }
            // giveUp und withdraw scheiterte: Die Zuteilung ist schon unterwegs – abholen und Platz zurückgeben.
            Event e = poll(giveUp ? 1_000_000_000L : Math.min(deadline - now, nextProbe - now));
            if (e instanceof Assigned a) {
                slotHeld = true;
                session = a.session();
                if (giveUp) {
                    return null;
                }
                return a.session();
            }
            if (e instanceof Lost l) {
                lostReason = l.reason();
                return null;
            }
            if (e != null) {
                throw new IllegalStateException("Antwort vor der Zuteilung");
            }
        }
    }

    /** Anfrage stückweise an die App. null: alles gesendet; sonst der Grund des Abbruchs. */
    private String send(Session s, LocalRequest req) throws InterruptedException {
        long deadline = System.nanoTime() + limits.queueWait().toNanos();
        int first = (int) Math.min(req.length(), Codec.MAX_CHUNK);
        if (first > 0 && !hub.reserveRequest(first)) {
            return "budget";
        }
        byte[] chunk = new byte[first];
        try {
            readBody(chunk, first);
        } catch (IOException e) {
            budget.release(first);
            return "plugin_aufgelegt";
        }
        long i = s.begin(this, req, chunk, first, deadline);
        if (i == 0) {
            budget.release(first);
            return s.isClosing() ? "verbindung_weg" : "ausgang_voll";
        }
        id = i;
        // Ab hier gehört der Platz der offenen Anfrage in der Sitzung (frei bei Antwortende oder Abbruch).
        slotHeld = false;
        bytesIn = first;
        long remaining = req.length() - first;
        while (remaining > 0) {
            int n = (int) Math.min(remaining, Codec.MAX_CHUNK);
            if (!hub.reserveRequest(n)) {
                s.abort(i);
                return "budget";
            }
            byte[] c = new byte[n];
            try {
                readBody(c, n);
            } catch (IOException e) {
                budget.release(n);
                s.abort(i);
                return "plugin_aufgelegt";
            }
            if (!s.data(i, c, n, deadline)) {
                budget.release(n);
                s.abort(i);
                return lostOr("anfrage_nicht_mehr_offen");
            }
            remaining -= n;
            bytesIn += n;
        }
        return null;
    }

    private void readBody(byte[] b, int n) throws IOException {
        socket.setSoTimeout((int) limits.localRead().toMillis());
        if (in.readNBytes(b, 0, n) != n) {
            throw new EOFException("Body kürzer als Content-Length");
        }
    }

    /** Antwort der App ans Plugin. Rückgabe: Ergebnis fürs Log. */
    private String receive(Session s) throws InterruptedException {
        long deadline = System.nanoTime() + limits.response().toNanos();
        long nextProbe = System.nanoTime();
        long expected = -1;
        long written = 0;
        while (true) {
            long now = System.nanoTime();
            if (deadline - now <= 0) {
                s.abort(id);
                return "frist";
            }
            if (now - nextProbe >= 0) {
                if (hungUp()) {
                    s.abort(id);
                    return "plugin_aufgelegt";
                }
                nextProbe = now + PROBE_NANOS;
            }
            Event e = poll(Math.min(deadline - now, nextProbe - now));
            if (e == null) {
                continue;
            }
            switch (e) {
                case Head h -> {
                    status = h.status();
                    expected = h.length();
                    if (!write(head(h))) {
                        s.abort(id);
                        return "plugin_aufgelegt";
                    }
                    if (expected == 0) {
                        completed = true;
                        return "ok";
                    }
                }
                case Piece p -> {
                    boolean ok;
                    try {
                        ok = write(p.buffer(), p.offset(), p.length());
                    } finally {
                        drop(p.length());
                    }
                    if (!ok) {
                        s.abort(id);
                        return lostOr("plugin_aufgelegt");
                    }
                    written += p.length();
                    bytesOut = written;
                    if (written == expected) {
                        completed = true;
                        return "ok";
                    }
                }
                case Lost l -> {
                    return l.reason();
                }
                case Assigned a -> {
                    // Kann nicht vorkommen (eine Zuteilung je Anfrage) – Platz trotzdem zurück.
                    hub.release(a.session());
                }
            }
        }
    }

    private static byte[] head(Head h) {
        StringBuilder b = new StringBuilder(160).append("HTTP/1.1 ").append(h.status()).append(' ')
                .append(HttpStatus.phrase(h.status())).append("\r\n");
        if (h.contentType() != null) {
            b.append("Content-Type: ").append(h.contentType()).append("\r\n");
        }
        b.append("Content-Length: ").append(h.length()).append("\r\n")
                .append("X-Baumeister-App: ").append(h.app()).append("\r\n")
                .append("Connection: close\r\n\r\n");
        return b.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private boolean write(byte[] b) {
        return write(b, 0, b.length);
    }

    /** false: Plugin weg (oder der Socket wurde von lost() zurückgesetzt). */
    private boolean write(byte[] b, int off, int len) {
        writingSince = System.nanoTime() | 1;
        try {
            out.write(b, off, len);
            out.flush();
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            writingSince = 0;
        }
    }

    /**
     * Hat das Plugin aufgelegt? Lesen mit 1 ms Frist: Zeitüberschreitung = Verbindung steht. EOF, RST oder Daten =
     * aufgelegt bzw. Verstoß gegen „eine Anfrage je Verbindung“ (beides beendet die Anfrage).
     */
    private boolean hungUp() {
        try {
            socket.setSoTimeout(1);
            in.read();
            return true;
        } catch (SocketTimeoutException e) {
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private Event poll(long nanos) throws InterruptedException {
        lock.lock();
        try {
            long left = nanos;
            while (events.isEmpty()) {
                if (closed) {
                    return new Lost(lostReason != null ? lostReason : "geschlossen");
                }
                if (left <= 0) {
                    return null;
                }
                left = arrived.awaitNanos(left);
            }
            return events.poll();
        } finally {
            lock.unlock();
        }
    }

    private String lostOr(String fallback) {
        lock.lock();
        try {
            return lostReason != null ? lostReason : fallback;
        } finally {
            lock.unlock();
        }
    }

    /** Eigene 4xx des Relays, dann den Rest der Anfrage kurz abwarten (sonst sähe das Plugin RST statt 4xx). */
    private void refuse(HttpFailure f) {
        status = f.status();
        relay.throttle().reject("lokal_" + f.status(), "relay | lokal_abgewiesen | status=" + f.status() + " grund="
                + f.code() + " methode=" + method + " pfad=" + path);
        if (!write(f.status() == 405 ? HttpStatus.empty(405, "Allow: GET, POST") : HttpStatus.empty(f.status(), null))) {
            return;
        }
        completed = true;
    }

    /** Ende: Puffer frei, Platz zurück, offene Anfrage abbrechen, Socket zu (FIN nach Erfolg, sonst RST). */
    private void finish() {
        long dropped = 0;
        List<Session> unusedSlots = new ArrayList<>();
        lock.lock();
        try {
            closed = true;
            for (Event e : events) {
                if (e instanceof Piece p) {
                    dropped += p.length();
                } else if (e instanceof Assigned a) {
                    unusedSlots.add(a.session());
                }
            }
            events.clear();
        } finally {
            lock.unlock();
        }
        drop(dropped);
        for (Session s : unusedSlots) {
            hub.release(s);
        }
        Session s = session;
        if (slotHeld && s != null) {
            hub.release(s);
            slotHeld = false;
        }
        if (s != null && id != 0) {
            s.abort(id);
        }
        hub.withdraw(this);
        hub.unregister(this);
        try {
            if (completed) {
                lingerClose();
            } else {
                Io.reset(socket);
            }
        } finally {
            onEnd.run();
        }
    }

    /**
     * Sauberes Ende nach einer Antwort: FIN senden und kurz auf das FIN des Plugins warten (höchstens 2 s bzw. 4 MiB
     * Rest, z. B. ein nicht gelesener Body nach 413). Sofortiges close() mit ungelesenen Bytes schickte RST, und das
     * Plugin verlöre womöglich die Antwort.
     */
    private void lingerClose() {
        try {
            socket.shutdownOutput();
            long until = System.nanoTime() + DRAIN_NANOS;
            long drained = 0;
            byte[] sink = new byte[8192];
            while (drained < DRAIN_BYTES) {
                long left = until - System.nanoTime();
                if (left <= 0) {
                    break;
                }
                socket.setSoTimeout((int) Math.max(1, left / 1_000_000));
                int n = in.read(sink);
                if (n < 0) {
                    break;
                }
                drained += n;
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "relay | nachlauf_beendet", e);
        } finally {
            Io.close(socket);
        }
    }

    private void report(String outcome) {
        long ms = (System.nanoTime() - started) / 1_000_000;
        Session s = session;
        String app = s != null ? s.app : "-";
        if (outcome.equals("ok")) {
            LOG.info("relay | anfrage | app=" + app + " id=" + id + " methode=" + method + " pfad=" + path + " status="
                    + status + " rein=" + bytesIn + " raus=" + bytesOut + " ms=" + ms);
        } else if (outcome.startsWith("abgewiesen_")) {
            LOG.log(Level.FINE, () -> "relay | anfrage_abgewiesen | " + outcome);
        } else {
            relay.throttle().reject("anfrage_" + outcome, "relay | anfrage_ohne_antwort | app=" + app + " id=" + id
                    + " methode=" + method + " pfad=" + path + " grund=" + outcome + " ms=" + ms);
        }
    }

    /** Pfad fürs Log: ohne Abfrage (Spielernamen), nur harmlose Zeichen, höchstens 80. */
    static String loggable(String target) {
        String p = PathRules.withoutQuery(target);
        StringBuilder b = new StringBuilder(Math.min(p.length(), 80));
        for (int i = 0; i < p.length() && b.length() < 80; i++) {
            char c = p.charAt(i);
            b.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "/_-.".indexOf(c) >= 0
                    ? c : '?');
        }
        return b.toString();
    }
}
