package com.blackn0va.baumeister.town.memory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pseudonym je Spieler („Spieler KM“) für Prompt und Gedächtnis der Bewohner (Vertrag v013, AK-41, Nicht-Ziel „kein
 * Spielername an die KI“).
 *
 * Warum HMAC statt Zähler oder UUID-Ausschnitt: Ein Ausschnitt der UUID ließe sich gegen die öffentliche
 * Spielerliste zurückrechnen, ein Zähler bräuchte eine Tabelle UUID → Pseudonym im Datenordner, die genau diese
 * Zuordnung offenlegt. HMAC-SHA256 mit einem Geheimnis, das nur im Datenordner liegt (zufällig beim ersten Start),
 * ist stabil je UUID und ohne das Geheimnis nicht umkehrbar (OWASP A02, ASVS V6.2.1 – geprüftes Verfahren, kein
 * Eigenbau). Wer das Geheimnis löscht, bekommt neue Pseudonyme; das alte Gedächtnis zeigt dann auf niemanden mehr.
 *
 * Zwei Buchstaben = 676 Pseudonyme: Bei einer Handvoll Spielern ist eine Doppelung selten, und sie kostet nur, dass
 * ein Bewohner zwei Spieler verwechselt. Ein Leerzeichen im Pseudonym schließt aus, dass es einem echten Namen gleicht
 * (Minecraft-Namen haben keins).
 *
 * Threads: unveränderlich nach dem Bau, Mac je Aufruf neu (Mac ist nicht threadsicher). Fehler: open wirft
 * IOException, wenn der Schlüssel weder zu lesen noch anzulegen ist – der Aufrufer nimmt dann ephemeral().
 */
public final class Pseudonyms {

    /** Dateiname des Geheimnisses im Datenordner des Stadtlebens. */
    public static final String KEY_FILE = "pseudonym.schluessel";  // umlaut-ok: Dateiname
    static final int KEY_BYTES = 32;
    private static final String ALGO = "HmacSHA256";

    private final byte[] secret;

    Pseudonyms(byte[] secret) {
        if (secret == null || secret.length < 16) {
            throw new IllegalArgumentException("Geheimnis zu kurz");
        }
        this.secret = secret.clone();
    }

    /** Geheimnis aus dir lesen oder zufällig anlegen (atomar umbenannt, damit kein halber Schlüssel liegen bleibt). */
    public static Pseudonyms open(Path dir) throws IOException {
        Path file = dir.resolve(KEY_FILE);
        if (Files.isRegularFile(file)) {
            byte[] b = Files.readAllBytes(file);
            if (b.length == KEY_BYTES) {
                return new Pseudonyms(b);
            }
            // Falsche Länge: kaputt geschrieben oder fremd. Nicht still weiterverwenden – neu anlegen; die alten
            // Pseudonyme passen dann nicht mehr, das Gedächtnis verliert nur seine Zuordnung.
        }
        Files.createDirectories(dir);
        byte[] fresh = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(fresh);
        Path tmp = Files.createTempFile(dir, "schluessel", ".tmp");  // umlaut-ok: Dateiname
        Files.write(tmp, fresh);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return new Pseudonyms(fresh);
    }

    /** Nur im Speicher (Tests, oder wenn der Datenordner nicht beschreibbar ist): Pseudonyme gelten bis zum Neustart. */
    public static Pseudonyms ephemeral() {
        byte[] b = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(b);
        return new Pseudonyms(b);
    }

    /** „Spieler “ und zwei Großbuchstaben aus dem HMAC der UUID. */
    public String of(UUID player) {
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(new SecretKeySpec(secret, ALGO));
            ByteBuffer buf = ByteBuffer.allocate(16);
            buf.putLong(player.getMostSignificantBits()).putLong(player.getLeastSignificantBits());
            mac.update("stadtleben-pseudonym-v1".getBytes(StandardCharsets.US_ASCII));
            byte[] h = mac.doFinal(buf.array());
            char a = (char) ('A' + Math.floorMod(h[0], 26));
            char b = (char) ('A' + Math.floorMod(h[1], 26));
            return "Spieler " + a + b;
        } catch (GeneralSecurityException e) {
            // HmacSHA256 gehört zu jeder Java-Laufzeit (JCA-Pflichtalgorithmus); fehlt er, ist die JVM kaputt.
            throw new IllegalStateException("hmac_unavailable", e);
        }
    }
}
