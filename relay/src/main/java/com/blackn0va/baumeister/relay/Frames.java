package com.blackn0va.baumeister.relay;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * WebSocket-Rahmen nach RFC 6455, so eng, wie Kanal v1 sie braucht (docs/kanal-v1.md, Abschnitt 2):
 * nur Binärrahmen mit FIN=1, keine Fragmente, keine Erweiterungen (RSV immer 0), Steuerrahmen ≤ 125 Bytes, App → Relay
 * maskiert, Relay → App unmaskiert, Binär-Nutzlast ≤ 2 + 4096 + 65536.
 *
 * Der Leser prüft alles, bevor er Speicher anlegt: Eine behauptete Länge von 2^63 Bytes endet als Ablehnung, nicht als
 * OutOfMemoryError (ASVS V13.1.3). Längenangaben müssen minimal sein (RFC 6455, 5.2: „MUST“) – sonst Close mit
 * Reason.FRAME_INVALID.
 *
 * Threads: Reader gehört genau einem Leser-Thread; die statischen Schreiber sind zustandslos.
 * Fehler: ProtocolException mit Grund aus Abschnitt 2, EOFException bei abgerissener Verbindung.
 */
final class Frames {

    static final int CONTINUATION = 0x0;
    static final int TEXT = 0x1;
    static final int BINARY = 0x2;
    static final int CLOSE = 0x8;
    static final int PING = 0x9;
    static final int PONG = 0xA;
    static final int MAX_CONTROL = 125;

    private Frames() {
    }

    record Frame(int opcode, byte[] payload) {
    }

    /** Liest Rahmen einer Gegenseite. fromClient: Rahmen müssen maskiert sein (so liest das Relay die App). */
    static final class Reader {

        private final InputStream in;
        private final boolean fromClient;
        private final int maxBinary;

        Reader(InputStream in, boolean fromClient, int maxBinary) {
            this.in = in;
            this.fromClient = fromClient;
            this.maxBinary = maxBinary;
        }

        Frame read() throws IOException, ProtocolException {
            int b0 = in.read();
            if (b0 < 0) {
                throw new EOFException("Gegenseite hat die Verbindung geschlossen");
            }
            int b1 = in.read();
            if (b1 < 0) {
                throw new EOFException("Rahmen abgeschnitten");
            }
            int opcode = b0 & 0x0F;
            if ((b0 & 0x70) != 0) {
                throw new ProtocolException(Reason.RSV);
            }
            if (!(opcode <= BINARY || (opcode >= CLOSE && opcode <= PONG))) {
                throw new ProtocolException(Reason.FRAME_INVALID);
            }
            if ((b0 & 0x80) == 0 || opcode == CONTINUATION) {
                throw new ProtocolException(Reason.FRAGMENT);
            }
            if (opcode == TEXT) {
                throw new ProtocolException(Reason.TEXT_FRAME);
            }
            boolean masked = (b1 & 0x80) != 0;
            if (fromClient && !masked) {
                throw new ProtocolException(Reason.MASK_MISSING);
            }
            if (!fromClient && masked) {
                throw new ProtocolException(Reason.MASK_FROM_SERVER);
            }
            long length = b1 & 0x7F;
            if (length == 126) {
                length = unsigned(2);
                if (length < 126) {
                    throw new ProtocolException(Reason.FRAME_INVALID);
                }
            } else if (length == 127) {
                length = unsigned(8);
                // Höchstes Bit gesetzt (negativ) oder eine Länge, die in 16 Bit gepasst hätte: nicht minimal.
                if (length < 65_536) {
                    throw new ProtocolException(Reason.FRAME_INVALID);
                }
            }
            boolean control = opcode >= CLOSE;
            if (control && length > MAX_CONTROL) {
                throw new ProtocolException(Reason.CONTROL_TOO_LONG);
            }
            if (!control && length > maxBinary) {
                throw new ProtocolException(Reason.FRAME_TOO_BIG);
            }
            byte[] mask = masked ? readFully(4) : null;
            byte[] payload = readFully((int) length);
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i & 3];
                }
            }
            return new Frame(opcode, payload);
        }

        private long unsigned(int bytes) throws IOException {
            long v = 0;
            for (int i = 0; i < bytes; i++) {
                int b = in.read();
                if (b < 0) {
                    throw new EOFException("Rahmen abgeschnitten");
                }
                v = (v << 8) | b;
            }
            return v;
        }

        private byte[] readFully(int n) throws IOException {
            byte[] b = in.readNBytes(n);
            if (b.length != n) {
                throw new EOFException("Rahmen abgeschnitten");
            }
            return b;
        }
    }

    /** Rahmen des Relays: FIN=1, unmaskiert. */
    static byte[] server(int opcode, byte[] payload) {
        return frame(opcode, payload, null);
    }

    /** Rahmen der App (für Tests und Messproben): FIN=1, maskiert mit mask (4 Bytes). */
    static byte[] client(int opcode, byte[] payload, byte[] mask) {
        if (mask.length != 4) {
            throw new IllegalArgumentException("Maske braucht 4 Bytes");
        }
        return frame(opcode, payload, mask);
    }

    private static byte[] frame(int opcode, byte[] p, byte[] mask) {
        int len = p.length;
        int head = 2 + (len < 126 ? 0 : len < 65_536 ? 2 : 8) + (mask != null ? 4 : 0);
        byte[] f = new byte[head + len];
        f[0] = (byte) (0x80 | opcode);
        int m = mask != null ? 0x80 : 0;
        int i = 1;
        if (len < 126) {
            f[i++] = (byte) (m | len);
        } else if (len < 65_536) {
            f[i++] = (byte) (m | 126);
            f[i++] = (byte) (len >>> 8);
            f[i++] = (byte) len;
        } else {
            f[i++] = (byte) (m | 127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                f[i++] = (byte) ((long) len >>> shift);
            }
        }
        if (mask != null) {
            System.arraycopy(mask, 0, f, i, 4);
            i += 4;
            for (int k = 0; k < len; k++) {
                f[i + k] = (byte) (p[k] ^ mask[k & 3]);
            }
        } else {
            System.arraycopy(p, 0, f, i, len);
        }
        return f;
    }

    /** Nutzlast eines Close-Rahmens: Code (u16) und Grund (UTF-8, ≤ 123 Bytes). */
    static byte[] closePayload(int code, String reason) {
        byte[] r = reason.getBytes(StandardCharsets.UTF_8);
        if (r.length > MAX_CONTROL - 2) {
            throw new IllegalArgumentException("Close-Grund zu lang");
        }
        byte[] p = new byte[2 + r.length];
        p[0] = (byte) (code >>> 8);
        p[1] = (byte) code;
        System.arraycopy(r, 0, p, 2, r.length);
        return p;
    }

    /**
     * Close der Gegenseite prüfen (RFC 6455, 5.5.1 und 7.4): leer oder Code plus UTF-8-Grund; 1 Byte, ein
     * reservierter Code (1004–1006, 1015, unter 1000) oder kaputtes UTF-8 sind ein Protokollfehler.
     */
    static void checkClose(byte[] p) throws ProtocolException {
        if (p.length == 0) {
            return;
        }
        if (p.length == 1) {
            throw new ProtocolException(Reason.FRAME_INVALID);
        }
        int code = closeCode(p);
        boolean valid = (code >= 1000 && code <= 1003) || (code >= 1007 && code <= 1014)
                || (code >= 3000 && code <= 4999);
        if (!valid) {
            throw new ProtocolException(Reason.FRAME_INVALID);
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(p, 2, p.length - 2));
        } catch (CharacterCodingException e) {
            throw new ProtocolException(Reason.FRAME_INVALID);
        }
    }

    /** Code eines Close-Rahmens, -1 ohne Code. */
    static int closeCode(byte[] p) {
        return p.length < 2 ? -1 : ((p[0] & 0xFF) << 8) | (p[1] & 0xFF);
    }

    /** Grund eines Close-Rahmens (für Tests und Log), "" ohne Grund. */
    static String closeReason(byte[] p) {
        return p.length <= 2 ? "" : new String(p, 2, p.length - 2, StandardCharsets.UTF_8);
    }
}
