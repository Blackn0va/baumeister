// Messprobe – Gegenstelle für winhttp_pin_probe.cpp: ein JSSE-Server wie das Relay (TLS 1.3/1.2, nur ECDHE + AEAD,
// Client-Zertifikat Pflicht, Kette genau 1 lang) mit einem kleinen WebSocket-Teil, der jeden Rahmen mitschreibt.
//
// WOZU: Die Host-App soll über WinHTTP eine gepinnte wss://-Verbindung mit Client-Zertifikat aufbauen
// (docs/kanal-v1.md). Was WinHTTP dabei wirklich tut – wann das Serverzertifikat lesbar ist, ob vor der Pin-Prüfung
// schon Bytes der Anfrage hinausgehen, wie es Rahmen sendet und auf falsche Rahmen reagiert –, steht nirgends sicher
// beschrieben. Diese Gegenstelle zeigt es: Sie schreibt jede Beobachtung als „schlüssel=wert“ auf stdout.
//
// Aufruf (Java 21+, eine Datei): java RelayProbe.java <keystore.p12> <passwort> <tls: 13|12|beide> <szenario>
//   Port 0 = frei wählen; die erste Zeile „bereit port=N server_pin=sha256:…“ nennt ihn.
//   Szenarien: echo, ping, stille:<s>, ping-stille:<s>, voll, fragment, maskiert, rsv, text, ping-lang, ablehnen
// Eine Verbindung je Lauf, danach Ende (Exit 0). Schlüssel je Lauf frisch über keytool (run_probe.ps1).

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

public final class RelayProbe {

    private static final String KANAL = "baumeister-kanal.v1";
    private static long start = System.nanoTime();
    private static boolean rejectClient;

    private RelayProbe() {
    }

    static synchronized void out(String line) {
        long ms = (System.nanoTime() - start) / 1_000_000L;
        System.out.println("[" + ms + " ms] " + line);
        System.out.flush();
    }

    static String pin(byte[] der) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(der));
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Aufruf: java RelayProbe.java <keystore.p12> <passwort> <13|12|beide> <szenario>");
            System.exit(2);
        }
        char[] pass = args[1].toCharArray();
        // Szenario „ablehnen“: wie das Relay bei einem nicht eingetragenen Client-Pin – was meldet WinHTTP dann?
        rejectClient = args[3].equals("ablehnen");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(args[0])) {
            ks.load(in, pass);
        }
        X509Certificate own = (X509Certificate) ks.getCertificate("relay");
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pass);
        TrustManager tm = new X509ExtendedTrustManager() {
            private void check(X509Certificate[] chain) throws CertificateException {
                try {
                    out("client_kette=" + chain.length + " client_pin=" + pin(chain[0].getEncoded())
                            + " client_subjekt=" + chain[0].getSubjectX500Principal().getName()
                            + " client_alg=" + chain[0].getSigAlgName());
                } catch (Exception e) {
                    throw new CertificateException(e);
                }
                if (chain.length != 1) {
                    throw new CertificateException("Kette nicht genau 1 lang");
                }
                if (rejectClient) {
                    out("client_abgelehnt");
                    throw new CertificateException("Pin nicht eingetragen");
                }
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                    throws CertificateException {
                check(chain);
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                    throws CertificateException {
                check(chain);
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                check(chain);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                    throws CertificateException {
                throw new CertificateException("nur Server");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                    throws CertificateException {
                throw new CertificateException("nur Server");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("nur Server");
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), new TrustManager[] {tm}, null);
        SSLServerSocket ss = (SSLServerSocket) ctx.getServerSocketFactory()
                .createServerSocket(0, 4, InetAddress.getLoopbackAddress());
        ss.setNeedClientAuth(true);
        List<String> protocols = new ArrayList<>();
        if (!args[2].equals("12")) {
            protocols.add("TLSv1.3");
        }
        if (!args[2].equals("13")) {
            protocols.add("TLSv1.2");
        }
        ss.setEnabledProtocols(protocols.toArray(new String[0]));
        List<String> suites = new ArrayList<>();
        for (String s : ss.getSupportedCipherSuites()) {
            boolean tls13 = s.startsWith("TLS_AES_") || s.startsWith("TLS_CHACHA20_");
            boolean ecdheAead = s.startsWith("TLS_ECDHE_") && (s.contains("_GCM_") || s.contains("CHACHA20"));
            if (tls13 || ecdheAead) {
                suites.add(s);
            }
        }
        ss.setEnabledCipherSuites(suites.toArray(new String[0]));
        System.out.println("bereit port=" + ss.getLocalPort() + " server_pin=" + pin(own.getEncoded()));
        System.out.flush();
        try (SSLSocket s = (SSLSocket) ss.accept()) {
            start = System.nanoTime();
            s.setSoTimeout(15000);
            try {
                s.startHandshake();
            } catch (IOException e) {
                out("handshake_fehler=" + e);
                return;
            }
            out("tls=" + s.getSession().getProtocol() + " suite=" + s.getSession().getCipherSuite());
            serve(s, args[3]);
        }
        out("ende");
    }

    static void serve(SSLSocket s, String scenario) throws Exception {
        InputStream in = s.getInputStream();
        OutputStream os = s.getOutputStream();
        s.setSoTimeout(5000);
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        try {
            while (true) {
                int b = in.read();
                if (b < 0) {
                    break;
                }
                head.write(b);
                byte[] h = head.toByteArray();
                int n = h.length;
                if (n >= 4 && h[n - 4] == '\r' && h[n - 3] == '\n' && h[n - 2] == '\r' && h[n - 1] == '\n') {
                    break;
                }
                if (n > 16384) {
                    break;
                }
            }
        } catch (SocketTimeoutException e) {
            out("http_frist_abgelaufen");
        } catch (IOException e) {
            out("http_lesefehler=" + e);
        }
        String request = head.toString(StandardCharsets.ISO_8859_1);
        out("http_bytes=" + head.size());
        if (head.size() == 0) {
            return;
        }
        String key = null;
        String proto = null;
        for (String line : request.split("\r\n")) {
            out("http_zeile=" + line);
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("sec-websocket-key:")) {
                key = line.substring(line.indexOf(':') + 1).trim();
            }
            if (lower.startsWith("sec-websocket-protocol:")) {
                proto = line.substring(line.indexOf(':') + 1).trim();
            }
        }
        if (key == null) {
            os.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.ISO_8859_1));
            return;
        }
        String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
        String reply = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n"
                + (KANAL.equals(proto) ? "Sec-WebSocket-Protocol: " + KANAL + "\r\n" : "") + "\r\n";
        os.write(reply.getBytes(StandardCharsets.ISO_8859_1));
        os.flush();
        out("upgrade=101 protokoll=" + proto);
        s.setSoTimeout(100000);
        switch (scenario.split(":")[0]) {
            case "echo" -> echo(in, os, 0);
            case "ping" -> {
                Thread t = pinger(os, 1000, 8);
                echo(in, os, 0);
                t.join();
            }
            case "stille" -> silence(in, os, seconds(scenario), false);
            case "ping-stille" -> silence(in, os, seconds(scenario), true);
            case "voll" -> {
                byte[] big = new byte[2 + 4096 + 65536];
                for (int i = 0; i < big.length; i++) {
                    big[i] = (byte) (i % 251);
                }
                byte[] frame = frame(0x82, big, null);
                for (int off = 0; off < frame.length; off += 10000) {
                    os.write(frame, off, Math.min(10000, frame.length - off));
                    os.flush();
                    Thread.sleep(100);
                }
                out("gesendet voll_in_stücken=" + big.length);
                os.write(frame(0x82, big, null));
                os.flush();
                out("gesendet voll_am_stück=" + big.length);
                echo(in, os, 0);
            }
            case "fragment" -> {
                os.write(frame(0x02, "teil1".getBytes(StandardCharsets.US_ASCII), null));
                os.write(frame(0x80, "teil2".getBytes(StandardCharsets.US_ASCII), null));
                os.flush();
                out("gesendet fragment");
                echo(in, os, 0);
            }
            case "maskiert" -> bad(in, os, frame(0x82, "maskiert".getBytes(StandardCharsets.US_ASCII),
                    new byte[] {0x37, (byte) 0xfa, 0x21, 0x3d}), "maskiert");
            case "rsv" -> bad(in, os, frame(0xC2, "rsv".getBytes(StandardCharsets.US_ASCII), null), "rsv");
            case "text" -> bad(in, os, frame(0x81, "text".getBytes(StandardCharsets.US_ASCII), null), "text");
            case "ping-lang" -> bad(in, os, frame(0x89, new byte[126], null), "ping-lang");
            default -> out("szenario_unbekannt=" + scenario);
        }
    }

    static int seconds(String scenario) {
        String[] p = scenario.split(":");
        return p.length > 1 ? Integer.parseInt(p[1]) : 60;
    }

    static Thread pinger(OutputStream os, int everyMs, int count) {
        Thread t = new Thread(() -> {
            try {
                for (int i = 1; i <= count; i++) {
                    Thread.sleep(everyMs);
                    synchronized (os) {
                        os.write(frame(0x89, ("p" + i).getBytes(StandardCharsets.US_ASCII), null));
                        os.flush();
                    }
                    out("gesendet ping p" + i);
                }
            } catch (Exception e) {
                out("ping_fehler=" + e);
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    // Kein Datenrahmen für <s> Sekunden (mit Pings je 2 s, falls pings) – zeigt Keepalive der App und ob ihr
    // Empfangen eine Frist hat. Danach ein Binärrahmen „nach-stille“ und weiter wie echo.
    static void silence(InputStream in, OutputStream os, int secs, boolean pings) throws Exception {
        Thread t = pings ? pinger(os, 2000, secs / 2) : null;
        Thread reader = new Thread(() -> {
            try {
                echo(in, os, 0);
            } catch (Exception e) {
                out("leser_ende=" + e);
            }
        });
        reader.setDaemon(true);
        reader.start();
        Thread.sleep(secs * 1000L);
        if (t != null) {
            t.join();
        }
        try {
            synchronized (os) {
                os.write(frame(0x82, "nach-stille".getBytes(StandardCharsets.US_ASCII), null));
                os.flush();
            }
            out("gesendet nach-stille");
        } catch (IOException e) {
            out("nach-stille_fehler=" + e);
        }
        reader.join(20000);
    }

    static void bad(InputStream in, OutputStream os, byte[] frame, String what) throws Exception {
        os.write(frame);
        os.flush();
        out("gesendet " + what);
        echo(in, os, 0);
    }

    static byte[] frame(int b0, byte[] payload, byte[] mask) {
        ByteArrayOutputStream h = new ByteArrayOutputStream();
        h.write(b0);
        int mb = mask != null ? 0x80 : 0;
        int len = payload.length;
        if (len < 126) {
            h.write(mb | len);
        } else if (len < 65536) {
            h.write(mb | 126);
            h.write(len >> 8);
            h.write(len & 0xFF);
        } else {
            h.write(mb | 127);
            for (int i = 7; i >= 0; i--) {
                h.write((int) (((long) len >> (8 * i)) & 0xFF));
            }
        }
        byte[] body = payload.clone();
        if (mask != null) {
            h.write(mask, 0, 4);
            for (int i = 0; i < body.length; i++) {
                body[i] ^= mask[i % 4];
            }
        }
        h.write(body, 0, body.length);
        return h.toByteArray();
    }

    static int read1(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new IOException("EOF mitten im Rahmen");
        }
        return b;
    }

    // Liest Rahmen der App, schreibt jeden mit und spiegelt Binärrahmen zurück. Endet mit Close oder EOF.
    static void echo(InputStream in, OutputStream os, int depth) throws Exception {
        while (true) {
            int b0;
            try {
                b0 = in.read();
            } catch (SocketTimeoutException e) {
                out("lesen_frist_abgelaufen");
                return;
            } catch (IOException e) {
                out("lesen_fehler=" + e);
                return;
            }
            if (b0 < 0) {
                out("eof");
                return;
            }
            int b1 = read1(in);
            boolean fin = (b0 & 0x80) != 0;
            int rsv = (b0 >> 4) & 7;
            int op = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = ((long) read1(in) << 8) | read1(in);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) {
                    len = (len << 8) | read1(in);
                }
            }
            byte[] mask = new byte[4];
            if (masked) {
                for (int i = 0; i < 4; i++) {
                    mask[i] = (byte) read1(in);
                }
            }
            if (len > (1 << 22)) {
                out("rahmen_zu_groß len=" + len);
                return;
            }
            byte[] payload = in.readNBytes((int) len);
            if (masked) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i % 4];
                }
            }
            String preview = op == 1 || op == 8 || op == 9 || op == 10
                    ? new String(payload, StandardCharsets.UTF_8).replaceAll("[^\\x20-\\x7e]", "?") : "";
            out("rahmen fin=" + (fin ? 1 : 0) + " rsv=" + rsv + " op=" + op + " maske=" + (masked ? 1 : 0) + " len="
                    + len + (preview.isEmpty() ? "" : " text=" + preview)
                    + (op == 2 && len > 0 ? " sha256=" + HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(payload)).substring(0, 16) : ""));
            synchronized (os) {
                if (op == 2) {
                    os.write(frame(0x82, payload, null));
                    os.flush();
                } else if (op == 9) {
                    os.write(frame(0x8A, payload, null));
                    os.flush();
                } else if (op == 8) {
                    int code = payload.length >= 2 ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF) : -1;
                    out("close code=" + code);
                    os.write(frame(0x88, payload.length >= 2 ? new byte[] {payload[0], payload[1]} : new byte[0],
                            null));
                    os.flush();
                    return;
                }
            }
        }
    }
}
