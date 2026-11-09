#include "server/city_api.hpp"

#include "base/encoding.hpp"

#include <cmath>
#include <limits>
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

bool get_int(const Json& o, std::string_view key, long long lo, long long hi, long long& out) {
    const Json* v = json_find(o, key);
    if (v == nullptr || !v->is_number_integer()) {
        return false;
    }
    const long long n = v->get<long long>();
    if (n < lo || n > hi) {
        return false;
    }
    out = n;
    return true;
}

bool get_string(const Json& o, std::string_view key, std::size_t max_bytes, std::string& out) {
    const Json* v = json_find(o, key);
    if (v == nullptr || !v->is_string()) {
        return false;
    }
    out = v->get<std::string>();
    return out.size() <= max_bytes;
}

bool valid_job(const std::string& job) {
    if (job.empty() || job.size() > 64) {
        return false;
    }
    for (const char c : job) {
        const bool ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' ||
                        c == '-';
        if (!ok) {
            return false;
        }
    }
    return true;
}

bool get_pair(const Json& o, std::string_view key, int& a, int& b) {
    const Json* v = json_find(o, key);
    if (v == nullptr || !v->is_array() || v->size() != 2 || !(*v)[0].is_number_integer() ||
        !(*v)[1].is_number_integer()) {
        return false;
    }
    constexpr long long kLim = 30'000'000;  // Weltgrenze von Minecraft
    const long long x = (*v)[0].get<long long>();
    const long long z = (*v)[1].get<long long>();
    if (x < -kLim || x > kLim || z < -kLim || z > kLim) {
        return false;
    }
    a = static_cast<int>(x);
    b = static_cast<int>(z);
    return true;
}

bool get_tiles(const Json& o, std::string_view key, std::size_t max, long long lim,
               std::vector<std::pair<int, int>>& out) {
    const Json* v = json_find(o, key);
    if (v == nullptr) {
        return true;  // fehlt = leer
    }
    if (!v->is_array() || v->size() > max) {
        return false;
    }
    for (const Json& e : *v) {
        if (!e.is_array() || e.size() != 2 || !e[0].is_number_integer() || !e[1].is_number_integer()) {
            return false;
        }
        const long long a = e[0].get<long long>();
        const long long b = e[1].get<long long>();
        if (a < -lim || a > lim || b < -lim || b > lim) {
            return false;
        }
        out.emplace_back(static_cast<int>(a), static_cast<int>(b));
    }
    return true;
}

// Ältestes Hintergrund-Raster zuerst opfern, bis die Zellgrenze wieder hält.
void trim_background(CityView& v) {
    std::size_t drop = 0;
    while (v.cells > kCityMaxCells && drop < v.background.size()) {
        v.cells -= v.background[drop]->cells.size();
        ++drop;
    }
    v.background.erase(v.background.begin(), v.background.begin() + static_cast<std::ptrdiff_t>(drop));
}

std::string parse_world(const Json& m, WorldOverview& w) {
    long long tile = 0;
    if (!get_string(m, "world", 256, w.world) || w.world.empty()) {
        return "world";
    }
    if (!get_int(m, "tile", 16, 65536, tile)) {
        return "tile";
    }
    w.tile = static_cast<int>(tile);
    constexpr long long kTiles = 2'000'000;  // Kachelindex: Weltgrenze / kleinste Kachel
    if (!get_tiles(m, "done", kWorldMaxDone, kTiles, w.done) || !get_tiles(m, "failed", kWorldMaxDone, kTiles, w.failed) ||
        !get_tiles(m, "queue", kWorldMaxQueue, kTiles, w.queue)) {
        return "kacheln";
    }
    if (!get_tiles(m, "players", kWorldMaxPlayers, 30'000'000, w.players)) {
        return "players";
    }
    if (json_find(m, "current") != nullptr) {
        if (!get_pair(m, "current", w.current_tx, w.current_tz)) {
            return "current";
        }
        w.has_current = true;
    }
    if (!get_string(m, "phase", 16, w.phase) ||
        (w.phase != "wartet" && w.phase != "karte" && w.phase != "planen" && w.phase != "bereit" &&
         w.phase != "bauen")) {
        return "phase";
    }
    const Json* a = json_find(m, "auto");
    if (a == nullptr || !a->is_boolean()) {
        return "auto";
    }
    w.auto_on = a->get<bool>();
    return {};
}

}  // namespace

std::string CityState::apply(const Json& m) {
    if (!m.is_object()) {
        return "kein_objekt";
    }
    long long version = 0;
    if (!get_int(m, "v", 1, 1, version)) {
        return "version";
    }
    std::string event;
    std::string job;
    if (!get_string(m, "event", 16, event)) {
        return "event";
    }
    if (event == "welt") {
        auto w = std::make_shared<WorldOverview>();
        if (std::string code = parse_world(m, *w); !code.empty()) {
            return code;
        }
        std::lock_guard<std::mutex> lock(mutex_);
        auto next = current_ ? std::make_shared<CityView>(*current_) : std::make_shared<CityView>();
        if (next->job.empty()) {
            // Noch kein Auftrag: Die Übersicht allein ist die Ansicht.
            next->kind = "weltbau";
            next->world = w->world;
            next->started = std::chrono::steady_clock::now();
        }
        next->overview = std::move(w);
        current_ = std::move(next);
        ++seq_;
        return {};
    }
    if (!get_string(m, "job", 64, job) || !valid_job(job)) {
        return "job";
    }
    constexpr long long kWorld = 30'000'000;

    if (event == "start") {
        auto v = std::make_shared<CityView>();
        v->job = job;
        long long stages = 0;
        if (!get_string(m, "kind", 16, v->kind) || (v->kind != "stadt" && v->kind != "weltbau")) {
            return "kind";
        }
        // Bytes statt Zeichen: 120 Zeichen Umlaut-Text sind bis zu 480 Byte UTF-8.
        if (!get_string(m, "place", 480, v->place) || !get_string(m, "world", 256, v->world)) {
            return "place_world";
        }
        if (!get_int(m, "stages", 1, kCityMaxStages, stages)) {
            return "stages";
        }
        v->stages = static_cast<int>(stages);
        if (!get_pair(m, "origin", v->origin_x, v->origin_z)) {
            return "origin";
        }
        v->started = std::chrono::steady_clock::now();
        std::lock_guard<std::mutex> lock(mutex_);
        if (current_ && current_->world == v->world) {
            // Übersicht der Welt bleibt; beim Weltbau auch die Raster der vorigen Kacheln.
            v->overview = current_->overview;
            if (v->kind == "weltbau" && current_->kind == "weltbau") {
                v->background = current_->background;
                v->background.insert(v->background.end(), current_->stage_list.begin(), current_->stage_list.end());
                v->cells = current_->cells;
                trim_background(*v);
            }
        }
        current_ = std::move(v);
        ++seq_;
        return {};
    }

    std::lock_guard<std::mutex> lock(mutex_);
    if (event != "stage" && event != "progress" && event != "end") {
        return "event";
    }
    if (!current_ || current_->job != job) {
        return {};  // fremder oder alter Auftrag: angenommen, verworfen
    }
    auto next = std::make_shared<CityView>(*current_);
    if (event == "stage") {
        auto s = std::make_shared<CityStage>();
        long long index = 0;
        long long x0 = 0;
        long long z0 = 0;
        long long cell = 0;
        long long w = 0;
        long long h = 0;
        if (!get_int(m, "index", 0, kCityMaxStages - 1, index) || !get_int(m, "x0", -kWorld, kWorld, x0) ||
            !get_int(m, "z0", -kWorld, kWorld, z0) || !get_int(m, "cell", 1, kCityMaxCell, cell) ||
            !get_int(m, "w", 1, kCityMaxSide, w) || !get_int(m, "h", 1, kCityMaxSide, h)) {
            return "stage_felder";
        }
        std::string text;
        // Base64 von höchstens 360² Byte: ≤ 172 800 Zeichen.
        if (!get_string(m, "cells", 4 * ((kCityMaxSide * kCityMaxSide + 2) / 3), text)) {
            return "cells";
        }
        std::optional<std::vector<std::uint8_t>> bytes = base64_decode(text);
        if (!bytes || bytes->size() != static_cast<std::size_t>(w * h)) {
            return "cells_laenge";
        }
        for (const std::uint8_t b : *bytes) {
            if (b >= kCityCategories) {
                return "cells_wert";
            }
        }
        s->index = static_cast<int>(index);
        s->x0 = static_cast<int>(x0);
        s->z0 = static_cast<int>(z0);
        s->cell = static_cast<int>(cell);
        s->w = static_cast<int>(w);
        s->h = static_cast<int>(h);
        s->cells = std::move(*bytes);
        // Gleicher index noch einmal (Wiederholung einer Etappe): ersetzen statt doppelt zählen.
        std::uint64_t cells = next->cells;
        for (auto it = next->stage_list.begin(); it != next->stage_list.end(); ++it) {
            if ((*it)->index == s->index) {
                cells -= (*it)->cells.size();
                next->stage_list.erase(it);
                break;
            }
        }
        cells += s->cells.size();
        next->cells = cells;
        trim_background(*next);  // erst alte Kacheln opfern, dann ablehnen
        if (next->cells > kCityMaxCells) {
            return "zu_viele_zellen";
        }
        next->stage_list.push_back(std::move(s));
    } else if (event == "progress") {
        CityProgress p;
        long long done = 0;
        long long cur = 0;
        long long blocks = 0;
        long long planned = 0;
        const Json* rate = json_find(m, "rate");
        const Json* paused = json_find(m, "paused");
        constexpr long long kMany = std::numeric_limits<long long>::max() / 2;
        if (!get_int(m, "stagesDone", 0, kCityMaxStages, done) || !get_int(m, "currentStage", -1, kCityMaxStages, cur) ||
            !get_int(m, "blocks", 0, kMany, blocks) || !get_int(m, "planned", 0, kMany, planned) || rate == nullptr ||
            !rate->is_number() || paused == nullptr || !paused->is_boolean()) {
            return "progress_felder";
        }
        const double r = rate->get<double>();
        if (!std::isfinite(r) || r < 0.0) {
            return "rate";
        }
        p.stages_done = static_cast<int>(done);
        p.current_stage = static_cast<int>(cur);
        p.blocks = blocks;
        p.planned = planned;
        p.rate = r;
        p.paused = paused->get<bool>();
        if (json_find(m, "npc") != nullptr) {
            if (!get_pair(m, "npc", p.npc_x, p.npc_z)) {
                return "npc";
            }
            p.has_npc = true;
        }
        next->progress = p;
        next->has_progress = true;
    } else {
        std::string reason;
        long long blocks = 0;
        if (!get_string(m, "reason", 16, reason) ||
            (reason != "fertig" && reason != "abgebrochen" && reason != "fehler")) {
            return "reason";
        }
        if (!get_int(m, "blocks", 0, std::numeric_limits<long long>::max() / 2, blocks)) {
            return "blocks";
        }
        next->ended = true;
        next->end_reason = reason;
        next->end_blocks = blocks;
    }
    current_ = std::move(next);
    ++seq_;
    return {};
}

std::shared_ptr<const CityView> CityState::snapshot() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return current_;
}

std::uint64_t CityState::seq() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return seq_;
}

bool is_city_path(std::string_view target) {
    return target.substr(0, target.find('?')) == kCityPath;
}

ApiResponse handle_city(const HttpRequest& request, CityState& state) {
    if (request.method != "POST") {
        ApiResponse r = reply(405, R"({"error":"nur_post"})");
        r.extra_headers.emplace_back("Allow: POST");
        return r;
    }
    const std::optional<Json> body = try_parse_utf8(request.body);
    if (!body) {
        return bad("body_kein_json");
    }
    const std::string code = state.apply(*body);
    if (!code.empty()) {
        return bad(code);
    }
    return reply(200, R"({"ok":true})");
}

}  // namespace swbh::server
