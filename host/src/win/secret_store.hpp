// Geheimnisse (Discord-Bot-Token) verschlüsselt ablegen: Windows DPAPI (CryptProtectData), an den angemeldeten
// Windows-Benutzer gebunden, mit eigener Zusatz-Entropie je Zweck. Auf der Platte steht nur Base64 des Chiffrats –
// ein kopiertes config.local.json ist auf einem anderen Konto/Rechner wertlos.
//
// CLAUDE.md: „Keine hartcodierten Secrets“ – das Token gibt der Betreiber im Einstellungsfenster ein, es steht nie im
// Code, nie im Log, nie in einer URL.

#pragma once

#include <optional>
#include <string>
#include <string_view>

namespace swbh::win {

// purpose: kurzer fester Name („discord-bot-token“) – dieselbe Zeichenkette zum Ver- und Entschlüsseln.
std::optional<std::string> protect_secret(std::string_view plain, std::string_view purpose);
std::optional<std::string> unprotect_secret(std::string_view stored, std::string_view purpose);

}  // namespace swbh::win
