// Chat-Schnittstelle: Anfragekörper, Antwortformat wie llama-server (das liest LlmClient.java), 503 bei Belegung.

#include "test_support.hpp"

#include "fake_backend.hpp"

#include "base/json.hpp"
#include "plan/bauplan.hpp"
#include "server/chat_api.hpp"

#include <thread>

using swbh::Json;
using swbh::server::ChatApi;
using swbh::server::HttpRequest;
using swbh::server::order_text;

namespace {

HttpRequest chat_request(const std::string& body) {
    HttpRequest r;
    r.method = "POST";
    r.target = "/v1/chat/completions";
    r.version = "HTTP/1.1";
    r.headers = {{"host", "127.0.0.1:18080"}, {"content-type", "application/json"}};
    r.body = body;
    return r;
}

// Genau der Körper, den LlmClient.complete() baut (Feldnamen und Struktur von dort übernommen).
std::string plugin_body() {
    return R"({"model":"lokal","messages":[{"role":"system","content":"Du bist Baumeister"},)"
           R"({"role":"user","content":"Bauauftrag: ein kleines Haus"}],"temperature":0.3,"max_tokens":3000,)"
           R"("stream":false,"response_format":{"type":"json_schema","json_schema":{"name":"bauplan","strict":true,)"
           R"("schema":{"type":"object"}}}})";
}

}  // namespace

TEST_CASE("Chat: Anfrage des Plugins → Bauplan-Grammatik, Antwort mit den Feldern, die LlmClient.java liest") {
    swbh::test::FakeBackend backend;
    ChatApi api(backend, nullptr);
    const auto resp = api.handle(chat_request(plugin_body()), {});
    REQUIRE(resp.status == 200);
    CHECK(backend.last.grammar == std::string(swbh::plan::bauplan_grammar()));
    CHECK(backend.last.max_tokens == 3000);
    CHECK(backend.last.temperature == doctest::Approx(0.3));
    REQUIRE(backend.last.messages.size() == 2);
    CHECK(backend.last.messages[1].content == "Bauauftrag: ein kleines Haus");

    const Json j = swbh::parse_utf8(resp.body);
    CHECK(j["choices"][0]["message"]["content"] == backend.text);
    CHECK(j["choices"][0]["message"]["role"] == "assistant");
    CHECK(j["choices"][0]["finish_reason"] == "stop");
    CHECK(j["timings"]["predicted_n"] == 40);
    CHECK(j["timings"]["predicted_per_second"].get<double>() == doctest::Approx(20.0));  // 40 Token in 2,0 s
    CHECK(j["timings"]["cache_n"] == 60);
    CHECK(j["usage"]["prompt_tokens"] == 100);
    CHECK(j["object"] == "chat.completion");
}

TEST_CASE("Chat: Reparaturrunde mit assistant-Nachricht wird angenommen") {
    swbh::test::FakeBackend backend;
    ChatApi api(backend, nullptr);
    const auto resp = api.handle(
        chat_request(R"({"messages":[{"role":"system","content":"s"},{"role":"user","content":"u"},)"
                     R"({"role":"assistant","content":"{}"},{"role":"user","content":"The plan is invalid: x"}]})"),
        {});
    CHECK(resp.status == 200);
    CHECK(backend.last.messages.size() == 4);
    CHECK(backend.last.grammar.empty());  // ohne response_format frei
}

TEST_CASE("Chat: ungültige Körper geben 400 mit Code") {
    swbh::test::FakeBackend backend;
    ChatApi api(backend, nullptr);
    const auto code = [&](const std::string& body) {
        const auto r = api.handle(chat_request(body), {});
        return r.status == 400 ? swbh::parse_utf8(r.body)["error"]["message"].get<std::string>() : std::string("kein 400");
    };
    CHECK(code("kein json") == "body_kein_json");
    CHECK(code(R"([1])") == "body_kein_objekt");
    CHECK(code(R"({"messages":[]})") == "messages_falsch");
    CHECK(code(R"({"messages":[{"role":"tool","content":"x"}]})") == "rolle_unbekannt");
    CHECK(code(R"({"messages":[{"role":"user","content":[{"type":"text"}]}]})") == "message_falsch");
    CHECK(code(R"({"messages":[{"role":"user","content":"x"}],"max_tokens":9000})") == "max_tokens_falsch");
    CHECK(code(R"({"messages":[{"role":"user","content":"x"}],"temperature":3})") == "temperature_falsch");
    CHECK(code(R"({"messages":[{"role":"user","content":"x"}],"stream":true})") == "stream_abgelehnt");
    CHECK(code(R"({"messages":[{"role":"user","content":"x"}],"response_format":{"type":"json_object"}})") ==
          "response_format_abgelehnt");
}

TEST_CASE("Chat: /health meldet 200 nur mit geladenem Modell; falsche Wege 404/405") {
    swbh::test::FakeBackend backend;
    ChatApi api(backend, nullptr);
    HttpRequest health;
    health.method = "GET";
    health.target = "/health";
    CHECK(api.handle(health, {}).status == 200);
    CHECK(api.handle(health, {}).body == R"({"status":"ok"})");
    backend.ready_ = false;
    CHECK(api.handle(health, {}).status == 503);
    CHECK(api.handle(chat_request(plugin_body()), {}).status == 503);
    health.target = "/v1/models";
    CHECK(api.handle(health, {}).status == 404);
    health.target = "/v1/chat/completions";
    CHECK(api.handle(health, {}).status == 405);
}

TEST_CASE("Chat: ein zweiter gleichzeitiger Auftrag bekommt 503 mit Retry-After") {
    swbh::test::FakeBackend backend;
    backend.block = true;
    ChatApi api(backend, nullptr);
    std::thread first([&] { api.handle(chat_request(plugin_body()), {}); });
    while (!backend.entered) {
        std::this_thread::yield();
    }
    const auto second = api.handle(chat_request(plugin_body()), {});
    CHECK(second.status == 503);
    CHECK(second.extra_headers == std::vector<std::string>{"Retry-After: 30"});
    backend.release = true;
    first.join();
    CHECK_FALSE(api.busy());
    backend.block = false;
    CHECK(api.handle(chat_request(plugin_body()), {}).status == 200);
}

TEST_CASE("Chat: geht die Gegenseite weg, wird die Erzeugung abgebrochen") {
    swbh::test::FakeBackend backend;
    backend.block = true;
    ChatApi api(backend, nullptr);
    const auto resp = api.handle(chat_request(plugin_body()), [] { return true; });
    CHECK(backend.cancelled);
    CHECK(resp.status == 500);
}

namespace {

// Merkt sich, was request_started meldet.
class RecordingObserver final : public swbh::server::ApiObserver {
public:
    swbh::server::RequestInfo started;
    void request_started(const swbh::server::RequestInfo& info) override { started = info; }
    void token(std::string_view) override {}
    void request_finished(const swbh::server::FinishInfo&) override {}
};

}  // namespace

TEST_CASE("Chat: Reparaturrunde meldet den ursprünglichen Auftrag, nicht den Reparaturtext") {
    swbh::test::FakeBackend backend;
    RecordingObserver observer;
    ChatApi api(backend, &observer);
    const auto resp = api.handle(
        chat_request(R"({"messages":[{"role":"system","content":"s"},)"
                     R"({"role":"user","content":"Bauauftrag: ein Turm aus Stein\n\nEXISTING SURROUNDINGS: none"},)"
                     R"({"role":"assistant","content":"{}"},)"
                     R"({"role":"user","content":"The plan is invalid: Step 3 puts a second roof on the build. )"
                     R"(Return the complete corrected plan as JSON."}]})"),
        {});
    CHECK(resp.status == 200);
    CHECK(observer.started.order == "ein Turm aus Stein");
    CHECK(observer.started.summary.starts_with("The plan is invalid"));
}

TEST_CASE("Chat: Auftragstext bis zum Zeilenumbruch, doppelte Leerzeichen im Auftrag bleiben") {
    CHECK(order_text({{"system", "Bauauftrag: nicht hier"}, {"user", "Bauauftrag: ein  Haus mit Turm\n\nEXISTING"}}) ==
          "ein  Haus mit Turm");
    CHECK(order_text({{"user", "  Bauauftrag:   kleines Haus  \r\n\nmehr"}}) == "kleines Haus");
    CHECK(order_text({{"user", "Wie spät ist es?"}}).empty());
    CHECK(order_text({}).empty());
}
