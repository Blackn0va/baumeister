package com.blackn0va.baumeister.relay;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Pins nach docs/kanal-v1.md, Abschnitt 1: {@code sha256:} + 64 Hex-Zeichen klein über die DER-Bytes des
 * Blattzertifikats (nicht nur des Schlüssels – ein exakter Pin ist strenger als jede Namens- oder CA-Prüfung).
 * Namen der Pins: [a-z0-9-]{1,32}.
 *
 * Ins Log kommt nie ein ganzer Pin, nur brief(): die ersten 8 Hex-Zeichen. Ein Pin ist kein Geheimnis, aber das Log
 * soll niemandem die Arbeit abnehmen, ein Zertifikat zu einem Eintrag zu finden; zum Wiedererkennen reichen 32 Bit.
 *
 * Threads: zustandslos. Fehler: CertificateEncodingException, wenn ein Zertifikat sich nicht kodieren lässt.
 */
final class Pins {

    static final Pattern NAME = Pattern.compile("[a-z0-9-]{1,32}");
    static final Pattern PIN = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final String PREFIX = "sha256:";

    private Pins() {
    }

    static String of(byte[] der) {
        try {
            return PREFIX + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(der));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fehlt im JDK", e);
        }
    }

    static String of(X509Certificate certificate) throws CertificateEncodingException {
        return of(certificate.getEncoded());
    }

    /** Die ersten 8 Hex-Zeichen – genug zum Wiedererkennen im Log. */
    static String brief(String pin) {
        return pin.startsWith(PREFIX) && pin.length() >= PREFIX.length() + 8
                ? pin.substring(PREFIX.length(), PREFIX.length() + 8) : "?";
    }
}
