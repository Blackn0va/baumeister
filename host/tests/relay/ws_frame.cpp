#include "ws_frame.hpp"

#include "base/encoding.hpp"
#include "base/hash.hpp"
#include "relay/kanal.hpp"

#include <windows.h>

#include <bcrypt.h>

#include <stdexcept>

namespace swbh::test {

FrameParse parse_frame(std::string_view b, FrameDir dir) {
    FrameParse r;
    if (b.size() < 2) {
        return r;
    }
    const auto b0 = static_cast<unsigned char>(b[0]);
    const auto b1 = static_cast<unsigned char>(b[1]);
    r.frame.fin = (b0 & 0x80) != 0;
    r.frame.rsv = (b0 >> 4) & 0x07;
    r.frame.opcode = b0 & 0x0F;
    r.frame.masked = (b1 & 0x80) != 0;
    std::uint64_t len = b1 & 0x7F;
    std::size_t pos = 2;
    if (len == 126) {
        if (b.size() < 4) {
            return r;
        }
        len = (static_cast<std::uint64_t>(static_cast<unsigned char>(b[2])) << 8) |
              static_cast<unsigned char>(b[3]);
        pos = 4;
    } else if (len == 127) {
        if (b.size() < 10) {
            return r;
        }
        len = 0;
        for (std::size_t i = 2; i < 10; ++i) {
            len = (len << 8) | static_cast<unsigned char>(b[i]);
        }
        pos = 10;
    }
    const auto fail = [&r](std::string reason) {
        r.status = FrameParse::Status::Error;
        r.reason = std::move(reason);
        return r;
    };
    const bool control = r.frame.opcode >= 0x8;
    if (r.frame.rsv != 0) {
        return fail("rsv");
    }
    if (r.frame.opcode == 0x1) {
        return fail("textrahmen");
    }
    if (r.frame.opcode == 0x0 || (!control && !r.frame.fin) || (control && !r.frame.fin)) {
        return fail("fragment");
    }
    if (r.frame.opcode != 0x2 && r.frame.opcode != 0x8 && r.frame.opcode != 0x9 && r.frame.opcode != 0xA) {
        return fail("opcode_unbekannt");
    }
    if (dir == FrameDir::AppToRelay && !r.frame.masked) {
        return fail("maske_fehlt");
    }
    if (dir == FrameDir::RelayToApp && r.frame.masked) {
        return fail("maske_vom_server");
    }
    if (control && len > 125) {
        return fail("steuerrahmen_zu_lang");
    }
    if (len > relay::kMaxPayloadBytes) {
        return fail("rahmen_zu_gross");  // umlaut-ok: Grund auf der Leitung
    }
    std::array<std::uint8_t, 4> mask{};
    if (r.frame.masked) {
        if (b.size() < pos + 4) {
            return r;
        }
        for (std::size_t i = 0; i < 4; ++i) {
            mask[i] = static_cast<std::uint8_t>(b[pos + i]);
        }
        pos += 4;
    }
    if (b.size() < pos + len) {
        return r;
    }
    r.frame.payload.assign(b.substr(pos, static_cast<std::size_t>(len)));
    if (r.frame.masked) {
        for (std::size_t i = 0; i < r.frame.payload.size(); ++i) {
            r.frame.payload[i] = static_cast<char>(static_cast<std::uint8_t>(r.frame.payload[i]) ^ mask[i % 4]);
        }
    }
    r.consumed = pos + static_cast<std::size_t>(len);
    r.status = FrameParse::Status::Ok;
    return r;
}

std::string encode_frame(int opcode, std::string_view payload, const std::optional<std::array<std::uint8_t, 4>>& mask,
                         bool fin, int rsv) {
    std::string out;
    out.push_back(static_cast<char>((fin ? 0x80 : 0) | ((rsv & 7) << 4) | (opcode & 0x0F)));
    const unsigned char mb = mask ? 0x80 : 0;
    const std::size_t n = payload.size();
    if (n < 126) {
        out.push_back(static_cast<char>(mb | n));
    } else if (n < 65536) {
        out.push_back(static_cast<char>(mb | 126));
        out.push_back(static_cast<char>((n >> 8) & 0xFF));
        out.push_back(static_cast<char>(n & 0xFF));
    } else {
        out.push_back(static_cast<char>(mb | 127));
        for (int i = 7; i >= 0; --i) {
            out.push_back(static_cast<char>((static_cast<std::uint64_t>(n) >> (8 * i)) & 0xFF));
        }
    }
    std::string body(payload);
    if (mask) {
        for (const std::uint8_t m : *mask) {
            out.push_back(static_cast<char>(m));
        }
        for (std::size_t i = 0; i < body.size(); ++i) {
            body[i] = static_cast<char>(static_cast<std::uint8_t>(body[i]) ^ (*mask)[i % 4]);
        }
    }
    return out + body;
}

std::string websocket_accept(std::string_view key) {
    const std::string input = std::string(key) + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    std::array<std::uint8_t, 20> digest{};
    if (BCryptHash(BCRYPT_SHA1_ALG_HANDLE, nullptr, 0,
                   reinterpret_cast<PUCHAR>(const_cast<char*>(input.data())), static_cast<ULONG>(input.size()),
                   digest.data(), static_cast<ULONG>(digest.size())) != 0) {
        throw std::runtime_error("SHA-1 nicht verfügbar");
    }
    return base64_encode(digest);
}

std::string from_hex_string(std::string_view hex) {
    const auto bytes = from_hex(hex);
    if (!bytes) {
        throw std::runtime_error("kein Hex");
    }
    return std::string(bytes->begin(), bytes->end());
}

std::string sha256_hex_of(std::string_view bytes) { return swbh::sha256_hex(bytes); }

}  // namespace swbh::test
