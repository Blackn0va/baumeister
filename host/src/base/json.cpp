#include "base/json.hpp"

#include "base/format.hpp"
#include "base/utf8.hpp"

#include <cmath>
#include <cstdio>

namespace swbh {
namespace {

struct DumpStyle {
    int indent = -1;  // < 0: einzeilig
    std::string_view item_sep;
    std::string_view key_sep;
};

void dump_string(std::string& out, const std::string& raw) {
    const std::string s = sanitize_utf8(raw);
    out.push_back('"');
    for (const char ch : s) {
        const auto c = static_cast<unsigned char>(ch);
        switch (c) {
            case '"':
                out += "\\\"";
                break;
            case '\\':
                out += "\\\\";
                break;
            case '\n':
                out += "\\n";
                break;
            case '\r':
                out += "\\r";
                break;
            case '\t':
                out += "\\t";
                break;
            case 0x08:
                out += "\\b";
                break;
            case 0x0C:
                out += "\\f";
                break;
            default:
                if (c < 0x20) {
                    // Python: '\\u{0:04x}' – Kleinbuchstaben; DEL (0x7F) bleibt roh.
                    char buf[8];
                    std::snprintf(buf, sizeof buf, "\\u%04x", static_cast<unsigned>(c));
                    out += buf;
                } else {
                    out.push_back(ch);
                }
        }
    }
    out.push_back('"');
}

void dump_float(std::string& out, double v) {
    // json.dumps(allow_nan=True) schreibt diese Literale; kommen nur vor, wenn ein
    // Aufrufer sie selbst in ein Json legt (Parser nimmt sie nicht an).
    if (std::isnan(v)) {
        out += "NaN";
    } else if (std::isinf(v)) {
        out += v < 0 ? "-Infinity" : "Infinity";
    } else {
        out += py_float_repr(v);
    }
}

void newline(std::string& out, const DumpStyle& style, int level) {
    if (style.indent >= 0) {
        out.push_back('\n');
        out.append(static_cast<std::size_t>(style.indent * level), ' ');
    }
}

void dump_value(std::string& out, const Json& v, const DumpStyle& style, int level) {
    switch (v.type()) {
        case Json::value_t::object: {
            if (v.empty()) {
                out += "{}";
                return;
            }
            out.push_back('{');
            bool first = true;
            for (auto it = v.begin(); it != v.end(); ++it) {
                if (!first) {
                    out += style.item_sep;
                }
                first = false;
                newline(out, style, level + 1);
                dump_string(out, it.key());
                out += style.key_sep;
                dump_value(out, it.value(), style, level + 1);
            }
            newline(out, style, level);
            out.push_back('}');
            return;
        }
        case Json::value_t::array: {
            if (v.empty()) {
                out += "[]";
                return;
            }
            out.push_back('[');
            bool first = true;
            for (const auto& item : v) {
                if (!first) {
                    out += style.item_sep;
                }
                first = false;
                newline(out, style, level + 1);
                dump_value(out, item, style, level + 1);
            }
            newline(out, style, level);
            out.push_back(']');
            return;
        }
        case Json::value_t::string:
            dump_string(out, v.get_ref<const std::string&>());
            return;
        case Json::value_t::boolean:
            out += v.get<bool>() ? "true" : "false";
            return;
        case Json::value_t::number_integer:
            out += std::to_string(v.get<std::int64_t>());
            return;
        case Json::value_t::number_unsigned:
            out += std::to_string(v.get<std::uint64_t>());
            return;
        case Json::value_t::number_float:
            dump_float(out, v.get<double>());
            return;
        case Json::value_t::null:
        case Json::value_t::binary:
        case Json::value_t::discarded:
        default:
            out += "null";
            return;
    }
}

std::string dump_with(const Json& value, const DumpStyle& style) {
    std::string out;
    dump_value(out, value, style, 0);
    return out;
}

std::string py_repr_string(const std::string& raw) {
    const std::string s = sanitize_utf8(raw);
    const bool has_single = s.find('\'') != std::string::npos;
    const bool has_double = s.find('"') != std::string::npos;
    const char quote = (has_single && !has_double) ? '"' : '\'';
    std::string out;
    out.push_back(quote);
    for (const char ch : s) {
        const auto c = static_cast<unsigned char>(ch);
        if (ch == quote || ch == '\\') {
            out.push_back('\\');
            out.push_back(ch);
        } else if (ch == '\n') {
            out += "\\n";
        } else if (ch == '\r') {
            out += "\\r";
        } else if (ch == '\t') {
            out += "\\t";
        } else if (c < 0x20 || c == 0x7F) {
            char buf[8];
            std::snprintf(buf, sizeof buf, "\\x%02x", static_cast<unsigned>(c));
            out += buf;
        } else {
            out.push_back(ch);
        }
    }
    out.push_back(quote);
    return out;
}

std::string py_repr(const Json& v) {
    if (v.is_string()) {
        return py_repr_string(v.get_ref<const std::string&>());
    }
    return py_str(v);
}

}  // namespace

Json parse_utf8(std::string_view bytes, const ParseOptions& options) {
    const bool has_bom = bytes.size() >= 3 && static_cast<unsigned char>(bytes[0]) == 0xEF &&
                         static_cast<unsigned char>(bytes[1]) == 0xBB && static_cast<unsigned char>(bytes[2]) == 0xBF;
    if (has_bom) {
        // nlohmann überliest ein BOM stillschweigend; ohne allow_bom soll es aber wie
        // bei Python ("Unexpected UTF-8 BOM") ein Fehler sein.
        if (!options.allow_bom) {
            throw JsonError("Ungültiges JSON: unerwartetes UTF-8-BOM am Anfang.");
        }
        bytes.remove_prefix(3);
    }
    std::string text;
    if (options.replace_invalid_utf8) {
        text = sanitize_utf8(bytes);
    } else {
        if (!is_valid_utf8(bytes)) {
            throw JsonError("Ungültiges UTF-8 im JSON-Text.");
        }
        text.assign(bytes);
    }
    try {
        return Json::parse(text);
    } catch (const nlohmann::json::exception& e) {
        throw JsonError(std::string("Ungültiges JSON: ") + e.what());
    }
}

Json parse_utf8(std::string_view bytes, bool allow_bom) {
    ParseOptions options;
    options.allow_bom = allow_bom;
    return parse_utf8(bytes, options);
}

std::optional<Json> try_parse_utf8(std::string_view bytes, const ParseOptions& options) {
    try {
        return parse_utf8(bytes, options);
    } catch (const JsonError&) {
        // Absichtlich ohne Meldung: Der Aufrufer wertet nullopt als „kein JSON“
        // und erzeugt die IST-Meldung selbst (z. B. "Bridge-Config-Antwort ist kein JSON.").
        return std::nullopt;
    }
}

std::string dump_compact(const Json& value) { return dump_with(value, DumpStyle{-1, ",", ":"}); }

std::string dump_python_separators(const Json& value) { return dump_with(value, DumpStyle{-1, ", ", ": "}); }

std::string dump_python_indent2(const Json& value) { return dump_with(value, DumpStyle{2, ",", ": "}); }

std::string py_str(const Json& value) {
    switch (value.type()) {
        case Json::value_t::string:
            return value.get<std::string>();
        case Json::value_t::boolean:
            return value.get<bool>() ? "True" : "False";
        case Json::value_t::number_integer:
            return std::to_string(value.get<std::int64_t>());
        case Json::value_t::number_unsigned:
            return std::to_string(value.get<std::uint64_t>());
        case Json::value_t::number_float:
            return py_float_repr(value.get<double>());
        case Json::value_t::array: {
            std::string out = "[";
            bool first = true;
            for (const auto& item : value) {
                if (!first) {
                    out += ", ";
                }
                first = false;
                out += py_repr(item);
            }
            out += "]";
            return out;
        }
        case Json::value_t::object: {
            std::string out = "{";
            bool first = true;
            for (auto it = value.begin(); it != value.end(); ++it) {
                if (!first) {
                    out += ", ";
                }
                first = false;
                out += py_repr_string(it.key());
                out += ": ";
                out += py_repr(it.value());
            }
            out += "}";
            return out;
        }
        case Json::value_t::null:
        case Json::value_t::binary:
        case Json::value_t::discarded:
        default:
            return "None";
    }
}

const Json* json_find(const Json& obj, std::string_view key) {
    if (!obj.is_object()) {
        return nullptr;
    }
    const auto it = obj.find(std::string(key));
    if (it == obj.end()) {
        return nullptr;
    }
    return &*it;
}

}  // namespace swbh
