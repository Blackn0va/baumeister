#include "base/pytext.hpp"

#include "base/utf8.hpp"

#include <windows.h>

#include <algorithm>
#include <climits>
#include <iterator>

namespace swbh {
namespace {

struct Range {
    char32_t lo;
    char32_t hi;
};

constexpr Range kAlphaRanges[] = {
#include "base/pytext_alpha.inc"
};

}  // namespace

bool py_isspace(char32_t cp) noexcept {
    // Gemessen mit Python 3.13.13 über alle Codepoints: genau diese 29 sind isspace().
    // U+180E und U+200B gehören seit Unicode 6.3 bzw. nie dazu.
    if (cp <= 0x20) {
        return (cp >= 0x09 && cp <= 0x0D) || (cp >= 0x1C && cp <= 0x20);
    }
    switch (cp) {
        case 0x0085:
        case 0x00A0:
        case 0x1680:
        case 0x2028:
        case 0x2029:
        case 0x202F:
        case 0x205F:
        case 0x3000:
            return true;
        default:
            return cp >= 0x2000 && cp <= 0x200A;
    }
}

bool py_isalpha_cp(char32_t cp) noexcept {
    const auto* begin = std::begin(kAlphaRanges);
    const auto* end = std::end(kAlphaRanges);
    // Erster Bereich, dessen Obergrenze >= cp ist.
    const auto* it = std::lower_bound(begin, end, cp, [](const Range& r, char32_t v) { return r.hi < v; });
    return it != end && it->lo <= cp;
}

namespace {

// Liefert [erstes Byte nach führendem Leerraum, Byte nach letztem Nicht-Leerraum)
// auf einer gültigen UTF-8-Zeichenkette.
void strip_bounds(std::string_view s, bool left, bool right, std::size_t& first, std::size_t& last) {
    first = 0;
    last = s.size();
    std::size_t pos = 0;
    bool seen_content = false;
    std::size_t content_end = 0;
    while (pos < s.size()) {
        const std::size_t start = pos;
        const char32_t cp = decode_next(s, pos);
        if (!py_isspace(cp)) {
            if (!seen_content) {
                seen_content = true;
                if (left) {
                    first = start;
                }
            }
            content_end = pos;
        }
    }
    if (!seen_content) {
        // Nur Leerraum: strip, lstrip und rstrip liefern alle "" (wie Python).
        first = 0;
        last = 0;
        return;
    }
    if (right) {
        last = content_end;
    }
}

std::string strip_impl(std::string_view input, bool left, bool right) {
    const std::string s = sanitize_utf8(input);
    std::size_t first = 0;
    std::size_t last = 0;
    strip_bounds(s, left, right, first, last);
    if (last <= first) {
        return {};
    }
    return s.substr(first, last - first);
}

}  // namespace

std::string py_strip(std::string_view s) { return strip_impl(s, true, true); }
std::string py_lstrip(std::string_view s) { return strip_impl(s, true, false); }
std::string py_rstrip(std::string_view s) { return strip_impl(s, false, true); }

std::vector<std::string> py_split(std::string_view input) {
    const std::string s = sanitize_utf8(input);
    std::vector<std::string> parts;
    std::size_t pos = 0;
    std::size_t token_start = std::string::npos;
    while (pos < s.size()) {
        const std::size_t start = pos;
        const char32_t cp = decode_next(s, pos);
        if (py_isspace(cp)) {
            if (token_start != std::string::npos) {
                parts.push_back(s.substr(token_start, start - token_start));
                token_start = std::string::npos;
            }
        } else if (token_start == std::string::npos) {
            token_start = start;
        }
    }
    if (token_start != std::string::npos) {
        parts.push_back(s.substr(token_start));
    }
    return parts;
}

std::string py_collapse_ws(std::string_view input) {
    const std::string s = sanitize_utf8(input);
    std::string out;
    out.reserve(s.size());
    std::size_t pos = 0;
    bool in_ws = false;
    while (pos < s.size()) {
        const std::size_t start = pos;
        const char32_t cp = decode_next(s, pos);
        if (py_isspace(cp)) {
            if (!in_ws) {
                out.push_back(' ');
                in_ws = true;
            }
        } else {
            out.append(s, start, pos - start);
            in_ws = false;
        }
    }
    return out;
}

std::string py_lower(std::string_view s) {
    if (s.empty()) {
        return {};
    }
    const std::wstring wide = widen(s);
    if (wide.size() > static_cast<std::size_t>(INT_MAX)) {
        return ascii_lower(sanitize_utf8(s));
    }
    const int in_len = static_cast<int>(wide.size());
    const int needed = LCMapStringEx(LOCALE_NAME_INVARIANT, LCMAP_LOWERCASE, wide.data(), in_len, nullptr, 0,
                                     nullptr, nullptr, 0);
    if (needed <= 0) {
        // LCMapStringEx scheitert praktisch nie; ohne Ergebnis bleibt nur die
        // sichere ASCII-Fassung statt eines leeren Strings.
        return ascii_lower(sanitize_utf8(s));
    }
    std::wstring lowered(static_cast<std::size_t>(needed), L'\0');
    const int written = LCMapStringEx(LOCALE_NAME_INVARIANT, LCMAP_LOWERCASE, wide.data(), in_len,
                                      lowered.data(), needed, nullptr, nullptr, 0);
    if (written <= 0) {
        return ascii_lower(sanitize_utf8(s));
    }
    lowered.resize(static_cast<std::size_t>(written));
    return narrow(lowered);
}

bool py_isalpha(std::string_view s) {
    if (s.empty()) {
        return false;
    }
    std::size_t pos = 0;
    while (pos < s.size()) {
        if (!py_isalpha_cp(decode_next(s, pos))) {
            return false;
        }
    }
    return true;
}

bool ascii_digits(std::string_view s) noexcept {
    if (s.empty()) {
        return false;
    }
    return std::all_of(s.begin(), s.end(), [](char c) { return c >= '0' && c <= '9'; });
}

std::string ascii_lower(std::string_view s) {
    std::string out(s);
    for (char& c : out) {
        if (c >= 'A' && c <= 'Z') {
            c = static_cast<char>(c - 'A' + 'a');
        }
    }
    return out;
}

}  // namespace swbh
