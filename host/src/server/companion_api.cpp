#include "server/companion_api.hpp"

#include "base/json.hpp"

#include <algorithm>
#include <iterator>
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

std::string_view path_of(std::string_view target) {
    return target.substr(0, target.find('?'));
}

// Roher Wert von key in der Abfrage (erster Treffer), nullopt ohne ihn.
std::optional<std::string_view> query_value(std::string_view target, std::string_view key) {
    const std::size_t q = target.find('?');
    if (q == std::string_view::npos) {
        return std::nullopt;
    }
    std::string_view rest = target.substr(q + 1);
    while (!rest.empty()) {
        const std::size_t amp = rest.find('&');
        const std::string_view pair = rest.substr(0, amp);
        if (pair.size() > key.size() && pair.starts_with(key) && pair[key.size()] == '=') {
            return pair.substr(key.size() + 1);
        }
        rest = amp == std::string_view::npos ? std::string_view() : rest.substr(amp + 1);
    }
    return std::nullopt;
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

// Formular-Kodierung wie java.net.URLEncoder: „+“ = Leerzeichen, %XX = ein Byte. Unvollständiges % = nullopt.
std::optional<std::string> form_decode(std::string_view raw) {
    std::string out;
    out.reserve(raw.size());
    for (std::size_t i = 0; i < raw.size(); ++i) {
        if (raw[i] == '+') {
            out.push_back(' ');
        } else if (raw[i] == '%') {
            if (i + 2 >= raw.size()) {
                return std::nullopt;
            }
            const int hi = hex_value(raw[i + 1]);
            const int lo = hex_value(raw[i + 2]);
            if (hi < 0 || lo < 0) {
                return std::nullopt;
            }
            out.push_back(static_cast<char>(hi * 16 + lo));
            i += 2;
        } else {
            out.push_back(raw[i]);
        }
    }
    return out;
}

}  // namespace

void HeardQueue::push(Spoken s) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (queue_.size() >= kHeardMax) {
            queue_.pop_front();
            ++dropped_;
        }
        queue_.push_back(std::move(s));
    }
    cv_.notify_all();
}

std::vector<Spoken> HeardQueue::take(std::chrono::milliseconds hold, const std::function<bool()>& gone) {
    const auto until = std::chrono::steady_clock::now() + hold;
    std::unique_lock<std::mutex> lock(mutex_);
    while (queue_.empty()) {
        const auto now = std::chrono::steady_clock::now();
        if (now >= until) {
            return {};
        }
        // In Scheiben warten, damit ein geschlossenes Plugin (Neustart, Tunnel weg) den Thread nicht 20 s hält.
        cv_.wait_for(lock, std::min<std::chrono::steady_clock::duration>(until - now, std::chrono::milliseconds(250)));
        if (queue_.empty() && gone) {
            lock.unlock();
            const bool g = gone();
            lock.lock();
            if (g) {
                return {};
            }
        }
    }
    std::vector<Spoken> out(std::make_move_iterator(queue_.begin()), std::make_move_iterator(queue_.end()));
    queue_.clear();
    return out;
}

std::size_t HeardQueue::dropped() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return dropped_;
}

bool is_companion_path(std::string_view target) {
    const std::string_view p = path_of(target);
    return p == kCompanionSayPath || p == kCompanionHeardPath || p == kCompanionHearPath || p == kCompanionVoicePath;
}

std::string query_player(std::string_view target) {
    const std::size_t q = target.find('?');
    if (q == std::string_view::npos) {
        return {};
    }
    std::string_view rest = target.substr(q + 1);
    while (!rest.empty()) {
        const std::size_t amp = rest.find('&');
        const std::string_view pair = rest.substr(0, amp);
        if (pair.rfind("spieler=", 0) == 0) {
            const std::string name(pair.substr(8));
            return valid_player_name(name) ? name : std::string();
        }
        rest = amp == std::string_view::npos ? std::string_view() : rest.substr(amp + 1);
    }
    return {};
}

std::vector<std::string> query_in_game(std::string_view target) {
    std::vector<std::string> out;
    const std::size_t q = target.find('?');
    if (q == std::string_view::npos) {
        return out;
    }
    std::string_view rest = target.substr(q + 1);
    while (!rest.empty()) {
        const std::size_t amp = rest.find('&');
        const std::string_view pair = rest.substr(0, amp);
        if (pair.rfind("imspiel=", 0) == 0) {
            std::string_view names = pair.substr(8);
            while (!names.empty() && out.size() < 32) {
                const std::size_t comma = names.find(',');
                const std::string name(names.substr(0, comma));
                if (valid_player_name(name)) {
                    out.push_back(name);
                }
                names = comma == std::string_view::npos ? std::string_view() : names.substr(comma + 1);
            }
            return out;
        }
        rest = amp == std::string_view::npos ? std::string_view() : rest.substr(amp + 1);
    }
    return out;
}

bool valid_companion_name(std::string_view utf8) {
    std::size_t chars = 0;
    bool last_space = false;
    for (std::size_t i = 0; i < utf8.size();) {
        const auto b0 = static_cast<unsigned char>(utf8[i]);
        char32_t cp = 0;
        if (b0 < 0x80) {
            cp = b0;
            i += 1;
        } else if (b0 >= 0xC2 && b0 <= 0xDF && i + 1 < utf8.size() &&
                   (static_cast<unsigned char>(utf8[i + 1]) & 0xC0) == 0x80) {
            // 2-Byte-Folge; C0/C1 wären überlang. Längere Folgen tragen nichts aus der Liste (alles ≤ U+024F).
            cp = static_cast<char32_t>(((b0 & 0x1F) << 6) | (static_cast<unsigned char>(utf8[i + 1]) & 0x3F));
            i += 2;
        } else {
            return false;
        }
        const bool letter = (cp >= U'A' && cp <= U'Z') || (cp >= U'a' && cp <= U'z') ||
                            (cp >= 0xC0u && cp <= 0x24Fu && cp != 0xD7u && cp != 0xF7u);
        if (!letter && (chars == 0 || cp != U' ')) {
            return false;
        }
        last_space = cp == U' ';
        if (++chars > kCompanionNameMaxChars) {
            return false;
        }
    }
    return chars >= 2 && !last_space;
}

std::string query_companion_name(std::string_view target) {
    const std::optional<std::string_view> raw = query_value(target, "begleiter");
    // 96 = 16 Zeichen zu je zwei Byte, jedes als %XX: Längeres kann kein gültiger Name sein – nicht erst dekodieren.
    if (!raw || raw->size() > kCompanionNameMaxChars * 6) {
        return std::string(kDefaultCompanionName);
    }
    const std::optional<std::string> name = form_decode(*raw);
    return name && valid_companion_name(*name) ? *name : std::string(kDefaultCompanionName);
}

bool own_player_in_game(std::string_view configured, const std::vector<std::string>& in_game) {
    for (const std::string& n : in_game) {
        if (speak_for(configured, n)) {
            return true;
        }
    }
    return false;
}

bool valid_player_name(std::string_view name) {
    if (name.size() < 3 || name.size() > 16) {
        return false;
    }
    for (const char c : name) {
        const bool ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
        if (!ok) {
            return false;
        }
    }
    return true;
}

bool speak_for(std::string_view configured, std::string_view player) {
    if (configured.empty() || configured.size() != player.size()) {
        return false;
    }
    for (std::size_t i = 0; i < configured.size(); ++i) {
        const auto lower = [](char c) { return c >= 'A' && c <= 'Z' ? static_cast<char>(c - 'A' + 'a') : c; };
        if (lower(configured[i]) != lower(player[i])) {
            return false;
        }
    }
    return true;
}

bool may_hear(std::string_view configured, std::string_view player, bool hear_others) {
    if (!valid_player_name(player)) {
        return false;
    }
    return speak_for(configured, player) || hear_others;
}

ApiResponse handle_companion(const HttpRequest& request, HeardQueue& heard, const std::function<bool()>& gone,
                             const CompanionHandlers& handlers, std::chrono::milliseconds hold) {
    const std::string_view p = path_of(request.target);
    if (p == kCompanionHearPath) {
        if (request.method != "POST") {
            ApiResponse r = reply(405, R"({"error":"nur_post"})");
            r.extra_headers.emplace_back("Allow: POST");
            return r;
        }
        const std::string player = query_player(request.target);
        if (player.empty()) {
            return bad("spieler_ungueltig");  // umlaut-ok: snake_case-Code ASCII
        }
        // Gerade Byteanzahl, mindestens 0,2 s, höchstens kMaxAudioBodyBytes (der Server hat schon begrenzt).
        if (request.body.size() % 2 != 0 || request.body.size() < 6400 || request.body.size() > kMaxAudioBodyBytes) {
            return bad("ton_ungueltig");  // umlaut-ok: snake_case-Code ASCII
        }
        std::vector<float> pcm(request.body.size() / 2);
        for (std::size_t i = 0; i < pcm.size(); ++i) {
            const auto lo = static_cast<unsigned char>(request.body[2 * i]);
            const auto hi = static_cast<unsigned char>(request.body[2 * i + 1]);
            pcm[i] = static_cast<float>(static_cast<std::int16_t>(lo | (hi << 8))) / 32768.0f;
        }
        if (!handlers.hear) {
            return reply(503, R"({"error":"erkennung_aus"})");
        }
        std::string error;
        const std::optional<std::string> text = handlers.hear(player, std::move(pcm), error);
        if (!text) {
            return reply(503, Json{{"error", "erkennung_aus"}, {"grund", error}}.dump());
        }
        return reply(200, Json{{"text", *text}}.dump());
    }
    if (p == kCompanionVoicePath) {
        if (request.method != "POST") {
            ApiResponse r = reply(405, R"({"error":"nur_post"})");
            r.extra_headers.emplace_back("Allow: POST");
            return r;
        }
        const std::optional<Json> body = try_parse_utf8(request.body);
        const Json* text = body && body->is_object() ? json_find(*body, "text") : nullptr;
        if (text == nullptr || !text->is_string() || text->get_ref<const std::string&>().empty() ||
            text->get_ref<const std::string&>().size() > kCompanionTextMax) {
            return bad("text_ungueltig");  // umlaut-ok: snake_case-Code ASCII
        }
        if (!handlers.voice) {
            return reply(503, R"({"error":"stimme_aus"})");
        }
        std::string error;
        const auto pcm = handlers.voice(text->get<std::string>(), error);
        if (!pcm) {
            return reply(503, Json{{"error", "stimme_aus"}, {"grund", error}}.dump());
        }
        ApiResponse r;
        r.status = 200;
        r.content_type = "application/octet-stream";
        r.body.resize(pcm->size() * 2);
        for (std::size_t i = 0; i < pcm->size(); ++i) {
            const auto u = static_cast<std::uint16_t>((*pcm)[i]);
            r.body[2 * i] = static_cast<char>(u & 0xFF);
            r.body[2 * i + 1] = static_cast<char>(u >> 8);
        }
        return r;
    }
    if (p == kCompanionHeardPath) {
        if (request.method != "GET") {
            ApiResponse r = reply(405, R"({"error":"nur_get"})");
            r.extra_headers.emplace_back("Allow: GET");
            return r;
        }
        if (handlers.in_game) {
            handlers.in_game(query_in_game(request.target));
        }
        if (handlers.companion_name) {
            handlers.companion_name(query_companion_name(request.target));
        }
        Json list = Json::array();
        for (Spoken& s : heard.take(hold, gone)) {
            list.push_back(Json{{"spieler", std::move(s.player)}, {"text", std::move(s.text)}});
        }
        return reply(200, Json{{"saetze", std::move(list)}}.dump());  // umlaut-ok: JSON-Schlüssel ASCII
    }
    if (request.method != "POST") {
        ApiResponse r = reply(405, R"({"error":"nur_post"})");
        r.extra_headers.emplace_back("Allow: POST");
        return r;
    }
    const std::optional<Json> body = try_parse_utf8(request.body);
    if (!body || !body->is_object()) {
        return bad("body_kein_json");
    }
    const Json* player = json_find(*body, "spieler");
    const Json* text = json_find(*body, "text");
    if (player == nullptr || !player->is_string() || !valid_player_name(player->get_ref<const std::string&>())) {
        return bad("spieler_ungueltig");  // umlaut-ok: snake_case-Code ASCII
    }
    if (text == nullptr || !text->is_string() || text->get_ref<const std::string&>().empty() ||
        text->get_ref<const std::string&>().size() > kCompanionTextMax) {
        return bad("text_ungueltig");  // umlaut-ok: snake_case-Code ASCII
    }
    if (handlers.say) {
        handlers.say(Spoken{player->get<std::string>(), text->get<std::string>()});
    }
    return reply(200, R"({"ok":true})");
}

}  // namespace swbh::server
