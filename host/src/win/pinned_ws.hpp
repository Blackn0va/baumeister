// Gepinnte WebSocket-Verbindung (wss://) über WinHTTP für Kanal v1 (docs/kanal-v1.md): eigene Sitzung OHNE Proxy,
// eigenes Client-Zertifikat (win/client_cert.hpp), Server-Pins statt CA.
//
// Warum das keine abgeschaltete Zertifikatsprüfung ist (win/http.hpp: „Zertifikatsprüfung ist NIE abschaltbar“ gilt
// weiter): Das Relay hat ein selbst signiertes Zertifikat; die Windows-Prüfung lehnt es ab (gemessen: 12175, Flags
// 0x18 = unbekannte CA + falscher Name). Statt einer CA zählt hier der exakte Pin – SHA-256 über das ganze
// Blattzertifikat. Er lässt genau EIN Zertifikat zu und ist damit strenger als jede CA- und Namensprüfung (eine CA
// stellt jedem Namen beliebig viele Zertifikate aus). Deshalb gilt:
//  - SECURITY_FLAG_IGNORE_UNKNOWN_CA und _CERT_CN_INVALID werden NUR gesetzt, wenn 1–2 gültige Pins übergeben sind.
//    Ohne Pin baut connect_pinned_ws keine Verbindung auf (fail-closed, kein Schalter „ohne Prüfung“).
//  - Alles andere prüft Schannel weiter: Ablaufdatum, Signatur, TLS-Version (nur 1.3/1.2), Schlüsselbesitz im
//    Handshake.
//  - Der Pin wird ZWEIMAL geprüft (gemessen, probes/winhttp_pin, 2026-11-08):
//    1. Im Rückruf SENDING_REQUEST ist das Serverzertifikat nach dem Handshake lesbar, BEVOR ein Byte der Anfrage
//       hinausgeht. Passt der Pin nicht, schließt der Rückruf das Handle: WinHTTP meldet 12017, die Gegenstelle
//       empfängt 0 HTTP-Bytes. Ohne diesen Abbruch gingen 243 Bytes Upgrade-Anfrage hinaus (ohne Nutzdaten).
//    2. Vor WinHttpWebSocketCompleteUpgrade noch einmal – keine Kanal-Nachricht geht an ein ungeprüftes Gegenüber,
//       auch wenn der Rückruf aus irgendeinem Grund nicht kam.
//  - Keine Weiterleitungen, keine Cookies, keine Windows-Anmeldung, kein Proxy: Der Proxy sähe sonst Name und Zeitpunkt
//    jeder Verbindung und könnte TLS nicht aufbrechen, nur stören – die Sitzung ist absichtlich direkt.
// ASVS V9.1.1/V9.2.1 (TLS, geprüfte Zertifikate), V9.2.4 sinngemäß; OWASP A02/A07.
//
// Was WinHTTP an Rahmen selbst erledigt (gemessen, gleiche Probe):
//  - Senden: jede Nachricht als EIN Binärrahmen (FIN=1, maskiert), auch 69 634 Bytes.
//  - Empfangen: große Rahmen kommen in Stücken (BINARY_FRAGMENT je TCP-Lieferung, ~16 KiB), deshalb setzt receive()
//    bis zur Obergrenze zusammen. Ein vom Server fragmentierter Rahmen ist davon nicht zu unterscheiden.
//  - Maskierte Server-Rahmen, RSV-Bits, Ping > 125 Bytes: WinHTTP bricht mit 12152 ab und trennt.
//  - Textrahmen reicht WinHTTP durch (UTF8_MESSAGE) – receive() meldet sie als Text, der Aufrufer schließt mit 1003.
//  - Pings des Servers beantwortet WinHTTP selbst (Pong mit derselben Nutzlast); Steuerrahmen sieht der Aufrufer nie.
//  - Lebenszeichen der App: WinHTTP schickt unaufgeforderte Pongs (RFC 6455 §5.5.3, op=10, leer), Vorgabe alle 30 s,
//    hier 20 s (WINHTTP_OPTION_WEB_SOCKET_KEEPALIVE_INTERVAL – nur am WebSocket-Handle setzbar, an Sitzung und Anfrage
//    12018). Eine Empfangsfrist gibt es nicht: WINHTTP_OPTION_RECEIVE_TIMEOUT am WebSocket-Handle → 12018, eine Frist
//    an der Anfrage erbt der WebSocket nicht (15 s Stille bei 5 s Frist überstanden). Eine tote Leitung bemerkt die App
//    deshalb über TCP: Das nächste Keepalive bleibt unbestätigt, TCP gibt nach seinen Wiederholungen auf, receive()
//    meldet einen Fehler (nicht gemessen – auf Loopback nicht nachstellbar).
//
// Threads: receive() in genau einem Lesethread; send_binary() aus beliebigen Threads (intern gesperrt); close() aus
// jedem Thread, idempotent, bricht ein hängendes receive() und send_binary() ab.
// Fehler: connect_pinned_ws liefert nullptr und füllt WsConnectError (deutscher Text, Art); receive() liefert
// WsMessage::Kind::Error bzw. Closed.

#pragma once

#include "win/http.hpp"

#include <cstddef>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

namespace swbh::win {

struct WsMessage {
    enum class Kind { Binary, Text, Closed, Error };
    Kind kind = Kind::Error;
    std::string data;    // Binary/Text
    int close_code = 0;  // Closed: Code des Gegenübers (0 = keiner)
    std::string text;    // Closed: Grund des Gegenübers; Error: deutscher Grund
};

// Eine offene WebSocket-Verbindung. Schnittstelle, damit relay/ ohne Netz testbar bleibt.
class WsConnection {
public:
    virtual ~WsConnection() = default;
    // Blockiert bis zur nächsten vollständigen Nachricht oder bis zum Ende der Verbindung.
    virtual WsMessage receive() = 0;
    // Eine Binärnachricht (ein Rahmen). false, wenn die Verbindung weg ist.
    virtual bool send_binary(std::string_view data) = 0;
    // Close-Rahmen mit Code und Grund (≤ 123 Bytes ASCII), dann Handle zu. Idempotent.
    virtual void close(int code, std::string_view reason) = 0;
};

struct PinnedWsOptions {
    std::string url;                       // wss://host[:port]/pfad
    std::vector<std::string> server_pins;  // 1–2 Pins (win::parse_pin) – Pflicht für wss
    const void* client_cert = nullptr;     // PCCERT_CONTEXT, Pflicht für wss; muss connect überleben
    std::string protocol;                  // Sec-WebSocket-Protocol, Pflicht (Token)
    std::string user_agent;                // Pflicht
    int connect_timeout_ms = 15000;        // Auflösen, Verbinden, TLS, Upgrade – je Schritt
    int keepalive_ms = 20000;              // unaufgeforderter Pong; WinHTTP verlangt mindestens 15 000
    std::size_t max_message_bytes = 2 + 4096 + 65536;
    // Nur Tests: ws://127.0.0.1:<port>/… ohne TLS und ohne Pins gegen eine Loopback-Gegenstelle. Aus keiner
    // Einstellung erreichbar (AppConfig kennt das Feld nicht).
    bool allow_plain_loopback_for_tests = false;
};

struct WsConnectError {
    enum class Kind {
        Config,       // Einstellungen unbrauchbar (Adresse, Pins, Zertifikat) – Wiederholen hilft nicht
        PinMismatch,  // Server zeigte ein anderes Zertifikat – abgebrochen, bevor etwas gesendet wurde
        Tls,          // Handshake gescheitert (Ablauf, eigener Schlüssel, Server verlangt etwas anderes)
        // Server lehnt ab: eigener Pin unbekannt (gemessen: TLS-Alarm certificate_unknown → 0x80090327, bei TLS 1.3
        // erst beim Lesen der Antwort), Abbruch nach dem Handshake, Upgrade ≠ 101 oder falsches Protokoll.
        Rejected,
        Connect,      // Name, Verbindung, Zeitüberschreitung
        Cancelled,
    };
    Kind kind = Kind::Connect;
    std::string text;       // deutsch, eine Zeile
    std::string found_pin;  // PinMismatch: Pin des gezeigten Zertifikats
};

// Baut die Verbindung auf. nullptr bei Fehler (error gefüllt). cancel bricht einen laufenden Aufbau ab.
std::unique_ptr<WsConnection> connect_pinned_ws(const PinnedWsOptions& options, WsConnectError& error,
                                                CancelToken* cancel = nullptr);

}  // namespace swbh::win
