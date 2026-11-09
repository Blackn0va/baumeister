package com.blackn0va.baumeister.relay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * WebSocket-Rahmen über die Testvektoren hinaus: Längengrenzen, nicht minimale Längen, unbekannte Opcodes, Fragmente,
 * übergroße Rahmen (abgelehnt, bevor Speicher angelegt wird), Close-Regeln, Zufallsbytes.
 */
class FramesTest {

    private static final byte[] MASK = {0x37, (byte) 0xFA, 0x21, 0x3D};

    private static Frames.Frame read(byte[] frame, boolean fromClient) throws IOException, ProtocolException {
        return new Frames.Reader(new ByteArrayInputStream(frame), fromClient, Codec.MAX_PAYLOAD).read();
    }

    private static String reason(byte[] frame, boolean fromClient) {
        try {
            read(frame, fromClient);
        } catch (ProtocolException e) {
            return e.reason().code();
        } catch (IOException e) {
            return "io:" + e.getClass().getSimpleName();
        }
        fail("Rahmen nicht abgelehnt");
        return null;
    }

    private static byte[] bytes(int... b) {
        byte[] out = new byte[b.length];
        for (int i = 0; i < b.length; i++) {
            out[i] = (byte) b[i];
        }
        return out;
    }

    @Test
    void lengthsAreDecodedAtEveryBoundary() throws Exception {
        for (int n : new int[] {0, 1, 125, 126, 127, 65_535, 65_536, Codec.MAX_PAYLOAD}) {
            byte[] p = new byte[n];
            new Random(n).nextBytes(p);
            assertArrayEquals(p, read(Frames.server(Frames.BINARY, p), false).payload(), "unmaskiert " + n);
            assertArrayEquals(p, read(Frames.client(Frames.BINARY, p, MASK), true).payload(), "maskiert " + n);
        }
    }

    /** RFC 6455, 5.2: Die Länge MUSS minimal kodiert sein. */
    @Test
    void nonMinimalLengthsAreRejected() {
        assertEquals("rahmen_ungueltig", reason(bytes(0x82, 0xFE, 0x00, 100, 1, 2, 3, 4), true));  // umlaut-ok
        assertEquals("rahmen_ungueltig", reason(bytes(0x82, 0xFF, 0, 0, 0, 0, 0, 0, 0x03, 0xE8, 1, 2, 3, 4),  // umlaut-ok
                true));
        assertEquals("rahmen_ungueltig", reason(bytes(0x82, 0xFF, 0x80, 0, 0, 0, 0, 1, 0, 0, 1, 2, 3, 4), true));  // umlaut-ok
    }

    @Test
    void unknownOpcodesAreRejected() {
        for (int op : new int[] {3, 4, 5, 6, 7, 0xB, 0xC, 0xD, 0xE, 0xF}) {
            assertEquals("rahmen_ungueltig", reason(bytes(0x80 | op, 0x80, 1, 2, 3, 4), true), "Opcode " + op);  // umlaut-ok
        }
    }

    @Test
    void continuationFramesAndFragmentedControlFramesAreRejected() {
        assertEquals("fragment", reason(bytes(0x80, 0x80, 1, 2, 3, 4), true));
        assertEquals("fragment", reason(bytes(0x09, 0x80, 1, 2, 3, 4), true));
        assertEquals("fragment", reason(bytes(0x01, 0x80, 1, 2, 3, 4), true));
    }

    /** Behauptete 2^40 Bytes: abgelehnt nach 10 Kopfbytes, ohne einen Puffer dieser Größe anzulegen. */
    @Test
    void oversizedFramesAreRejectedBeforeTheirPayloadIsRead() {
        assertEquals("rahmen_zu_gross", reason(bytes(0x82, 0xFF, 0, 0, 1, 0, 0, 0, 0, 0), true));  // umlaut-ok
        byte[] justOver = new byte[Codec.MAX_PAYLOAD + 1];
        assertEquals("rahmen_zu_gross", reason(Frames.client(Frames.BINARY, justOver, MASK), true));  // umlaut-ok
        assertEquals("steuerrahmen_zu_lang", reason(bytes(0x8A, 0xFE, 0x00, 126), true));
        assertEquals("steuerrahmen_zu_lang", reason(Frames.server(Frames.CLOSE, new byte[126]), false));
    }

    @Test
    void truncatedFramesEndWithEof() {
        assertThrows(EOFException.class, () -> read(new byte[0], true));
        assertThrows(EOFException.class, () -> read(bytes(0x82), true));
        assertThrows(EOFException.class, () -> read(bytes(0x82, 0xFE, 0x01), true));
        assertThrows(EOFException.class, () -> read(bytes(0x82, 0x85, 1, 2, 3, 4, 5), true));
    }

    @Test
    void closePayloadsFollowRfc6455() throws Exception {
        Frames.checkClose(new byte[0]);
        for (int code : new int[] {1000, 1001, 1002, 1003, 1007, 1008, 1009, 1010, 1011, 1012, 1013, 1014, 3000, 4999}) {
            Frames.checkClose(Frames.closePayload(code, "grund"));
        }
        for (int code : new int[] {0, 999, 1004, 1005, 1006, 1015, 1016, 2999, 5000, 65_535}) {
            int c = code;
            assertThrows(ProtocolException.class, () -> Frames.checkClose(Frames.closePayload(c, "")), "Code " + c);
        }
        assertThrows(ProtocolException.class, () -> Frames.checkClose(new byte[] {3}));
        assertThrows(ProtocolException.class, () -> Frames.checkClose(new byte[] {3, (byte) 0xE8, (byte) 0xFF}));
        assertEquals(1008, Frames.closeCode(Frames.closePayload(1008, "pin_entzogen")));
        assertEquals("pin_entzogen", Frames.closeReason(Frames.closePayload(1008, "pin_entzogen")));
        assertThrows(IllegalArgumentException.class, () -> Frames.closePayload(1000, "x".repeat(124)));
    }

    @Test
    void controlFramesFitTheirLimit() throws Exception {
        byte[] p = new byte[Frames.MAX_CONTROL];
        assertEquals(Frames.PING, read(Frames.client(Frames.PING, p, MASK), true).opcode());
        assertEquals(Frames.PONG, read(Frames.server(Frames.PONG, p), false).opcode());
    }

    /** Zufallsbytes und verfälschte gültige Rahmen: nur ProtocolException oder IOException, nie etwas anderes. */
    @Test
    void randomBytesNeverThrowAnythingButProtocolOrIoExceptions() {
        Random r = new Random(4711);
        byte[] valid = Frames.client(Frames.BINARY, Codec.encode(new Message.Abort(7)), MASK);
        int rejected = 0;
        for (int i = 0; i < 40_000; i++) {
            byte[] f;
            if (i % 2 == 0) {
                f = new byte[r.nextInt(64)];
                r.nextBytes(f);
            } else {
                f = valid.clone();
                f[r.nextInt(f.length)] ^= (byte) (1 << r.nextInt(8));
            }
            try {
                Frames.Frame fr = read(f, true);
                if (fr.opcode() == Frames.BINARY) {
                    Codec.decode(fr.payload(), Codec.Direction.APP_TO_RELAY);
                }
            } catch (ProtocolException | IOException e) {
                rejected++;
            }
        }
        assertTrue(rejected > 30_000, "zu wenig abgelehnt: " + rejected);
    }
}
