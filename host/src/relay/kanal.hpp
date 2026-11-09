// Kanal v1 – Nachrichten zwischen Host-App und Relay (verbindlich: docs/kanal-v1.md, Testvektoren
// docs/kanal-v1-testvektoren.json). Reiner Code ohne Sockets und ohne Windows, damit jede Regel testbar ist.
//
// Eine Nachricht ist die Nutzlast eines WebSocket-Binärrahmens:
//   u16 Kopflänge (Big Endian, 1..4096) | Kopf: JSON-Objekt, UTF-8 | Datenstück 0..65 536 Bytes
// Den Rahmen selbst (Maske, FIN, Opcode) baut und prüft WinHTTP (win/pinned_ws.hpp); hier geht es um alles darin.
//
// Lesen ist streng, weil auf der anderen Seite ein Rechner im Internet steht (ASVS V5.1.3/V13.1.1, OWASP A03/A08):
//  - Kopf nur als wohlgeformtes UTF-8 und JSON-OBJEKT, doppelte Schlüssel und tiefe Schachtelung abgelehnt (zwei
//    Parser, die bei doppelten Schlüsseln verschieden wählen, sind ein klassischer Umgehungsweg).
//  - Jede Zahl ganzzahlig und im Bereich (id 1 … 2^32−1, s 100 … 599, len bis 2 bzw. 16 MiB, max 1 … 6).
//  - Pfade nur nach der Freigabeliste aus Abschnitt 5 (das Relay prüft vorher, die App prüft erneut).
//  - Typen je Richtung: Das Relay schickt nie eine „antwort“, die App nie eine „anfrage“.
// Unbekannte Felder werden überlesen, ein unbekannter Typ ist ein Fehler (Abschnitt 3).
//
// Threads: reentrant. Fehler: decode liefert den Grund (Testvektoren) statt einer Ausnahme; encode wirft
// std::invalid_argument nur bei Programmierfehlern (Nachricht verletzt selbst die Regeln).

#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <string_view>

namespace swbh::relay {

inline constexpr std::string_view kProtocol = "baumeister-kanal.v1";
inline constexpr std::string_view kChannelPath = "/kanal/v1";
inline constexpr int kVersion = 1;
inline constexpr std::size_t kMaxHeadBytes = 4096;
inline constexpr std::size_t kMaxPieceBytes = 65536;
inline constexpr std::size_t kMaxPayloadBytes = 2 + kMaxHeadBytes + kMaxPieceBytes;
inline constexpr std::uint64_t kMaxRequestBytes = 2ull * 1024 * 1024;
inline constexpr std::uint64_t kMaxResponseBytes = 16ull * 1024 * 1024;
// Höchstens so viele Aufträge gleichzeitig (hallo.max). Der eigene Server erlaubt 8 Verbindungen
// (server::kMaxConnections) – zwei bleiben frei, z. B. für einen Minecraft-Server auf diesem PC.
inline constexpr int kMaxJobs = 6;
inline constexpr std::size_t kMaxPathBytes = 2048;
inline constexpr std::size_t kMaxContentTypeBytes = 128;
inline constexpr std::size_t kMaxPeerNameBytes = 64;

// Close-Codes (Abschnitt 8).
inline constexpr int kCloseNormal = 1000;
inline constexpr int kCloseGoingAway = 1001;
inline constexpr int kCloseProtocol = 1002;
inline constexpr int kCloseDataType = 1003;
inline constexpr int kClosePolicy = 1008;
inline constexpr int kCloseTooBig = 1009;
inline constexpr int kCloseInternal = 1011;

// Vorgabe der Einstellungen. Vorher stand hier die Adresse des eigenen Servers (2026-11-08); seit das Repo öffentlich
// werden soll (2026-11-09) ein Platzhalter – jede Installation speichert ihre Adresse in der Config („fern_adresse“),
// die Vorgabe greift nur bei neuer oder ungültiger Config. Ohne eingetragenen Server-Pin baut die App ohnehin KEINE
// Verbindung auf (relay::validate).
inline constexpr std::string_view kDefaultRelayUrl = "wss://dein-server.example.net:25580/kanal/v1";
// wss://<host>[:<port>]/kanal/v1 – Host aus [A-Za-z0-9.-] (Name oder IPv4), Port 1 … 65535, nichts dahinter.
bool relay_url_allowed(std::string_view url);
// "host:port" für Meldungen (Port 443, wenn keiner angegeben ist); leer bei ungültiger Adresse.
std::string relay_host_port(std::string_view url);
enum class Sender { App, Relay };
enum class Type { Hallo, Anfrage, Antwort, Daten, Abbruch };

struct Message {
    Type type = Type::Hallo;
    std::uint32_t id = 0;     // anfrage, antwort, daten, abbruch
    int version = kVersion;   // hallo: v
    std::string peer;         // hallo: „app“ bzw. „relay“ (Kennung, ≤ 64 druckbare ASCII-Zeichen)
    int max_jobs = 0;         // hallo der App: max (1 … 6)
    std::string method;       // anfrage: GET | POST
    std::string path;         // anfrage: Pfad samt Abfrage (path_allowed)
    std::optional<std::string> content_type;  // anfrage, antwort: ct
    std::uint64_t length = 0;  // anfrage, antwort: len (Gesamtlänge des Bodys)
    int status = 0;            // antwort: s
    std::string data;          // Datenstück
};

struct Decoded {
    std::optional<Message> message;
    std::string reason;  // Grund aus Abschnitt 3 bzw. den Testvektoren, wenn message leer ist
};

// Liest eine Nachricht von `from`. Grund und Close-Code bei Verstoß: reason und close_code(reason).
Decoded decode(std::string_view payload, Sender from);

// Schreibt eine Nachricht kompakt (Schlüsselreihenfolge wie in den Testvektoren). Wirft std::invalid_argument, wenn
// die Nachricht selbst gegen die Regeln verstößt (eigener Fehler, darf nie hinausgehen).
std::string encode(const Message& message, Sender from);

// Abschnitt 5: genau /health, oder /v1/… bzw. /baumeister/v1/… mit Segmenten aus [A-Za-z0-9_-], optional ? und eine
// Abfrage aus [A-Za-z0-9_.~%=&+-] mit gültigen %XX (nie %2e/%2E), insgesamt ≤ 2 048 Zeichen.
bool path_allowed(std::string_view path);

// ct: 1 … 128 druckbare ASCII-Zeichen (0x20 … 0x7E), damit kein CR/LF eine Kopfzeile einschleust.
bool content_type_allowed(std::string_view content_type);

// Close-Code zum Grund (Abschnitt 8): Textrahmen 1003, falsche Version 1008, Rahmen oder Stück zu groß 1009, sonst
// 1002.
int close_code(std::string_view reason);

std::string_view type_name(Type type);

}  // namespace swbh::relay
