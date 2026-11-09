package com.blackn0va.baumeister.relay;

/**
 * Eine Nachricht des Kanals v1 (docs/kanal-v1.md, Abschnitt 3), schon geprüft. Gelesen und geschrieben über Codec.
 *
 * Threads: unveränderlich – bis auf das Datenstück, das einen fremden Puffer zeigt (siehe Chunk).
 */
sealed interface Message permits Message.Hello, Message.Request, Message.Response, Message.Data, Message.Abort {

    /**
     * Datenstück als Ausschnitt eines Puffers – ohne Kopie, weil jedes Stück bis zu 64 KiB groß ist und die Rahmen des
     * Relays sonst zweimal kopiert würden. Wer den Puffer danach ändert, ändert das Stück mit.
     */
    record Chunk(byte[] buffer, int offset, int length) {

        static final Chunk EMPTY = new Chunk(new byte[0], 0, 0);

        public Chunk {
            if (offset < 0 || length < 0 || offset + length > buffer.length) {
                throw new IllegalArgumentException("Stück außerhalb des Puffers");
            }
        }

        static Chunk of(byte[] all) {
            return new Chunk(all, 0, all.length);
        }

        byte[] copy() {
            byte[] b = new byte[length];
            System.arraycopy(buffer, offset, b, 0, length);
            return b;
        }
    }

    /**
     * hallo: from ist der Absender. id = Kennung der Gegenseite (app bzw. relay, druckbares ASCII ≤ 64 Zeichen), max =
     * parallele Aufträge der App (1…6); im hallo des Relays 0.
     */
    record Hello(Codec.Direction from, String id, int max) implements Message {
    }

    /** anfrage (nur Relay → App). contentType null = ohne ct. */
    record Request(long id, String method, String path, String contentType, long length, Chunk chunk)
            implements Message {
    }

    /** antwort (nur App → Relay). contentType null = ohne ct. */
    record Response(long id, int status, String contentType, long length, Chunk chunk) implements Message {
    }

    record Data(long id, Chunk chunk) implements Message {
    }

    record Abort(long id) implements Message {
    }
}
