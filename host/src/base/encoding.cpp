#include "base/encoding.hpp"

#include <array>

namespace swbh {
namespace {

constexpr char kHexDigits[] = "0123456789abcdef";
constexpr char kB64[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

int hex_value(char c) noexcept {
    if (c >= '0' && c <= '9') {
        return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
        return c - 'a' + 10;
    }
    if (c >= 'A' && c <= 'F') {
        return c - 'A' + 10;
    }
    return -1;
}

constexpr std::array<int, 256> make_b64_table() {
    std::array<int, 256> t{};
    for (auto& v : t) {
        v = -1;
    }
    for (int i = 0; i < 64; ++i) {
        t[static_cast<unsigned char>(kB64[i])] = i;
    }
    return t;
}

constexpr auto kB64Table = make_b64_table();

}  // namespace

std::string to_hex(std::span<const std::uint8_t> bytes) {
    std::string out;
    out.reserve(bytes.size() * 2);
    for (const std::uint8_t b : bytes) {
        out.push_back(kHexDigits[b >> 4]);
        out.push_back(kHexDigits[b & 0x0F]);
    }
    return out;
}

std::optional<std::vector<std::uint8_t>> from_hex(std::string_view hex) {
    if (hex.size() % 2 != 0) {
        return std::nullopt;
    }
    std::vector<std::uint8_t> out;
    out.reserve(hex.size() / 2);
    for (std::size_t i = 0; i < hex.size(); i += 2) {
        const int hi = hex_value(hex[i]);
        const int lo = hex_value(hex[i + 1]);
        if (hi < 0 || lo < 0) {
            return std::nullopt;
        }
        out.push_back(static_cast<std::uint8_t>((hi << 4) | lo));
    }
    return out;
}

bool is_sha256_hex_lower(std::string_view s) noexcept {
    if (s.size() != 64) {
        return false;
    }
    for (const char c : s) {
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
            return false;
        }
    }
    return true;
}

std::string base64_encode(std::span<const std::uint8_t> bytes) {
    std::string out;
    out.reserve(((bytes.size() + 2) / 3) * 4);
    std::size_t i = 0;
    for (; i + 3 <= bytes.size(); i += 3) {
        const std::uint32_t v = (static_cast<std::uint32_t>(bytes[i]) << 16) |
                                (static_cast<std::uint32_t>(bytes[i + 1]) << 8) | bytes[i + 2];
        out.push_back(kB64[(v >> 18) & 0x3F]);
        out.push_back(kB64[(v >> 12) & 0x3F]);
        out.push_back(kB64[(v >> 6) & 0x3F]);
        out.push_back(kB64[v & 0x3F]);
    }
    const std::size_t rest = bytes.size() - i;
    if (rest == 1) {
        const std::uint32_t v = static_cast<std::uint32_t>(bytes[i]) << 16;
        out.push_back(kB64[(v >> 18) & 0x3F]);
        out.push_back(kB64[(v >> 12) & 0x3F]);
        out += "==";
    } else if (rest == 2) {
        const std::uint32_t v = (static_cast<std::uint32_t>(bytes[i]) << 16) |
                                (static_cast<std::uint32_t>(bytes[i + 1]) << 8);
        out.push_back(kB64[(v >> 18) & 0x3F]);
        out.push_back(kB64[(v >> 12) & 0x3F]);
        out.push_back(kB64[(v >> 6) & 0x3F]);
        out.push_back('=');
    }
    return out;
}

std::string base64_encode(std::string_view bytes) {
    return base64_encode(std::span<const std::uint8_t>(reinterpret_cast<const std::uint8_t*>(bytes.data()), bytes.size()));
}

std::optional<std::vector<std::uint8_t>> base64_decode(std::string_view text) {
    if (text.size() % 4 != 0) {
        return std::nullopt;
    }
    std::vector<std::uint8_t> out;
    out.reserve(text.size() / 4 * 3);
    for (std::size_t i = 0; i < text.size(); i += 4) {
        const bool last_block = i + 4 == text.size();
        int vals[4];
        int pad = 0;
        for (int k = 0; k < 4; ++k) {
            const char c = text[i + static_cast<std::size_t>(k)];
            if (c == '=') {
                // '=' nur im letzten Block und nur an den letzten beiden Stellen.
                if (!last_block || k < 2) {
                    return std::nullopt;
                }
                vals[k] = 0;
                ++pad;
            } else {
                if (pad > 0) {
                    return std::nullopt;  // Zeichen nach '='
                }
                const int v = kB64Table[static_cast<unsigned char>(c)];
                if (v < 0) {
                    return std::nullopt;
                }
                vals[k] = v;
            }
        }
        const std::uint32_t v = (static_cast<std::uint32_t>(vals[0]) << 18) |
                                (static_cast<std::uint32_t>(vals[1]) << 12) |
                                (static_cast<std::uint32_t>(vals[2]) << 6) | static_cast<std::uint32_t>(vals[3]);
        out.push_back(static_cast<std::uint8_t>((v >> 16) & 0xFF));
        if (pad < 2) {
            out.push_back(static_cast<std::uint8_t>((v >> 8) & 0xFF));
        } else if ((v & 0xFFFF) != 0) {
            return std::nullopt;  // nicht-kanonische Füllbits
        }
        if (pad < 1) {
            out.push_back(static_cast<std::uint8_t>(v & 0xFF));
        } else if (pad == 1 && (v & 0xFF) != 0) {
            return std::nullopt;
        }
    }
    return out;
}

}  // namespace swbh
