// Fernverbindung (relay::RelayLink) gegen eine Loopback-Gegenstelle (fake_relay, ws:// ohne TLS) und den ECHTEN
// eigenen Server (server::HttpServer an 127.0.0.1:<frei>). Der WebSocket ist der echte WinHTTP-Weg
// (win::connect_pinned_ws im Testmodus) – so prüft die Gegenstelle mit, dass WinHTTP so rahmt, wie Kanal v1 verlangt.
// TLS, Pins und Client-Zertifikat misst probes/winhttp_pin gegen JSSE (Loopback-TLS ginge nur mit einem Eingriff in
// den Zertifikatsspeicher des Benutzers).

#include "test_support.hpp"

#include "fake_relay.hpp"

#include "relay/forwarder.hpp"
#include "relay/kanal.hpp"
#include "relay/relay_link.hpp"
#include "server/http_server.hpp"
#include "win/pinned_ws.hpp"

#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <functional>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <vector>

using namespace std::chrono_literals;

namespace {

namespace relay = swbh::relay;
namespace server = swbh::server;
using swbh::test::FakeConnection;
using swbh::test::FakeRelay;

const std::string kPinA = "sha256:" + std::string(64, 'a');

// Eigener Server der App mit Test-Handler (die Prüfungen von HttpServer – Host, Content-Type, Größen – laufen echt).
class LocalServer final {
public:
    LocalServer()
        : server_([this](const server::HttpRequest& r, const std::function<bool()>& gone) { return handle(r, gone); },
                  {}) {
        port_ = swbh::test::unused_port();
        std::string error;
        REQUIRE(server_.start(static_cast<std::uint16_t>(port_), error));
    }

    int port() const { return port_; }
    server::HttpRequest last() {
        std::lock_guard<std::mutex> lock(mutex_);
        return last_;
    }

    std::atomic<bool> block{false};
    std::atomic<bool> gone_seen{false};
    std::atomic<int> requests{0};
    std::atomic<int> active{0};

private:
    server::ApiResponse handle(const server::HttpRequest& r, const std::function<bool()>& gone) {
        ++requests;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            last_ = r;
        }
        server::ApiResponse resp;
        if (r.target == "/v1/chat/completions") {
            if (block) {
                ++active;
                const auto until = std::chrono::steady_clock::now() + 20s;
                while (!gone() && std::chrono::steady_clock::now() < until) {
                    std::this_thread::sleep_for(10ms);
                }
                if (gone()) {
                    gone_seen = true;
                }
                --active;
            }
            resp.body = R"({"echo":)" + r.body + "}";
            return resp;
        }
        if (r.target == "/baumeister/v1/stadt") {
            resp.status = 503;
            resp.body = R"({"error":"belegt"})";
            return resp;
        }
        if (r.target == "/baumeister/v1/ergebnis") {
            resp.content_type = "application/octet-stream";
            resp.body.resize(200 * 1024 + 7);
            for (std::size_t i = 0; i < resp.body.size(); ++i) {
                resp.body[i] = static_cast<char>(i % 251);
            }
            resp.extra_headers.push_back("X-Body-Bytes: " + std::to_string(r.body.size()));
            return resp;
        }
        if (r.target.starts_with("/baumeister/v1/begleiter/gehoert")) {  // umlaut-ok: URL
            resp.body = R"({"ziel":")" + r.target + "\"}";
            return resp;
        }
        resp.status = 404;
        resp.body = R"({"error":"unbekannt"})";
        return resp;
    }

    int port_ = 0;
    std::mutex mutex_;
    server::HttpRequest last_;
    server::HttpServer server_;  // zuletzt: wird zuerst zerstört und wartet seine Threads ab
};

class Harness final {
public:
    explicit Harness(swbh::test::FakeRelayOptions fake_options = {}, relay::RelayLink::Connector connector = {},
                     std::chrono::milliseconds hello = 3s)
        : fake(std::move(fake_options)),
          link([this](const relay::RelayLink::Event& e) { on_event(e); },
               options(std::move(connector), hello)) {}

    relay::LinkConfig config(int port = 0) const {
        relay::LinkConfig c;
        c.url = "wss://relay.test:25580/kanal/v1";
        c.server_pins = {kPinA};
        c.local_port = port != 0 ? port : local.port();
        c.app_name = "BaumeisterHost/test";
        return c;
    }

    // Startet, nimmt die Verbindung an, prüft das hallo der App und antwortet.
    std::unique_ptr<FakeConnection> connect(int port = 0) {
        std::string error;
        REQUIRE(link.start(config(port), error));
        return accept_and_greet();
    }

    std::unique_ptr<FakeConnection> accept_and_greet() {
        auto c = fake.accept(10s);
        REQUIRE(c != nullptr);
        const auto hello = c->read_message(5s);
        REQUIRE_MESSAGE(hello.has_value(), c->last_error());
        CHECK(hello->type == relay::Type::Hallo);
        CHECK(hello->peer == "BaumeisterHost/test");
        CHECK(hello->max_jobs == 6);
        relay::Message reply;
        reply.type = relay::Type::Hallo;
        reply.peer = "baumeister-relay/test";
        c->send_message(reply);
        REQUIRE(wait_for(relay::State::Connected, 5s));
        return c;
    }

    bool wait_for(relay::State s, std::chrono::milliseconds timeout) {
        std::unique_lock<std::mutex> lock(mutex_);
        return cv_.wait_for(lock, timeout, [&] {
            for (std::size_t i = seen_; i < events_.size(); ++i) {
                if (events_[i].state == s) {
                    seen_ = i + 1;
                    return true;
                }
            }
            return false;
        });
    }

    std::vector<relay::RelayLink::Event> events() {
        std::lock_guard<std::mutex> lock(mutex_);
        return events_;
    }

    FakeRelay fake;
    LocalServer local;
    relay::RelayLink link;

private:
    relay::RelayLink::Options options(relay::RelayLink::Connector connector, std::chrono::milliseconds hello) {
        relay::RelayLink::Options o;
        o.connect = connector ? std::move(connector)
                              : [this](const relay::LinkConfig&, swbh::win::WsConnectError& e,
                                       swbh::win::CancelToken& cancel) {
                                    swbh::win::PinnedWsOptions w;
                                    w.url = fake.url();
                                    w.protocol = std::string(relay::kProtocol);
                                    w.user_agent = "BaumeisterHost-Test";
                                    w.allow_plain_loopback_for_tests = true;
                                    return swbh::win::connect_pinned_ws(w, e, &cancel);
                                };
        o.backoff = [](int) { return std::chrono::milliseconds(200); };
        o.hello_timeout = hello;
        return o;
    }

    void on_event(const relay::RelayLink::Event& e) {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            events_.push_back(e);
        }
        cv_.notify_all();
    }

    std::mutex mutex_;
    std::condition_variable cv_;
    std::vector<relay::RelayLink::Event> events_;
    std::size_t seen_ = 0;
};

relay::Message request(std::uint32_t id, std::string method, std::string path, std::string body = {},
                       std::optional<std::string> ct = std::string("application/json")) {
    relay::Message m;
    m.type = relay::Type::Anfrage;
    m.id = id;
    m.method = std::move(method);
    m.path = std::move(path);
    m.content_type = m.method == "GET" ? std::nullopt : std::move(ct);
    m.length = body.size();
    m.data = std::move(body);
    return m;
}

// Nutzlast mit beliebigem (auch regelwidrigem) Kopf – an relay::encode vorbei.
std::string raw_payload(const std::string& head) {
    return std::string{static_cast<char>((head.size() >> 8) & 0xFF), static_cast<char>(head.size() & 0xFF)} + head;
}

relay::Message control(relay::Type type, std::uint32_t id, std::string data = {}) {
    relay::Message m;
    m.type = type;
    m.id = id;
    m.data = std::move(data);
    return m;
}

// Liest eine komplette Antwort (antwort + daten) zu id; nullopt, wenn statt dessen etwas anderes kommt.
std::optional<relay::Message> read_response(FakeConnection& c, std::uint32_t id) {
    auto head = c.read_message(10s);
    if (!head || head->type != relay::Type::Antwort || head->id != id) {
        return head;
    }
    while (head->data.size() < head->length) {
        auto piece = c.read_message(10s);
        if (!piece || piece->type != relay::Type::Daten || piece->id != id || piece->data.size() > 65536) {
            return std::nullopt;
        }
        head->data += piece->data;
    }
    return head;
}

}  // namespace

TEST_CASE("Fernverbindung: hallo, Auftrag an den eigenen Server, Antwort unverändert zurück") {
    Harness h;
    auto c = h.connect();
    CHECK(h.fake.last_request().find("Sec-WebSocket-Protocol: baumeister-kanal.v1\r\n") != std::string::npos);
    c->send_message(request(1, "POST", "/v1/chat/completions", R"({"x":1})"));
    const auto r = read_response(*c, 1);
    REQUIRE(r.has_value());
    CHECK(r->type == relay::Type::Antwort);
    CHECK(r->status == 200);
    CHECK(r->content_type == std::optional<std::string>("application/json; charset=utf-8"));
    CHECK(r->data == R"({"echo":{"x":1}})");
    // Der eigene Server sah eine normale Loopback-Anfrage: Host 127.0.0.1, Content-Type vom Relay.
    const server::HttpRequest seen = h.local.last();
    REQUIRE(seen.header("host") != nullptr);
    CHECK(seen.header("host")->starts_with("127.0.0.1:"));
    REQUIRE(seen.header("content-type") != nullptr);
    CHECK(*seen.header("content-type") == "application/json");
    h.link.stop();
    CHECK(h.link.stats().jobs == 1);
}

TEST_CASE("Fernverbindung: Body in Stücken hinein, große Antwort in Stücken zu höchstens 64 KiB zurück") {
    Harness h;
    auto c = h.connect();
    std::string body(100 * 1024, 'b');
    relay::Message first = request(1, "POST", "/baumeister/v1/ergebnis", body);
    first.data = body.substr(0, 65536);
    c->send_message(first);
    c->send_message(control(relay::Type::Daten, 1, body.substr(65536)));
    const auto r = read_response(*c, 1);
    REQUIRE(r.has_value());
    CHECK(r->status == 200);
    CHECK(r->length == 200 * 1024 + 7);
    REQUIRE(r->data.size() == 200 * 1024 + 7);
    bool same = true;
    for (std::size_t i = 0; i < r->data.size(); ++i) {
        same = same && r->data[i] == static_cast<char>(i % 251);
    }
    CHECK(same);
    CHECK(h.local.last().body.size() == body.size());
}

TEST_CASE("Fernverbindung: 503 des eigenen Servers geht unverändert zurück, GET samt Abfrage kommt an") {
    Harness h;
    auto c = h.connect();
    c->send_message(request(1, "POST", "/baumeister/v1/stadt", "{}"));
    const auto busy = read_response(*c, 1);
    REQUIRE(busy.has_value());
    CHECK(busy->status == 503);
    CHECK(busy->data == R"({"error":"belegt"})");
    c->send_message(request(2, "GET", "/baumeister/v1/begleiter/gehoert?imspiel=J%C3%BCrgen"));  // umlaut-ok: URL
    const auto got = read_response(*c, 2);
    REQUIRE(got.has_value());
    CHECK(got->status == 200);
    CHECK(h.local.last().target == "/baumeister/v1/begleiter/gehoert?imspiel=J%C3%BCrgen");  // umlaut-ok: URL
}

TEST_CASE("Fernverbindung: eigener Server nicht erreichbar – abbruch statt einer erfundenen 502") {
    Harness h;
    auto c = h.connect(swbh::test::unused_port());
    c->send_message(request(5, "POST", "/v1/chat/completions", "{}"));
    const auto r = c->read_message(10s);
    REQUIRE(r.has_value());
    CHECK(r->type == relay::Type::Abbruch);
    CHECK(r->id == 5);
    h.link.stop();
    CHECK(h.link.stats().local_failed == 1);
}

TEST_CASE("Fernverbindung: abbruch vom Relay schließt die lokale Anfrage, danach kommt zu der id nichts mehr") {
    Harness h;
    h.local.block = true;
    auto c = h.connect();
    c->send_message(request(1, "POST", "/v1/chat/completions", "{}"));
    for (int i = 0; i < 500 && h.local.active == 0; ++i) {
        std::this_thread::sleep_for(10ms);
    }
    REQUIRE(h.local.active == 1);
    c->send_message(control(relay::Type::Abbruch, 1));
    for (int i = 0; i < 500 && !h.local.gone_seen; ++i) {
        std::this_thread::sleep_for(10ms);
    }
    CHECK(h.local.gone_seen);  // der eigene Server sah den aufgelegten Client – die Erzeugung hört dort auf
    const auto after = c->read_message(1500ms);
    CHECK_FALSE(after.has_value());
    h.link.stop();
    CHECK(h.link.stats().aborted == 1);
}

TEST_CASE("Fernverbindung: Verbindungsverlust bricht laufende Aufträge ab, danach neuer Aufbau") {
    Harness h;
    h.local.block = true;
    auto c = h.connect();
    c->send_message(request(1, "POST", "/v1/chat/completions", "{}"));
    for (int i = 0; i < 500 && h.local.active == 0; ++i) {
        std::this_thread::sleep_for(10ms);
    }
    REQUIRE(h.local.active == 1);
    c->close_tcp();
    for (int i = 0; i < 500 && !h.local.gone_seen; ++i) {
        std::this_thread::sleep_for(10ms);
    }
    CHECK(h.local.gone_seen);
    CHECK(h.wait_for(relay::State::Waiting, 5s));
    auto again = h.accept_and_greet();  // Neustart nach der (verkürzten) Wartezeit
    CHECK(again != nullptr);
}

TEST_CASE("Fernverbindung: Verstöße des Relays beenden die Verbindung mit Grund und Close-Code aus Kanal v1") {
    struct Case {
        std::string name;
        std::function<void(FakeConnection&)> act;
        int code;
        const char* reason;
    };
    const std::vector<Case> cases = {
        {"Pfad außerhalb der Freigabe",
         [](FakeConnection& c) {
             c.send_payload(raw_payload(R"({"t":"anfrage","id":1,"m":"GET","p":"/admin","len":0})"));
         },
         1002, "pfad_ungueltig"},  // umlaut-ok
        {"Textrahmen", [](FakeConnection& c) { c.send_raw(swbh::test::encode_frame(0x1, "hallo", std::nullopt)); }, 1003,
         "textrahmen"},
        {"id nicht steigend",
         [](FakeConnection& c) {
             c.send_message(request(7, "GET", "/health"));
             c.send_message(request(7, "GET", "/health"));
         },
         1002, "id_ungueltig"},  // umlaut-ok
        {"mehr daten als len",
         [](FakeConnection& c) {
             relay::Message m = request(1, "POST", "/v1/chat/completions", "{}");
             m.length = 4;
             c.send_message(m);
             c.send_message(control(relay::Type::Daten, 1, "abc"));
         },
         1002, "laenge_ungueltig"},  // umlaut-ok
        {"zweites hallo",
         [](FakeConnection& c) {
             relay::Message m;
             m.type = relay::Type::Hallo;
             m.peer = "noch-einmal";
             c.send_message(m);
         },
         1002, "hallo_doppelt"},
        {"Kopf mit doppeltem Schlüssel",
         [](FakeConnection& c) {
             c.send_payload(raw_payload(R"({"t":"abbruch","id":1,"id":2})"));
         },
         1002, "kopf_kein_objekt"},
    };
    for (const Case& k : cases) {
        CAPTURE(k.name);
        Harness h;
        auto c = h.connect();
        k.act(*c);
        relay::Decoded d = c->read_decoded(5s);
        // Die App schickt vor dem Schließen ggf. noch eine Antwort auf einen gültigen ersten Auftrag – überlesen.
        while (d.message.has_value()) {
            d = c->read_decoded(5s);
        }
        CHECK(c->close_code() == k.code);
        CHECK(c->close_reason() == k.reason);
        CHECK(h.wait_for(relay::State::Waiting, 5s));
    }
}

TEST_CASE("Fernverbindung: hallo mit Version 2 schließt mit 1008, anfrage vor dem hallo mit hallo_fehlt") {
    SUBCASE("Version") {
        Harness h;
        std::string error;
        REQUIRE(h.link.start(h.config(), error));
        auto c = h.fake.accept(10s);
        REQUIRE(c != nullptr);
        REQUIRE(c->read_message(5s).has_value());
        c->send_payload(raw_payload(R"({"t":"hallo","v":2,"relay":"zukunft"})"));
        CHECK_FALSE(c->read_decoded(5s).message.has_value());
        CHECK(c->close_code() == 1008);
        CHECK(c->close_reason() == "version_ungueltig");  // umlaut-ok
    }
    SUBCASE("anfrage vor hallo") {
        Harness h;
        std::string error;
        REQUIRE(h.link.start(h.config(), error));
        auto c = h.fake.accept(10s);
        REQUIRE(c != nullptr);
        REQUIRE(c->read_message(5s).has_value());
        c->send_message(request(1, "GET", "/health"));
        CHECK_FALSE(c->read_decoded(5s).message.has_value());
        CHECK(c->close_code() == 1002);
        CHECK(c->close_reason() == "hallo_fehlt");
    }
}

TEST_CASE("Fernverbindung: ohne hallo des Relays binnen der Frist schließt die App mit hallo_fehlt") {
    Harness h({}, {}, 1s);
    std::string error;
    REQUIRE(h.link.start(h.config(), error));
    auto c = h.fake.accept(10s);
    REQUIRE(c != nullptr);
    REQUIRE(c->read_message(5s).has_value());
    CHECK_FALSE(c->read_decoded(5s).message.has_value());
    CHECK(c->close_code() == 1002);
    CHECK(c->close_reason() == "hallo_fehlt");
    CHECK(h.wait_for(relay::State::Waiting, 5s));
}

TEST_CASE("Fernverbindung: maskierter Rahmen des Relays trennt die Verbindung (WinHTTP lehnt ihn ab)") {
    Harness h;
    auto c = h.connect();
    c->send_raw(swbh::test::encode_frame(0x2, "x", std::array<std::uint8_t, 4>{1, 2, 3, 4}));
    CHECK(c->wait_closed(5s));
    CHECK(h.wait_for(relay::State::Waiting, 5s));
}

TEST_CASE("Fernverbindung: mehr als 6 gleichzeitige Aufträge – der siebte bekommt abbruch") {
    Harness h;
    h.local.block = true;
    auto c = h.connect();
    for (std::uint32_t id = 1; id <= 7; ++id) {
        c->send_message(request(id, "POST", "/v1/chat/completions", "{}"));
    }
    const auto r = c->read_message(10s);
    REQUIRE(r.has_value());
    CHECK(r->type == relay::Type::Abbruch);
    CHECK(r->id == 7);
    h.link.stop();
    CHECK(h.link.stats().refused == 1);
}

TEST_CASE("Fernverbindung: daten zu unbekannter id werden verworfen und gezählt, die Verbindung bleibt") {
    Harness h;
    auto c = h.connect();
    c->send_message(control(relay::Type::Daten, 99, "x"));
    c->send_message(control(relay::Type::Abbruch, 98));
    c->send_message(request(1, "GET", "/health"));
    const auto r = read_response(*c, 1);
    REQUIRE(r.has_value());
    CHECK(r->type == relay::Type::Antwort);
    h.link.stop();
    CHECK(h.link.stats().dropped == 2);
}

TEST_CASE("Fernverbindung: abgelehntes Upgrade und falsches Protokoll führen zu Warten und neuem Versuch") {
    SUBCASE("HTTP 404") {
        Harness h(swbh::test::FakeRelayOptions{404, std::string(relay::kProtocol)});
        std::string error;
        REQUIRE(h.link.start(h.config(), error));
        REQUIRE(h.wait_for(relay::State::Waiting, 10s));
        bool found = false;
        for (const auto& e : h.events()) {
            found = found || e.text.find("HTTP 404") != std::string::npos;
        }
        CHECK(found);
    }
    SUBCASE("Protokoll fehlt") {
        Harness h(swbh::test::FakeRelayOptions{101, ""});
        std::string error;
        REQUIRE(h.link.start(h.config(), error));
        REQUIRE(h.wait_for(relay::State::Waiting, 10s));
        bool found = false;
        for (const auto& e : h.events()) {
            found = found || e.text.find("spricht nicht baumeister-kanal.v1") != std::string::npos;
        }
        CHECK(found);
    }
}

TEST_CASE("Fernverbindung: falscher Fingerabdruck des Servers meldet einen Alarm und versucht es später neu") {
    std::atomic<int> attempts{0};
    Harness h({}, [&attempts](const relay::LinkConfig&, swbh::win::WsConnectError& e, swbh::win::CancelToken&) {
        ++attempts;
        e.kind = swbh::win::WsConnectError::Kind::PinMismatch;
        e.text = "Fingerabdruck passt nicht";
        e.found_pin = "sha256:" + std::string(64, 'f');
        return std::unique_ptr<swbh::win::WsConnection>();
    });
    std::string error;
    REQUIRE(h.link.start(h.config(), error));
    REQUIRE(h.wait_for(relay::State::Waiting, 5s));
    const auto events = h.events();
    bool alarm = false;
    for (const auto& e : events) {
        alarm = alarm || (e.state == relay::State::Waiting && e.alarm);
    }
    CHECK(alarm);
    REQUIRE(h.wait_for(relay::State::Waiting, 5s));  // zweiter Versuch nach der Wartezeit
    CHECK(attempts >= 2);
}

TEST_CASE("Fernverbindung: ohne Server-Pin, mit falscher Adresse oder kaputtem Pin startet nichts") {
    Harness h;
    relay::LinkConfig c = h.config();
    c.server_pins.clear();
    std::string error;
    CHECK_FALSE(h.link.start(c, error));
    CHECK(error.find("Kein Fingerabdruck") != std::string::npos);
    CHECK(h.link.state() == relay::State::Failed);
    c = h.config();
    c.server_pins = {"sha256:zz"};
    CHECK_FALSE(h.link.start(c, error));
    c = h.config();
    c.server_pins = {kPinA, kPinA, kPinA};
    CHECK_FALSE(h.link.start(c, error));
    c = h.config();
    c.url = "ws://relay.test:25580/kanal/v1";
    CHECK_FALSE(h.link.start(c, error));
    CHECK(h.fake.connections() == 0);
}

TEST_CASE("Fernverbindung: Adressen nur als wss://<host>[:port]/kanal/v1") {
    CHECK(relay::relay_url_allowed(relay::kDefaultRelayUrl));
    CHECK(relay::relay_url_allowed("wss://192.168.178.47:25580/kanal/v1"));
    CHECK(relay::relay_url_allowed("wss://relay.example/kanal/v1"));
    CHECK(relay::relay_host_port("wss://relay.example/kanal/v1") == "relay.example:443");
    CHECK(relay::relay_host_port(relay::kDefaultRelayUrl) == "dein-server.example.net:25580");
    CHECK_FALSE(relay::relay_url_allowed("ws://relay.example:25580/kanal/v1"));
    CHECK_FALSE(relay::relay_url_allowed("https://relay.example:25580/kanal/v1"));
    CHECK_FALSE(relay::relay_url_allowed("wss://relay.example:25580/kanal/v2"));
    CHECK_FALSE(relay::relay_url_allowed("wss://relay.example:25580/kanal/v1?x=1"));
    CHECK_FALSE(relay::relay_url_allowed("wss://relay.example:0/kanal/v1"));
    CHECK_FALSE(relay::relay_url_allowed("wss://relay.example:65536/kanal/v1"));
    CHECK_FALSE(relay::relay_url_allowed("wss://user@relay.example/kanal/v1"));
    CHECK_FALSE(relay::relay_url_allowed("wss://[::1]:25580/kanal/v1"));
    CHECK_FALSE(relay::relay_url_allowed("wss://-relay/kanal/v1"));
    CHECK_FALSE(relay::relay_url_allowed("wss://relay..example/kanal/v1"));
}

TEST_CASE("Gepinnte Verbindung: ohne Pin, mit kaputtem Pin oder ohne Zertifikat geht nichts hinaus") {
    // Alle Fälle scheitern vor dem ersten Netzzugriff (Config) – der Name existiert nicht, würde also auffallen.
    swbh::win::PinnedWsOptions o;
    o.url = "wss://nicht-vorhanden.invalid:25580/kanal/v1";
    o.protocol = std::string(relay::kProtocol);
    o.user_agent = "BaumeisterHost-Test";
    swbh::win::WsConnectError e;
    CHECK(swbh::win::connect_pinned_ws(o, e) == nullptr);
    CHECK(e.kind == swbh::win::WsConnectError::Kind::Config);
    CHECK(e.text.find("ohne Fingerabdruck keine Verbindung") != std::string::npos);
    o.server_pins = {"sha256:kaputt"};
    CHECK(swbh::win::connect_pinned_ws(o, e) == nullptr);
    CHECK(e.kind == swbh::win::WsConnectError::Kind::Config);
    o.server_pins = {kPinA};
    CHECK(swbh::win::connect_pinned_ws(o, e) == nullptr);  // kein Client-Zertifikat
    CHECK(e.kind == swbh::win::WsConnectError::Kind::Config);
    o.url = "ws://127.0.0.1:1/kanal/v1";  // Klartext nur mit Testschalter
    CHECK(swbh::win::connect_pinned_ws(o, e) == nullptr);
    CHECK(e.kind == swbh::win::WsConnectError::Kind::Config);
}

TEST_CASE("Fernverbindung: Fristen je Pfad für die Antwort des eigenen Servers") {
    using std::chrono::seconds;
    CHECK(relay::receive_timeout_for("/v1/chat/completions") == seconds(1900));
    CHECK(relay::receive_timeout_for("/baumeister/v1/begleiter/gehoert?imspiel=Alex") == seconds(40));  // umlaut-ok
    CHECK(relay::receive_timeout_for("/baumeister/v1/begleiter/hoeren?spieler=Alex") == seconds(120));  // umlaut-ok
    CHECK(relay::receive_timeout_for("/baumeister/v1/begleiter/stimme") == seconds(120));
    CHECK(relay::receive_timeout_for("/health") == seconds(30));
    CHECK(relay::receive_timeout_for("/baumeister/v1/stadt") == seconds(30));
}
