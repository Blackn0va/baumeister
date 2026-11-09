// Hex und Base64 (Standard-Alphabet mit '=').
//
// Verwendung: DPAPI-Werte "dpapi:v1:<base64>" in der Konfig, Signaturen im
// Update-Manifest (Base64-DER), SHA-256-Vergleiche (Hex klein).
//
// Threads: reentrant. Fehler: Dekodierer liefern nullopt statt Ausnahme – ein
// kaputter Wert darf nie teilweise übernommen werden (fail-closed).

#pragma once

#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace swbh {

// Hex in Kleinschreibung.
std::string to_hex(std::span<const std::uint8_t> bytes);

// Nimmt Groß- und Kleinschreibung; nullopt bei ungerader Länge oder Nicht-Hex.
std::optional<std::vector<std::uint8_t>> from_hex(std::string_view hex);

// Genau 64 Zeichen [0-9a-f] (Kleinschreibung erzwungen, wie das Plugin).
bool is_sha256_hex_lower(std::string_view s) noexcept;

std::string base64_encode(std::span<const std::uint8_t> bytes);
std::string base64_encode(std::string_view bytes);

// Streng: nur Standard-Alphabet, Länge Vielfaches von 4, '=' nur am Ende
// (höchstens zwei), keine Leerzeichen, nicht-kanonische Füllbits abgelehnt.
// Leere Eingabe ergibt leere Bytes.
std::optional<std::vector<std::uint8_t>> base64_decode(std::string_view text);

}  // namespace swbh
