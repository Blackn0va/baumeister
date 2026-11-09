// Proxy-Entscheidung je Anfrage (Regeln in proxy.hpp).

#include "win/proxy.hpp"

#include "base/pytext.hpp"
#include "base/utf8.hpp"

#include <windows.h>

#include <charconv>
#include <vector>

namespace swbh::win {
namespace {

std::string read_env(const wchar_t* name) {
    // GetEnvironmentVariableW ist unabhängig von Groß-/Kleinschreibung – genau wie
    // os.environ unter Windows, das HTTPS_PROXY und https_proxy nicht unterscheidet.
    DWORD size = GetEnvironmentVariableW(name, nullptr, 0);
    if (size == 0) {
        return {};
    }
    std::wstring value(size, L'\0');
    size = GetEnvironmentVariableW(name, value.data(), size);
    if (size == 0 || size >= value.size()) {
        return {};
    }
    value.resize(size);
    return narrow(value);
}

std::string_view trim_ascii(std::string_view s) {
    while (!s.empty() && (s.front() == ' ' || s.front() == '\t')) {
        s.remove_prefix(1);
    }
    while (!s.empty() && (s.back() == ' ' || s.back() == '\t')) {
        s.remove_suffix(1);
    }
    return s;
}

int hex_value(char c) {
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

// urllib.parse.unquote: %XX → Byte, ungültige Folgen bleiben stehen.
std::string percent_decode(std::string_view s) {
    std::string out;
    out.reserve(s.size());
    for (std::size_t i = 0; i < s.size(); ++i) {
        if (s[i] == '%' && i + 2 < s.size()) {
            const int hi = hex_value(s[i + 1]);
            const int lo = hex_value(s[i + 2]);
            if (hi >= 0 && lo >= 0) {
                out.push_back(static_cast<char>(hi * 16 + lo));
                i += 2;
                continue;
            }
        }
        out.push_back(s[i]);
    }
    return sanitize_utf8(out);
}

bool valid_proxy_host(std::string_view host) {
    if (host.empty()) {
        return false;
    }
    for (const char c : host) {
        const bool ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' ||
                        c == '.' || c == '_';
        if (!ok) {
            return false;
        }
    }
    return true;
}

// Zerlegt eine Proxy-Angabe wie urllib _parse_proxy: "[schema://][user[:pass]@]host[:port][/…]".
ProxyChoice parse_proxy_value(std::string_view raw) {
    ProxyChoice choice;
    std::string_view value = trim_ascii(raw);
    std::string_view authority = value;
    const std::size_t scheme_end = value.find("://");
    if (scheme_end != std::string_view::npos) {
        const std::string scheme = ascii_lower(value.substr(0, scheme_end));
        // WinHTTP spricht mit dem Proxy nur Klartext-HTTP (CONNECT für https).
        // urllib behandelt "https://proxy" genauso; alles andere (socks …) kann
        // WinHTTP nicht und wird abgelehnt statt ignoriert.
        if (scheme != "http" && scheme != "https") {
            choice.error = "Proxy-Schema nicht unterstützt: " + scheme;
            return choice;
        }
        authority = value.substr(scheme_end + 3);
        const std::size_t at = authority.rfind('@');
        const std::size_t slash = authority.find('/', at == std::string_view::npos ? 0 : at);
        if (slash != std::string_view::npos) {
            authority = authority.substr(0, slash);
        }
    }
    std::string_view hostport = authority;
    const std::size_t at = authority.rfind('@');
    if (at != std::string_view::npos) {
        const std::string_view userinfo = authority.substr(0, at);
        hostport = authority.substr(at + 1);
        const std::size_t colon = userinfo.find(':');
        if (colon == std::string_view::npos) {
            choice.user = percent_decode(userinfo);
        } else {
            choice.user = percent_decode(userinfo.substr(0, colon));
            choice.password = percent_decode(userinfo.substr(colon + 1));
        }
    }
    std::string_view host = hostport;
    int port = 80;
    const std::size_t colon = hostport.rfind(':');
    if (colon != std::string_view::npos) {
        host = hostport.substr(0, colon);
        const std::string_view port_text = hostport.substr(colon + 1);
        const auto res = std::from_chars(port_text.data(), port_text.data() + port_text.size(), port);
        if (port_text.empty() || res.ec != std::errc{} || res.ptr != port_text.data() + port_text.size() || port < 1 ||
            port > 65535) {
            choice.error = "Proxy-Port ungültig";
            return choice;
        }
    }
    if (!valid_proxy_host(host)) {
        choice.error = "Proxy-Angabe ungültig";
        return choice;
    }
    choice.mode = ProxyChoice::Mode::Named;
    choice.server = std::string(host) + ":" + std::to_string(port);
    return choice;
}

bool ends_with(std::string_view s, std::string_view suffix) {
    return s.size() >= suffix.size() && s.compare(s.size() - suffix.size(), suffix.size(), suffix) == 0;
}

}  // namespace

ProxyEnv read_proxy_env() {
    ProxyEnv env;
    env.https_proxy = read_env(L"HTTPS_PROXY");
    env.http_proxy = read_env(L"HTTP_PROXY");
    env.no_proxy = read_env(L"NO_PROXY");
    return env;
}

bool is_loopback_host(std::string_view host) {
    const std::string h = ascii_lower(host);
    if (h == "localhost" || h == "localhost.") {
        return true;
    }
    // 127.x.y.z: vier Dezimaloktette, erstes genau 127.
    int parts = 0;
    std::size_t pos = 0;
    int first = -1;
    while (pos <= h.size()) {
        const std::size_t dot = h.find('.', pos);
        const std::string_view part =
            std::string_view(h).substr(pos, dot == std::string::npos ? std::string::npos : dot - pos);
        int value = -1;
        const auto res = std::from_chars(part.data(), part.data() + part.size(), value);
        if (part.empty() || part.size() > 3 || res.ec != std::errc{} || res.ptr != part.data() + part.size() ||
            value < 0 || value > 255) {
            return false;
        }
        if (parts == 0) {
            first = value;
        }
        ++parts;
        if (dot == std::string::npos) {
            break;
        }
        pos = dot + 1;
    }
    return parts == 4 && first == 127;
}

bool no_proxy_matches(std::string_view host, int port, std::string_view no_proxy) {
    const std::string_view list = trim_ascii(no_proxy);
    if (list.empty()) {
        return false;
    }
    if (list == "*") {
        return true;
    }
    const std::string hostonly = ascii_lower(host);
    const std::string hostport = hostonly + ":" + std::to_string(port);
    std::size_t pos = 0;
    while (pos <= list.size()) {
        const std::size_t comma = list.find(',', pos);
        std::string_view entry =
            trim_ascii(list.substr(pos, comma == std::string_view::npos ? std::string_view::npos : comma - pos));
        while (!entry.empty() && entry.front() == '.') {
            entry.remove_prefix(1);
        }
        if (!entry.empty()) {
            const std::string name = ascii_lower(entry);
            if (hostonly == name || hostport == name) {
                return true;
            }
            const std::string dotted = "." + name;
            if (ends_with(hostonly, dotted) || ends_with(hostport, dotted)) {
                return true;
            }
        }
        if (comma == std::string_view::npos) {
            break;
        }
        pos = comma + 1;
    }
    return false;
}

ProxyChoice choose_proxy(const ParsedUrl& target, const ProxyEnv& env) {
    if (is_loopback_host(target.host)) {
        ProxyChoice direct;
        direct.mode = ProxyChoice::Mode::Direct;
        return direct;
    }
    const std::string& value = target.scheme == "https" ? env.https_proxy : env.http_proxy;
    if (trim_ascii(value).empty()) {
        return ProxyChoice{};
    }
    if (no_proxy_matches(target.host, target.port, env.no_proxy)) {
        ProxyChoice direct;
        direct.mode = ProxyChoice::Mode::Direct;
        return direct;
    }
    return parse_proxy_value(value);
}

}  // namespace swbh::win
