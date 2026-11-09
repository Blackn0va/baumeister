package com.blackn0va.baumeister.relay;

import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;

/**
 * TLS der öffentlichen Seite (docs/kanal-v1.md, Abschnitt 1; ASVS V9.1.1–V9.1.3):
 * - TLS 1.3, ersatzweise TLS 1.2 (Windows 10 kann in Schannel kein TLS 1.3) – in 1.2 nur ECDHE mit ECDSA und AEAD
 *   (AES-GCM, ChaCha20-Poly1305). Kein CBC, kein RSA-Schlüsseltausch, kein FFDHE.
 * - Client-Zertifikat Pflicht (needClientAuth), geprüft nur über den Pin (PinTrustManager).
 * - Keine Sitzungs-Wiederaufnahme: Bei einer Wiederaufnahme ruft JSSE den Trust-Manager nicht auf – ein entzogener Pin
 *   gälte für diese Verbindung weiter. Drei Riegel: keine zustandslosen Tickets (Systemeigenschaft, siehe
 *   hardenJsse), ein Sitzungscache von 1 Eintrag mit 1 s Lebenszeit, und PublicListener erklärt jede Sitzung nach dem
 *   Handshake für ungültig und prüft den Pin des Peers selbst noch einmal. Gemessen 2026-11-08 (Gegenproben gegen
 *   everyConnectionRunsAFullHandshakeWithoutResumption, Temurin 21.0.12): Tickets allein wieder an → Wiederaufnahme;
 *   nur invalidate() weg → Wiederaufnahme aus dem Cache (1 Eintrag/1 s reicht dafür); nur der Cache-Deckel weg → keine
 *   Wiederaufnahme. Tickets aus und invalidate() tragen also; der Deckel ist Reserve. Und selbst MIT Wiederaufnahme
 *   wies die Pin-Nachprüfung einen entzogenen Pin ab (removedPinIsRejectedAtTheNextConnection blieb grün).
 * - Keine vom Client angestoßene Neuverhandlung (TLS 1.2): Mit ihr könnte ein Client mitten in der Verbindung ein
 *   anderes Zertifikat vorlegen.
 *
 * Threads: hardenJsse() einmal vor dem ersten TLS-Zugriff im Prozess; der Rest zustandslos.
 * Fehler: GeneralSecurityException beim Aufbau des Kontexts.
 */
final class Tls {

    static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};
    static final String[] CIPHER_SUITES = {
        "TLS_AES_256_GCM_SHA384",
        "TLS_CHACHA20_POLY1305_SHA256",
        "TLS_AES_128_GCM_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
    };
    /** Nur elliptische Kurven für den Schlüsseltausch (Abschnitt 1: ECDHE); x25519 zuerst. */
    static final String[] NAMED_GROUPS = {"x25519", "secp256r1", "secp384r1"};
    /** Beide Schlüssel sind EC P-256 (Abschnitt 1); RSA-Zertifikate scheitern so schon im Handshake. */
    static final String[] SIGNATURE_SCHEMES = {"ecdsa_secp256r1_sha256", "ecdsa_secp384r1_sha384",
        "ecdsa_secp521r1_sha512"};

    private static final String ALIAS = "relay";

    private Tls() {
    }

    /**
     * JSSE-Systemeigenschaften – JSSE liest sie EINMAL beim ersten TLS-Zugriff im Prozess. Main setzt sie deshalb als
     * Allererstes. Wer das später aufruft (oder die Zeilen löscht), bekommt zustandslose TLS-1.3-Tickets zurück:
     * Wiederaufnahme ohne Cache, ohne Trust-Manager, auch nach dem Entzug eines Pins (der Test
     * removedPinIsRejectedAtTheNextConnectionWithoutResumption zählt die Prüfungen je Verbindung).
     */
    static void hardenJsse() {
        System.setProperty("jdk.tls.server.enableSessionTicketExtension", "false");
        System.setProperty("jdk.tls.rejectClientInitiatedRenegotiation", "true");
    }

    static SSLContext serverContext(KeyMaterial keys, PinTrustManager trust) throws GeneralSecurityException {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(new KeyManager[] {new ServerKeyManager(keys.key(), keys.certificate())},
                new TrustManager[] {trust}, new SecureRandom());
        SSLSessionContext sessions = ctx.getServerSessionContext();
        // 0 hieße „unbegrenzt“ – 1 ist das Kleinste, was JSSE annimmt.
        sessions.setSessionCacheSize(1);
        sessions.setSessionTimeout(1);
        check(ctx);
        return ctx;
    }

    /** Alle festen Listen müssen vom JDK unterstützt sein – sonst beim Start scheitern, nicht im ersten Handshake. */
    private static void check(SSLContext ctx) throws GeneralSecurityException {
        SSLParameters supported = ctx.getSupportedSSLParameters();
        List<String> protocols = Arrays.asList(supported.getProtocols());
        List<String> suites = Arrays.asList(supported.getCipherSuites());
        for (String p : PROTOCOLS) {
            if (!protocols.contains(p)) {
                throw new GeneralSecurityException("JDK kann " + p + " nicht");
            }
        }
        for (String s : CIPHER_SUITES) {
            if (!suites.contains(s)) {
                throw new GeneralSecurityException("JDK kann " + s + " nicht");
            }
        }
    }

    /** Parameter für einen angenommenen Socket (Server-Seite). */
    static void configure(SSLSocket socket) {
        SSLParameters p = socket.getSSLParameters();
        p.setProtocols(PROTOCOLS);
        p.setCipherSuites(CIPHER_SUITES);
        p.setUseCipherSuitesOrder(true);
        p.setNeedClientAuth(true);
        p.setNamedGroups(NAMED_GROUPS);
        p.setSignatureSchemes(SIGNATURE_SCHEMES);
        socket.setSSLParameters(p);
        socket.setUseClientMode(false);
    }

    /**
     * Genau der eine Schlüssel des Relays, nur für EC. Eigener Key-Manager statt KeyManagerFactory: Die wählt einen
     * Alias nach Heuristik; hier gibt es nichts zu wählen.
     */
    private static final class ServerKeyManager extends X509ExtendedKeyManager {

        private final PrivateKey key;
        private final X509Certificate certificate;

        ServerKeyManager(PrivateKey key, X509Certificate certificate) {
            this.key = key;
            this.certificate = certificate;
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return "EC".equals(keyType) ? new String[] {ALIAS} : null;
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return "EC".equals(keyType) ? ALIAS : null;
        }

        @Override
        public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
            return "EC".equals(keyType) ? ALIAS : null;
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return ALIAS.equals(alias) ? new X509Certificate[] {certificate} : null;
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return ALIAS.equals(alias) ? key : null;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return null;
        }

        @Override
        public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
            return null;
        }
    }
}
