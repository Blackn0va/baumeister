#include "server/module_api.hpp"

#include "base/json.hpp"
#include "base/utf8.hpp"

#include <algorithm>
#include <array>
#include <utility>

namespace swbh::server {

namespace {

ApiResponse bad(std::string_view code) {
    ApiResponse r;
    r.status = 400;
    r.body = R"({"error":")" + std::string(code) + R"("})";
    return r;
}

bool valid_id(std::string_view id) {
    if (id.empty() || id.size() > kModuleIdMax) {
        return false;
    }
    return std::all_of(id.begin(), id.end(),
                       [](char c) { return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-'; });
}

// Text für die Anzeige: höchstens max Codepoints, keine Steuerzeichen (C0, DEL, C1). Ein Zeilenumbruch im Namen
// zerlegte sonst die Liste und die Konsolenzeile, ein U+0085 ebenso (Qt bricht dort um). Die Gültigkeit des UTF-8 hat
// try_parse_utf8 schon erzwungen.
bool valid_text(std::string_view s, std::size_t min_cp, std::size_t max_cp) {
    std::size_t count = 0;
    std::size_t pos = 0;
    while (pos < s.size()) {
        const char32_t cp = decode_next(s, pos);
        if (cp < 0x20 || (cp >= 0x7F && cp <= 0x9F)) {
            return false;
        }
        if (++count > max_cp) {
            return false;
        }
    }
    return count >= min_cp;
}

std::optional<ModuleRunState> parse_state(std::string_view s) {
    if (s == "aktiv") {
        return ModuleRunState::Active;
    }
    if (s == "aus") {
        return ModuleRunState::Off;
    }
    if (s == "wartet") {
        return ModuleRunState::Waiting;
    }
    if (s == "fehler") {
        return ModuleRunState::Failed;
    }
    return std::nullopt;
}

// Ein optionales Textfeld: fehlt = leer; vorhanden muss es ein String innerhalb der Grenzen sein.
bool read_optional_text(const Json& m, std::string_view key, std::size_t max_cp, std::string& out) {
    const Json* v = json_find(m, key);
    if (v == nullptr) {
        return true;
    }
    if (!v->is_string()) {
        return false;
    }
    out = v->get<std::string>();
    return valid_text(out, 0, max_cp);
}

// Leer = gültig, sonst snake_case-Code.
std::string parse_module(const Json& m, ServerModule& out) {
    if (!m.is_object()) {
        return "modul_kein_objekt";
    }
    static constexpr std::array<std::string_view, 7> kFields = {"id",     "name",   "description", "on",
                                                                "state",  "reason", "needs"};
    for (auto it = m.begin(); it != m.end(); ++it) {
        if (std::find(kFields.begin(), kFields.end(), it.key()) == kFields.end()) {
            return "modul_feld_unbekannt";
        }
    }
    const Json* id = json_find(m, "id");
    if (id == nullptr || !id->is_string() || !valid_id(id->get_ref<const std::string&>())) {
        return "modul_id";
    }
    out.id = id->get<std::string>();
    const Json* name = json_find(m, "name");
    if (name == nullptr || !name->is_string() || !valid_text(name->get_ref<const std::string&>(), 1, kModuleNameMax)) {
        return "modul_name";
    }
    out.name = name->get<std::string>();
    if (!read_optional_text(m, "description", kModuleDescriptionMax, out.description)) {
        return "modul_description";
    }
    const Json* on = json_find(m, "on");
    if (on == nullptr || !on->is_boolean()) {
        return "modul_on";
    }
    out.on = on->get<bool>();
    const Json* state = json_find(m, "state");
    const std::optional<ModuleRunState> st =
        state != nullptr && state->is_string() ? parse_state(state->get_ref<const std::string&>()) : std::nullopt;
    if (!st) {
        return "modul_state";
    }
    out.state = *st;
    if (!read_optional_text(m, "reason", kModuleReasonMax, out.reason)) {
        return "modul_reason";
    }
    if (const Json* needs = json_find(m, "needs"); needs != nullptr) {
        if (!needs->is_array() || needs->size() > kModuleMaxNeeds) {
            return "modul_needs";
        }
        for (const Json& n : *needs) {
            if (!n.is_string() || !valid_id(n.get_ref<const std::string&>())) {
                return "modul_needs";
            }
            out.needs.push_back(n.get<std::string>());
        }
    }
    return {};
}

}  // namespace

std::optional<std::vector<ServerModule>> parse_modules(std::string_view body, std::string& error) {
    // Eigene Grenze vor dem Parsen: Der Server nimmt bis kMaxBodyBytes (256 KiB) für alle Pfade an, eine Modulliste
    // braucht höchstens die Hälfte – mehr wird gar nicht erst in einen JSON-Baum gelesen.
    if (body.size() > kModuleMaxBodyBytes) {
        error = "zu_gross";  // umlaut-ok: snake_case-Code auf der Leitung (ASCII)
        return std::nullopt;
    }
    const std::optional<Json> j = try_parse_utf8(body);
    if (!j || !j->is_object()) {
        error = "body_kein_json";
        return std::nullopt;
    }
    for (auto it = j->begin(); it != j->end(); ++it) {
        if (it.key() != "version" && it.key() != "modules") {
            error = "feld_unbekannt";
            return std::nullopt;
        }
    }
    const Json* version = json_find(*j, "version");
    if (version == nullptr || !version->is_number_integer() || version->get<long long>() != 1) {
        error = "version";
        return std::nullopt;
    }
    const Json* modules = json_find(*j, "modules");
    if (modules == nullptr || !modules->is_array()) {
        error = "modules";
        return std::nullopt;
    }
    if (modules->size() > kModuleMaxCount) {
        error = "zu_viele_module";
        return std::nullopt;
    }
    std::vector<ServerModule> out;
    out.reserve(modules->size());
    for (const Json& m : *modules) {
        ServerModule mod;
        if (std::string code = parse_module(m, mod); !code.empty()) {
            error = std::move(code);
            return std::nullopt;
        }
        const bool duplicate =
            std::any_of(out.begin(), out.end(), [&](const ServerModule& o) { return o.id == mod.id; });
        if (duplicate) {
            error = "modul_id_doppelt";
            return std::nullopt;
        }
        out.push_back(std::move(mod));
    }
    return out;
}

std::string_view module_state_name(ModuleRunState state) {
    switch (state) {
    case ModuleRunState::Active: return "aktiv";
    case ModuleRunState::Off: return "aus";
    case ModuleRunState::Waiting: return "wartet";
    case ModuleRunState::Failed: return "fehler";
    }
    return "aus";
}

std::string ModuleState::apply(std::string_view body, std::int64_t now_unix, std::string now_hms) {
    std::string error;
    std::optional<std::vector<ServerModule>> modules = parse_modules(body, error);
    if (!modules) {
        return error;
    }
    auto report = std::make_shared<ModuleReport>();
    report->modules = std::move(*modules);
    report->received_unix = now_unix;
    report->received_hms = std::move(now_hms);
    std::lock_guard<std::mutex> lock(mutex_);
    current_ = std::move(report);
    ++seq_;
    return {};
}

std::shared_ptr<const ModuleReport> ModuleState::snapshot() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return current_;
}

std::uint64_t ModuleState::seq() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return seq_;
}

bool is_module_path(std::string_view target) {
    return target.substr(0, target.find('?')) == kModulePath;
}

ApiResponse handle_modules(const HttpRequest& request, ModuleState& state, std::int64_t now_unix,
                           const std::string& now_hms) {
    if (request.method != "POST") {
        ApiResponse r;
        r.status = 405;
        r.body = R"({"error":"nur_post"})";
        r.extra_headers.emplace_back("Allow: POST");
        return r;
    }
    if (const std::string code = state.apply(request.body, now_unix, now_hms); !code.empty()) {
        return bad(code);
    }
    ApiResponse r;
    r.status = 204;
    return r;
}

}  // namespace swbh::server
