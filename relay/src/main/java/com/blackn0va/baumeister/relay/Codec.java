package com.blackn0va.baumeister.relay;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;

/**
 * Nachrichten des Kanals v1 lesen und schreiben (docs/kanal-v1.md, Abschnitt 3):
 * {@code u16 Kopflänge (BE, 1..4096) | Kopf: JSON-Objekt, UTF-8 | Datenstück 0..65536}.
 *
 * Leser nehmen jede Schlüsselreihenfolge und Leerraum an und übergehen unbekannte Felder; ein unbekannter Typ ist ein
 * Fehler. Welcher Typ in welche Richtung darf, prüft decode(): Eine anfrage von der App oder eine antwort vom Relay
 * ist für den Leser ein unbekannter Typ. Die Reihenfolge der Prüfungen (Kopf, Typ, id, Methode/Status, Pfad, ct,
 * len, Stück) legt fest, welcher Grund bei mehreren Fehlern zählt – die Testvektoren haben je genau einen.
 *
 * Schreiber: kompakt, Felder in der Reihenfolge der Testvektoren (t, id, m/s, p, ct, len bzw. t, v, app/relay, max).
 *
 * Threads: zustandslos. Fehler: ProtocolException mit dem Grund aus Abschnitt 3 (siehe Reason).
 */
final class Codec {

    /** Wer eine Nachricht schickt; davon hängen erlaubte Typen und Felder des hallo ab. */
    enum Direction {
        APP_TO_RELAY,
        RELAY_TO_APP
    }

    static final int MAX_HEADER = 4096;
    static final int MAX_CHUNK = 65_536;
    /** Größte Nutzlast eines Binärrahmens (Abschnitt 2). */
    static final int MAX_PAYLOAD = 2 + MAX_HEADER + MAX_CHUNK;
    static final long MAX_REQUEST = 2L * 1024 * 1024;
    static final long MAX_RESPONSE = 16L * 1024 * 1024;
    static final long MAX_ID = 0xFFFF_FFFFL;
    static final int MAX_PARALLEL = 6;
    static final int MAX_PEER_ID = 64;
    static final int MAX_CONTENT_TYPE = 128;
    /** Ein Kanal-Kopf ist flach; Tiefe 8 lässt unbekannte Felder mit kleinen Objekten zu und hält den Stapel klein. */
    private static final int JSON_DEPTH = 8;

    private Codec() {
    }

    static Message decode(byte[] payload, Direction from) throws ProtocolException {
        return decode(payload, 0, payload.length, from);
    }

    static Message decode(byte[] buf, int off, int len, Direction from) throws ProtocolException {
        if (len < 2) {
            throw new ProtocolException(Reason.HEADER_TRUNCATED);
        }
        int headerLength = ((buf[off] & 0xFF) << 8) | (buf[off + 1] & 0xFF);
        if (headerLength == 0) {
            throw new ProtocolException(Reason.HEADER_EMPTY);
        }
        if (headerLength > MAX_HEADER) {
            throw new ProtocolException(Reason.HEADER_TOO_LONG);
        }
        if (headerLength > len - 2) {
            throw new ProtocolException(Reason.HEADER_TRUNCATED);
        }
        int chunkLength = len - 2 - headerLength;
        if (chunkLength > MAX_CHUNK) {
            throw new ProtocolException(Reason.CHUNK_TOO_BIG);
        }
        Json.Obj head = header(buf, off + 2, headerLength);
        Message.Chunk chunk = new Message.Chunk(buf, off + 2 + headerLength, chunkLength);
        if (!(head.get("t") instanceof Json.Str type)) {
            throw new ProtocolException(Reason.TYPE_UNKNOWN);
        }
        return switch (type.value()) {
            case "hallo" -> hello(head, from, chunkLength);
            case "anfrage" -> {
                if (from != Direction.RELAY_TO_APP) {
                    throw new ProtocolException(Reason.TYPE_UNKNOWN);
                }
                yield request(head, chunk);
            }
            case "antwort" -> {
                if (from != Direction.APP_TO_RELAY) {
                    throw new ProtocolException(Reason.TYPE_UNKNOWN);
                }
                yield response(head, chunk);
            }
            case "daten" -> new Message.Data(id(head), chunk);
            case "abbruch" -> {
                long id = id(head);
                if (chunkLength != 0) {
                    throw new ProtocolException(Reason.CHUNK_TOO_BIG);
                }
                yield new Message.Abort(id);
            }
            default -> throw new ProtocolException(Reason.TYPE_UNKNOWN);
        };
    }

    /** Kopf streng als UTF-8 (kein Ersetzen kaputter Bytes) und als JSON-Objekt. */
    private static Json.Obj header(byte[] buf, int off, int len) throws ProtocolException {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(buf, off, len))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new ProtocolException(Reason.HEADER_NOT_UTF8);
        }
        try {
            if (Json.parse(text, JSON_DEPTH) instanceof Json.Obj o) {
                return o;
            }
        } catch (Json.JsonException e) {
            // Kein gültiges JSON ist erst recht kein JSON-Objekt; der Grund zählt, der Text des Lesers nicht.
            throw new ProtocolException(Reason.HEADER_NOT_OBJECT);
        }
        throw new ProtocolException(Reason.HEADER_NOT_OBJECT);
    }

    private static Message hello(Json.Obj head, Direction from, int chunkLength) throws ProtocolException {
        OptionalLong v = integer(head.get("v"));
        if (v.isEmpty() || v.getAsLong() != 1) {
            throw new ProtocolException(Reason.VERSION_INVALID);
        }
        String id = peerId(head.get(from == Direction.APP_TO_RELAY ? "app" : "relay"));
        if (id == null) {
            throw new ProtocolException(Reason.PEER_ID_INVALID);
        }
        int max = 0;
        if (from == Direction.APP_TO_RELAY) {
            OptionalLong m = integer(head.get("max"));
            if (m.isEmpty() || m.getAsLong() < 1 || m.getAsLong() > MAX_PARALLEL) {
                throw new ProtocolException(Reason.MAX_INVALID);
            }
            max = (int) m.getAsLong();
        }
        if (chunkLength != 0) {
            throw new ProtocolException(Reason.CHUNK_TOO_BIG);
        }
        return new Message.Hello(from, id, max);
    }

    private static Message request(Json.Obj head, Message.Chunk chunk) throws ProtocolException {
        long id = id(head);
        if (!(head.get("m") instanceof Json.Str m) || !(m.value().equals("GET") || m.value().equals("POST"))) {
            throw new ProtocolException(Reason.METHOD_INVALID);
        }
        if (!(head.get("p") instanceof Json.Str p) || !PathRules.allowed(p.value())) {
            throw new ProtocolException(Reason.PATH_INVALID);
        }
        String ct = contentType(head.get("ct"));
        long length = length(head.get("len"), MAX_REQUEST);
        if (chunk.length() > length) {
            throw new ProtocolException(Reason.LENGTH_INVALID);
        }
        return new Message.Request(id, m.value(), p.value(), ct, length, chunk);
    }

    private static Message response(Json.Obj head, Message.Chunk chunk) throws ProtocolException {
        long id = id(head);
        OptionalLong s = integer(head.get("s"));
        // 200…599 (zweite Sicherheitsprüfung 2026-11-08, M1): Eine 1xx-Zwischenantwort verwirft der HttpClient des
        // Plugins und liest den Body als ENDGÜLTIGE Antwort – samt eingeschmuggeltem „X-Baumeister-App: fremd“.
        if (s.isEmpty() || s.getAsLong() < 200 || s.getAsLong() > 599) {
            throw new ProtocolException(Reason.STATUS_INVALID);
        }
        String ct = contentType(head.get("ct"));
        long length = length(head.get("len"), MAX_RESPONSE);
        if (chunk.length() > length) {
            throw new ProtocolException(Reason.LENGTH_INVALID);
        }
        return new Message.Response(id, (int) s.getAsLong(), ct, length, chunk);
    }

    private static long id(Json.Obj head) throws ProtocolException {
        Json.Value v = head.get("id");
        if (v == null) {
            throw new ProtocolException(Reason.ID_MISSING);
        }
        OptionalLong id = integer(v);
        if (id.isEmpty() || id.getAsLong() < 1 || id.getAsLong() > MAX_ID) {
            throw new ProtocolException(Reason.ID_INVALID);
        }
        return id.getAsLong();
    }

    private static long length(Json.Value v, long max) throws ProtocolException {
        OptionalLong n = integer(v);
        if (n.isEmpty() || n.getAsLong() < 0 || n.getAsLong() > max) {
            throw new ProtocolException(Reason.LENGTH_INVALID);
        }
        return n.getAsLong();
    }

    /** ct fehlt: null. Sonst 1…128 druckbare ASCII-Zeichen – ohne CR/LF kann ct keinen HTTP-Kopf einschleusen. */
    private static String contentType(Json.Value v) throws ProtocolException {
        if (v == null) {
            return null;
        }
        if (v instanceof Json.Str s && printableAscii(s.value(), MAX_CONTENT_TYPE)) {
            return s.value();
        }
        throw new ProtocolException(Reason.CONTENT_TYPE_INVALID);
    }

    /**
     * Kennung aus dem hallo, 1…64 druckbare ASCII-Zeichen, sonst null. Strenger als „≤ 64 Zeichen“ in Abschnitt 3: Die
     * Kennung landet im Journal, und Steuerzeichen oder Unicode-Tricks dort (Zeilenumbruch, Rechts-nach-links) könnten
     * Logzeilen fälschen (ASVS V7.3.1). Die App schickt „BaumeisterHost/<Fassung>“.
     */
    private static String peerId(Json.Value v) {
        return v instanceof Json.Str s && printableAscii(s.value(), MAX_PEER_ID) ? s.value() : null;
    }

    static boolean printableAscii(String s, int max) {
        if (s.isEmpty() || s.length() > max) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    private static OptionalLong integer(Json.Value v) {
        return v instanceof Json.Num n ? n.exactLong() : OptionalLong.empty();
    }

    /** Nutzlast eines Binärrahmens: u16 Kopflänge, Kopf, Datenstück. Ungültige Werte sind ein Programmierfehler. */
    static byte[] encode(Message m) {
        String header;
        Message.Chunk chunk = Message.Chunk.EMPTY;
        switch (m) {
            case Message.Hello h -> header = h.from() == Direction.APP_TO_RELAY
                    ? Json.object("t", "hallo", "v", 1, "app", h.id(), "max", h.max())
                    : Json.object("t", "hallo", "v", 1, "relay", h.id());
            case Message.Request r -> {
                header = r.contentType() == null
                        ? Json.object("t", "anfrage", "id", r.id(), "m", r.method(), "p", r.path(), "len", r.length())
                        : Json.object("t", "anfrage", "id", r.id(), "m", r.method(), "p", r.path(),
                                "ct", r.contentType(), "len", r.length());
                chunk = r.chunk();
            }
            case Message.Response r -> {
                header = r.contentType() == null
                        ? Json.object("t", "antwort", "id", r.id(), "s", r.status(), "len", r.length())
                        : Json.object("t", "antwort", "id", r.id(), "s", r.status(), "ct", r.contentType(),
                                "len", r.length());
                chunk = r.chunk();
            }
            case Message.Data d -> {
                header = Json.object("t", "daten", "id", d.id());
                chunk = d.chunk();
            }
            case Message.Abort a -> header = Json.object("t", "abbruch", "id", a.id());
        }
        byte[] h = header.getBytes(StandardCharsets.UTF_8);
        if (h.length > MAX_HEADER || chunk.length() > MAX_CHUNK) {
            throw new IllegalArgumentException("Nachricht zu groß für Kanal v1");
        }
        byte[] out = new byte[2 + h.length + chunk.length()];
        out[0] = (byte) (h.length >>> 8);
        out[1] = (byte) h.length;
        System.arraycopy(h, 0, out, 2, h.length);
        System.arraycopy(chunk.buffer(), chunk.offset(), out, 2 + h.length, chunk.length());
        return out;
    }
}
