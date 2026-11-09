#include "base/format.hpp"

#include <algorithm>
#include <charconv>
#include <cmath>
#include <string_view>
#include <system_error>

namespace swbh {
namespace {

std::string non_finite(double v) {
    if (std::isnan(v)) {
        return "nan";
    }
    return v < 0 ? "-inf" : "inf";
}

}  // namespace

std::string fmt_fixed(double v, int prec) {
    if (!std::isfinite(v)) {
        return non_finite(v);
    }
    prec = std::clamp(prec, 0, 17);
    // 1e308 mit 17 Nachkommastellen braucht gut 330 Zeichen.
    char buf[400];
    const auto result = std::to_chars(buf, buf + sizeof buf, v, std::chars_format::fixed, prec);
    if (result.ec != std::errc{}) {
        return non_finite(std::nan(""));
    }
    return std::string(buf, result.ptr);
}

std::string fmt_trim(double v, int prec) {
    std::string s = fmt_fixed(v, prec);
    if (s.find('.') == std::string::npos) {
        return s;
    }
    while (!s.empty() && s.back() == '0') {
        s.pop_back();
    }
    if (!s.empty() && s.back() == '.') {
        s.pop_back();
    }
    return s;
}

std::string py_float_repr(double v) {
    if (!std::isfinite(v)) {
        return non_finite(v);
    }
    // to_chars(scientific) ohne Genauigkeit liefert die kürzeste eindeutige
    // Ziffernfolge (MSVC: Ryu) – dieselbe Ziffernwahl wie Pythons repr.
    // Danach wird nach Pythons Regeln (format_float_short, Modus 'r') gesetzt.
    char buf[64];
    const auto result = std::to_chars(buf, buf + sizeof buf, v, std::chars_format::scientific);
    if (result.ec != std::errc{}) {
        return "nan";
    }
    std::string_view sci(buf, static_cast<std::size_t>(result.ptr - buf));
    bool negative = false;
    if (!sci.empty() && sci.front() == '-') {
        negative = true;
        sci.remove_prefix(1);
    }
    const std::size_t e_pos = sci.find('e');
    const std::string_view mantissa = sci.substr(0, e_pos);
    int exponent = 0;
    const std::string_view exp_text = sci.substr(e_pos + 1);
    std::from_chars(exp_text.data() + (exp_text.front() == '+' ? 1 : 0), exp_text.data() + exp_text.size(),
                    exponent);
    std::string digits;
    for (const char c : mantissa) {
        if (c != '.') {
            digits.push_back(c);
        }
    }
    // Wert = 0.d1d2… × 10^decpt
    const int decpt = exponent + 1;
    const int ndigits = static_cast<int>(digits.size());
    std::string out = negative ? "-" : "";
    if (decpt <= -4 || decpt > 16) {
        out.push_back(digits[0]);
        if (ndigits > 1) {
            out.push_back('.');
            out.append(digits, 1, std::string::npos);
        }
        const int e = decpt - 1;
        out.push_back('e');
        out.push_back(e < 0 ? '-' : '+');
        const int abs_e = e < 0 ? -e : e;
        if (abs_e < 10) {
            out.push_back('0');
        }
        out += std::to_string(abs_e);
        return out;
    }
    if (decpt <= 0) {
        out += "0.";
        out.append(static_cast<std::size_t>(-decpt), '0');
        out += digits;
    } else if (decpt < ndigits) {
        out.append(digits, 0, static_cast<std::size_t>(decpt));
        out.push_back('.');
        out.append(digits, static_cast<std::size_t>(decpt), std::string::npos);
    } else {
        out += digits;
        out.append(static_cast<std::size_t>(decpt - ndigits), '0');
        out += ".0";
    }
    return out;
}

std::string fmt_int(std::int64_t v) {
    char buf[32];
    const auto result = std::to_chars(buf, buf + sizeof buf, v);
    return std::string(buf, result.ptr);
}

}  // namespace swbh
