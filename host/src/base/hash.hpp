// SHA-256 in einem Schritt über CNG (BCryptHash mit BCRYPT_SHA256_ALG_HANDLE).
//
// Warum in base: log::ref braucht einen SHA-256, und base darf nicht von win
// abhängen. Die streamende Fassung für Downloads und Dateien (Sha256Stream,
// sha256_file_hex) liegt in win/cng.hpp.
//
// Threads: reentrant (Pseudo-Handle, kein geteilter Zustand).
// Fehler: std::runtime_error, falls CNG versagt – fail-closed, nie ein falscher Hash.

#pragma once

#include <array>
#include <cstdint>
#include <span>
#include <string>
#include <string_view>

namespace swbh {

std::array<std::uint8_t, 32> sha256(std::span<const std::uint8_t> data);
std::array<std::uint8_t, 32> sha256(std::string_view data);

// Kleingeschriebenes Hex des SHA-256.
std::string sha256_hex(std::string_view data);

}  // namespace swbh
