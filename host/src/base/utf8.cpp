#include "base/utf8.hpp"

namespace swbh {
namespace {

// Dekodiert nach Unicode-Tabelle 3-7 (wohlgeformte Bytefolgen). ok = false, wenn
// ein Ersatzzeichen für eine ungültige Folge entstand. Bei einer abgebrochenen
// Folge werden nur Leitbyte und gültige Folgebytes verbraucht – das störende Byte
// beginnt die nächste Runde (maximales Teilstück, wie Python).
char32_t decode_impl(std::string_view s, std::size_t& pos, bool& ok) noexcept {
    const auto b0 = static_cast<unsigned char>(s[pos]);
    ok = true;
    if (b0 < 0x80) {
        ++pos;
        return b0;
    }
    int need = 0;
    char32_t cp = 0;
    unsigned char lo = 0x80;
    unsigned char hi = 0xBF;
    if (b0 >= 0xC2 && b0 <= 0xDF) {
        need = 1;
        cp = b0 & 0x1Fu;
    } else if (b0 == 0xE0) {
        need = 2;
        cp = b0 & 0x0Fu;
        lo = 0xA0;
    } else if ((b0 >= 0xE1 && b0 <= 0xEC) || b0 == 0xEE || b0 == 0xEF) {
        need = 2;
        cp = b0 & 0x0Fu;
    } else if (b0 == 0xED) {
        // ED A0..BF wären UTF-16-Surrogate, die in UTF-8 nicht vorkommen dürfen.
        need = 2;
        cp = b0 & 0x0Fu;
        hi = 0x9F;
    } else if (b0 == 0xF0) {
        need = 3;
        cp = b0 & 0x07u;
        lo = 0x90;
    } else if (b0 >= 0xF1 && b0 <= 0xF3) {
        need = 3;
        cp = b0 & 0x07u;
    } else if (b0 == 0xF4) {
        need = 3;
        cp = b0 & 0x07u;
        hi = 0x8F;
    } else {
        ++pos;
        ok = false;
        return kReplacementChar;
    }
    std::size_t i = pos + 1;
    for (int k = 0; k < need; ++k, ++i) {
        if (i >= s.size()) {
            pos = i;
            ok = false;
            return kReplacementChar;
        }
        const auto b = static_cast<unsigned char>(s[i]);
        if (b < lo || b > hi) {
            pos = i;
            ok = false;
            return kReplacementChar;
        }
        cp = (cp << 6) | (b & 0x3Fu);
        lo = 0x80;
        hi = 0xBF;
    }
    pos = i;
    return cp;
}

}  // namespace

char32_t decode_next(std::string_view u8, std::size_t& pos) noexcept {
    bool ok = true;
    return decode_impl(u8, pos, ok);
}

void append_utf8(std::string& out, char32_t cp) {
    if (cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
        cp = kReplacementChar;
    }
    if (cp < 0x80) {
        out.push_back(static_cast<char>(cp));
    } else if (cp < 0x800) {
        out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
        out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

bool is_valid_utf8(std::string_view u8) noexcept {
    std::size_t pos = 0;
    while (pos < u8.size()) {
        bool ok = true;
        decode_impl(u8, pos, ok);
        if (!ok) {
            return false;
        }
    }
    return true;
}

std::string sanitize_utf8(std::string_view u8) {
    // Schneller Weg für den Normalfall: gültige Eingabe wird unverändert kopiert.
    if (is_valid_utf8(u8)) {
        return std::string(u8);
    }
    std::string out;
    out.reserve(u8.size() + 8);
    std::size_t pos = 0;
    while (pos < u8.size()) {
        append_utf8(out, decode_next(u8, pos));
    }
    return out;
}

std::size_t codepoint_count(std::string_view u8) noexcept {
    std::size_t count = 0;
    std::size_t pos = 0;
    while (pos < u8.size()) {
        decode_next(u8, pos);
        ++count;
    }
    return count;
}

std::string truncate_cp(std::string_view u8, std::size_t n) {
    std::string out;
    std::size_t pos = 0;
    std::size_t taken = 0;
    while (pos < u8.size() && taken < n) {
        const std::size_t start = pos;
        bool ok = true;
        const char32_t cp = decode_impl(u8, pos, ok);
        if (ok) {
            out.append(u8.substr(start, pos - start));
        } else {
            append_utf8(out, cp);
        }
        ++taken;
    }
    return out;
}

std::u32string to_u32(std::string_view u8) {
    std::u32string out;
    out.reserve(u8.size());
    std::size_t pos = 0;
    while (pos < u8.size()) {
        out.push_back(decode_next(u8, pos));
    }
    return out;
}

std::string from_u32(std::u32string_view u32) {
    std::string out;
    out.reserve(u32.size());
    for (const char32_t cp : u32) {
        append_utf8(out, cp);
    }
    return out;
}

std::wstring widen(std::string_view u8) {
    std::wstring out;
    out.reserve(u8.size());
    std::size_t pos = 0;
    while (pos < u8.size()) {
        const char32_t cp = decode_next(u8, pos);
        if (cp >= 0x10000) {
            const char32_t v = cp - 0x10000;
            out.push_back(static_cast<wchar_t>(0xD800 + (v >> 10)));
            out.push_back(static_cast<wchar_t>(0xDC00 + (v & 0x3FF)));
        } else {
            out.push_back(static_cast<wchar_t>(cp));
        }
    }
    return out;
}

std::string narrow(std::wstring_view w) {
    std::string out;
    out.reserve(w.size());
    for (std::size_t i = 0; i < w.size(); ++i) {
        const auto unit = static_cast<char32_t>(static_cast<unsigned short>(w[i]));
        if (unit >= 0xD800 && unit <= 0xDBFF && i + 1 < w.size()) {
            const auto next = static_cast<char32_t>(static_cast<unsigned short>(w[i + 1]));
            if (next >= 0xDC00 && next <= 0xDFFF) {
                append_utf8(out, 0x10000 + ((unit - 0xD800) << 10) + (next - 0xDC00));
                ++i;
                continue;
            }
        }
        // Ungepaarte Surrogate werden in append_utf8 zu U+FFFD.
        append_utf8(out, unit);
    }
    return out;
}

}  // namespace swbh
