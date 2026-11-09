// Strenger WebSocket-Rahmen (RFC 6455) mit den Regeln aus Kanal v1, Abschnitt 2 – NUR für Tests.
//
// Warum nur hier: Die App lässt Rahmen von WinHTTP bauen und prüfen (win/pinned_ws.hpp, gemessen). Die Tests brauchen
// trotzdem einen eigenen, strengen Rahmenleser: für die Testvektoren (Byte für Byte, mit Maske) und für die
// Loopback-Gegenstelle (fake_relay), die jeden Rahmen der App so prüft, wie es das Relay tut – so fällt auf, wenn
// WinHTTP je anders rahmt (z. B. fragmentiert oder unmaskiert sendet).
//
// Threads: reentrant. Fehler: parse_frame liefert den Grund aus den Testvektoren.

#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>

namespace swbh::test {

enum class FrameDir { AppToRelay, RelayToApp };

struct Frame {
    bool fin = true;
    int rsv = 0;
    int opcode = 2;
    bool masked = false;
    std::string payload;  // entmaskiert
};

struct FrameParse {
    enum class Status { Ok, Incomplete, Error };
    Status status = Status::Incomplete;
    Frame frame;
    std::size_t consumed = 0;
    std::string reason;  // textrahmen, rsv, maske_fehlt, maske_vom_server, fragment, steuerrahmen_zu_lang, …
};

// Liest einen Rahmen vom Anfang von bytes. Steuerrahmen (Ping/Pong/Close) sind erlaubt; Daten nur binär mit FIN=1.
FrameParse parse_frame(std::string_view bytes, FrameDir dir);

// Baut einen Rahmen. mask gesetzt = maskiert (App → Relay).
std::string encode_frame(int opcode, std::string_view payload, const std::optional<std::array<std::uint8_t, 4>>& mask,
                         bool fin = true, int rsv = 0);

// Sec-WebSocket-Accept zu Sec-WebSocket-Key (RFC 6455 Abschnitt 1.3).
std::string websocket_accept(std::string_view key);

std::string from_hex_string(std::string_view hex);
std::string sha256_hex_of(std::string_view bytes);

}  // namespace swbh::test
