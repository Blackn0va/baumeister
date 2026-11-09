package com.blackn0va.baumeister.relay;

import java.util.Locale;

/**
 * Anfrage des Plugins an 127.0.0.1:18080 nach den Regeln aus docs/kanal-v1.md, Abschnitt 6: HTTP/1.1, eine Anfrage je
 * Verbindung, Kopf ≤ 16 KiB, Content-Length Pflicht bei POST, kein chunked, Body ≤ 2 MiB, Pfad aus der Freigabeliste.
 *
 * Prüfreihenfolge: Version (400) → Methode (405) → Pfad (404) → Host (400) → Transfer-Encoding (400) → Expect (417) →
 * Länge (411/400/413) → Content-Type (400). Den Body liest das Relay erst, wenn eine App die Anfrage übernimmt –
 * wartende Anfragen belegen so keinen Heap (bis zu 64 × 2 MiB passten nie in -Xmx32m).
 *
 * Host: nur 127.0.0.1, localhost oder [::1], mit Port nur dem eigenen. Schutz gegen DNS-Rebinding: Eine Webseite in
 * einem Browser auf dev01 (heute keiner) könnte sonst über einen umgebogenen Namen POSTs an 127.0.0.1:18080 schicken –
 * die tragen dann ihren eigenen Host-Kopf (ASVS V14.5.3).
 *
 * Threads: unveränderlich. Fehler: HttpFailure mit 4xx.
 */
record LocalRequest(String method, String target, String contentType, long length) {

    static final int MAX_HEADER = 16 * 1024;

    static LocalRequest check(HttpHead h, int localPort) throws HttpFailure {
        if (!h.version().equals("HTTP/1.1")) {
            throw new HttpFailure(400, "version");
        }
        boolean post = h.method().equals("POST");
        if (!post && !h.method().equals("GET")) {
            throw new HttpFailure(405, "methode");
        }
        if (!PathRules.allowed(h.target())) {
            throw new HttpFailure(404, "pfad");
        }
        if (!hostAllowed(h.header("host"), localPort)) {
            throw new HttpFailure(400, "host");
        }
        if (h.header("transfer-encoding") != null) {
            throw new HttpFailure(400, "transfer_encoding");
        }
        if (h.header("expect") != null) {
            throw new HttpFailure(417, "expect");
        }
        String contentLength = h.header("content-length");
        long length = 0;
        if (contentLength == null) {
            if (post) {
                throw new HttpFailure(411, "länge_fehlt");
            }
        } else {
            // Nur Ziffern, höchstens 10: kein Vorzeichen, keine Liste („5, 5“), kein Überlauf beim Lesen.
            if (!contentLength.matches("[0-9]{1,10}")) {
                throw new HttpFailure(400, "content_length");
            }
            length = Long.parseLong(contentLength);
        }
        if (!post && length != 0) {
            throw new HttpFailure(400, "get_mit_body");
        }
        if (length > Codec.MAX_REQUEST) {
            throw new HttpFailure(413, "zu_groß");
        }
        String contentType = h.header("content-type");
        if (contentType != null && !Codec.printableAscii(contentType, Codec.MAX_CONTENT_TYPE)) {
            throw new HttpFailure(400, "content_type");
        }
        return new LocalRequest(h.method(), h.target(), contentType, length);
    }

    static boolean hostAllowed(String host, int localPort) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        for (String name : new String[] {"127.0.0.1", "localhost", "[::1]"}) {
            if (h.equals(name) || h.equals(name + ":" + localPort)) {
                return true;
            }
        }
        return false;
    }
}
