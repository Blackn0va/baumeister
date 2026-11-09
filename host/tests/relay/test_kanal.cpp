// Kanal v1: Testvektoren aus docs/kanal-v1-testvektoren.json Byte für Byte – Rahmen über den strengen Test-Rahmenleser
// (ws_frame), Nachrichten über den echten relay::decode/encode, Pfade, Pin, WebSocket-Accept. Dazu Regeln, die die
// Vektoren nicht abdecken (doppelte Schlüssel, Richtung, Grenzen). Das Relay (Java) liest dieselbe Datei – was hier
// grün ist, versteht auch die andere Seite.

#include "test_support.hpp"

#include "ws_frame.hpp"

#include "base/encoding.hpp"
#include "base/json.hpp"
#include "relay/kanal.hpp"
#include "win/cert_pin.hpp"

#include <array>
#include <optional>
#include <string>
#include <vector>

namespace {

using swbh::Json;
namespace relay = swbh::relay;
using swbh::test::FrameDir;
using swbh::test::FrameParse;

// Die Vektoren liegen eine Ebene über host/ (docs/). Wird host/ allein kopiert (Bau auf dem GPU-PC), fehlen sie – dann
// gilt die Abschrift in tests/relay/data (wie beim Grammatik-Test). Mit docs/ hält „Abschrift aktuell“ beide gleich.
std::filesystem::path docs_vectors() { return swbh::test::source_path("../docs/kanal-v1-testvektoren.json"); }

std::filesystem::path copy_vectors() { return swbh::test::source_path("tests/relay/data/kanal-v1-testvektoren.json"); }

bool docs_present() {
    std::error_code ec;
    return std::filesystem::is_regular_file(docs_vectors(), ec);
}

const Json& vectors() {
    static const Json j = swbh::parse_utf8(swbh::test::read_bytes(docs_present() ? docs_vectors() : copy_vectors()));
    return j;
}

relay::Sender sender_of(const Json& v) {
    return v.at("richtung").get<std::string>() == "app->relay" ? relay::Sender::App : relay::Sender::Relay;
}

FrameDir dir_of(const Json& v) {
    return v.at("richtung").get<std::string>() == "app->relay" ? FrameDir::AppToRelay : FrameDir::RelayToApp;
}

std::optional<std::array<std::uint8_t, 4>> mask_of(const Json& v) {
    const Json* m = swbh::json_find(v, "maske");
    if (m == nullptr || m->is_null()) {
        return std::nullopt;
    }
    const std::string bytes = swbh::test::from_hex_string(m->get<std::string>());
    REQUIRE(bytes.size() == 4);
    std::array<std::uint8_t, 4> out{};
    for (std::size_t i = 0; i < 4; ++i) {
        out[i] = static_cast<std::uint8_t>(bytes[i]);
    }
    return out;
}

// Daten eines gültigen Vektors: daten_hex oder die Erzeugungsregel bytes(i % 251 …).
std::string data_of(const Json& v) {
    if (const Json* h = swbh::json_find(v, "daten_hex"); h != nullptr) {
        return swbh::test::from_hex_string(h->get<std::string>());
    }
    const std::size_t n = v.at("daten_laenge").get<std::size_t>();  // umlaut-ok: Schlüssel der Testvektoren
    std::string out(n, '\0');
    for (std::size_t i = 0; i < n; ++i) {
        out[i] = static_cast<char>(i % 251);
    }
    return out;
}

relay::Message message_of(const Json& kopf, std::string data) {
    relay::Message m;
    const std::string t = kopf.at("t").get<std::string>();
    m.type = t == "hallo"     ? relay::Type::Hallo
             : t == "anfrage" ? relay::Type::Anfrage
             : t == "antwort" ? relay::Type::Antwort
             : t == "daten"   ? relay::Type::Daten
                              : relay::Type::Abbruch;
    if (const Json* x = swbh::json_find(kopf, "id")) {
        m.id = x->get<std::uint32_t>();
    }
    if (const Json* x = swbh::json_find(kopf, "app")) {
        m.peer = x->get<std::string>();
    }
    if (const Json* x = swbh::json_find(kopf, "relay")) {
        m.peer = x->get<std::string>();
    }
    if (const Json* x = swbh::json_find(kopf, "max")) {
        m.max_jobs = x->get<int>();
    }
    if (const Json* x = swbh::json_find(kopf, "m")) {
        m.method = x->get<std::string>();
    }
    if (const Json* x = swbh::json_find(kopf, "p")) {
        m.path = x->get<std::string>();
    }
    if (const Json* x = swbh::json_find(kopf, "ct")) {
        m.content_type = x->get<std::string>();
    }
    if (const Json* x = swbh::json_find(kopf, "len")) {
        m.length = x->get<std::uint64_t>();
    }
    if (const Json* x = swbh::json_find(kopf, "s")) {
        m.status = x->get<int>();
    }
    m.data = std::move(data);
    return m;
}

void check_same(const relay::Message& got, const relay::Message& want) {
    CHECK(got.type == want.type);
    CHECK(got.id == want.id);
    CHECK(got.peer == want.peer);
    CHECK(got.max_jobs == want.max_jobs);
    CHECK(got.method == want.method);
    CHECK(got.path == want.path);
    CHECK(got.content_type == want.content_type);
    CHECK(got.length == want.length);
    CHECK(got.status == want.status);
    CHECK(got.data == want.data);
}

std::string u16(std::size_t n) {
    return std::string{static_cast<char>((n >> 8) & 0xFF), static_cast<char>(n & 0xFF)};
}

}  // namespace

TEST_CASE("Kanal v1: WebSocket-Accept aus RFC 6455 Abschnitt 1.3 stimmt") {
    const Json& w = vectors().at("websocket");
    CHECK(swbh::test::websocket_accept(w.at("key").get<std::string>()) == w.at("accept").get<std::string>());
    CHECK(w.at("protokoll").get<std::string>() == relay::kProtocol);
}

TEST_CASE("Kanal v1: Pin des Testzertifikats ist SHA-256 über die DER-Bytes, klein, mit sha256:") {
    const Json& p = vectors().at("pin");
    std::string pem = p.at("pem").get<std::string>();
    const std::string begin = "-----BEGIN CERTIFICATE-----";
    const std::string end = "-----END CERTIFICATE-----";
    pem = pem.substr(pem.find(begin) + begin.size());
    pem = pem.substr(0, pem.find(end));
    std::string b64;
    for (const char c : pem) {
        if (c != '\n' && c != '\r') {
            b64.push_back(c);
        }
    }
    const auto der = swbh::base64_decode(b64);
    REQUIRE(der.has_value());
    CHECK(swbh::win::pin_of_der(*der) == p.at("pin").get<std::string>());
}

TEST_CASE("Kanal v1: jeder gültige Testvektor liest sich Byte für Byte und schreibt sich gleich zurück") {
    for (const Json& v : vectors().at("gueltig")) {  // umlaut-ok: Schlüssel der Testvektoren
        const std::string name = v.at("name").get<std::string>();
        CAPTURE(name);
        const relay::Sender sender = sender_of(v);
        const std::string data = data_of(v);
        CHECK(swbh::test::sha256_hex_of(data) == v.at("daten_sha256").get<std::string>());
        const relay::Message want = message_of(v.at("kopf"), data);

        // Schreiben: kanonischer Kopf + Maske = Rahmen des Vektors (bei der Umlaut-Reihenfolge nur inhaltlich gleich).
        const std::string payload = relay::encode(want, sender);
        const std::string frame = swbh::test::encode_frame(0x2, payload, mask_of(v));
        std::string vector_frame;
        if (const Json* hex = swbh::json_find(v, "rahmen_hex")) {
            vector_frame = swbh::test::from_hex_string(hex->get<std::string>());
        } else {
            CHECK(frame.size() == v.at("rahmen_laenge").get<std::size_t>());  // umlaut-ok: Testvektoren
            CHECK(swbh::test::sha256_hex_of(frame) == v.at("rahmen_sha256").get<std::string>());
            vector_frame = frame;
        }
        const std::string head = payload.substr(2, payload.size() - 2 - data.size());
        const bool canonical_order = swbh::dump_compact(v.at("kopf")) == head;
        if (canonical_order) {
            CHECK(frame == vector_frame);
        }

        // Lesen: Rahmen des Vektors → Nutzlast → Nachricht.
        const FrameParse fp = swbh::test::parse_frame(vector_frame, dir_of(v));
        REQUIRE(fp.status == FrameParse::Status::Ok);
        CHECK(fp.consumed == vector_frame.size());
        const relay::Decoded d = relay::decode(fp.frame.payload, sender);
        REQUIRE_MESSAGE(d.message.has_value(), d.reason);
        check_same(*d.message, want);
    }
}

TEST_CASE("Kanal v1: jeder ungültige Testvektor wird mit genau seinem Grund abgelehnt") {
    int frame_level = 0;
    int message_level = 0;
    for (const Json& v : vectors().at("ungueltig")) {  // umlaut-ok: Schlüssel der Testvektoren
        const std::string name = v.at("name").get<std::string>();
        const std::string reason = v.at("grund").get<std::string>();
        CAPTURE(name);
        std::string frame;
        if (const Json* hex = swbh::json_find(v, "rahmen_hex")) {
            frame = swbh::test::from_hex_string(hex->get<std::string>());
        } else {
            // Regel: Kopf {"t":"daten","id":1} kompakt + 65537 Nullbytes, maskiert mit 37fa213d.
            const std::string head = R"({"t":"daten","id":1})";
            const std::string payload = u16(head.size()) + head + std::string(65537, '\0');
            frame = swbh::test::encode_frame(0x2, payload, std::array<std::uint8_t, 4>{0x37, 0xfa, 0x21, 0x3d});
            CHECK(frame.size() == v.at("rahmen_laenge").get<std::size_t>());  // umlaut-ok: Testvektoren
            CHECK(swbh::test::sha256_hex_of(frame) == v.at("rahmen_sha256").get<std::string>());
        }
        const FrameParse fp = swbh::test::parse_frame(frame, dir_of(v));
        if (fp.status == FrameParse::Status::Error) {
            CHECK(fp.reason == reason);
            ++frame_level;
            continue;
        }
        REQUIRE(fp.status == FrameParse::Status::Ok);
        const relay::Decoded d = relay::decode(fp.frame.payload, sender_of(v));
        CHECK_FALSE(d.message.has_value());
        CHECK(d.reason == reason);
        ++message_level;
    }
    CHECK(frame_level == 6);
    CHECK(message_level == static_cast<int>(vectors().at("ungueltig").size()) - 6);  // umlaut-ok: Testvektoren
}

TEST_CASE("Kanal v1: Pfade – die erlaubten gehen durch, jeder verbotene nicht") {
    const Json& p = vectors().at("pfade");
    for (const Json& ok : p.at("erlaubt")) {
        CAPTURE(ok.get<std::string>());
        CHECK(relay::path_allowed(ok.get<std::string>()));
    }
    for (const Json& bad : p.at("verboten")) {
        CAPTURE(bad.get<std::string>());
        CHECK_FALSE(relay::path_allowed(bad.get<std::string>()));
    }
}

TEST_CASE("Kanal v1: Abfragen nur mit gültigem %XX, nie %2e, nie leer, Pfad höchstens 2048 Zeichen") {
    CHECK(relay::path_allowed("/baumeister/v1/begleiter/gehoert?imspiel=J%C3%BCrgen"));  // umlaut-ok: URL
    CHECK(relay::path_allowed("/baumeister/v1/begleiter/hoeren?spieler=.Bedrock_Name"));  // umlaut-ok: URL
    CHECK(relay::path_allowed("/baumeister/v1/begleiter/gehoert?imspiel=Alex,Steve"));  // umlaut-ok: URL
    CHECK_FALSE(relay::path_allowed("/v1/x?a=%2e"));
    CHECK_FALSE(relay::path_allowed("/v1/x?a=%2E%2E"));
    CHECK_FALSE(relay::path_allowed("/v1/x?a=%zz"));
    CHECK_FALSE(relay::path_allowed("/v1/x?a=%4"));
    CHECK_FALSE(relay::path_allowed("/v1/x?"));
    CHECK_FALSE(relay::path_allowed("/v1/x?a=b?c"));
    CHECK_FALSE(relay::path_allowed("/v1/x?a=<b>"));
    CHECK_FALSE(relay::path_allowed("/health?x=1"));
    CHECK(relay::path_allowed("/v1/" + std::string(2044, 'a')));
    CHECK_FALSE(relay::path_allowed("/v1/" + std::string(2045, 'a')));
}

TEST_CASE("Kanal v1: Abschrift der Testvektoren in tests/relay/data ist aktuell") {
    if (!docs_present()) {
        MESSAGE("docs/ fehlt (host/ allein kopiert) – geprüft wird gegen die Abschrift in tests/relay/data");
        return;
    }
    CHECK_MESSAGE(swbh::test::read_bytes(docs_vectors()) == swbh::test::read_bytes(copy_vectors()),
                  "docs/kanal-v1-testvektoren.json geändert – Abschrift in host/tests/relay/data nachziehen");
}

namespace {

std::string payload_of(const std::string& head, const std::string& data = {}) { return u16(head.size()) + head + data; }

std::string reason_of(const std::string& head, relay::Sender from, const std::string& data = {}) {
    const relay::Decoded d = relay::decode(payload_of(head, data), from);
    return d.message ? std::string("ok") : d.reason;
}

}  // namespace

TEST_CASE("Kanal v1: doppelte Schlüssel und tiefe Schachtelung im Kopf werden abgelehnt") {
    // Zwei Parser wählen bei doppelten Schlüsseln verschieden (erster/letzter) – genau das darf nicht durchgehen.
    CHECK(reason_of(R"({"t":"abbruch","id":1,"id":2})", relay::Sender::Relay) == "kopf_kein_objekt");
    CHECK(reason_of(R"({"t":"daten","t":"abbruch","id":1})", relay::Sender::Relay) == "kopf_kein_objekt");
    // Unbekannte Felder werden überlesen – auch geschachtelt, aber nicht beliebig tief.
    CHECK(reason_of(R"({"t":"abbruch","id":1,"x":{"y":[1,{"z":2}]}})", relay::Sender::Relay) == "ok");
    CHECK(reason_of(R"({"t":"abbruch","id":1,"x":[[[[[[[[[1]]]]]]]]]})", relay::Sender::Relay) == "kopf_kein_objekt");
    CHECK(reason_of(R"({"t":"abbruch","id":1} x)", relay::Sender::Relay) == "kopf_kein_objekt");
    CHECK(reason_of(R"(  { "id" : 7 , "t" : "abbruch" }  )", relay::Sender::Relay) == "ok");
}

TEST_CASE("Kanal v1: Typen nur in ihrer Richtung – anfrage nie von der App, antwort nie vom Relay") {
    CHECK(reason_of(R"({"t":"anfrage","id":1,"m":"GET","p":"/health","len":0})", relay::Sender::App) == "typ_unbekannt");
    CHECK(reason_of(R"({"t":"antwort","id":1,"s":200,"len":0})", relay::Sender::Relay) == "typ_unbekannt");
    CHECK(reason_of(R"({"t":"anfrage","id":1,"m":"GET","p":"/health","len":0})", relay::Sender::Relay) == "ok");
    CHECK(reason_of(R"({"t":"hallo","v":1,"relay":"r"})", relay::Sender::Relay) == "ok");
    // Das hallo des Relays braucht die Kennung „relay“, das der App „app“ und max 1 … 6.
    CHECK(reason_of(R"({"t":"hallo","v":1,"app":"a","max":6})", relay::Sender::Relay) ==
          "kennung_ungueltig");  // umlaut-ok
    CHECK(reason_of(R"({"t":"hallo","v":1,"app":"a","max":7})", relay::Sender::App) == "max_ungueltig");  // umlaut-ok
    CHECK(reason_of(R"({"t":"hallo","v":1,"app":"a","max":0})", relay::Sender::App) == "max_ungueltig");  // umlaut-ok
}

TEST_CASE("Kanal v1: Grenzen – id, len, erstes Stück, Daten bei hallo und abbruch") {
    CHECK(reason_of(R"({"t":"abbruch","id":0})", relay::Sender::Relay) == "id_ungueltig");  // umlaut-ok
    CHECK(reason_of(R"({"t":"abbruch","id":4294967295})", relay::Sender::Relay) == "ok");
    CHECK(reason_of(R"({"t":"abbruch","id":1.0})", relay::Sender::Relay) == "id_ungueltig");  // umlaut-ok
    CHECK(reason_of(R"({"t":"abbruch","id":"1"})", relay::Sender::Relay) == "id_ungueltig");  // umlaut-ok
    CHECK(reason_of(R"({"t":"anfrage","id":1,"m":"POST","p":"/v1/a","len":2097152})", relay::Sender::Relay) == "ok");
    CHECK(reason_of(R"({"t":"anfrage","id":1,"m":"POST","p":"/v1/a","len":2097153})", relay::Sender::Relay) ==
          "laenge_ungueltig");  // umlaut-ok
    CHECK(reason_of(R"({"t":"antwort","id":1,"s":200,"len":16777217})", relay::Sender::App) ==
          "laenge_ungueltig");  // umlaut-ok
    CHECK(reason_of(R"({"t":"anfrage","id":1,"m":"POST","p":"/v1/a","len":2})", relay::Sender::Relay, "abc") ==
          "laenge_ungueltig");  // umlaut-ok: erstes Stück länger als len
    CHECK(reason_of(R"({"t":"abbruch","id":1})", relay::Sender::Relay, "x") == "stueck_zu_gross");  // umlaut-ok
    CHECK(reason_of(R"({"t":"hallo","v":1,"relay":"r"})", relay::Sender::Relay, "x") == "stueck_zu_gross");  // umlaut-ok
    CHECK(reason_of(R"({"t":"anfrage","id":1,"m":"GET","p":"/health","ct":"","len":0})", relay::Sender::Relay) ==
          "typ_ungueltig");  // umlaut-ok
    CHECK(reason_of(R"({"t":"anfrage","id":1,"m":"get","p":"/health","len":0})", relay::Sender::Relay) ==
          "methode_ungueltig");  // umlaut-ok
    CHECK(reason_of(R"({"t":"daten","id":1})", relay::Sender::Relay, std::string(65536, 'x')) == "ok");
    CHECK(relay::decode(std::string_view("\x00", 1), relay::Sender::Relay).reason == "kopf_abgeschnitten");
}

TEST_CASE("Kanal v1: Close-Codes – Version 1008, Textrahmen 1003, zu groß 1009, sonst 1002") {
    CHECK(reason_of(R"({"t":"hallo","v":2,"relay":"r"})", relay::Sender::Relay) == "version_ungueltig");  // umlaut-ok
    CHECK(reason_of(R"({"t":"hallo","relay":"r"})", relay::Sender::Relay) == "version_ungueltig");  // umlaut-ok
    CHECK(relay::close_code("version_ungueltig") == 1008);  // umlaut-ok
    CHECK(relay::close_code("textrahmen") == 1003);
    CHECK(relay::close_code("stueck_zu_gross") == 1009);  // umlaut-ok
    CHECK(relay::close_code("rahmen_zu_gross") == 1009);  // umlaut-ok
    CHECK(relay::close_code("pfad_ungueltig") == 1002);  // umlaut-ok
}

TEST_CASE("Kanal v1: encode lehnt eigene Regelverstöße ab, statt sie zu senden") {
    relay::Message m;
    m.type = relay::Type::Antwort;
    m.id = 1;
    m.status = 200;
    m.length = 1;
    m.data = "zu lang";
    CHECK_THROWS_AS(relay::encode(m, relay::Sender::App), std::invalid_argument);
    m.data = "x";
    m.content_type = "text/plain\r\nX: y";
    CHECK_THROWS_AS(relay::encode(m, relay::Sender::App), std::invalid_argument);
    m.content_type.reset();
    CHECK_THROWS_AS(relay::encode(m, relay::Sender::Relay), std::invalid_argument);  // antwort nie vom Relay
    m.id = 0;
    CHECK_THROWS_AS(relay::encode(m, relay::Sender::App), std::invalid_argument);
    relay::Message d;
    d.type = relay::Type::Daten;
    d.id = 1;
    d.data = std::string(65537, 'x');
    CHECK_THROWS_AS(relay::encode(d, relay::Sender::App), std::invalid_argument);
}

TEST_CASE("Pin: Eingaben aus den Einstellungen werden geprüft und klein geschrieben") {
    const std::string hex(64, 'a');
    CHECK(swbh::win::parse_pin("sha256:" + hex) == "sha256:" + hex);
    CHECK(swbh::win::parse_pin("  SHA256:" + std::string(64, 'A') + " \n") == "sha256:" + hex);
    CHECK_FALSE(swbh::win::parse_pin("sha256:" + std::string(63, 'a')).has_value());
    CHECK_FALSE(swbh::win::parse_pin("sha256:" + std::string(63, 'a') + "g").has_value());
    CHECK_FALSE(swbh::win::parse_pin("sha1:" + hex).has_value());
    CHECK_FALSE(swbh::win::parse_pin(hex).has_value());
    CHECK_FALSE(swbh::win::parse_pin("sha256: " + hex.substr(1)).has_value());
    CHECK(swbh::win::pin_listed("sha256:" + hex, {"sha256:" + std::string(64, 'b'), "sha256:" + hex}));
    CHECK_FALSE(swbh::win::pin_listed("sha256:" + hex, {"sha256:" + std::string(64, 'b')}));
    CHECK_FALSE(swbh::win::pin_listed("sha256:" + hex, {}));
}
