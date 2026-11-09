package com.blackn0va.baumeister.relay;

/**
 * Ablehnungsgründe des Kanals v1 samt Close-Code (docs/kanal-v1.md, Abschnitte 2, 3 und 8).
 *
 * Die Codes sind Protokollbezeichner: App und Relay vergleichen sie wörtlich, die Testvektoren nennen sie. Deshalb
 * bleiben sie ASCII, so wie sie in der Spezifikation stehen.
 *
 * Über die Spezifikation hinaus (Stand 2026-11-08):
 * - hallo_fehlt, hallo_doppelt, kennung_ungueltig, max_ungueltig, version_ungueltig (1008), rahmen_zu_gross (1009):  // umlaut-ok
 *   so heißen die Fälle in der fertigen Host-App (P2). Das Relay benutzt dieselben Namen – ein Satz Gründe für beide
 *   Seiten; die Spezifikation wird danach nachgezogen.
 * - rahmen_ungueltig: nur im Relay – unbekannter Opcode, nicht minimale Längenangabe, 64-Bit-Länge mit gesetztem  // umlaut-ok
 *   höchsten Bit, kaputter Close-Rahmen (RFC 6455 verlangt für alle vier den Abbruch).
 *
 * Close-Codes: 1003 nur für den Textrahmen („falscher Datentyp“), 1009 für alles, was zu groß ist, 1008 für die
 * Version, sonst 1002 (Protokollfehler) – wie in der App.
 */
enum Reason {
    TEXT_FRAME("textrahmen", 1003),
    RSV("rsv", 1002),
    MASK_MISSING("maske_fehlt", 1002),
    MASK_FROM_SERVER("maske_vom_server", 1002),
    FRAGMENT("fragment", 1002),
    CONTROL_TOO_LONG("steuerrahmen_zu_lang", 1002),
    HEADER_EMPTY("kopf_leer", 1002),
    HEADER_TOO_LONG("kopf_zu_lang", 1009),
    HEADER_TRUNCATED("kopf_abgeschnitten", 1002),
    HEADER_NOT_UTF8("kopf_kein_utf8", 1002),
    HEADER_NOT_OBJECT("kopf_kein_objekt", 1002),
    TYPE_UNKNOWN("typ_unbekannt", 1002),
    ID_MISSING("id_fehlt", 1002),
    ID_INVALID("id_ungueltig", 1002),  // umlaut-ok: Protokollcode aus docs/kanal-v1.md
    STATUS_INVALID("status_ungueltig", 1002),  // umlaut-ok: Protokollcode
    CHUNK_TOO_BIG("stueck_zu_gross", 1009),  // umlaut-ok: Protokollcode
    LENGTH_INVALID("laenge_ungueltig", 1002),  // umlaut-ok: Protokollcode
    PATH_INVALID("pfad_ungueltig", 1002),  // umlaut-ok: Protokollcode
    METHOD_INVALID("methode_ungueltig", 1002),  // umlaut-ok: Protokollcode
    CONTENT_TYPE_INVALID("typ_ungueltig", 1002),  // umlaut-ok: Protokollcode
    FRAME_INVALID("rahmen_ungueltig", 1002),  // umlaut-ok: Protokollcode (Ergänzung, siehe oben)
    FRAME_TOO_BIG("rahmen_zu_gross", 1009),  // umlaut-ok: Protokollcode wie in der App
    HELLO_MISSING("hallo_fehlt", 1002),
    HELLO_DUPLICATE("hallo_doppelt", 1002),
    PEER_ID_INVALID("kennung_ungueltig", 1002),  // umlaut-ok: Protokollcode wie in der App
    MAX_INVALID("max_ungueltig", 1002),  // umlaut-ok: Protokollcode wie in der App
    VERSION_INVALID("version_ungueltig", 1008);  // umlaut-ok: Protokollcode wie in der App

    private final String code;
    private final int closeCode;

    Reason(String code, int closeCode) {
        this.code = code;
        this.closeCode = closeCode;
    }

    /** Grund im Close-Rahmen und im Log, genau wie in den Testvektoren. */
    String code() {
        return code;
    }

    int closeCode() {
        return closeCode;
    }
}
