#include "base/hash.hpp"

#include "base/encoding.hpp"
#include "base/errors.hpp"

#include <windows.h>
#include <bcrypt.h>

#include <limits>
#include <stdexcept>

namespace swbh {

std::array<std::uint8_t, 32> sha256(std::span<const std::uint8_t> data) {
    std::array<std::uint8_t, 32> digest{};
    if (data.size() > std::numeric_limits<ULONG>::max()) {
        // BCryptHash nimmt nur ULONG; so große Puffer gibt es hier nicht –
        // lieber laut scheitern als einen Hash über einen Teil liefern.
        throw std::runtime_error("SHA-256: Eingabe zu groß für den Einmal-Aufruf");
    }
    // PUCHAR ist nicht const-korrekt deklariert; BCryptHash liest nur.
    auto* input = const_cast<PUCHAR>(data.data());
    const NTSTATUS status = BCryptHash(BCRYPT_SHA256_ALG_HANDLE, nullptr, 0, input, static_cast<ULONG>(data.size()),
                                       digest.data(), static_cast<ULONG>(digest.size()));
    if (!BCRYPT_SUCCESS(status)) {
        throw std::runtime_error("SHA-256 über CNG fehlgeschlagen: " +
                                 system_error_text(static_cast<unsigned long>(status)));
    }
    return digest;
}

std::array<std::uint8_t, 32> sha256(std::string_view data) {
    return sha256(std::span<const std::uint8_t>(reinterpret_cast<const std::uint8_t*>(data.data()), data.size()));
}

std::string sha256_hex(std::string_view data) {
    const auto digest = sha256(data);
    return to_hex(digest);
}

}  // namespace swbh
