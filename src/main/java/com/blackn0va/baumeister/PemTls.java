package com.blackn0va.baumeister;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * TLS aus den PEM-Dateien von certbot (fullchain.pem, privkey.pem) für den Modpaket-Download (Wunsch 2026-11-08). Ohne
 * Fremdbibliothek: Zertifikate liest CertificateFactory, der Schlüssel muss PKCS#8
 * sein („BEGIN PRIVATE KEY“) – so schreibt ihn certbot seit 2.0 für ECDSA und RSA. „BEGIN EC/RSA PRIVATE KEY“
 * (älteres Format) wird mit einem deutschen Grund abgelehnt statt falsch gelesen.
 *
 * Threads: zustandslos. Fehler: IOException mit Grund (Datei fehlt, abgelaufen, Schlüssel passt nicht zum Zertifikat).
 */
final class PemTls {

    private static final Pattern BLOCK = Pattern.compile(
            "-----BEGIN ([A-Z ]+)-----([A-Za-z0-9+/=\\s]+)-----END \\1-----");

    private PemTls() {
    }

    /** SSLContext mit Kette und Schlüssel; prüft Gültigkeit und dass Schlüssel und erstes Zertifikat zusammenpassen. */
    static SSLContext context(Path chainFile, Path keyFile, long nowMillis) throws IOException {
        List<X509Certificate> chain = certificates(Files.readString(chainFile, StandardCharsets.US_ASCII));
        if (chain.isEmpty()) {
            throw new IOException("In " + chainFile.getFileName() + " steht kein Zertifikat");
        }
        X509Certificate leaf = chain.getFirst();
        if (leaf.getNotAfter().getTime() < nowMillis || leaf.getNotBefore().getTime() > nowMillis) {
            throw new IOException("Zertifikat gilt nicht (gültig " + leaf.getNotBefore() + " bis " + leaf.getNotAfter()
                    + ")");
        }
        PrivateKey key = privateKey(Files.readString(keyFile, StandardCharsets.US_ASCII),
                leaf.getPublicKey().getAlgorithm());
        try {
            // Passt der Schlüssel zum Zertifikat? Einmal signieren und mit dem öffentlichen Schlüssel prüfen.
            String alg = key.getAlgorithm().equals("EC") ? "SHA256withECDSA" : "SHA256withRSA";
            java.security.Signature s = java.security.Signature.getInstance(alg);
            s.initSign(key);
            byte[] probe = "baumeister".getBytes(StandardCharsets.US_ASCII);
            s.update(probe);
            byte[] sig = s.sign();
            s.initVerify(leaf.getPublicKey());
            s.update(probe);
            if (!s.verify(sig)) {
                throw new IOException("Schlüssel passt nicht zum Zertifikat");
            }
            char[] pw = new char[0];
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setKeyEntry("download", key, pw, chain.toArray(Certificate[]::new));
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, pw);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new IOException("TLS lässt sich nicht einrichten: " + e.getMessage(), e);
        }
    }

    static List<X509Certificate> certificates(String pem) throws IOException {
        List<X509Certificate> out = new ArrayList<>();
        Matcher m = BLOCK.matcher(pem);
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            while (m.find()) {
                if (m.group(1).equals("CERTIFICATE")) {
                    out.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(decode(m.group(2)))));
                }
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("Zertifikat unlesbar: " + e.getMessage(), e);
        }
        return out;
    }

    static PrivateKey privateKey(String pem, String algorithm) throws IOException {
        Matcher m = BLOCK.matcher(pem);
        while (m.find()) {
            String kind = m.group(1);
            if (kind.equals("PRIVATE KEY")) {
                try {
                    return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(decode(m.group(2))));
                } catch (GeneralSecurityException e) {
                    throw new IOException("Schlüssel unlesbar (" + algorithm + "): " + e.getMessage(), e);
                }
            }
            if (kind.endsWith("PRIVATE KEY")) {
                throw new IOException("Schlüssel im Format „" + kind + "“ – gebraucht wird PKCS#8 („PRIVATE KEY“), "
                        + "z. B. openssl pkcs8 -topk8 -nocrypt");
            }
        }
        throw new IOException("Kein privater Schlüssel in der Datei");
    }

    private static byte[] decode(String body) {
        return Base64.getMimeDecoder().decode(body.replaceAll("\\s", ""));
    }
}
