// Strenger DER-Parser für ECDSA-P-256-Signaturen (Probe nat (c), „Weg B“).
//
// Warum nicht CryptDecodeObjectEx(X509_ECC_SIGNATURE): gemessen nimmt es eine
// Signatur mit angehängtem Restbyte an. Für die fail-closed Update-Prüfung
// zählt jede Abweichung von kanonischem DER als ungültig – auch dann, wenn CNG
// die Signatur mathematisch annehmen würde.

#include "win/der.hpp"

#include <algorithm>
#include <cstddef>
#include <cstring>

namespace swbh::win {
namespace {

// Ordnung n der Kurve P-256 (big-endian). r und s müssen in [1, n−1] liegen.
// CNG prüft den Bereich selbst; der Parser lehnt solche Werte trotzdem ab, damit
// nie etwas durchgereicht wird, das keine Signatur sein kann.
constexpr std::array<std::uint8_t, 32> kP256Order = {
    0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
    0xBC, 0xE6, 0xFA, 0xAD, 0xA7, 0x17, 0x9E, 0x84, 0xF3, 0xB9, 0xCA, 0xC2, 0xFC, 0x63, 0x25, 0x51};

// Längenfeld nach X.690 §8.1.3 in DER-Form: Kurzform unter 0x80, Langform nur
// minimal. 0x80 (unbestimmte Länge) ist in DER verboten; mehr als zwei
// Längenbytes kommen bei ECDSA nie vor und werden abgelehnt.
std::optional<std::size_t> read_length(std::span<const std::uint8_t> in, std::size_t& pos) {
    if (pos >= in.size()) {
        return std::nullopt;
    }
    const std::uint8_t first = in[pos++];
    if (first < 0x80) {
        return first;
    }
    if (first == 0x81) {
        if (pos >= in.size()) {
            return std::nullopt;
        }
        const std::uint8_t value = in[pos++];
        if (value < 0x80) {
            return std::nullopt;  // hätte in die Kurzform gepasst → nicht minimal
        }
        return value;
    }
    if (first == 0x82) {
        if (in.size() - pos < 2) {
            return std::nullopt;
        }
        const std::size_t value = (static_cast<std::size_t>(in[pos]) << 8) | in[pos + 1];
        pos += 2;
        if (value < 0x100) {
            return std::nullopt;  // hätte mit einem Längenbyte gepasst → nicht minimal
        }
        return value;
    }
    return std::nullopt;
}

// INTEGER in 32 Byte big-endian. Verlangt: positiv (kein gesetztes Vorzeichenbit),
// keine überflüssige führende 0x00, Wert ≠ 0 und < n.
bool read_integer(std::span<const std::uint8_t> in, std::size_t& pos, std::uint8_t* out32) {
    if (pos >= in.size() || in[pos] != 0x02) {
        return false;
    }
    ++pos;
    const auto len = read_length(in, pos);
    if (!len || *len == 0 || *len > 33 || *len > in.size() - pos) {
        return false;
    }
    const std::uint8_t* p = in.data() + pos;
    if ((p[0] & 0x80) != 0) {
        return false;  // negativer INTEGER
    }
    std::size_t skip = 0;
    if (p[0] == 0x00 && *len > 1) {
        if ((p[1] & 0x80) == 0) {
            return false;  // führende Null ohne Not → nicht minimal
        }
        skip = 1;
    }
    const std::size_t value_len = *len - skip;
    if (value_len > 32) {
        return false;
    }
    std::memset(out32, 0, 32);
    std::memcpy(out32 + (32 - value_len), p + skip, value_len);
    pos += *len;

    const bool nonzero = std::any_of(out32, out32 + 32, [](std::uint8_t b) { return b != 0; });
    if (!nonzero) {
        return false;
    }
    return std::lexicographical_compare(out32, out32 + 32, kP256Order.begin(), kP256Order.end());
}

}  // namespace

std::optional<std::array<std::uint8_t, 64>> der_to_rs_strict(std::span<const std::uint8_t> der) {
    // Kürzeste mögliche Signatur: 30 06 02 01 r 02 01 s; längste bei P-256:
    // 30 44 02 21 00‖r 02 21 00‖s = 72 Byte.
    if (der.size() < 8 || der.size() > 72) {
        return std::nullopt;
    }
    std::size_t pos = 0;
    if (der[pos++] != 0x30) {
        return std::nullopt;
    }
    const auto seq_len = read_length(der, pos);
    // Die SEQUENCE muss die Eingabe exakt abdecken – genau hier nimmt
    // CryptDecodeObjectEx Restbytes an (gemessen).
    if (!seq_len || *seq_len != der.size() - pos) {
        return std::nullopt;
    }
    std::array<std::uint8_t, 64> rs{};
    if (!read_integer(der, pos, rs.data()) || !read_integer(der, pos, rs.data() + 32)) {
        return std::nullopt;
    }
    if (pos != der.size()) {
        return std::nullopt;
    }
    return rs;
}

}  // namespace swbh::win
