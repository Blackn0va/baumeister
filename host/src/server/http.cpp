#include "server/http.hpp"

#include <algorithm>
#include <cctype>

namespace swbh::server {

namespace {

std::string lower(std::string_view s) {
    std::string out(s);
    std::transform(out.begin(), out.end(), out.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return out;
}

// RFC 9110 token: Kopfnamen und Methode.
bool is_tchar(unsigned char c) {
    return std::isalnum(c) != 0 || std::string_view("!#$%&'*+-.^_`|~").find(static_cast<char>(c)) != std::string_view::npos;
}

std::string_view trim(std::string_view s) {
    while (!s.empty() && (s.front() == ' ' || s.front() == '\t')) {
        s.remove_prefix(1);
    }
    while (!s.empty() && (s.back() == ' ' || s.back() == '\t')) {
        s.remove_suffix(1);
    }
    return s;
}

ParseResult fail(int status, std::string code) {
    ParseResult r;
    r.status = ParseStatus::Error;
    r.http_status = status;
    r.error = std::move(code);
    return r;
}

bool all_digits(std::string_view s) {
    return !s.empty() && std::all_of(s.begin(), s.end(), [](unsigned char c) { return std::isdigit(c) != 0; });
}

}  // namespace

const std::string* HttpRequest::header(std::string_view lower_name) const {
    for (const auto& [name, value] : headers) {
        if (name == lower_name) {
            return &value;
        }
    }
    return nullptr;
}

ParseResult parse_request(std::string_view buffer, const Limits& limits) {
    const std::size_t end = buffer.find("\r\n\r\n");
    if (end == std::string_view::npos) {
        if (buffer.size() > limits.max_header_bytes) {
            return fail(431, "kopf_zu_gross");
        }
        return {};
    }
    if (end + 4 > limits.max_header_bytes) {
        return fail(431, "kopf_zu_gross");
    }
    const std::string_view head = buffer.substr(0, end);
    // Nackte LF sind mehrdeutig (manche Server sehen ein Zeilenende, andere nicht) – nur CRLF.
    for (std::size_t i = 0; i < head.size(); ++i) {
        if ((head[i] == '\n' && (i == 0 || head[i - 1] != '\r')) || (head[i] == '\r' && (i + 1 >= head.size() || head[i + 1] != '\n'))) {
            return fail(400, "kopf_zeilenende");
        }
    }

    ParseResult r;
    HttpRequest& req = r.request;
    std::size_t line_end = head.find("\r\n");
    const std::string_view request_line = head.substr(0, line_end);
    const std::size_t sp1 = request_line.find(' ');
    const std::size_t sp2 = sp1 == std::string_view::npos ? sp1 : request_line.find(' ', sp1 + 1);
    if (sp1 == std::string_view::npos || sp2 == std::string_view::npos ||
        request_line.find(' ', sp2 + 1) != std::string_view::npos) {
        return fail(400, "anfragezeile_falsch");
    }
    req.method = std::string(request_line.substr(0, sp1));
    req.target = std::string(request_line.substr(sp1 + 1, sp2 - sp1 - 1));
    req.version = std::string(request_line.substr(sp2 + 1));
    if (req.method.empty() ||
        !std::all_of(req.method.begin(), req.method.end(), [](unsigned char c) { return c >= 'A' && c <= 'Z'; })) {
        return fail(400, "methode_falsch");
    }
    if (req.target.empty() || req.target.front() != '/' ||
        !std::all_of(req.target.begin(), req.target.end(), [](unsigned char c) { return c > 0x20 && c < 0x7F; })) {
        return fail(400, "ziel_falsch");
    }
    if (req.version != "HTTP/1.1") {
        return fail(505, "http_version");
    }

    std::size_t content_lengths = 0;
    std::size_t hosts = 0;
    std::size_t body_size = 0;
    while (line_end != std::string_view::npos) {
        const std::size_t start = line_end + 2;
        line_end = head.find("\r\n", start);
        const std::string_view line = head.substr(start, line_end == std::string_view::npos ? std::string_view::npos
                                                                                              : line_end - start);
        if (line.empty()) {
            return fail(400, "kopf_leerzeile");
        }
        if (line.front() == ' ' || line.front() == '\t') {
            return fail(400, "kopf_fortsetzung");  // obs-fold, RFC 9112 §5.2: ablehnen
        }
        const std::size_t colon = line.find(':');
        if (colon == std::string_view::npos || colon == 0 ||
            !std::all_of(line.begin(), line.begin() + static_cast<std::ptrdiff_t>(colon),
                         [](unsigned char c) { return is_tchar(c); })) {
            return fail(400, "kopf_name_falsch");
        }
        const std::string_view value = trim(line.substr(colon + 1));
        if (std::any_of(value.begin(), value.end(), [](unsigned char c) { return (c < 0x20 && c != '\t') || c == 0x7F; })) {
            return fail(400, "kopf_wert_falsch");
        }
        if (req.headers.size() >= limits.max_header_count) {
            return fail(431, "zu_viele_kopfzeilen");
        }
        req.headers.emplace_back(lower(line.substr(0, colon)), std::string(value));
        const std::string& name = req.headers.back().first;
        if (name == "content-length") {
            ++content_lengths;
            if (!all_digits(value) || value.size() > 9) {
                return fail(400, "content_length_falsch");
            }
            body_size = static_cast<std::size_t>(std::stoul(std::string(value)));
        } else if (name == "transfer-encoding") {
            return fail(501, "transfer_encoding_abgelehnt");
        } else if (name == "host") {
            ++hosts;
        }
    }
    if (content_lengths > 1) {
        return fail(400, "content_length_doppelt");
    }
    if (hosts != 1) {
        return fail(400, hosts == 0 ? "host_fehlt" : "host_doppelt");
    }
    const bool needs_body = req.method == "POST" || req.method == "PUT" || req.method == "PATCH";
    if (needs_body && content_lengths == 0) {
        return fail(411, "content_length_fehlt");
    }
    const bool audio = req.target.substr(0, req.target.find('?')) == kAudioBodyPath;
    if (body_size > (audio ? std::max(limits.max_body_bytes, kMaxAudioBodyBytes) : limits.max_body_bytes)) {
        return fail(413, "body_zu_gross");
    }
    const std::size_t body_start = end + 4;
    if (buffer.size() - body_start < body_size) {
        return {};
    }
    req.body = std::string(buffer.substr(body_start, body_size));
    r.status = ParseStatus::Complete;
    return r;
}

bool host_is_loopback(std::string_view host) {
    std::string_view name;
    std::string_view rest;
    if (!host.empty() && host.front() == '[') {
        const std::size_t close = host.find(']');
        if (close == std::string_view::npos) {
            return false;
        }
        name = host.substr(1, close - 1);
        rest = host.substr(close + 1);
        if (name != "::1") {
            return false;
        }
    } else {
        const std::size_t colon = host.find(':');
        name = host.substr(0, colon);
        rest = colon == std::string_view::npos ? std::string_view() : host.substr(colon);
        const std::string n = lower(name);
        if (n != "127.0.0.1" && n != "localhost") {
            return false;
        }
    }
    if (rest.empty()) {
        return true;
    }
    return rest.front() == ':' && all_digits(rest.substr(1)) && rest.size() <= 6;
}

std::optional<Rejection> check_request(const HttpRequest& request) {
    const std::string* host = request.header("host");
    if (host == nullptr || !host_is_loopback(*host)) {
        return Rejection{403, "host_nicht_erlaubt"};
    }
    if (request.header("origin") != nullptr || request.header("sec-fetch-site") != nullptr) {
        return Rejection{403, "browser_anfrage_verboten"};
    }
    if (request.method == "POST") {
        const std::string* type = request.header("content-type");
        const std::string t = type != nullptr ? lower(*type) : std::string();
        // Ton aus dem Spiel (kAudioBodyPath) kommt roh als application/octet-stream (CompanionVoiceLink.hear), nur dort
        // und nur so. Vorher galt JSON für jeden POST: dev01 2026-11-04 22:11 wies die App jeden Voice-Chat-Satz mit
        // 415 ab, Vera hörte im Spiel nichts. Der Schutz gegen Browser (CSRF, ASVS V13.2.1, OWASP A01) bleibt: Wie
        // application/json ist octet-stream kein CORS-safelisted Typ, ein Browser schickt ihn nur nach Preflight – und
        // Origin/Sec-Fetch-Site lehnt die Prüfung darüber ab. Wer octet-stream für alle Pfade öffnet, nimmt den
        // JSON-Endpunkten diesen Typ-Zwang.
        const bool audio = request.target.substr(0, request.target.find('?')) == kAudioBodyPath;
        if (audio) {
            if (t != "application/octet-stream") {
                return Rejection{415, "content_type_nicht_ton"};
            }
        } else if (t != "application/json" && !t.starts_with("application/json;")) {
            return Rejection{415, "content_type_nicht_json"};
        }
    }
    return std::nullopt;
}

std::string_view reason_phrase(int status) {
    switch (status) {
    case 200: return "OK";
    case 400: return "Bad Request";
    case 403: return "Forbidden";
    case 404: return "Not Found";
    case 405: return "Method Not Allowed";
    case 408: return "Request Timeout";
    case 411: return "Length Required";
    case 413: return "Content Too Large";
    case 415: return "Unsupported Media Type";
    case 431: return "Request Header Fields Too Large";
    case 500: return "Internal Server Error";
    case 501: return "Not Implemented";
    case 503: return "Service Unavailable";
    case 505: return "HTTP Version Not Supported";
    default: return "Status";
    }
}

std::string make_response(int status, std::string_view content_type, std::string_view body,
                          const std::vector<std::string>& extra_headers) {
    std::string out = "HTTP/1.1 " + std::to_string(status) + " " + std::string(reason_phrase(status)) + "\r\n";
    out += "Content-Type: " + std::string(content_type) + "\r\n";
    out += "Content-Length: " + std::to_string(body.size()) + "\r\n";
    out += "Connection: close\r\n";
    out += "Cache-Control: no-store\r\n";
    out += "X-Content-Type-Options: nosniff\r\n";
    for (const std::string& h : extra_headers) {
        out += h + "\r\n";
    }
    out += "\r\n";
    out += body;
    return out;
}

}  // namespace swbh::server
