package com.blackn0va.baumeister.relay;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * Nachgebaute Host-App für die Tests: TLS mit Client-Zertifikat, Server-Pin geprüft, Upgrade, hallo, dann Rahmen nach
 * Kanal v1 (maskiert, wie die echte App). Ein Kontext lässt sich über mehrere Verbindungen wiederverwenden – so
 * versucht der JDK-Client eine Sitzungs-Wiederaufnahme, und der Test sieht, ob das Relay sie zulässt.
 */
final class FakeApp implements Closeable {

    /** Close des Relays. */
    static final class Closed extends IOException {
        private static final long serialVersionUID = 1L;
        final int code;
        final String reason;

        Closed(int code, String reason) {
            super("Close " + code + " " + reason);
            this.code = code;
            this.reason = reason;
        }
    }

    /** Vollständig gelesene anfrage samt Body. */
    record Request(long id, String method, String path, String contentType, byte[] body) {
    }

    /** TLS-Kontext der App. key null: ohne Client-Zertifikat. chooseCount zählt volle Handshakes (Client-Seite). */
    static final class Identity {
        final SSLContext context;
        final AtomicInteger chooseCount = new AtomicInteger();

        Identity(PrivateKey key, X509Certificate[] chain, String serverPin) throws Exception {
            context = SSLContext.getInstance("TLS");
            KeyManager[] km = key == null ? new KeyManager[0] : new KeyManager[] {new ClientKeys(key, chain,
                    chooseCount)};
            context.init(km, new TrustManager[] {new ServerPin(serverPin)}, new SecureRandom());
        }
    }

    private final SSLSocket ssl;
    private final BufferedInputStream in;
    private final OutputStream out;
    private final Frames.Reader reader;
    private final byte[] mask = new byte[4];
    String relayHello;

    private FakeApp(SSLSocket ssl) throws IOException {
        this.ssl = ssl;
        this.in = new BufferedInputStream(ssl.getInputStream());
        this.out = ssl.getOutputStream();
        this.reader = new Frames.Reader(in, false, Codec.MAX_PAYLOAD);
        new SecureRandom().nextBytes(mask);
    }

    /** Nur TLS (für Handshake-Tests). protocols/suites null: Vorgabe des JDK. */
    static SSLSocket tls(Identity t, int port, String[] protocols, String[] suites) throws IOException {
        SSLSocket s = (SSLSocket) t.context.getSocketFactory().createSocket(InetAddress.getLoopbackAddress(), port);
        s.setSoTimeout(10_000);
        SSLParameters p = s.getSSLParameters();
        if (protocols != null) {
            p.setProtocols(protocols);
        }
        if (suites != null) {
            p.setCipherSuites(suites);
        }
        s.setSSLParameters(p);
        s.startHandshake();
        return s;
    }

    /** TLS, Upgrade und hallo – danach ist die App angemeldet. */
    static FakeApp connect(Identity t, int port, int max) throws IOException {
        FakeApp a = upgraded(t, port);
        a.send(new Message.Hello(Codec.Direction.APP_TO_RELAY, "FakeApp/test", max));
        Message m = a.message();
        if (!(m instanceof Message.Hello h)) {
            throw new IOException("hallo des Relays erwartet, kam " + m);
        }
        a.relayHello = h.id();
        return a;
    }

    /** TLS und Upgrade, noch ohne hallo. */
    static FakeApp upgraded(Identity t, int port) throws IOException {
        FakeApp a = new FakeApp(tls(t, port, null, null));
        String key = Base64.getEncoder().encodeToString(random(16));
        a.out.write(("GET /kanal/v1 HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n"
                + "Sec-WebSocket-Protocol: baumeister-kanal.v1\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        a.out.flush();
        String head = a.readHead();
        if (!head.startsWith("HTTP/1.1 101 ") || !head.contains("\r\nSec-WebSocket-Accept: " + Upgrade.accept(key)
                + "\r\n") || !head.contains("\r\nSec-WebSocket-Protocol: baumeister-kanal.v1\r\n")) {
            throw new IOException("Upgrade abgelehnt: " + head);
        }
        return a;
    }

    /** Roher Upgrade-Versuch: schreibt request, liefert den Antwortkopf. */
    static String rawUpgrade(Identity t, int port, String request) throws IOException {
        try (FakeApp a = new FakeApp(tls(t, port, null, null))) {
            a.out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            a.out.flush();
            return a.readHead();
        }
    }

    private String readHead() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        while (true) {
            int c = in.read();
            if (c < 0) {
                throw new EOFException("Antwortkopf abgeschnitten: " + b);
            }
            b.write(c);
            byte[] x = b.toByteArray();
            int n = x.length;
            if (n >= 4 && x[n - 4] == '\r' && x[n - 3] == '\n' && x[n - 2] == '\r' && x[n - 1] == '\n') {
                return b.toString(StandardCharsets.ISO_8859_1);
            }
        }
    }

    static byte[] random(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    void send(Message m) throws IOException {
        sendFrame(Frames.client(Frames.BINARY, Codec.encode(m), mask));
    }

    synchronized void sendFrame(byte[] frame) throws IOException {
        out.write(frame);
        out.flush();
    }

    Frames.Frame frame() throws IOException, ProtocolException {
        return reader.read();
    }

    /** Nächste Nachricht; Pings werden beantwortet, Close wird zu Closed. */
    Message message() throws IOException {
        while (true) {
            Frames.Frame f;
            try {
                f = reader.read();
            } catch (ProtocolException e) {
                throw new IOException("Relay verletzt Kanal v1: " + e.reason().code());
            }
            switch (f.opcode()) {
                case Frames.PING -> sendFrame(Frames.client(Frames.PONG, f.payload(), mask));
                case Frames.PONG -> {
                    // nichts
                }
                case Frames.CLOSE -> throw new Closed(Frames.closeCode(f.payload()), Frames.closeReason(f.payload()));
                default -> {
                    try {
                        return Codec.decode(f.payload(), Codec.Direction.RELAY_TO_APP);
                    } catch (ProtocolException e) {
                        throw new IOException("Relay verletzt Kanal v1: " + e.reason().code());
                    }
                }
            }
        }
    }

    /** Nächste anfrage samt aller daten. */
    Request request() throws IOException {
        Message m = message();
        if (!(m instanceof Message.Request r)) {
            throw new IOException("anfrage erwartet, kam " + m);
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(r.chunk().buffer(), r.chunk().offset(), r.chunk().length());
        while (body.size() < r.length()) {
            Message d = message();
            if (!(d instanceof Message.Data data) || data.id() != r.id()) {
                throw new IOException("daten zu " + r.id() + " erwartet, kam " + d);
            }
            body.write(data.chunk().buffer(), data.chunk().offset(), data.chunk().length());
        }
        return new Request(r.id(), r.method(), r.path(), r.contentType(), body.toByteArray());
    }

    /** antwort samt daten in Stücken von chunk Bytes. */
    void answer(long id, int status, String contentType, byte[] body, int chunk) throws IOException {
        int first = Math.min(body.length, chunk);
        send(new Message.Response(id, status, contentType, body.length, new Message.Chunk(body, 0, first)));
        for (int off = first; off < body.length; off += chunk) {
            int n = Math.min(chunk, body.length - off);
            send(new Message.Data(id, new Message.Chunk(body, off, n)));
        }
    }

    Socket socket() {
        return ssl;
    }

    @Override
    public void close() throws IOException {
        ssl.close();
    }

    /** Client-Schlüssel der App; zählt jede Wahl – die gibt es nur im vollen Handshake, nie bei Wiederaufnahme. */
    private static final class ClientKeys extends X509ExtendedKeyManager {
        private final PrivateKey key;
        private final X509Certificate[] chain;
        private final AtomicInteger count;

        ClientKeys(PrivateKey key, X509Certificate[] chain, AtomicInteger count) {
            this.key = key;
            this.chain = chain;
            this.count = count;
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            count.incrementAndGet();
            return "app";
        }

        @Override
        public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
            count.incrementAndGet();
            return "app";
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return chain.clone();
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return key;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return new String[] {"app"};
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return null;
        }
    }

    /** Server-Pin wie in der echten App: kein CA, kein Name, genau dieser Pin. */
    private static final class ServerPin extends X509ExtendedTrustManager {
        private final String pin;

        ServerPin(String pin) {
            this.pin = pin;
        }

        private void check(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length != 1 || !Pins.of(chain[0]).equals(pin)) {
                throw new CertificateException("Server-Pin passt nicht");
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            check(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            check(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            check(chain);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("kein Server");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            throw new CertificateException("kein Server");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            throw new CertificateException("kein Server");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
