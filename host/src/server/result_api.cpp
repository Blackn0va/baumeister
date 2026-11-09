#include "server/result_api.hpp"

#include "base/json.hpp"

#include <algorithm>
#include <array>
#include <cmath>
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

// Auf höchstens max Byte kürzen, ohne ein UTF-8-Zeichen zu zerschneiden (wie research_api).
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

std::string text(const Json& o, std::string_view key, std::size_t max) {
    const Json* v = json_find(o, key);
    return v != nullptr && v->is_string() ? cut_utf8(v->get<std::string>(), max) : std::string();
}

// 8652 → „8 652“ wie im übrigen Deutsch der App.
std::string grouped(std::int64_t n) {
    std::string digits = std::to_string(n);
    std::string out;
    for (std::size_t i = 0; i < digits.size(); ++i) {
        if (i > 0 && (digits.size() - i) % 3 == 0) {
            out += ' ';
        }
        out += digits[i];
    }
    return out;
}

// Grenze vor order_key: weit über dem, was log::safe(…, 160) behält (160 Codepunkte ≤ 640 Byte).
constexpr std::size_t kMaxOrderBytes = 2048;

constexpr std::array<std::string_view, 4> kOutcomes{"gebaut", "verworfen", "abgesagt", "abgebrochen"};

}  // namespace

bool is_result_path(std::string_view target) {
    return target.substr(0, target.find('?')) == kResultPath;
}

ApiResponse handle_result(const HttpRequest& request, const std::function<void(const ResultNote&)>& noted) {
    if (request.method != "POST") {
        ApiResponse r = reply(405, R"({"error":"nur_post"})");
        r.extra_headers.emplace_back("Allow: POST");
        return r;
    }
    const std::optional<Json> body = try_parse_utf8(request.body);
    if (!body || !body->is_object()) {
        return bad("body_kein_json");
    }
    ResultNote note;
    note.outcome = text(*body, "ergebnis", 20);
    bool known = false;
    for (const std::string_view o : kOutcomes) {
        known = known || note.outcome == o;
    }
    if (!known) {
        return bad("ergebnis_unbekannt");
    }
    // Vorher text(…, 200): 200 Byte ohne log::safe – ab 161 Zeichen oder mit Tab traf das Ergebnis den LLM-Auftrag im
    // Verlauf nicht mehr (siehe order_key). Jetzt erst grob auf kMaxOrderBytes (Schutz vor Riesentexten, OHNE „…“,
    // sonst wiche der Schlüssel bei langen Texten wieder ab), dann derselbe Schlüssel wie order_text.
    if (const Json* a = json_find(*body, "auftrag"); a != nullptr && a->is_string()) {
        const std::string& raw = a->get_ref<const std::string&>();
        std::size_t n = std::min(raw.size(), kMaxOrderBytes);
        while (n < raw.size() && n > 0 && (static_cast<unsigned char>(raw[n]) & 0xC0) == 0x80) {
            --n;
        }
        note.order = order_key(std::string_view(raw).substr(0, n));
    }
    note.reason = text(*body, "grund", 300);
    note.plot = text(*body, "plot", 40);
    note.client = text(*body, "quelle", 20) == "client";
    note.fixes = text(*body, "korrekturen", 300);
    if (const Json* n = json_find(*body, "runden"); n != nullptr) {
        if (!n->is_number_integer() || n->get<std::int64_t>() < 0 || n->get<std::int64_t>() > 20) {
            return bad("runden_ungueltig");  // umlaut-ok: snake_case-Code wie im Plugin (ASCII auf der Leitung)
        }
        note.rounds = static_cast<int>(n->get<std::int64_t>());
    }
    if (const Json* b = json_find(*body, "bloecke"); b != nullptr) {  // umlaut-ok: Schlüssel ASCII
        if (!b->is_number()) {
            return bad("bloecke_ungueltig");  // umlaut-ok: snake_case-Code wie im Plugin (ASCII auf der Leitung)
        }
        const double d = b->get<double>();
        if (!std::isfinite(d) || d < 0.0 || d > 100'000'000.0) {
            return bad("bloecke_ungueltig");  // umlaut-ok: wie oben
        }
        note.blocks = static_cast<std::int64_t>(d);
    }
    noted(note);
    return reply(200, R"({"ok":true})");
}

std::string result_line_de(const ResultNote& note) {
    std::string s = note.outcome + (note.client ? " (Client)" : "") +
                    (note.outcome == "gebaut" && !note.fixes.empty() ? ", selbst nachgebessert" : "");
    if (note.outcome == "gebaut") {
        s += ": " + grouped(note.blocks) + " Blöcke";
    } else if (!note.reason.empty()) {
        s += ": " + note.reason;
    }
    if (!note.plot.empty()) {
        s += " auf Plot " + note.plot;
    }
    return s;
}

bool result_is_failure(const ResultNote& note) {
    return note.outcome != "gebaut";
}

}  // namespace swbh::server
