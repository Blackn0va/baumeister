// HTTP-Parser und Abwehrregeln (Host/Origin/Content-Type) ohne Sockets.

#include "test_support.hpp"

#include "server/http.hpp"

#include <string>

using swbh::server::check_request;
using swbh::server::host_is_loopback;
using swbh::server::parse_request;
using swbh::server::ParseStatus;

namespace {

std::string post(const std::string& body, const std::string& extra = "",
                 const std::string& host = "127.0.0.1:18080") {
    return "POST /v1/chat/completions HTTP/1.1\r\nHost: " + host +
           "\r\nContent-Type: application/json\r\nContent-Length: " + std::to_string(body.size()) + "\r\n" + extra +
           "\r\n" + body;
}

swbh::server::ParseResult parse(const std::string& raw) { return parse_request(raw); }

}  // namespace

TEST_CASE("HTTP: Anfrage wie vom Java-HttpClient des Plugins wird vollständig gelesen") {
    const std::string body = R"({"messages":[]})";
    const auto r = parse("POST /v1/chat/completions HTTP/1.1\r\nConnection: Upgrade, HTTP2-Settings\r\nContent-Length: " +
                         std::to_string(body.size()) +
                         "\r\nHost: 127.0.0.1:18080\r\nHTTP2-Settings: AAEAAEAAAAIAAAABAAMAAABkAAQBAAAAAAUAAEAA\r\n"
                         "Upgrade: h2c\r\nUser-Agent: Java-http-client/21.0.5\r\nContent-Type: application/json\r\n\r\n" +
                         body);
    REQUIRE(r.status == ParseStatus::Complete);
    CHECK(r.request.method == "POST");
    CHECK(r.request.target == "/v1/chat/completions");
    CHECK(r.request.body == body);
    REQUIRE(r.request.header("host") != nullptr);
    CHECK(*r.request.header("host") == "127.0.0.1:18080");
    CHECK_FALSE(check_request(r.request).has_value());
}

TEST_CASE("HTTP: unvollständiger Kopf oder Body heißt weiterlesen") {
    CHECK(parse("GET /health HTTP/1.1\r\nHost: 127.0.0.1").status == ParseStatus::Incomplete);
    const std::string full = post(R"({"a":1})");
    CHECK(parse(full.substr(0, full.size() - 2)).status == ParseStatus::Incomplete);
    CHECK(parse(full).status == ParseStatus::Complete);
}

TEST_CASE("HTTP: Größengrenzen – Body über 256 KiB 413, Kopf über 16 KiB 431") {
    const auto big = parse("POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n"
                           "Content-Length: 262145\r\n\r\n");
    CHECK(big.status == ParseStatus::Error);
    CHECK(big.http_status == 413);
    const auto exact = parse("POST / HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 262144\r\n\r\n");
    CHECK(exact.status == ParseStatus::Incomplete);  // genau die Grenze ist erlaubt
    const auto head = parse("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nX: " + std::string(17000, 'a'));
    CHECK(head.http_status == 431);
}

TEST_CASE("HTTP: Content-Length ist Pflicht, eindeutig und numerisch; chunked wird abgelehnt") {
    CHECK(parse("POST / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").http_status == 411);
    CHECK(parse("POST / HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 1\r\nContent-Length: 1\r\n\r\nx").http_status ==
          400);
    CHECK(parse("POST / HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: -1\r\n\r\n").http_status == 400);
    CHECK(parse("POST / HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 0x10\r\n\r\n").http_status == 400);
    CHECK(parse("POST / HTTP/1.1\r\nHost: 127.0.0.1\r\nTransfer-Encoding: chunked\r\n\r\n").http_status == 501);
}

TEST_CASE("HTTP: mehrdeutige Kopfzeilen werden abgelehnt") {
    CHECK(parse("GET / HTTP/1.1\nHost: 127.0.0.1\r\n\r\n").http_status == 400);              // nacktes LF
    CHECK(parse("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n x\r\n\r\n").http_status == 400);       // Fortsetzungszeile
    CHECK(parse("GET / HTTP/1.1\r\nHost : 127.0.0.1\r\n\r\n").http_status == 400);            // Leerzeichen im Namen
    CHECK(parse("GET / HTTP/1.1\r\n\r\n").http_status == 400);                                // Host fehlt
    CHECK(parse("GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n").http_status == 400);          // Host doppelt
    CHECK(parse("GET / HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n").http_status == 505);
    CHECK(parse("GET http://x/ HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").http_status == 400);    // nur Pfad
    CHECK(parse("get / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").http_status == 400);
}

TEST_CASE("HTTP: Host muss Loopback sein (DNS-Rebinding)") {
    CHECK(host_is_loopback("127.0.0.1"));
    CHECK(host_is_loopback("127.0.0.1:18080"));
    CHECK(host_is_loopback("localhost:8080"));
    CHECK(host_is_loopback("LOCALHOST"));
    CHECK(host_is_loopback("[::1]:8080"));
    CHECK_FALSE(host_is_loopback("evil.example"));
    CHECK_FALSE(host_is_loopback("evil.example:8080"));
    CHECK_FALSE(host_is_loopback("127.0.0.1.nip.io"));
    CHECK_FALSE(host_is_loopback("localhost.evil.example"));
    CHECK_FALSE(host_is_loopback("127.0.0.1:"));
    CHECK_FALSE(host_is_loopback("127.0.0.1:80x"));
    CHECK_FALSE(host_is_loopback("[::2]"));
    CHECK_FALSE(host_is_loopback(""));

    const auto r = parse(post("{}", "", "rebind.example:8080"));
    REQUIRE(r.status == ParseStatus::Complete);
    const auto rej = check_request(r.request);
    REQUIRE(rej.has_value());
    CHECK(rej->http_status == 403);
    CHECK(rej->error == "host_nicht_erlaubt");
}

TEST_CASE("HTTP: Browser-Anfragen (Origin, Sec-Fetch-Site) bekommen 403") {
    for (const char* extra : {"Origin: https://evil.example\r\n", "Origin: null\r\n", "Sec-Fetch-Site: cross-site\r\n"}) {
        const auto r = parse(post("{}", extra));
        REQUIRE(r.status == ParseStatus::Complete);
        const auto rej = check_request(r.request);
        REQUIRE(rej.has_value());
        CHECK(rej->http_status == 403);
        CHECK(rej->error == "browser_anfrage_verboten");
    }
}

TEST_CASE("HTTP: POST nur mit application/json") {
    const std::string raw = "POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: text/plain\r\n"
                            "Content-Length: 2\r\n\r\n{}";
    const auto r = parse(raw);
    REQUIRE(r.status == ParseStatus::Complete);
    const auto rej = check_request(r.request);
    REQUIRE(rej.has_value());
    CHECK(rej->http_status == 415);
    const auto ok = parse(post("{}", "", "localhost"));
    CHECK_FALSE(check_request(ok.request).has_value());
}

TEST_CASE("HTTP: Ton aus dem Spiel nur auf /hoeren und nur als application/octet-stream") {
    // dev01 2026-11-04 22:11: Die App wies jeden Satz aus Simple Voice Chat mit 415 content_type_nicht_json ab.
    const std::string pcm(6400, '\0');
    const auto sound = [&](const std::string& target, const std::string& type) {
        return parse("POST " + target + " HTTP/1.1\r\nHost: 127.0.0.1:18080\r\nContent-Type: " + type +
                     "\r\nContent-Length: " + std::to_string(pcm.size()) + "\r\n\r\n" + pcm);
    };
    const auto ok = sound("/baumeister/v1/begleiter/hoeren?spieler=Blackkn0va", "application/octet-stream");
    REQUIRE(ok.status == ParseStatus::Complete);
    CHECK_FALSE(check_request(ok.request).has_value());
    const auto json = sound("/baumeister/v1/begleiter/hoeren?spieler=Blackkn0va", "application/json");
    REQUIRE(check_request(json.request).has_value());
    CHECK(check_request(json.request)->error == "content_type_nicht_ton");
    // Nirgends sonst: JSON-Endpunkte nehmen octet-stream weiterhin nicht an.
    const auto other = sound("/v1/chat/completions", "application/octet-stream");
    REQUIRE(check_request(other.request).has_value());
    CHECK(check_request(other.request)->http_status == 415);
    // Browser bleiben auch hier draußen.
    const auto browser = parse("POST /baumeister/v1/begleiter/hoeren HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: "
                               "https://evil.example\r\nContent-Type: application/octet-stream\r\nContent-Length: " +
                               std::to_string(pcm.size()) + "\r\n\r\n" + pcm);
    REQUIRE(check_request(browser.request).has_value());
    CHECK(check_request(browser.request)->http_status == 403);
}

TEST_CASE("HTTP: Antwort hat Content-Length, Connection: close und no-store") {
    const std::string resp = swbh::server::make_response(200, "application/json", "{}", {"Retry-After: 5"});
    CHECK(resp.starts_with("HTTP/1.1 200 OK\r\n"));
    CHECK(resp.find("Content-Length: 2\r\n") != std::string::npos);
    CHECK(resp.find("Connection: close\r\n") != std::string::npos);
    CHECK(resp.find("Cache-Control: no-store\r\n") != std::string::npos);
    CHECK(resp.find("Retry-After: 5\r\n") != std::string::npos);
    CHECK(resp.ends_with("\r\n\r\n{}"));
}
