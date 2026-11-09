package com.blackn0va.baumeister.relay;

import java.net.Socket;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.LongAdder;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * Prüft das Client-Zertifikat der App – ausschließlich über den Pin (docs/kanal-v1.md, Abschnitt 1):
 * Kette genau 1 Zertifikat lang, {@code sha256:<DER-Hash>} steht in den erlaubten Pins. Sonst nichts:
 * - keine CA: getAcceptedIssuers() ist leer, kein Zertifikat „bürgt“ für ein anderes. Eine Kette der Länge 2 mit
 *   erlaubtem Blatt wird trotzdem abgewiesen – die App schickt immer genau ihr eines Zertifikat, alles andere ist
 *   nicht die App.
 * - kein Gültigkeitsdatum: Der Pin ist der Vertrauensanker, Entzug geht über die Konfiguration (sofort wirksam). Ein
 *   Ablaufdatum hieße nur: Am Stichtag fällt die Verbindung aus, ohne dass es sicherer würde.
 * - Server-Zertifikate prüft das Relay nie (es ist kein Client) – checkServerTrusted lehnt immer ab.
 *
 * X509ExtendedTrustManager statt X509TrustManager: JSSE wickelt einen einfachen X509TrustManager in eigene Prüfungen
 * (Endpunkt-Identifizierung, Algorithmen-Beschränkungen) – hier soll genau diese Prüfung gelten, nicht mehr und nicht
 * weniger. Die Wiederaufnahme einer Sitzung ruft den Trust-Manager NICHT auf; deshalb prüft PublicListener den Pin nach
 * jedem Handshake noch einmal (und Wiederaufnahme ist ohnehin aus, siehe Tls).
 *
 * Threads: beliebig (PinStore ist lock-frei). Fehler: Rejected mit Grund fürs Log.
 */
final class PinTrustManager extends X509ExtendedTrustManager {

    private final PinStore pins;
    private final LongAdder checks = new LongAdder();

    /** Abgewiesenes Zertifikat; code: kein_zertifikat, kette_nicht_1, kein_x509, pin_unbekannt. */
    static final class Rejected extends CertificateException {

        private static final long serialVersionUID = 1L;

        Rejected(String code) {
            super(code);
        }
    }

    PinTrustManager(PinStore pins) {
        this.pins = pins;
    }

    /** Wie oft eine Kette geprüft wurde – ein Test zählt damit, dass jede Verbindung voll geprüft wird. */
    long checks() {
        return checks.sum();
    }

    /** Prüfung aus dem Handshake (zählt mit). */
    String check(Certificate[] chain) throws Rejected {
        checks.increment();
        return match(chain);
    }

    /** Name zum Pin der Kette – dieselbe Regel, ohne mitzuzählen (Nachprüfung nach dem Handshake). */
    String match(Certificate[] chain) throws Rejected {
        if (chain == null || chain.length == 0) {
            throw new Rejected("kein_zertifikat");
        }
        if (chain.length != 1) {
            throw new Rejected("kette_nicht_1");
        }
        if (!(chain[0] instanceof X509Certificate certificate)) {
            throw new Rejected("kein_x509");
        }
        String pin;
        try {
            pin = Pins.of(certificate);
        } catch (CertificateEncodingException e) {
            throw new Rejected("kein_x509");
        }
        String name = pins.nameFor(pin);
        if (name == null) {
            throw new Rejected("pin_unbekannt");
        }
        return name;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        check(chain);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        check(chain);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        check(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        throw new CertificateException("Das Relay prüft keine Server-Zertifikate");
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        throw new CertificateException("Das Relay prüft keine Server-Zertifikate");
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        throw new CertificateException("Das Relay prüft keine Server-Zertifikate");
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }
}
