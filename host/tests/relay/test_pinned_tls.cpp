// Gepinnte TLS-Verbindung (win::connect_pinned_ws) gegen die JSSE-Gegenstelle der Messprobe (probes/winhttp_pin/
// RelayProbe.java, TLS 1.3/1.2, Client-Zertifikat Pflicht) – der PRODUKTIVE Weg mit Pins und eigenem Zertifikat.
//
// Label „integration“ (läuft nicht in build.ps1 -Tests): braucht ein JDK (JAVA_HOME mit java und keytool) und legt
// einen kurzlebigen Software-Schlüssel samt Zertifikat in einem eigenen Testspeicher an (am Ende gelöscht). Kein Netz:
// alles an 127.0.0.1. Ohne JDK meldet der Test das und prüft nichts.
//   ctest --test-dir <bau> -L integration -R swbh_test_relay_tls --output-on-failure

#include "test_support.hpp"

#include "base/utf8.hpp"
#include "relay/kanal.hpp"
#include "win/cert_pin.hpp"
#include "win/client_cert.hpp"
#include "win/pinned_ws.hpp"

#include <windows.h>

#include <chrono>
#include <cstdlib>
#include <optional>
#include <span>
#include <string>
#include <vector>

namespace {

std::optional<std::filesystem::path> jdk_tool(const wchar_t* name) {
    wchar_t home[MAX_PATH]{};
    if (GetEnvironmentVariableW(L"JAVA_HOME", home, MAX_PATH) == 0) {
        return std::nullopt;
    }
    const std::filesystem::path p = std::filesystem::path(home) / L"bin" / name;
    std::error_code ec;
    return std::filesystem::is_regular_file(p, ec) ? std::optional(p) : std::nullopt;
}

std::wstring quote(const std::wstring& s) { return L"\"" + s + L"\""; }

// Startet einen Prozess mit umgeleiteter Ausgabe (stdout+stderr in eine Pipe).
class Child final {
public:
    explicit Child(std::wstring cmd) {
        SECURITY_ATTRIBUTES sa{sizeof sa, nullptr, TRUE};
        HANDLE write_end = nullptr;
        REQUIRE(CreatePipe(&read_, &write_end, &sa, 0));
        SetHandleInformation(read_, HANDLE_FLAG_INHERIT, 0);
        STARTUPINFOW si{};
        si.cb = sizeof si;
        si.dwFlags = STARTF_USESTDHANDLES;
        si.hStdOutput = write_end;
        si.hStdError = write_end;
        PROCESS_INFORMATION pi{};
        const BOOL ok = CreateProcessW(nullptr, cmd.data(), nullptr, nullptr, TRUE, CREATE_NO_WINDOW, nullptr, nullptr,
                                       &si, &pi);
        CloseHandle(write_end);
        REQUIRE(ok);
        CloseHandle(pi.hThread);
        process_ = pi.hProcess;
    }
    ~Child() {
        if (process_ != nullptr) {
            if (WaitForSingleObject(process_, 0) == WAIT_TIMEOUT) {
                TerminateProcess(process_, 1);
            }
            CloseHandle(process_);
        }
        if (read_ != nullptr) {
            CloseHandle(read_);
        }
    }
    Child(const Child&) = delete;
    Child& operator=(const Child&) = delete;

    // Eine Zeile (ohne CR/LF); leer bei EOF.
    std::string line() {
        std::string out;
        char c = 0;
        DWORD n = 0;
        while (ReadFile(read_, &c, 1, &n, nullptr) && n == 1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                out.push_back(c);
            }
        }
        return out;
    }
    // Rest der Ausgabe bis zum Prozessende.
    std::string rest() {
        std::string out;
        char buf[4096];
        DWORD n = 0;
        while (ReadFile(read_, buf, sizeof buf, &n, nullptr) && n > 0) {
            out.append(buf, n);
        }
        WaitForSingleObject(process_, 30000);
        return out;
    }
    DWORD wait() {
        WaitForSingleObject(process_, 60000);
        DWORD code = 1;
        GetExitCodeProcess(process_, &code);
        return code;
    }

private:
    HANDLE read_ = nullptr;
    HANDLE process_ = nullptr;
};

struct Keys {
    std::filesystem::path server_p12;
    std::filesystem::path fremd_p12;
    std::string pass = "probe-" + std::to_string(GetCurrentProcessId());
    std::string fremd_pin;
};

Keys make_keys(const swbh::test::TempDir& dir, const std::filesystem::path& keytool) {
    Keys k;
    k.server_p12 = dir / "server.p12";
    k.fremd_p12 = dir / "fremd.p12";
    for (const auto& [p12, cn] : {std::pair{k.server_p12, L"relay-test"}, std::pair{k.fremd_p12, L"relay-fremd"}}) {
        Child kt(quote(keytool.wstring()) + L" -genkeypair -alias relay -keyalg EC -groupname secp256r1 -sigalg "
                                            L"SHA256withECDSA -dname CN=" + cn + L" -validity 2 -keystore " +
                 quote(p12.wstring()) + L" -storetype PKCS12 -storepass " + swbh::widen(k.pass) + L" -keypass " +
                 swbh::widen(k.pass));
        kt.rest();
        REQUIRE(kt.wait() == 0);
    }
    const std::filesystem::path der = dir / "fremd.der";
    Child ex(quote(keytool.wstring()) + L" -exportcert -alias relay -keystore " + quote(k.fremd_p12.wstring()) +
             L" -storepass " + swbh::widen(k.pass) + L" -file " + quote(der.wstring()));
    ex.rest();
    REQUIRE(ex.wait() == 0);
    const std::string bytes = swbh::test::read_bytes(der);
    k.fremd_pin = swbh::win::pin_of_der(
        std::span<const std::uint8_t>(reinterpret_cast<const std::uint8_t*>(bytes.data()), bytes.size()));
    return k;
}

struct Server {
    std::unique_ptr<Child> child;
    int port = 0;
    std::string pin;
};

Server start_server(const std::filesystem::path& java, const Keys& k, const char* tls, const char* scenario) {
    Server s;
    s.child = std::make_unique<Child>(
        quote(java.wstring()) + L" -Dstdout.encoding=UTF-8 " +
        quote(swbh::test::source_path("probes/winhttp_pin/RelayProbe.java").wstring()) + L" " +
        quote(k.server_p12.wstring()) + L" " + swbh::widen(k.pass) + L" " + swbh::widen(tls) + L" " +
        swbh::widen(scenario));
    const std::string first = s.child->line();
    REQUIRE_MESSAGE(first.starts_with("bereit port="), first);
    const std::size_t pin_at = first.find("server_pin=");
    REQUIRE(pin_at != std::string::npos);
    s.port = std::stoi(first.substr(12, first.find(' ', 12) - 12));
    s.pin = first.substr(pin_at + 11);
    return s;
}

swbh::win::PinnedWsOptions options(int port, std::vector<std::string> pins, const swbh::win::ClientCert& cert) {
    swbh::win::PinnedWsOptions o;
    o.url = "wss://127.0.0.1:" + std::to_string(port) + "/kanal/v1";
    o.server_pins = std::move(pins);
    o.client_cert = cert.context();
    o.protocol = std::string(swbh::relay::kProtocol);
    o.user_agent = "BaumeisterHost-Test";
    return o;
}

swbh::win::ClientCertOptions cert_options() {
    swbh::win::ClientCertOptions o;
    o.store = L"BaumeisterHostTest";
    o.name_prefix = "BaumeisterHostTest-";
    o.allow_tpm = false;
    return o;
}

}  // namespace

TEST_CASE("Gepinnte Verbindung (TLS gegen JSSE): Pins, eigenes Zertifikat, falscher Pin bricht vor dem ersten Byte ab") {
    const auto java = jdk_tool(L"java.exe");
    const auto keytool = jdk_tool(L"keytool.exe");
    if (!java || !keytool) {
        MESSAGE("JAVA_HOME mit java/keytool fehlt – Test übersprungen");
        return;
    }
    swbh::test::TempDir dir("pinned-tls");
    const Keys keys = make_keys(dir, *keytool);
    swbh::win::ClientCert cert = swbh::win::create_client_cert(cert_options());
    struct Cleanup {
        std::string pin;
        ~Cleanup() { swbh::win::delete_client_cert(pin, cert_options()); }
    } cleanup{cert.pin()};

    SUBCASE("richtiger Pin: TLS 1.3, Client-Zertifikat kommt an, Nachricht hin und zurück") {
        Server s = start_server(*java, keys, "beide", "echo");
        swbh::win::WsConnectError e;
        auto ws = swbh::win::connect_pinned_ws(options(s.port, {s.pin}, cert), e);
        REQUIRE_MESSAGE(ws != nullptr, e.text);
        CHECK(ws->send_binary("hallo kanal"));
        const swbh::win::WsMessage back = ws->receive();
        CHECK(back.kind == swbh::win::WsMessage::Kind::Binary);
        CHECK(back.data == "hallo kanal");
        ws->close(1000, "");
        const std::string log = s.child->rest();
        CHECK(log.find("client_pin=" + cert.pin()) != std::string::npos);
        CHECK(log.find("client_kette=1") != std::string::npos);
        CHECK(log.find("tls=TLSv1.3") != std::string::npos);
    }
    SUBCASE("zweiter Pin für den Schlüsselwechsel des Relays") {
        Server s = start_server(*java, keys, "beide", "echo");
        swbh::win::WsConnectError e;
        auto ws = swbh::win::connect_pinned_ws(options(s.port, {keys.fremd_pin, s.pin}, cert), e);
        REQUIRE_MESSAGE(ws != nullptr, e.text);
        ws->close(1000, "");
        s.child->rest();
    }
    SUBCASE("falscher Pin: Abbruch im Handshake-Rückruf, die Gegenstelle bekommt 0 HTTP-Bytes") {
        Server s = start_server(*java, keys, "beide", "echo");
        swbh::win::WsConnectError e;
        auto ws = swbh::win::connect_pinned_ws(options(s.port, {keys.fremd_pin}, cert), e);
        CHECK(ws == nullptr);
        if (ws) {
            ws->close(1000, "");  // nur bei Fehlschlag: sonst hielten die Keepalives die Gegenstelle ewig offen
        }
        CHECK(e.kind == swbh::win::WsConnectError::Kind::PinMismatch);
        CHECK(e.found_pin == s.pin);
        const std::string log = s.child->rest();
        CAPTURE(log);
        CHECK(log.find("http_bytes=0") != std::string::npos);
        CHECK(log.find("upgrade=101") == std::string::npos);
    }
    SUBCASE("ohne Pin: keine Verbindung, obwohl Zertifikat und Gegenstelle bereitstehen") {
        Server s = start_server(*java, keys, "beide", "echo");
        swbh::win::WsConnectError e;
        auto ws = swbh::win::connect_pinned_ws(options(s.port, {}, cert), e);
        CHECK(ws == nullptr);
        CHECK(e.kind == swbh::win::WsConnectError::Kind::Config);
    }
    SUBCASE("nur TLS 1.2 beim Relay (wie Windows 10 auf der anderen Seite)") {
        Server s = start_server(*java, keys, "12", "echo");
        swbh::win::WsConnectError e;
        auto ws = swbh::win::connect_pinned_ws(options(s.port, {s.pin}, cert), e);
        REQUIRE_MESSAGE(ws != nullptr, e.text);
        ws->close(1000, "");
        CHECK(s.child->rest().find("tls=TLSv1.2") != std::string::npos);
    }
    SUBCASE("Relay kennt den eigenen Pin nicht: abgewiesen, als solches gemeldet") {
        Server s = start_server(*java, keys, "beide", "ablehnen");
        swbh::win::WsConnectError e;
        auto ws = swbh::win::connect_pinned_ws(options(s.port, {s.pin}, cert), e);
        CHECK(ws == nullptr);
        CHECK(e.kind == swbh::win::WsConnectError::Kind::Rejected);
        CHECK(e.text.find("eigene Fingerabdruck") != std::string::npos);
        s.child->rest();
    }
}
