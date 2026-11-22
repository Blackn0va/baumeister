// Server-Module (POST /baumeister/v1/module, Vertrag v012 AK-38): Parser mit allen Grenzen aus interface.modul_json,
// Endpunkt 204/400/405, abgelehnte Meldung lässt den alten Stand stehen, 204 ohne Content-Length – auch über einen
// echten Socket auf 127.0.0.1.

#include "test_support.hpp"

#include "server/http_server.hpp"
#include "server/module_api.hpp"

#include <winsock2.h>
#include <ws2tcpip.h>

#include <windows.h>

#include <string>

using namespace swbh::server;

namespace {

// Ein Modul mit allen Feldern; extra wird vor der schließenden Klammer eingefügt.
std::string module(const std::string& id, const std::string& name = "Stadtleben", const std::string& extra = "",
                   const std::string& state = "wartet") {
    return R"({"id":")" + id + R"(","name":")" + name + R"(","description":"KI-Bewohner in Städten","on":true,)" +
           R"("state":")" + state + R"(","reason":"KI nicht erreichbar","needs":["ki"])" + extra + "}";
}

std::string body_of(const std::string& modules) { return R"({"version":1,"modules":[)" + modules + "]}"; }

std::string error_of(const std::string& body) {
    std::string error;
    const auto parsed = parse_modules(body, error);
    return parsed ? std::string("ok") : error;
}

std::string repeat(const std::string& s, std::size_t n) {
    std::string out;
    for (std::size_t i = 0; i < n; ++i) {
        out += s;
    }
    return out;
}

HttpRequest post(const std::string& body) {
    HttpRequest r;
    r.method = "POST";
    r.target = std::string(kModulePath);
    r.body = body;
    return r;
}

}  // namespace

TEST_CASE("Module: gültige Meldung wie im Vertrag wird vollständig gelesen") {
    std::string error;
    const std::string body = body_of(module("stadtleben") + "," +
                                     R"({"id":"bau-ki","name":"Bau-KI","on":false,"state":"aus"})");
    const auto mods = parse_modules(body, error);
    REQUIRE(mods.has_value());
    REQUIRE(mods->size() == 2);
    const ServerModule& a = (*mods)[0];
    CHECK(a.id == "stadtleben");
    CHECK(a.name == "Stadtleben");
    CHECK(a.description == "KI-Bewohner in Städten");
    CHECK(a.on);
    CHECK(a.state == ModuleRunState::Waiting);
    CHECK(a.reason == "KI nicht erreichbar");
    REQUIRE(a.needs.size() == 1);
    CHECK(a.needs[0] == "ki");
    // Fehlende description/reason/needs gelten als leer.
    const ServerModule& b = (*mods)[1];
    CHECK_FALSE(b.on);
    CHECK(b.state == ModuleRunState::Off);
    CHECK(b.description.empty());
    CHECK(b.reason.empty());
    CHECK(b.needs.empty());
    CHECK(error_of(body_of("")) == "ok");  // leere Liste ist eine gültige Meldung
    for (const char* s : {"aktiv", "aus", "wartet", "fehler"}) {
        CHECK(error_of(body_of(module("x", "X", "", s))) == "ok");
    }
}

TEST_CASE("Module: id nur [a-z0-9-] mit 1 bis 32 Zeichen, auch in needs") {
    CHECK(error_of(body_of(module(std::string(32, 'a')))) == "ok");
    CHECK(error_of(body_of(module(std::string(33, 'a')))) == "modul_id");
    CHECK(error_of(body_of(module(""))) == "modul_id");
    CHECK(error_of(body_of(module("Stadtleben"))) == "modul_id");
    CHECK(error_of(body_of(module("stadt_leben"))) == "modul_id");
    CHECK(error_of(body_of(module("stadt leben"))) == "modul_id");
    CHECK(error_of(body_of(module("städte"))) == "modul_id");
    CHECK(error_of(body_of(R"({"id":"a","name":"A","on":true,"state":"aktiv","needs":["KI"]})")) == "modul_needs");
    CHECK(error_of(body_of(R"({"id":"a","name":"A","on":true,"state":"aktiv","needs":"ki"})")) == "modul_needs");
    std::string needs16;
    for (int i = 0; i < 16; ++i) {
        needs16 += (i == 0 ? "" : ",") + std::string("\"n") + std::to_string(i) + "\"";
    }
    CHECK(error_of(body_of(R"({"id":"a","name":"A","on":true,"state":"aktiv","needs":[)" + needs16 + "]}")) == "ok");
    CHECK(error_of(body_of(R"({"id":"a","name":"A","on":true,"state":"aktiv","needs":[)" + needs16 + R"(,"x"]})")) ==
          "modul_needs");
}

TEST_CASE("Module: Textgrenzen zählen Zeichen, nicht Byte (name 40, description 160, reason 120)") {
    // „ä“ sind zwei Byte – 40 davon sind 80 Byte und trotzdem erlaubt.
    CHECK(error_of(body_of(module("a", repeat("ä", 40)))) == "ok");
    CHECK(error_of(body_of(module("a", repeat("ä", 41)))) == "modul_name");
    CHECK(error_of(body_of(module("a", ""))) == "modul_name");
    const std::string head = R"({"id":"a","name":"A","on":true,"state":"aktiv",)";
    CHECK(error_of(body_of(head + R"("description":")" + repeat("ü", 160) + R"("})")) == "ok");
    CHECK(error_of(body_of(head + R"("description":")" + repeat("u", 161) + R"("})")) == "modul_description");
    CHECK(error_of(body_of(head + R"("reason":")" + repeat("ß", 120) + R"("})")) == "ok");
    CHECK(error_of(body_of(head + R"("reason":")" + repeat("r", 121) + R"("})")) == "modul_reason");
    CHECK(error_of(body_of(head + R"("reason":17})")) == "modul_reason");
    // Steuerzeichen (Zeilenumbruch, U+0085) zerlegten Liste und Konsole.
    CHECK(error_of(body_of(module("a", "Stadt\\nleben"))) == "modul_name");
    CHECK(error_of(body_of(head + R"("reason":"weg\u0085da"})")) == "modul_reason");
}

TEST_CASE("Module: Zustand, on, Version und unbekannte Felder werden streng geprüft") {
    CHECK(error_of(body_of(module("a", "A", "", "läuft"))) == "modul_state");
    CHECK(error_of(body_of(module("a", "A", "", "AKTIV"))) == "modul_state");
    CHECK(error_of(body_of(R"({"id":"a","name":"A","on":"ja","state":"aktiv"})")) == "modul_on");
    CHECK(error_of(body_of(R"({"id":"a","name":"A","state":"aktiv"})")) == "modul_on");
    CHECK(error_of(body_of(module("a", "A", R"(,"spieler":"Steve")"))) == "modul_feld_unbekannt");
    CHECK(error_of(R"({"version":1,"modules":[],"adresse":"1.2.3.4"})") == "feld_unbekannt");
    CHECK(error_of(R"({"version":2,"modules":[]})") == "version");
    CHECK(error_of(R"({"version":"1","modules":[]})") == "version");
    CHECK(error_of(R"({"modules":[]})") == "version");
    CHECK(error_of(R"({"version":1})") == "modules");
    CHECK(error_of(R"({"version":1,"modules":{}})") == "modules");
    CHECK(error_of(body_of("17")) == "modul_kein_objekt");
    CHECK(error_of(body_of(module("a") + "," + module("a"))) == "modul_id_doppelt");
}

TEST_CASE("Module: kaputtes JSON, ungültiges UTF-8, zu viele Module und zu großer Körper") {
    CHECK(error_of("") == "body_kein_json");
    CHECK(error_of("{") == "body_kein_json");
    CHECK(error_of("[]") == "body_kein_json");
    CHECK(error_of(R"({"version":1,"modules":[)") == "body_kein_json");
    CHECK(error_of(body_of(module("a", "Stadt\xC3"))) == "body_kein_json");
    std::string many;
    for (std::size_t i = 0; i < kModuleMaxCount; ++i) {
        many += (i == 0 ? "" : ",") + module("m" + std::to_string(i));
    }
    CHECK(error_of(body_of(many)) == "ok");
    CHECK(error_of(body_of(many + "," + module("zu-viel"))) == "zu_viele_module");
    // Über 128 KiB: abgelehnt, bevor überhaupt geparst wird (auch wenn es gültiges JSON wäre).
    const std::string big = R"({"version":1,"modules":[],"x":")" + std::string(kModuleMaxBodyBytes, 'x') + R"("})";
    CHECK(error_of(big) == "zu_gross");  // umlaut-ok: snake_case-Code
}

TEST_CASE("Module-Endpunkt: 204 bei gültiger Meldung, 400 bei kaputter, alter Stand bleibt, 405 für GET") {
    ModuleState state;
    CHECK(state.snapshot() == nullptr);
    CHECK(state.seq() == 0);

    const ApiResponse ok = handle_modules(post(body_of(module("stadtleben"))), state, 1'800'000'000, "14:03:22");
    CHECK(ok.status == 204);
    CHECK(ok.body.empty());
    REQUIRE(state.snapshot() != nullptr);
    CHECK(state.seq() == 1);
    CHECK(state.snapshot()->modules.size() == 1);
    CHECK(state.snapshot()->received_unix == 1'800'000'000);
    CHECK(state.snapshot()->received_hms == "14:03:22");

    const ApiResponse broken = handle_modules(post("{kaputt"), state, 1'800'000'060, "14:04:22");
    CHECK(broken.status == 400);
    CHECK(broken.body == R"({"error":"body_kein_json"})");
    const ApiResponse too_long = handle_modules(post(body_of(module("a", std::string(41, 'n')))), state, 1, "x");
    CHECK(too_long.status == 400);
    CHECK(too_long.body == R"({"error":"modul_name"})");
    CHECK(state.seq() == 1);
    CHECK(state.snapshot()->received_hms == "14:03:22");
    CHECK(state.snapshot()->modules[0].id == "stadtleben");

    HttpRequest get = post("");
    get.method = "GET";
    const ApiResponse wrong = handle_modules(get, state, 0, "");
    CHECK(wrong.status == 405);
    REQUIRE(wrong.extra_headers.size() == 1);
    CHECK(wrong.extra_headers[0] == "Allow: POST");

    CHECK(is_module_path("/baumeister/v1/module"));
    CHECK(is_module_path("/baumeister/v1/module?x=1"));
    CHECK_FALSE(is_module_path("/baumeister/v1/modules"));
    CHECK(module_state_name(ModuleRunState::Failed) == "fehler");
}

TEST_CASE("HTTP: 204 ohne Content-Type, Content-Length und Körper (RFC 9110 §8.6)") {
    const std::string resp = make_response(204, "application/json; charset=utf-8", "{}");
    CHECK(resp.starts_with("HTTP/1.1 204 No Content\r\n"));
    CHECK(resp.find("Content-Length") == std::string::npos);
    // Mit Zeilenanfang: „X-Content-Type-Options: nosniff“ bleibt und enthält „Content-Type“.
    CHECK(resp.find("\r\nContent-Type:") == std::string::npos);
    CHECK(resp.find("\r\nX-Content-Type-Options: nosniff\r\n") != std::string::npos);
    CHECK(resp.find("Connection: close\r\n") != std::string::npos);
    CHECK(resp.ends_with("\r\n\r\n"));
    // Alle anderen Status behalten Content-Length.
    CHECK(make_response(200, "application/json", "{}").find("Content-Length: 2\r\n") != std::string::npos);
}

TEST_CASE("Module über einen echten Socket: POST → 204 ohne Körper, kaputt → 400") {
    static const bool init = [] {
        WSADATA d{};
        return WSAStartup(MAKEWORD(2, 2), &d) == 0;
    }();
    REQUIRE(init);
    ModuleState state;
    HttpServer server([&](const HttpRequest& r, const std::function<bool()>&) {
        return handle_modules(r, state, 1'800'000'000, "12:00:00");
    },
                      nullptr);
    // Freien Port vom System nehmen.
    SOCKET probe = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    bind(probe, reinterpret_cast<sockaddr*>(&a), sizeof a);
    int len = sizeof a;
    getsockname(probe, reinterpret_cast<sockaddr*>(&a), &len);
    closesocket(probe);
    const std::uint16_t port = ntohs(a.sin_port);
    std::string error;
    REQUIRE(server.start(port, error));

    const auto send_body = [port](const std::string& body) {
        SOCKET s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
        sockaddr_in t{};
        t.sin_family = AF_INET;
        t.sin_port = htons(port);
        t.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        REQUIRE(connect(s, reinterpret_cast<sockaddr*>(&t), sizeof t) == 0);
        const std::string raw = "POST /baumeister/v1/module HTTP/1.1\r\nHost: 127.0.0.1:18080\r\n"
                                "Content-Type: application/json\r\nContent-Length: " +
                                std::to_string(body.size()) + "\r\n\r\n" + body;
        send(s, raw.data(), static_cast<int>(raw.size()), 0);
        std::string out;
        char buf[4096];
        int n = 0;
        while ((n = recv(s, buf, sizeof buf, 0)) > 0) {
            out.append(buf, static_cast<std::size_t>(n));
        }
        closesocket(s);
        return out;
    };
    const std::string ok = send_body(body_of(module("stadtleben")));
    CHECK(ok.starts_with("HTTP/1.1 204 No Content\r\n"));
    CHECK(ok.ends_with("\r\n\r\n"));
    CHECK(ok.find("Content-Length") == std::string::npos);
    REQUIRE(state.snapshot() != nullptr);
    CHECK(state.snapshot()->modules[0].reason == "KI nicht erreichbar");
    const std::string bad = send_body("{\"version\":1");
    CHECK(bad.starts_with("HTTP/1.1 400 "));
    CHECK(bad.ends_with(R"({"error":"body_kein_json"})"));
    server.stop();
}
