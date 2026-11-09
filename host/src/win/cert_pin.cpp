#include "win/cert_pin.hpp"

#include "base/encoding.hpp"
#include "base/hash.hpp"

namespace swbh::win {

namespace {

bool is_space(char c) { return c == ' ' || c == '\t' || c == '\r' || c == '\n'; }

char lower(char c) { return c >= 'A' && c <= 'Z' ? static_cast<char>(c - 'A' + 'a') : c; }

}  // namespace

std::string pin_of_der(std::span<const std::uint8_t> der) {
    const auto digest = sha256(der);
    return std::string(kPinPrefix) + to_hex(digest);
}

std::optional<std::string> parse_pin(std::string_view text) {
    while (!text.empty() && is_space(text.front())) {
        text.remove_prefix(1);
    }
    while (!text.empty() && is_space(text.back())) {
        text.remove_suffix(1);
    }
    if (text.size() != kPinPrefix.size() + 64) {
        return std::nullopt;
    }
    std::string out;
    out.reserve(text.size());
    for (const char c : text) {
        out.push_back(lower(c));
    }
    if (!out.starts_with(kPinPrefix) || !is_sha256_hex_lower(std::string_view(out).substr(kPinPrefix.size()))) {
        return std::nullopt;
    }
    return out;
}

bool pin_listed(std::string_view pin, const std::vector<std::string>& pins) {
    bool found = false;
    for (const std::string& p : pins) {
        if (p.size() != pin.size()) {
            continue;
        }
        unsigned diff = 0;
        for (std::size_t i = 0; i < p.size(); ++i) {
            diff |= static_cast<unsigned>(static_cast<unsigned char>(p[i]) ^ static_cast<unsigned char>(pin[i]));
        }
        found = found || diff == 0;
    }
    return found;
}

}  // namespace swbh::win
