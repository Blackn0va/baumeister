#include "server/research_api.hpp"

#include "base/json.hpp"

#include <cmath>
#include <cstdio>
#include <optional>

namespace swbh::server {

namespace {

ApiResponse reply(int status, std::string body) {
    ApiResponse r;
    r.status = status;
    r.body = std::move(body);
    return r;
}

ApiResponse bad(std::string_view code) {
    return reply(400, R"({"error":")" + std::string(code) + R"("})");
}

// Auf höchstens max Byte kürzen, ohne ein UTF-8-Zeichen zu zerschneiden.
std::string cut_utf8(const std::string& s, std::size_t max) {
    if (s.size() <= max) {
        return s;
    }
    std::size_t n = max;
    while (n > 0 && (static_cast<unsigned char>(s[n]) & 0xC0) == 0x80) {
        --n;
    }
    return s.substr(0, n) + "…";
}

bool get_number(const Json& o, std::string_view key, double lo, double hi, double& out) {
    const Json* v = json_find(o, key);
    if (v == nullptr || !v->is_number()) {
        return false;
    }
    const double d = v->get<double>();
    if (!std::isfinite(d) || d < lo || d > hi) {
        return false;
    }
    out = d;
    return true;
}

std::string with_comma(double v, int digits) {
    char buf[32];
    std::snprintf(buf, sizeof(buf), "%.*f", digits, v);
    std::string s(buf);
    for (char& c : s) {
        if (c == '.') {
            c = ',';
        }
    }
    return s;
}

}  // namespace

bool is_research_path(std::string_view target) {
    return target.substr(0, target.find('?')) == kResearchPath;
}

ApiResponse handle_research(const HttpRequest& request, const std::function<bool()>& allowed,
                            const std::function<void(const ResearchNote&)>& noted) {
    if (request.method == "GET") {
        return reply(200, allowed() ? R"({"erlaubt":true})" : R"({"erlaubt":false})");
    }
    if (request.method != "POST") {
        ApiResponse r = reply(405, R"({"error":"nur_get_oder_post"})");
        r.extra_headers.emplace_back("Allow: GET, POST");
        return r;
    }
    const std::optional<Json> body = try_parse_utf8(request.body);
    if (!body || !body->is_object()) {
        return bad("body_kein_json");
    }
    const Json* title = json_find(*body, "titel");
    if (title == nullptr || !title->is_string() || title->get<std::string>().empty()) {
        return bad("titel_fehlt");
    }
    ResearchNote note;
    note.title = cut_utf8(title->get<std::string>(), 120);
    if (const Json* order = json_find(*body, "auftrag"); order != nullptr && order->is_string()) {
        note.order = cut_utf8(order->get<std::string>(), 200);
    }
    double target = 0.0;
    if (!get_number(*body, "hoehe_m", 0.0, 10000.0, note.height_m) ||
        !get_number(*body, "zielhoehe", 0.0, 4096.0, target) || !get_number(*body, "massstab", 0.0, 1.0, note.scale)) {
        return bad("zahl_ungueltig");  // umlaut-ok: snake_case-Code wie im Plugin (ASCII auf der Leitung)
    }
    note.target_blocks = static_cast<int>(std::lround(target));
    noted(note);
    return reply(200, R"({"ok":true})");
}

std::string research_line_de(const ResearchNote& note) {
    std::string s = note.title;
    if (note.height_m > 0.0) {
        s += " – " + with_comma(note.height_m, note.height_m == std::floor(note.height_m) ? 0 : 1) + " m";
    }
    if (note.scale >= 0.999) {
        s += ", 1:1";
    } else {
        s += ", verkleinert auf " + std::to_string(static_cast<int>(std::lround(note.scale * 100))) + " %";
        if (note.target_blocks > 0) {
            s += " (" + std::to_string(note.target_blocks) + " Blöcke)";
        }
    }
    return s;
}

}  // namespace swbh::server
