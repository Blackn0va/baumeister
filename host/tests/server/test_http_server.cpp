// Echter Winsock-Server auf 127.0.0.1 mit Rohanfragen (nur Loopback, kein Netz).

#include "test_support.hpp"

#include "fake_backend.hpp"

#include "server/http_server.hpp"

#include <winsock2.h>
#include <ws2tcpip.h>

#include <windows.h>

#include <string>

namespace {

// Freier Port: an 0 binden und fragen, was Windows vergeben hat.
std::uint16_t free_port() {
    static const bool init = [] {
        WSADATA d{};
        return WSAStartup(MAKEWORD(2, 2), &d) == 0;
    }();
    REQUIRE(init);
    SOCKET s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    bind(s, reinterpret_cast<sockaddr*>(&a), sizeof a);
    int len = sizeof a;
    getsockname(s, reinterpret_cast<sockaddr*>(&a), &len);
    closesocket(s);
    return ntohs(a.sin_port);
}

std::string roundtrip(std::uint16_t port, const std::string& raw) {
    SOCKET s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_port = htons(port);
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    REQUIRE(connect(s, reinterpret_cast<sockaddr*>(&a), sizeof a) == 0);
    send(s, raw.data(), static_cast<int>(raw.size()), 0);
    std::string out;
    char buf[4096];
    int n = 0;
    while ((n = recv(s, buf, sizeof buf, 0)) > 0) {
        out.append(buf, static_cast<std::size_t>(n));
    }
    closesocket(s);
    return out;
}

}  // namespace

TEST_CASE("Server: /health und Chat über einen echten Socket, Host-Prüfung greift") {
    swbh::test::FakeBackend backend;
    swbh::server::ChatApi api(backend, nullptr);
    std::vector<std::string> lines;
    swbh::server::HttpServer server(
        [&](const swbh::server::HttpRequest& r, const std::function<bool()>& gone) { return api.handle(r, gone); },
        [&](const std::string& l) { lines.push_back(l); });
    const std::uint16_t port = free_port();
    std::string error;
    REQUIRE(server.start(port, error));

    const std::string health = roundtrip(port, "GET /health HTTP/1.1\r\nHost: 127.0.0.1:18080\r\n\r\n");
    CHECK(health.starts_with("HTTP/1.1 200 OK"));
    CHECK(health.ends_with(R"({"status":"ok"})"));

    const std::string body = R"({"messages":[{"role":"user","content":"Haus"}]})";
    const std::string chat = roundtrip(port, "POST /v1/chat/completions HTTP/1.1\r\nHost: localhost\r\n"
                                             "Content-Type: application/json\r\nContent-Length: " +
                                                 std::to_string(body.size()) + "\r\n\r\n" + body);
    CHECK(chat.starts_with("HTTP/1.1 200 OK"));
    CHECK(chat.find("\"predicted_per_second\"") != std::string::npos);

    const std::string rebind = roundtrip(port, "GET /health HTTP/1.1\r\nHost: attacker.example:8080\r\n\r\n");
    CHECK(rebind.starts_with("HTTP/1.1 403"));
    const std::string browser =
        roundtrip(port, "GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: https://evil.example\r\n\r\n");
    CHECK(browser.starts_with("HTTP/1.1 403"));
    const std::string chunked = roundtrip(port, "POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                                                "Transfer-Encoding: chunked\r\n\r\n0\r\n\r\n");
    CHECK(chunked.starts_with("HTTP/1.1 501"));

    // Zweiter Server auf demselben Port: SO_EXCLUSIVEADDRUSE / belegt → klare Meldung.
    swbh::server::HttpServer second([](const swbh::server::HttpRequest&, const std::function<bool()>&) {
        return swbh::server::ApiResponse{};
    }, {});
    std::string second_error;
    CHECK_FALSE(second.start(port, second_error));
    CHECK(second_error.find("belegt") != std::string::npos);

    server.stop();
    CHECK_FALSE(server.running());
    CHECK(lines.size() >= 3);  // erfolgreiche /health-Abfragen protokolliert er nicht
}
