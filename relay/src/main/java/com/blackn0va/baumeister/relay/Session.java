package com.blackn0va.baumeister.relay;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * Eine angemeldete App: WebSocket über TLS nach Kanal v1 (docs/kanal-v1.md, Abschnitte 2–4).
 *
 * Zwei Threads: Der Leser (der Thread, der die Verbindung angenommen hat) liest Rahmen der App; der Schreiber ist der
 * EINZIGE, der auf den Socket schreibt. Alle anderen reihen Rahmen nur ein und blockieren nie am Socket – sonst
 * hielte eine App, die nicht mehr liest, über eine volle Sendepuffer-Schlange jeden Thread fest, der ihr etwas
 * schicken will (auch den Takt). Hängt der Schreiber selbst länger als die Stille-Frist, schließt der Takt den Socket.
 *
 * Anfrage-IDs vergibt die Sitzung beim Einreihen unter der Sperre: Die Reihenfolge auf der Leitung ist damit die
 * Reihenfolge der IDs (aufsteigend je Verbindung, Abschnitt 3).
 *
 * Nach einem abbruch (in welche Richtung auch immer) geht zu dieser ID nichts mehr raus: Der Schreiber überspringt
 * eingereihte Rahmen, deren Anfrage nicht mehr offen ist. abbruch selbst geht nur raus, wenn die anfrage schon auf der
 * Leitung war – sonst wüsste die App von nichts. Was danach noch zu der ID eintrifft, wird gezählt und verworfen.
 *
 * Endet die Sitzung (Protokollfehler, Stille, Close, ersetzt, Pin entzogen), verlieren alle offenen Anfragen ihre
 * Antwort: Das Plugin bekommt RST, nie eine erfundene 502/503/504 (Abschnitt 6).
 *
 * Threads: Leser, Schreiber, beliebige Einreiher; Zustand unter lock. slots gehört dem Hub (dessen Sperre).
 * Fehler: Ein-/Ausgabe- und Protokollfehler beenden die Sitzung, sie werden nie nach außen geworfen.
 */
final class Session {

    private static final Logger LOG = RelayLog.LOG;
    /** Höchstens so viele Bytes Anfrage-Rahmen warten im Ausgang; abbruch, Ping, Pong und Close zählen nicht. */
    static final int OUT_LIMIT = 256 * 1024;
    private static final byte[] EMPTY = new byte[0];
    /** So lange darf der Schreiber für den Close-Rahmen brauchen, dann schließt der Socket ohne ihn. */
    private static final Duration CLOSE_GRACE = Duration.ofSeconds(2);

    /** Eingereihter Rahmen. id != 0: gehört zu dieser Anfrage und entfällt, wenn sie nicht mehr offen ist. */
    private record Out(byte[] frame, long id, boolean request, long reserved) {
    }

    /** Eine Anfrage, deren Antwort noch aussteht. */
    static final class Open {
        final long id;
        final LocalExchange exchange;
        /** anfrage ist auf der Leitung (bzw. gerade dabei) – unter lock. */
        boolean onWire;
        /** Ab hier nur der Leser-Thread. */
        boolean answered;
        long expected;
        long received;

        Open(long id, LocalExchange exchange) {
            this.id = id;
            this.exchange = exchange;
        }
    }

    final String app;
    /** Voller Pin – nur zum Abgleich mit der Konfiguration, nie ins Log. */
    final String pin;
    final String peer;
    final int max;
    final String address;
    final Instant since = Instant.now();
    /** Plätze in Benutzung – gehört dem Hub (nur unter dessen Sperre). */
    int slots;

    private final Socket raw;
    private final Frames.Reader reader;
    private final OutputStream out;
    private final Hub hub;
    private final ByteBudget budget;
    private final java.util.concurrent.ScheduledExecutorService scheduler;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition wake = lock.newCondition();
    private final Condition space = lock.newCondition();
    private final ArrayDeque<Out> queue = new ArrayDeque<>();
    private final Map<Long, Open> open = new HashMap<>();
    private long queued;
    private byte[] pong;
    private boolean ping;
    private byte[] closeFrame;
    private boolean closing;
    private long nextId = 1;

    private volatile boolean closingFlag;
    private volatile long lastFrame;
    private volatile long lastPing;
    private volatile long writingSince;
    /** Verworfene Nachrichten zu unbekannten IDs (Abschnitt 3) – nur der Leser zählt. */
    private long discarded;
    private final AtomicBoolean logged = new AtomicBoolean();

    Session(String app, String pin, String peer, int max, String address, Socket raw, Frames.Reader reader,
            OutputStream out, Hub hub, ByteBudget budget, java.util.concurrent.ScheduledExecutorService scheduler) {
        this.app = app;
        this.pin = pin;
        this.peer = peer;
        this.max = max;
        this.address = address;
        this.raw = raw;
        this.reader = reader;
        this.out = out;
        this.hub = hub;
        this.budget = budget;
        this.scheduler = scheduler;
        long now = System.nanoTime();
        this.lastFrame = now;
        this.lastPing = now;
    }

    /** Schreiber starten; danach darf eingereiht werden. */
    void start() {
        Io.start("relay-schreiber-" + app, this::writeLoop);
    }

    boolean isClosing() {
        return closingFlag;
    }

    long lastFrame() {
        return lastFrame;
    }

    long lastPing() {
        return lastPing;
    }

    /** 0 = der Schreiber wartet; sonst Beginn des laufenden Schreibvorgangs (System.nanoTime). */
    long writingSince() {
        return writingSince;
    }

    int openCount() {
        lock.lock();
        try {
            return open.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Neue Anfrage: ID vergeben, eintragen, anfrage-Rahmen samt erstem Stück einreihen. Wartet höchstens bis deadline
     * auf Platz im Ausgang. 0: Sitzung schließt oder kein Platz – reserved gibt dann der Aufrufer frei.
     */
    long begin(LocalExchange ex, LocalRequest req, byte[] chunk, int n, long deadline) throws InterruptedException {
        boolean exhausted = false;
        lock.lock();
        try {
            if (!awaitSpace(deadline)) {
                return 0;
            }
            if (nextId > Codec.MAX_ID) {
                exhausted = true;
                return 0;
            }
            long id = nextId++;
            byte[] frame = Frames.server(Frames.BINARY, Codec.encode(new Message.Request(id, req.method(),
                    req.target(), req.contentType(), req.length(), new Message.Chunk(chunk, 0, n))));
            open.put(id, new Open(id, ex));
            queue.add(new Out(frame, id, true, n));
            queued += frame.length;
            wake.signal();
            return id;
        } finally {
            lock.unlock();
            if (exhausted) {
                close(1001, "ids_aufgebraucht", "ids_aufgebraucht");
            }
        }
    }

    /** Weiteres Stück der Anfrage. false: Anfrage nicht mehr offen, Sitzung zu oder Frist – reserved beim Aufrufer. */
    boolean data(long id, byte[] chunk, int n, long deadline) throws InterruptedException {
        lock.lock();
        try {
            if (!awaitSpace(deadline) || !open.containsKey(id)) {
                return false;
            }
            byte[] frame = Frames.server(Frames.BINARY, Codec.encode(new Message.Data(id,
                    new Message.Chunk(chunk, 0, n))));
            queue.add(new Out(frame, id, false, n));
            queued += frame.length;
            wake.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    private boolean awaitSpace(long deadline) throws InterruptedException {
        while (!closing && queued >= OUT_LIMIT) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return false;
            }
            space.awaitNanos(left);
        }
        return !closing;
    }

    /**
     * Das Relay bricht ab (Plugin aufgelegt, Frist, Budget). abbruch geht nur raus, wenn die anfrage schon auf der
     * Leitung ist. false: Die Anfrage war nicht (mehr) offen.
     */
    boolean abort(long id) {
        Open f;
        lock.lock();
        try {
            f = open.remove(id);
            if (f == null) {
                return false;
            }
            if (f.onWire && !closing) {
                byte[] frame = Frames.server(Frames.BINARY, Codec.encode(new Message.Abort(id)));
                queue.add(new Out(frame, 0, false, 0));
                queued += frame.length;
                wake.signal();
            }
        } finally {
            lock.unlock();
        }
        hub.release(this);
        return true;
    }

    /** Vom Takt alle 20 s (Abschnitt 4). */
    void ping() {
        lock.lock();
        try {
            if (!closing) {
                ping = true;
                wake.signal();
            }
        } finally {
            lock.unlock();
        }
        lastPing = System.nanoTime();
    }

    /** Schreiber-Thread: der einzige, der auf den Socket schreibt. */
    private void writeLoop() {
        boolean graceful = false;
        try {
            while (true) {
                byte[] frame;
                long reserved = 0;
                boolean last = false;
                lock.lock();
                try {
                    while (closeFrame == null && pong == null && !ping && queue.isEmpty() && !closing) {
                        wake.await();
                    }
                    if (closeFrame != null) {
                        frame = closeFrame;
                        closeFrame = null;
                        last = true;
                    } else if (closing) {
                        return;
                    } else if (pong != null) {
                        frame = Frames.server(Frames.PONG, pong);
                        pong = null;
                    } else if (ping) {
                        frame = Frames.server(Frames.PING, EMPTY);
                        ping = false;
                    } else {
                        Out o = queue.poll();
                        queued -= o.frame().length;
                        space.signalAll();
                        if (o.id() != 0) {
                            Open f = open.get(o.id());
                            if (f == null) {
                                // Anfrage abgebrochen, während der Rahmen wartete: nichts mehr zu dieser ID senden.
                                budget.release(o.reserved());
                                continue;
                            }
                            if (o.request()) {
                                f.onWire = true;
                            }
                        }
                        frame = o.frame();
                        reserved = o.reserved();
                    }
                } finally {
                    lock.unlock();
                }
                writingSince = System.nanoTime() | 1;
                try {
                    out.write(frame);
                    out.flush();
                } finally {
                    writingSince = 0;
                    budget.release(reserved);
                }
                if (last) {
                    // Nach dem Close ein FIN statt RST: Ein RST ließe die App den Close-Rahmen womöglich nie lesen
                    // (Windows verwirft bei RST ungelesene Daten). Den Socket schließt der Riegel aus close().
                    graceful = true;
                    raw.shutdownOutput();
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close(0, "", "schreiber_unterbrochen");
        } catch (IOException e) {
            close(0, "", "schreiben_" + e.getClass().getSimpleName());
        } finally {
            if (!graceful) {
                Io.reset(raw);
            }
        }
    }

    /** Leser-Schleife; kehrt zurück, wenn die Sitzung endet. */
    void readLoop() {
        int code = 0;
        String reason = "";
        String why = "?";
        try {
            while (true) {
                Frames.Frame f = reader.read();
                lastFrame = System.nanoTime();
                switch (f.opcode()) {
                    case Frames.PING -> {
                        lock.lock();
                        try {
                            if (!closing) {
                                // Nur das jüngste Ping bekommt sein Pong (RFC 6455, 5.5.3) – eine App, die Pings
                                // flutet und nicht liest, kann so keine Schlange aufbauen.
                                pong = f.payload();
                                wake.signal();
                            }
                        } finally {
                            lock.unlock();
                        }
                    }
                    case Frames.PONG -> {
                        // Lebenszeichen, auch unaufgefordert: Die Host-App (WinHTTP) kann keine Pings senden und
                        // schickt alle 20 s ein Pong. lastFrame ist schon gesetzt – jeder Rahmen zählt für die 45 s.
                    }
                    case Frames.CLOSE -> {
                        Frames.checkClose(f.payload());
                        code = 1000;
                        why = "app_schließt_" + Frames.closeCode(f.payload()) + "_" + loggable(Frames.closeReason(
                                f.payload()));
                        return;
                    }
                    default -> handle(Codec.decode(f.payload(), Codec.Direction.APP_TO_RELAY));
                }
            }
        } catch (ProtocolException e) {
            code = e.reason().closeCode();
            reason = e.reason().code();
            why = "protokoll_" + reason;
        } catch (SocketTimeoutException e) {
            why = "stille";
        } catch (EOFException e) {
            why = "verbindung_zu";
        } catch (IOException e) {
            why = "lesen_" + e.getClass().getSimpleName();
        } finally {
            close(code, reason, why);
        }
    }

    /** Close-Grund der App fürs Log: nur [a-z0-9_], höchstens 40 Zeichen (fremder Text, ASVS V7.3.1). */
    static String loggable(String reason) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < reason.length() && b.length() < 40; i++) {
            char c = reason.charAt(i);
            b.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' ? c : '?');
        }
        return b.isEmpty() ? "-" : b.toString();
    }

    private void handle(Message m) throws ProtocolException {
        switch (m) {
            case Message.Hello h -> throw new ProtocolException(Reason.HELLO_DUPLICATE);
            case Message.Request r -> throw new ProtocolException(Reason.TYPE_UNKNOWN);
            case Message.Response r -> response(r);
            case Message.Data d -> data(d);
            case Message.Abort a -> appAbort(a.id());
        }
    }

    private void response(Message.Response r) throws ProtocolException {
        Open f;
        boolean done;
        lock.lock();
        try {
            f = open.get(r.id());
            if (f == null) {
                discarded++;
                return;
            }
            if (f.answered) {
                throw new ProtocolException(Reason.ID_INVALID);
            }
            f.answered = true;
            f.expected = r.length();
            f.received = r.chunk().length();
            done = f.received == f.expected;
            if (done) {
                open.remove(r.id());
            }
        } finally {
            lock.unlock();
        }
        if (done) {
            hub.release(this);
        }
        if (!f.exchange.head(r.status(), r.contentType(), r.length(), app)) {
            // Plugin-Seite schon zu: Der App sagen, dass niemand mehr zuhört.
            abort(r.id());
            return;
        }
        deliver(f, r.chunk());
    }

    private void data(Message.Data d) throws ProtocolException {
        Open f;
        boolean done;
        lock.lock();
        try {
            f = open.get(d.id());
            if (f == null) {
                discarded++;
                return;
            }
            if (!f.answered) {
                // daten vor der antwort: Die App vertauscht Anfrage- und Antwort-Richtung.
                throw new ProtocolException(Reason.ID_INVALID);
            }
            if (f.received + d.chunk().length() > f.expected) {
                throw new ProtocolException(Reason.LENGTH_INVALID);
            }
            f.received += d.chunk().length();
            done = f.received == f.expected;
            if (done) {
                open.remove(d.id());
            }
        } finally {
            lock.unlock();
        }
        if (done) {
            hub.release(this);
        }
        deliver(f, d.chunk());
    }

    private void deliver(Open f, Message.Chunk chunk) {
        if (chunk.length() == 0) {
            return;
        }
        if (!hub.reserveResponse(f.exchange, chunk.length())) {
            return;
        }
        if (!f.exchange.piece(chunk)) {
            budget.release(chunk.length());
        }
    }

    private void appAbort(long id) {
        Open f;
        lock.lock();
        try {
            f = open.remove(id);
        } finally {
            lock.unlock();
        }
        if (f == null) {
            discarded++;
            return;
        }
        hub.release(this);
        f.exchange.lost("app_abbruch");
    }

    /**
     * Sitzung beenden; mehrfacher Aufruf ist harmlos (der erste zählt). code 0: ohne Close-Rahmen (Gegenseite tot),
     * sonst Close(code, reason). why: Grund fürs Log.
     */
    void close(int code, String reason, String why) {
        List<Open> lost;
        long release = 0;
        lock.lock();
        try {
            if (closing) {
                return;
            }
            closing = true;
            closingFlag = true;
            if (code > 0) {
                closeFrame = Frames.server(Frames.CLOSE, Frames.closePayload(code, reason));
            }
            lost = new ArrayList<>(open.values());
            open.clear();
            for (Out o : queue) {
                release += o.reserved();
            }
            queue.clear();
            queued = 0;
            pong = null;
            ping = false;
            wake.signalAll();
            space.signalAll();
        } finally {
            lock.unlock();
        }
        budget.release(release);
        hub.detach(this);
        for (Open f : lost) {
            f.exchange.lost("verbindung_weg");
        }
        if (code > 0) {
            // Der Schreiber sendet Close und schließt dann selbst; hängt er, schließt dieser Riegel.
            Io.schedule(scheduler, () -> Io.reset(raw), CLOSE_GRACE.toMillis());
        } else {
            Io.reset(raw);
        }
        if (logged.compareAndSet(false, true)) {
            long seconds = Duration.between(since, Instant.now()).toSeconds();
            LOG.info("relay | app_getrennt | app=" + app + " grund=" + why + " dauer_s=" + seconds
                    + " offen_verloren=" + lost.size() + " verworfen=" + discarded);
        }
    }
}
