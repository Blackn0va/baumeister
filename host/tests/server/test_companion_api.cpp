// Begleiter-Endpunkte /baumeister/v1/begleiter/sagen und /gehoert: Gegenstück zu CompanionVoiceLink.java.  umlaut-ok

#include "test_support.hpp"

#include "base/json.hpp"
#include "server/companion_api.hpp"

#include <chrono>
#include <optional>
#include <string>
#include <cstdint>
#include <thread>
#include <vector>

using namespace swbh;
using namespace swbh::server;
using namespace std::chrono_literals;

namespace {

HttpRequest req(std::string method, std::string_view path, std::string body = {}) {
    HttpRequest r;
    r.method = std::move(method);
    r.target = std::string(path);
    r.version = "HTTP/1.1";
    r.body = std::move(body);
    return r;
}

CompanionHandlers saying(std::optional<Spoken>& seen) {
    CompanionHandlers h;
    h.say = [&seen](const Spoken& s) { seen = s; };
    return h;
}

ApiResponse say(HeardQueue& q, const std::string& body, std::optional<Spoken>& seen) {
    return handle_companion(req("POST", kCompanionSayPath, body), q, {}, saying(seen), 10ms);
}

// 16-kHz-PCM als Körper: n Samples mit Wert v.
std::string pcm_body(std::size_t n, std::int16_t v) {
    std::string b(n * 2, '\0');
    for (std::size_t i = 0; i < n; ++i) {
        b[2 * i] = static_cast<char>(static_cast<std::uint16_t>(v) & 0xFF);
        b[2 * i + 1] = static_cast<char>(static_cast<std::uint16_t>(v) >> 8);
    }
    return b;
}

}  // namespace

TEST_CASE("Begleiter: Satz zum Vorlesen kommt an, wie CompanionVoiceLink ihn schickt") {
    HeardQueue q;
    std::optional<Spoken> seen;
    const ApiResponse r = say(q, R"({"spieler":"Blackkn0va","text":"Hier sind sechzehn Fackeln für dich!"})", seen);
    CHECK(r.status == 200);
    REQUIRE(seen.has_value());
    CHECK(seen->player == "Blackkn0va");
    CHECK(seen->text == "Hier sind sechzehn Fackeln für dich!");
}

TEST_CASE("Begleiter: ungültiger Spieler, leerer oder zu langer Text und falsche Methode werden abgelehnt") {
    HeardQueue q;
    std::optional<Spoken> seen;
    CHECK(say(q, R"({"spieler":"ab","text":"x"})", seen).status == 400);                 // zu kurz
    CHECK(say(q, R"({"spieler":"Name mit Leer","text":"x"})", seen).status == 400);
    CHECK(say(q, R"({"spieler":"Ünlaut","text":"x"})", seen).status == 400);
    CHECK(say(q, R"({"spieler":"Blackkn0va","text":""})", seen).status == 400);
    CHECK(say(q, R"({"spieler":"Blackkn0va","text":")" + std::string(401, 'a') + R"("})", seen).status == 400);
    CHECK(say(q, R"({"spieler":"Blackkn0va","text":)" + std::string("\"") + std::string(400, 'a') + R"("})", seen)
              .status == 200);
    seen.reset();
    CHECK(say(q, R"(["spieler"])", seen).status == 400);
    CHECK_FALSE(seen.has_value());
    CHECK(handle_companion(req("GET", kCompanionSayPath), q, {}, CompanionHandlers{}, 10ms).status == 405);
    CHECK(handle_companion(req("POST", kCompanionHeardPath, "{}"), q, {}, CompanionHandlers{}, 10ms).status == 405);
}

TEST_CASE("Begleiter: Long-Poll liefert wartende Sätze sofort und leer nach Ablauf") {
    HeardQueue q;
    const auto get = [&](std::chrono::milliseconds hold) {
        return handle_companion(req("GET", kCompanionHeardPath), q, {}, CompanionHandlers{}, hold);
    };
    const auto t0 = std::chrono::steady_clock::now();
    ApiResponse r = get(50ms);
    CHECK(r.status == 200);
    CHECK(parse_utf8(r.body)["saetze"].empty());  // umlaut-ok: JSON-Schlüssel ASCII
    CHECK(std::chrono::steady_clock::now() - t0 >= 50ms);
    q.push({"Blackkn0va", "Hey Vera, wie spät ist es?"});
    r = get(5000ms);
    const Json list = parse_utf8(r.body)["saetze"];  // umlaut-ok: JSON-Schlüssel ASCII
    REQUIRE(list.size() == 1);
    CHECK(list[0]["spieler"] == "Blackkn0va");
    CHECK(list[0]["text"] == "Hey Vera, wie spät ist es?");
}

TEST_CASE("Begleiter: ein Satz während des Wartens weckt den Long-Poll sofort") {
    HeardQueue q;
    std::thread speaker([&] {
        std::this_thread::sleep_for(100ms);
        q.push({"Blackkn0va", "komm"});
    });
    const auto t0 = std::chrono::steady_clock::now();
    const std::vector<Spoken> got = q.take(10'000ms, {});
    speaker.join();
    REQUIRE(got.size() == 1);
    CHECK(std::chrono::steady_clock::now() - t0 < 2000ms);
}

TEST_CASE("Begleiter: geschlossenes Plugin beendet das Warten, die Schlange ist begrenzt") {
    HeardQueue q;
    const auto t0 = std::chrono::steady_clock::now();
    CHECK(q.take(10'000ms, [] { return true; }).empty());
    CHECK(std::chrono::steady_clock::now() - t0 < 2000ms);
    for (int i = 0; i < 20; ++i) {
        q.push({"Blackkn0va", "Satz " + std::to_string(i)});
    }
    const std::vector<Spoken> got = q.take(10ms, {});
    REQUIRE(got.size() == kHeardMax);
    CHECK(got.front().text == "Satz 4");  // die ältesten vier fielen weg
    CHECK(q.dropped() == 4);
}

TEST_CASE("Begleiter: vorgelesen wird nur für den eingetragenen Spieler, leer heißt niemand") {
    CHECK(speak_for("Blackkn0va", "Blackkn0va"));
    CHECK(speak_for("blackkn0va", "BlackKn0va"));
    CHECK_FALSE(speak_for("Blackkn0va", "Steve"));
    CHECK_FALSE(speak_for("Blackkn0va", "Steve_1234"));  // gleiche Länge, anderer Name
    CHECK_FALSE(speak_for("Blackkn0va", "Blackkn0vb"));
    CHECK_FALSE(speak_for("", "Blackkn0va"));
    CHECK_FALSE(speak_for("Black", "Blackkn0va"));
}

TEST_CASE("Begleiter: Spricht der eigene Spieler im Voice-Chat, meldet das Abholen es der App") {
    CHECK(query_in_game("/baumeister/v1/begleiter/gehoert?imspiel=Blackkn0va,Steve") ==
          std::vector<std::string>{"Blackkn0va", "Steve"});
    CHECK(query_in_game("/baumeister/v1/begleiter/gehoert").empty());
    CHECK(query_in_game("/x?a=1&imspiel=a%20b,ok_name") == std::vector<std::string>{"ok_name"});  // ungültig fällt weg
    CHECK(own_player_in_game("blackkn0va", {"Steve", "Blackkn0va"}));
    CHECK_FALSE(own_player_in_game("Blackkn0va", {"Steve"}));
    CHECK_FALSE(own_player_in_game("", {"Steve"}));

    HeardQueue q;
    std::vector<std::string> seen;
    CompanionHandlers h;
    h.in_game = [&](const std::vector<std::string>& names) { seen = names; };
    const ApiResponse r =
        handle_companion(req("GET", "/baumeister/v1/begleiter/gehoert?imspiel=Blackkn0va"), q, {}, h, 10ms);
    CHECK(r.status == 200);
    CHECK(seen == std::vector<std::string>{"Blackkn0va"});
}

TEST_CASE("Begleiter: Erkannt wird der eigene Spieler immer, Mitspieler nur mit dem Schalter") {
    CHECK(may_hear("Blackkn0va", "blackkn0va", false));
    CHECK_FALSE(may_hear("Blackkn0va", "Steve", false));
    CHECK(may_hear("Blackkn0va", "Steve", true));
    CHECK(may_hear("", "Steve", true));
    CHECK_FALSE(may_hear("", "Steve", false));
    CHECK_FALSE(may_hear("Blackkn0va", "a b", true));  // ungültiger Name nie
    CHECK_FALSE(may_hear("Blackkn0va", "", true));
}

TEST_CASE("Begleiter: Ton aus dem Spiel kommt als 16-kHz-Samples bei der Erkennung an, Text geht zurück") {
    HeardQueue q;
    CompanionHandlers h;
    std::string seen_player;
    std::size_t seen_samples = 0;
    float first = 0;
    h.hear = [&](const std::string& player, std::vector<float> pcm, std::string&) -> std::optional<std::string> {
        seen_player = player;
        seen_samples = pcm.size();
        first = pcm.front();
        return std::string("Hey Vera, komm");
    };
    const ApiResponse r = handle_companion(
        req("POST", std::string(kCompanionHearPath) + "?spieler=Blackkn0va", pcm_body(16000, -16384)), q, {}, h, 10ms);
    CHECK(r.status == 200);
    CHECK(parse_utf8(r.body)["text"] == "Hey Vera, komm");
    CHECK(seen_player == "Blackkn0va");
    CHECK(seen_samples == 16000);
    CHECK(first == doctest::Approx(-0.5f));
}

TEST_CASE("Begleiter: Ton ohne gültigen Spieler, zu kurz, ungerade oder ohne Erkennung wird abgelehnt") {
    HeardQueue q;
    CompanionHandlers h;
    h.hear = [](const std::string&, std::vector<float>, std::string&) -> std::optional<std::string> { return ""; };
    const std::string path(kCompanionHearPath);
    CHECK(handle_companion(req("POST", path, pcm_body(16000, 1)), q, {}, h, 10ms).status == 400);   // kein Spieler
    CHECK(handle_companion(req("POST", path + "?spieler=a b", pcm_body(16000, 1)), q, {}, h, 10ms).status == 400);
    CHECK(handle_companion(req("POST", path + "?spieler=Steve", pcm_body(100, 1)), q, {}, h, 10ms).status == 400);
    CHECK(handle_companion(req("POST", path + "?spieler=Steve", pcm_body(16000, 1) + "x"), q, {}, h, 10ms).status ==
          400);
    CHECK(handle_companion(req("GET", path + "?spieler=Steve"), q, {}, h, 10ms).status == 405);
    CHECK(handle_companion(req("POST", path + "?spieler=Steve", pcm_body(16000, 1)), q, {}, CompanionHandlers{}, 10ms)
              .status == 503);
    h.hear = [](const std::string&, std::vector<float>, std::string& e) -> std::optional<std::string> {
        e = "aus";
        return std::nullopt;
    };
    CHECK(handle_companion(req("POST", path + "?spieler=Steve", pcm_body(16000, 1)), q, {}, h, 10ms).status == 503);
}

TEST_CASE("Begleiter: Stimme fürs Spiel kommt als 48-kHz-PCM little-endian zurück") {
    HeardQueue q;
    CompanionHandlers h;
    std::string seen;
    h.voice = [&](const std::string& text, std::string&) -> std::optional<std::vector<std::int16_t>> {
        seen = text;
        return std::vector<std::int16_t>{1, -2, 0x1234};
    };
    const ApiResponse r =
        handle_companion(req("POST", kCompanionVoicePath, R"({"spieler":"Blackkn0va","text":"Hallo!"})"), q, {}, h, 10ms);
    CHECK(r.status == 200);
    CHECK(r.content_type == "application/octet-stream");
    CHECK(seen == "Hallo!");
    REQUIRE(r.body.size() == 6);
    CHECK(static_cast<unsigned char>(r.body[0]) == 0x01);
    CHECK(static_cast<unsigned char>(r.body[2]) == 0xFE);
    CHECK(static_cast<unsigned char>(r.body[3]) == 0xFF);
    CHECK(static_cast<unsigned char>(r.body[4]) == 0x34);
    CHECK(static_cast<unsigned char>(r.body[5]) == 0x12);
    CHECK(handle_companion(req("POST", kCompanionVoicePath, R"({"text":""})"), q, {}, h, 10ms).status == 400);
    CHECK(handle_companion(req("POST", kCompanionVoicePath, R"({"text":"x"})"), q, {}, CompanionHandlers{}, 10ms)
              .status == 503);
}

TEST_CASE("Begleiter: nur /hoeren darf bis 1 MiB groß sein, alle anderen Pfade bleiben bei 256 KiB") {
    const auto head = [](std::string_view path, std::size_t n) {
        return "POST " + std::string(path) + " HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: " + std::to_string(n) +
               "\r\n\r\n";
    };
    const std::size_t big = 600 * 1024;
    const std::string body(big, 'a');
    CHECK(parse_request(head(std::string(kCompanionHearPath) + "?spieler=Steve", big) + body).status ==
          ParseStatus::Complete);
    const ParseResult other = parse_request(head(kCompanionSayPath, big) + body);
    CHECK(other.status == ParseStatus::Error);
    CHECK(other.http_status == 413);
    const ParseResult too_big = parse_request(head(kCompanionHearPath, kMaxAudioBodyBytes + 1));
    CHECK(too_big.http_status == 413);
}

TEST_CASE("Begleiter: Spielername aus der Anfrage") {
    CHECK(query_player("/x?spieler=Blackkn0va") == "Blackkn0va");
    CHECK(query_player("/x?a=1&spieler=Steve_1") == "Steve_1");
    CHECK(query_player("/x?spieler=a%20b").empty());
    CHECK(query_player("/x").empty());
}

TEST_CASE("Begleiter: Pfaderkennung und Minecraft-Namen") {
    CHECK(is_companion_path("/baumeister/v1/begleiter/sagen"));
    CHECK(is_companion_path("/baumeister/v1/begleiter/gehoert?x=1"));  // umlaut-ok
    CHECK_FALSE(is_companion_path("/baumeister/v1/begleiter"));
    CHECK(valid_player_name("Blackkn0va"));
    CHECK(valid_player_name("a_b"));
    CHECK_FALSE(valid_player_name("a"));
    CHECK_FALSE(valid_player_name("seventeen_chars_x"));
}
