#include "server/chat_api.hpp"

#include "base/log.hpp"
#include "plan/bauplan.hpp"

#include <algorithm>
#include <chrono>
#include <ctime>
#include <exception>

namespace swbh::server {

namespace {

ApiResponse json_response(int status, std::string body, std::vector<std::string> extra = {}) {
    ApiResponse r;
    r.status = status;
    r.body = std::move(body);
    r.extra_headers = std::move(extra);
    return r;
}

ApiResponse error_response(int status, std::string_view message, std::vector<std::string> extra = {}) {
    return json_response(status, error_json(status, message), std::move(extra));
}

std::string_view error_type(int status) {
    switch (status) {
    case 400: return "invalid_request_error";
    case 404: return "not_found_error";
    case 503: return "unavailable_error";
    default: return "server_error";
    }
}

}  // namespace

std::string order_text(const std::vector<llm::ChatMessage>& messages) {
    constexpr std::string_view kPrefix = "Bauauftrag:";
    constexpr std::string_view kSpace = " \t\r\n";
    for (const llm::ChatMessage& m : messages) {
        if (m.role != "user") {
            continue;
        }
        std::string_view s = m.content;
        s.remove_prefix(std::min(s.size(), s.find_first_not_of(kSpace)));
        if (!s.starts_with(kPrefix)) {
            continue;
        }
        s.remove_prefix(kPrefix.size());
        return order_key(s);
    }
    return {};
}

std::string order_key(std::string_view raw) {
    constexpr std::string_view kSpace = " \t\r\n";
    std::string_view s = raw;
    // Erst am Zeilenumbruch schneiden, dann trimmen – dieselbe Reihenfolge wie bisher in order_text, sonst wiche
    // „Bauauftrag:\nText“ ab.
    if (const std::size_t cut = s.find('\n'); cut != std::string_view::npos) {
        s = s.substr(0, cut);
    }
    s.remove_prefix(std::min(s.size(), s.find_first_not_of(kSpace)));
    if (const std::size_t last = s.find_last_not_of(kSpace); last != std::string_view::npos) {
        s = s.substr(0, last + 1);
    }
    return log::safe(s, 160);
}

std::string error_json(int status, std::string_view message) {
    Json j;
    j["error"]["code"] = status;
    j["error"]["message"] = std::string(message);
    j["error"]["type"] = std::string(error_type(status));
    return dump_compact(j);
}

std::optional<llm::CompletionRequest> parse_chat_body(const Json& body, std::string& error_code) {
    if (!body.is_object()) {
        error_code = "body_kein_objekt";
        return std::nullopt;
    }
    llm::CompletionRequest req;
    req.max_tokens = kDefaultMaxTokens;
    const Json* messages = json_find(body, "messages");
    if (messages == nullptr || !messages->is_array() || messages->empty() ||
        messages->size() > static_cast<std::size_t>(kMaxMessages)) {
        error_code = "messages_falsch";
        return std::nullopt;
    }
    for (const Json& m : *messages) {
        const Json* role = m.is_object() ? json_find(m, "role") : nullptr;
        const Json* content = m.is_object() ? json_find(m, "content") : nullptr;
        if (role == nullptr || !role->is_string() || content == nullptr || !content->is_string()) {
            error_code = "message_falsch";
            return std::nullopt;
        }
        const std::string r = role->get<std::string>();
        if (r != "system" && r != "user" && r != "assistant") {
            error_code = "rolle_unbekannt";
            return std::nullopt;
        }
        req.messages.push_back({r, content->get<std::string>()});
    }
    if (const Json* mt = json_find(body, "max_tokens"); mt != nullptr && !mt->is_null()) {
        if (!mt->is_number_integer() || mt->get<long long>() < 1 || mt->get<long long>() > kMaxTokensLimit) {
            error_code = "max_tokens_falsch";
            return std::nullopt;
        }
        req.max_tokens = static_cast<int>(mt->get<long long>());
    }
    if (const Json* t = json_find(body, "temperature"); t != nullptr && !t->is_null()) {
        if (!t->is_number() || t->get<double>() < 0.0 || t->get<double>() > 2.0) {
            error_code = "temperature_falsch";
            return std::nullopt;
        }
        req.temperature = static_cast<float>(t->get<double>());
    }
    if (const Json* s = json_find(body, "stream"); s != nullptr && !s->is_null()) {
        // Streaming (SSE) liest das Plugin nicht; lieber deutlich ablehnen als eine halbe Umsetzung anbieten.
        if (!s->is_boolean() || s->get<bool>()) {
            error_code = "stream_abgelehnt";
            return std::nullopt;
        }
    }
    return req;
}

std::string chat_response_json(const llm::CompletionResult& result, const std::string& model, const std::string& id,
                               long long created) {
    const double predicted_ms = (result.seconds - result.prompt_seconds) * 1000.0;
    const double prompt_ms = result.prompt_seconds * 1000.0;
    const int prompt_n = result.prompt_tokens - result.reused_tokens;
    Json j;
    Json choice;
    choice["finish_reason"] = result.hit_limit ? "length" : "stop";
    choice["index"] = 0;
    choice["message"]["role"] = "assistant";
    choice["message"]["content"] = result.text;
    j["choices"] = Json::array({choice});
    j["created"] = created;
    j["model"] = model;
    j["object"] = "chat.completion";
    j["usage"]["completion_tokens"] = result.generated_tokens;
    j["usage"]["prompt_tokens"] = result.prompt_tokens;
    j["usage"]["total_tokens"] = result.prompt_tokens + result.generated_tokens;
    j["id"] = id;
    // Feldnamen wie llama-server (tools/server: timings): cache_n = aus dem KV-Speicher übernommen.
    j["timings"]["cache_n"] = result.reused_tokens;
    j["timings"]["prompt_n"] = prompt_n;
    j["timings"]["prompt_ms"] = prompt_ms;
    j["timings"]["prompt_per_second"] = prompt_ms > 0.0 ? prompt_n * 1000.0 / prompt_ms : 0.0;
    j["timings"]["predicted_n"] = result.generated_tokens;
    j["timings"]["predicted_ms"] = predicted_ms;
    j["timings"]["predicted_per_second"] = predicted_ms > 0.0 ? result.generated_tokens * 1000.0 / predicted_ms : 0.0;
    return dump_compact(j);
}

ChatApi::ChatApi(CompletionBackend& backend, ApiObserver* observer) : backend_(backend), observer_(observer) {}

ApiResponse ChatApi::handle(const HttpRequest& request, const std::function<bool()>& client_gone) {
    const std::string path = request.target.substr(0, request.target.find('?'));
    if (path == "/health") {
        if (request.method != "GET") {
            return error_response(405, "Nur GET", {"Allow: GET"});
        }
        if (!backend_.ready()) {
            // Wie llama-server während des Ladens: 503. Das Plugin meldet dann „nicht erreichbar“ – richtig, denn
            // ein Auftrag würde ebenfalls scheitern.
            return error_response(503, "Loading model");
        }
        return json_response(200, R"({"status":"ok"})");
    }
    if (path == "/v1/chat/completions") {
        if (request.method != "POST") {
            return error_response(405, "Nur POST", {"Allow: POST"});
        }
        return chat(request, client_gone);
    }
    return error_response(404, "File Not Found");
}

ApiResponse ChatApi::chat(const HttpRequest& request, const std::function<bool()>& client_gone) {
    const auto body = try_parse_utf8(request.body);
    if (!body) {
        return error_response(400, "body_kein_json");
    }
    std::string code;
    std::optional<llm::CompletionRequest> req = parse_chat_body(*body, code);
    if (!req) {
        return error_response(400, code);
    }
    const std::optional<plan::GrammarChoice> grammar = plan::choose_grammar(*body, code);
    if (!grammar) {
        return error_response(400, code);
    }
    req->grammar = grammar->grammar;
    if (!backend_.ready()) {
        return error_response(503, "Modell nicht geladen");
    }
    if (busy_.exchange(true)) {
        return error_response(503, "Es läuft schon ein Auftrag", {"Retry-After: 30"});
    }
    struct BusyReset {
        std::atomic<bool>& flag;
        ~BusyReset() { flag = false; }
    } reset{busy_};

    RequestInfo info;
    for (auto it = req->messages.rbegin(); it != req->messages.rend(); ++it) {
        if (it->role == "user") {
            info.summary = log::safe(it->content, 160);
            break;
        }
    }
    info.order = order_text(req->messages);
    info.messages = static_cast<int>(req->messages.size());
    info.max_tokens = req->max_tokens;
    info.temperature = req->temperature;
    info.grammar = grammar->label;
    if (observer_ != nullptr) {
        observer_->request_started(info);
    }

    // Abbruch, wenn die Gegenseite weg ist: höchstens alle 500 ms am Socket nachsehen (Kosten je Token sonst unnötig).
    auto last_check = std::chrono::steady_clock::now();
    bool gone = false;
    req->on_text = [&](std::string_view piece) {
        if (observer_ != nullptr) {
            observer_->token(piece);
        }
        const auto now = std::chrono::steady_clock::now();
        if (!gone && client_gone && now - last_check >= std::chrono::milliseconds(500)) {
            last_check = now;
            if (client_gone()) {
                gone = true;
                backend_.cancel();
            }
        }
    };

    FinishInfo finish;
    std::optional<llm::CompletionResult> result;
    try {
        result = backend_.complete(*req, finish.error);
    } catch (const std::exception& e) {
        finish.error = std::string("Ausnahme: ") + e.what();
    }
    if (gone && finish.error.empty()) {
        finish.error = "Gegenseite hat die Verbindung geschlossen";
    } else if (gone) {
        finish.error += " (Gegenseite hat die Verbindung geschlossen)";
    }
    finish.ok = result.has_value();
    if (result) {
        finish.result = *result;
    }
    if (observer_ != nullptr) {
        observer_->request_finished(finish);
    }
    if (!result) {
        return error_response(finish.error.starts_with(llm::kPromptTooLong) ? 400 : 500, finish.error);
    }
    static std::atomic<unsigned> counter{0};
    const long long created = static_cast<long long>(std::time(nullptr));
    const std::string id = "chatcmpl-swbh-" + std::to_string(created) + "-" + std::to_string(++counter);
    return json_response(200, chat_response_json(*result, backend_.model_name(), id, created));
}

}  // namespace swbh::server
