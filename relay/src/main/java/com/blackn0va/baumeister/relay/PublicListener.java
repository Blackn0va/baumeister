package com.blackn0va.baumeister.relay;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Öffentliche Seite (Vorgabe 0.0.0.0:25580): Hier meldet sich die Host-App über WebSocket in TLS an (docs/kanal-v1.md,
 * Abschnitt 1). Der Port steht über die Portfreigabe im Internet – jede Zeile hier ist Angriffsfläche.
 *
 * Reihenfolge je Verbindung (ein Thread, nie der Annahme-Thread – ein langsamer Handshake hält sonst alle auf):
 * 1. HandshakeGate: Sperre, Grenze je Adresse bzw. /64, Gesamtgrenze → sonst sofort RST.
 * 2. TLS-Handshake mit harter Frist von 10 s (ein Riegel setzt den Socket zurück; SO_TIMEOUT allein ließe einen
 *    Client, der alle 9 s ein Byte schickt, ewig hängen).
 * 3. Pin des Peers noch einmal selbst prüfen und die Sitzung für ungültig erklären – keine Wiederaufnahme, und ein
 *    Handshake, der den Trust-Manager umginge, scheitert hier trotzdem (ASVS V9.2.1).
 * 4. Ab hier angemeldet: zählt nicht mehr gegen die Grenzen ohne Anmeldung.
 * 5. Upgrade (≤ 8 KiB, streng), dann das hallo der App binnen 10 s, dann das hallo des Relays.
 * 6. Sitzung beim Hub anmelden (ersetzt eine alte mit demselben Pin) und lesen, bis sie endet.
 * Jeder Fehlschlag vor Schritt 6 verlängert die Sperre der Adresse (HandshakeGate.failure).
 *
 * Threads: ein Annahme-Thread, ein Thread je Verbindung (höchstens Limits.unauthenticated vor der Anmeldung).
 * Fehler: werden gedrosselt geloggt; die Verbindung endet mit RST (bzw. Close nach dem Upgrade).
 */
final class PublicListener {

    private static final Logger LOG = RelayLog.LOG;

    private final Relay relay;
    private final SSLSocketFactory factory;
    private final PinTrustManager trust;
    private final PinStore pins;
    private final HandshakeGate gate;
    private final Limits limits;
    private final AtomicLong counter = new AtomicLong();
    /**
     * Anmeldungen je Pin zwischen TLS-Handshake und hallo (zweite Sicherheitsprüfung 2026-11-08, N1): Vorher zählte
     * eine Verbindung nach dem Handshake nirgends mehr – mit einem gültigen oder gestohlenen App-Schlüssel ließen sich
     * ~70 Anmeldungen/s öffnen, jede bis zu 10 s, bis Threads (TasksMax) und Heap voll waren.
     */
    static final int MAX_PENDING_PER_PIN = 2;
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> pending =
            new java.util.concurrent.ConcurrentHashMap<>();
    private ServerSocket server;

    PublicListener(Relay relay, SSLContext context, PinTrustManager trust, PinStore pins, HandshakeGate gate) {
        this.relay = relay;
        this.factory = context.getSocketFactory();
        this.trust = trust;
        this.pins = pins;
        this.gate = gate;
        this.limits = relay.limits();
    }

    /** Offene Anmeldung eines Pins beenden (hallo erreicht oder Verbindung zu). null = keine gezählt. */
    private void releasePending(String pin) {
        if (pin == null) {
            return;
        }
        java.util.concurrent.atomic.AtomicInteger n = pending.get(pin);
        if (n != null && n.decrementAndGet() <= 0) {
            pending.remove(pin, n);
        }
    }

    void bind(InetSocketAddress address) throws IOException {
        ServerSocket s = new ServerSocket();
        try {
            s.bind(address, 64);
        } catch (IOException e) {
            // Neuer Versuch im Takt (Relay, N2): den ungebundenen Socket nicht liegen lassen.
            Io.close(s);
            throw e;
        }
        server = s;
    }

    /** Gebundener Port oder -1, solange 25580 noch belegt ist (Relay, N2). */
    int port() {
        return server == null || !server.isBound() ? -1 : server.getLocalPort();
    }

    String describe() {
        return server == null || !server.isBound() ? "nicht_gebunden"
                : server.getInetAddress().getHostAddress() + ":" + server.getLocalPort();
    }

    void start() {
        Io.start("relay-tls-annahme", this::acceptLoop);
    }

    void close() {
        Io.close(server);
    }

    private void acceptLoop() {
        while (true) {
            Socket raw;
            try {
                raw = server.accept();
            } catch (IOException e) {
                if (relay.closing()) {
                    return;
                }
                if (server.isClosed()) {
                    relay.fatal("öffentlicher_port_verloren");
                    return;
                }
                relay.throttle().reject("tls_annahmefehler", "relay | tls_annahmefehler | grund="
                        + e.getClass().getSimpleName());
                sleep();
                continue;
            }
            HandshakeGate.Admission a = gate.enter(raw.getInetAddress(), System.nanoTime());
            if (a.ticket() == null) {
                Io.reset(raw);
                relay.throttle().reject("tls_" + a.refusal(), "relay | abgewiesen | adresse="
                        + raw.getInetAddress().getHostAddress() + " grund=" + a.refusal());
                continue;
            }
            try {
                Io.start("relay-tls-" + counter.incrementAndGet(), () -> admit(raw, a.ticket()));
            } catch (RuntimeException | OutOfMemoryError e) {
                gate.release(a.ticket());
                Io.reset(raw);
                relay.throttle().reject("tls_kein_thread", "relay | tls_kein_thread | grund="
                        + e.getClass().getSimpleName());
            }
        }
    }

    private void admit(Socket raw, HandshakeGate.Ticket ticket) {
        String address = raw.getInetAddress().getHostAddress();
        String stage = "tls";
        String pendingPin = null;
        boolean established = false;
        // Hat die App schon eine Ablehnung bekommen (4xx oder Close), endet die Verbindung mit FIN statt RST – ein RST
        // ließe sie die Ablehnung womöglich nie lesen (gemessen 2026-11-08 unter Windows: „Connection reset“ statt 404).
        boolean answered = false;
        AtomicBoolean expired = new AtomicBoolean();
        Runnable kill = () -> {
            expired.set(true);
            Io.reset(raw);
        };
        Future<?> deadline = Io.schedule(relay.scheduler(), kill, limits.handshake().toMillis());
        try {
            raw.setTcpNoDelay(true);
            raw.setSoTimeout((int) limits.handshake().toMillis());
            // Gerade geänderte Konfiguration gilt schon für diese Verbindung, nicht erst beim nächsten Takt.
            pins.refresh();
            SSLSocket ssl = (SSLSocket) factory.createSocket(raw, null, true);
            Tls.configure(ssl);
            ssl.startHandshake();
            String[] peer = verifiedPeer(ssl.getSession());
            String app = peer[0];
            String pin = peer[1];
            deadline.cancel(false);
            gate.release(ticket);
            pendingPin = pin;
            if (pending.computeIfAbsent(pin, k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet()
                    > MAX_PENDING_PER_PIN) {
                stage = "zu_viele_anmeldungen";
                throw new EOFException("zu viele offene Anmeldungen dieses Pins");
            }
            // Spätere Handshakes auf derselben Verbindung (TLS-1.2-Neuverhandlung – per Eigenschaft abgelehnt) dürften
            // die Identität nicht wechseln: dritter Riegel.
            ssl.addHandshakeCompletedListener(ev -> {
                try {
                    if (!pin.equals(Pins.of((X509Certificate) ev.getPeerCertificates()[0]))) {
                        Io.reset(raw);
                    }
                } catch (SSLPeerUnverifiedException | CertificateEncodingException | RuntimeException e) {
                    Io.reset(raw);
                }
            });

            stage = "upgrade";
            deadline = Io.schedule(relay.scheduler(), kill, limits.hello().toMillis());
            raw.setSoTimeout((int) limits.hello().toMillis());
            BufferedInputStream in = new BufferedInputStream(ssl.getInputStream(), 16 * 1024);
            OutputStream out = ssl.getOutputStream();
            String key;
            try {
                HttpHead h = HttpHead.read(in, Upgrade.MAX_HEADER, 400);
                key = Upgrade.check(h);
                if (in.available() > 0) {
                    // RFC 6455, 4.1: Der Client wartet auf die 101, bevor er Rahmen schickt.
                    throw new HttpFailure(400, "daten_vor_101");
                }
            } catch (HttpFailure f) {
                answered = true;
                out.write(Upgrade.refusal(f.status()));
                out.flush();
                stage = f.code();
                throw new EOFException("Upgrade abgelehnt");
            }
            out.write(Upgrade.switching(key));
            out.flush();

            stage = "hallo";
            Frames.Reader reader = new Frames.Reader(in, true, Codec.MAX_PAYLOAD);
            Message.Hello hello;
            try {
                hello = readHello(reader, out);
            } catch (ProtocolException e) {
                answered = true;
                out.write(Frames.server(Frames.CLOSE, Frames.closePayload(e.reason().closeCode(),
                        e.reason().code())));
                out.flush();
                stage = "hallo_" + e.reason().code();
                throw new EOFException("hallo abgelehnt");
            }
            out.write(Frames.server(Frames.BINARY, Codec.encode(new Message.Hello(Codec.Direction.RELAY_TO_APP,
                    relay.id(), 0))));
            out.flush();
            deadline.cancel(false);
            raw.setSoTimeout((int) limits.silence().toMillis());
            releasePending(pendingPin);
            pendingPin = null;

            Session session = new Session(app, pin, hello.id(), hello.max(), address, raw, reader, out, relay.hub(),
                    relay.budget(), relay.scheduler());
            established = true;
            gate.success(ticket.key);
            session.start();
            relay.hub().attach(session);
            LOG.info("relay | app_angemeldet | app=" + app + " pin=" + Pins.brief(pin) + " adresse=" + address
                    + " kennung=" + hello.id() + " max=" + hello.max() + " tls=" + ssl.getSession().getProtocol()
                    + "/" + ssl.getSession().getCipherSuite());
            session.readLoop();
        } catch (PinTrustManager.Rejected e) {
            stage = e.getMessage();
        } catch (SocketTimeoutException e) {
            stage = stage + "_frist";
        } catch (SSLException e) {
            stage = classify(e);
        } catch (IOException e) {
            if (stage.equals("tls") || stage.equals("upgrade") || stage.equals("hallo")) {
                stage = stage + "_" + (e instanceof SocketException ? "abgebrochen" : e.getClass().getSimpleName());
            }
        } catch (RuntimeException e) {
            // Thread-Grenze: Ein Fehler im Relay kostet diese Verbindung, nicht den Prozess.
            LOG.log(Level.SEVERE, "relay | anmeldung_fehler | adresse=" + address, e);
            stage = "fehler";
        } finally {
            releasePending(pendingPin);
            deadline.cancel(false);
            gate.release(ticket);
            if (!established) {
                gate.failure(ticket.key, System.nanoTime());
                if (answered) {
                    Io.lingerClose(raw, 1_000);
                } else {
                    Io.reset(raw);
                }
                String why = expired.get() ? stage.replaceAll("_.*", "") + "_frist" : stage;
                relay.throttle().reject("tls_abgewiesen", "relay | abgewiesen | adresse=" + address + " grund="
                        + why);
            }
        }
    }

    /**
     * Pin des Peers selbst prüfen (nicht nur über den Trust-Manager) und Wiederaufnahme ausschließen.
     * Rückgabe: {Name, voller Pin}.
     */
    private String[] verifiedPeer(SSLSession session) throws PinTrustManager.Rejected {
        Certificate[] chain;
        try {
            chain = session.getPeerCertificates();
        } catch (SSLPeerUnverifiedException e) {
            throw new PinTrustManager.Rejected("kein_zertifikat");
        }
        session.invalidate();
        String app = trust.match(chain);
        if (!Arrays.asList(Tls.PROTOCOLS).contains(session.getProtocol())
                || !Arrays.asList(Tls.CIPHER_SUITES).contains(session.getCipherSuite())) {
            throw new PinTrustManager.Rejected("tls_parameter");
        }
        try {
            return new String[] {app, Pins.of((X509Certificate) chain[0])};
        } catch (CertificateEncodingException e) {
            throw new PinTrustManager.Rejected("kein_x509");
        }
    }

    /** Wartet auf das hallo der App; Ping/Pong davor sind erlaubt (Steuerrahmen sind keine Nachrichten). */
    private static Message.Hello readHello(Frames.Reader reader, OutputStream out)
            throws IOException, ProtocolException {
        while (true) {
            Frames.Frame f = reader.read();
            switch (f.opcode()) {
                case Frames.PING -> {
                    out.write(Frames.server(Frames.PONG, f.payload()));
                    out.flush();
                }
                case Frames.PONG -> {
                    // Unaufgefordertes Pong: Die Host-App (WinHTTP) kann keine Pings senden und schickt stattdessen alle
                    // 20 s ein Pong als Lebenszeichen. Kein Fehler – still annehmen.
                }
                case Frames.CLOSE -> throw new EOFException("App schließt vor dem hallo");
                default -> {
                    if (Codec.decode(f.payload(), Codec.Direction.APP_TO_RELAY) instanceof Message.Hello h) {
                        return h;
                    }
                    throw new ProtocolException(Reason.HELLO_MISSING);
                }
            }
        }
    }

    /** Grund eines gescheiterten Handshakes fürs Log – ohne fremden Text. */
    private static String classify(SSLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof PinTrustManager.Rejected r) {
                return r.getMessage();
            }
        }
        String m = String.valueOf(e.getMessage());
        if (m.contains("Empty client certificate") || m.contains("certificate_required")
                || m.contains("bad_certificate")) {
            return "kein_zertifikat";
        }
        if (m.contains("protocol") || m.contains("cipher")) {
            return "tls_parameter";
        }
        return "tls_" + e.getClass().getSimpleName();
    }

    private static void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
