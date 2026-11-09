package com.blackn0va.baumeister.relay;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Schlüssel und Zertifikat des Relays aus dem PKCS12-Schlüsselbund (erzeugt von relay/deploy/
 * schluessel-erzeugen.sh). pin ist der Server-Pin, den die App eingetragen bekommt.
 *
 * Fail-closed: genau ein Schlüsseleintrag, Kette genau ein selbst signiertes Zertifikat, Schlüssel EC P-256 (beides
 * legt docs/kanal-v1.md, Abschnitt 1 fest). Ein RSA-Schlüssel liefe zwar, passte aber nicht zur festen Liste der
 * Cipher-Suites (nur ECDHE-ECDSA) – lieber beim Start mit klarem Satz scheitern als im ersten Handshake.
 *
 * Das Passwort lebt nur als char[] und wird nach dem Laden überschrieben; es steht in keinem Log und in keiner
 * Ausnahme (ASVS V2.10.4, V7.1.1).
 *
 * Threads: unveränderlich. Fehler: ConfigException.
 */
record KeyMaterial(PrivateKey key, X509Certificate certificate, String pin) {

    private static final int MAX_PASSWORD_FILE = 1024;

    @Override
    public String toString() {
        return "KeyMaterial[pin=" + Pins.brief(pin) + "…]";
    }

    static KeyMaterial load(Path keystore, Path passwordFile) throws ConfigException {
        char[] password = password(passwordFile);
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(keystore)) {
                ks.load(in, password);
            } catch (IOException e) {
                throw new ConfigException("Schlüsselbund " + keystore + " lässt sich nicht öffnen (Passwort falsch, "
                        + "Datei fehlt oder ist kein PKCS12): " + e.getClass().getSimpleName(), e);
            }
            List<String> keys = new ArrayList<>();
            for (String alias : Collections.list(ks.aliases())) {
                if (ks.isKeyEntry(alias)) {
                    keys.add(alias);
                }
            }
            if (keys.size() != 1) {
                throw new ConfigException("Schlüsselbund " + keystore + " enthält " + keys.size()
                        + " Schlüssel – erwartet genau einen (schluessel-erzeugen.sh legt einen an).");
            }
            String alias = keys.get(0);
            if (!(ks.getKey(alias, password) instanceof PrivateKey key)) {
                throw new ConfigException("Eintrag „" + alias + "“ in " + keystore + " ist kein privater Schlüssel.");
            }
            Certificate[] chain = ks.getCertificateChain(alias);
            if (chain == null || chain.length != 1 || !(chain[0] instanceof X509Certificate cert)) {
                throw new ConfigException("Zertifikatskette in " + keystore + " muss genau ein X.509-Zertifikat sein "
                        + "(selbst signiert, gepinnt – keine CA).");
            }
            if (!(key instanceof ECPrivateKey ec) || !isP256(ec)) {
                throw new ConfigException("Schlüssel in " + keystore + " ist nicht EC P-256 (Kanal v1, Abschnitt 1).");
            }
            if (!cert.getPublicKey().getAlgorithm().equals("EC")) {
                throw new ConfigException("Zertifikat in " + keystore + " passt nicht zum EC-Schlüssel.");
            }
            return new KeyMaterial(key, cert, Pins.of(cert));
        } catch (GeneralSecurityException e) {
            throw new ConfigException("Schlüsselbund " + keystore + " unbrauchbar: " + e.getClass().getSimpleName(), e);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /**
     * Ist der Schlüssel auf P-256? Verglichen werden die Kurvenparameter selbst (Kurve, Erzeuger, Ordnung, Kofaktor) mit
     * denen von secp256r1. Vorher der Name aus AlgorithmParameters: JDK 21 liefert dort die OID 1.2.840.10045.3.1.7,
     * JDK 25 den Namen „secp256r1“ – auf dev01 (Temurin 25) lehnte das Relay 2026-11-08 so seinen eigenen, richtig
     * erzeugten Schlüssel ab. Die Parameter sind von der JDK-Fassung unabhängig.
     */
    static boolean isP256(ECPrivateKey key) throws GeneralSecurityException {
        AlgorithmParameters p = AlgorithmParameters.getInstance("EC");
        p.init(new ECGenParameterSpec("secp256r1"));
        java.security.spec.ECParameterSpec ref = p.getParameterSpec(java.security.spec.ECParameterSpec.class);
        java.security.spec.ECParameterSpec got = key.getParams();
        return got != null && ref.getCurve().equals(got.getCurve()) && ref.getGenerator().equals(got.getGenerator())
                && ref.getOrder().equals(got.getOrder()) && ref.getCofactor() == got.getCofactor();
    }

    /** Erste Zeile der Datei ohne Zeilenende, streng UTF-8; leer oder über 1 KiB ist ein Fehler. */
    static char[] password(Path file) throws ConfigException {
        byte[] raw;
        try {
            if (Files.size(file) > MAX_PASSWORD_FILE) {
                throw new ConfigException("Passwortdatei " + file + " ist größer als 1 KiB.");
            }
            raw = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new ConfigException("Passwortdatei " + file + " nicht lesbar (" + e.getClass().getSimpleName()
                    + ") – läuft das Relay unter systemd mit LoadCredential=?", e);
        }
        try {
            int end = 0;
            while (end < raw.length && raw[end] != '\n' && raw[end] != '\r') {
                end++;
            }
            if (end == 0) {
                throw new ConfigException("Passwortdatei " + file + " ist leer.");
            }
            CharBuffer chars;
            try {
                chars = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(raw, 0, end));
            } catch (CharacterCodingException e) {
                throw new ConfigException("Passwortdatei " + file + " ist kein gültiges UTF-8.", e);
            }
            char[] password = new char[chars.remaining()];
            chars.get(password);
            Arrays.fill(chars.array(), '\0');
            return password;
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }
}
