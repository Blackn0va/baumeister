// plink-Kommandozeile, Prüfung der Einstellungen, Auswertung der plink-Ausgabe, Wartezeiten.

#include "test_support.hpp"

#include "tunnel/tunnel.hpp"

#include <condition_variable>
#include <mutex>
#include <vector>

using namespace swbh::tunnel;

namespace {

TunnelConfig config_with(const std::filesystem::path& plink) {
    TunnelConfig c;
    c.plink = plink;
    c.target = "root@192.168.178.47";  // seit 2026-11-01 kein Vorgabeziel mehr
    return c;
}

}  // namespace

TEST_CASE("Tunnel: Kommandozeile wie start-llm.ps1 plus -v") {
    TunnelConfig c = config_with(L"C:\\Program Files\\PuTTY\\plink.exe");
    CHECK(command_line(c) ==
          L"\"C:\\Program Files\\PuTTY\\plink.exe\" -batch -v -N -R 127.0.0.1:18080:127.0.0.1:8080 root@192.168.178.47");
    c.local_port = 9090;
    c.remote_port = 18081;
    c.ssh_port = 2222;
    c.hostkey = "SHA256:abc+/=";
    CHECK(command_line(c) == L"\"C:\\Program Files\\PuTTY\\plink.exe\" -batch -v -N -R "
                             L"127.0.0.1:18081:127.0.0.1:9090 -P 2222 -hostkey SHA256:abc+/= root@192.168.178.47");
}

TEST_CASE("Tunnel: Passwort geht über -pwfile, nie über die Befehlszeile") {
    TunnelConfig c = config_with(L"C:\\Program Files\\PuTTY\\plink.exe");
    c.password = "geheim-123";
    const std::wstring cmd = command_line(c, L"C:\\daten\\tmp\\plink-1.tmp");
    CHECK(cmd.find(L"-pwfile \"C:\\daten\\tmp\\plink-1.tmp\"") != std::wstring::npos);
    CHECK(cmd.find(L"geheim") == std::wstring::npos);
    CHECK(cmd.find(L" -pw ") == std::wstring::npos);
    CHECK(command_line(c).find(L"-pwfile") == std::wstring::npos);  // ohne Datei: Pageant
}

TEST_CASE("Tunnel: Einstellungen lassen keine zusätzlichen Schalter zu") {
    swbh::test::TempDir dir("tunnel");
    const auto plink = dir / "plink.exe";
    swbh::write_file_atomic(plink, "MZ");
    TunnelConfig c = config_with(plink);
    CHECK_FALSE(validate(c).has_value());

    c.target = "root@192.168.178.47 -L 1:2:3";
    CHECK(validate(c).has_value());
    c.target = "-oProxyCommand=calc@x";
    CHECK(validate(c).has_value());
    c.target = "root@-x";
    CHECK(validate(c).has_value());
    c.target = "root\"@x";
    CHECK(validate(c).has_value());
    c.target = "rootx";
    CHECK(validate(c).has_value());
    c.target = "root@dev01.lan";
    CHECK_FALSE(validate(c).has_value());

    c.password = "zwei\nzeilen";
    c.secret_dir = dir.path();
    CHECK(validate(c).has_value());
    c.password = "gut";
    c.secret_dir.clear();
    CHECK(validate(c).has_value());
    c.secret_dir = dir.path();
    CHECK_FALSE(validate(c).has_value());
    c.password.clear();

    c.hostkey = "SHA256:x y";
    CHECK(validate(c).has_value());
    c.hostkey.clear();
    c.remote_port = 70000;
    CHECK(validate(c).has_value());
    c.remote_port = 18080;

    c.plink = L"plink.exe";  // relativ: würde über PATH gesucht
    CHECK(validate(c).has_value());
    c.plink = dir / "putty.exe";
    CHECK(validate(c).has_value());
    c.plink = dir / "fehlt" / "plink.exe";
    CHECK(validate(c).has_value());
}

TEST_CASE("Tunnel: plink-Ausgabe wird richtig gedeutet") {
    CHECK(classify_line("Remote port forwarding from 127.0.0.1:18080 enabled") == Signal::Forwarding);
    CHECK(classify_line("Remote port forwarding from 127.0.0.1:18080 refused") == Signal::ForwardRefused);
    CHECK(classify_line("Pageant has 0 SSH-2 keys") == Signal::PageantNoKeys);
    CHECK(classify_line("FATAL ERROR: No supported authentication methods available (server sent: publickey)") ==
          Signal::AuthFailed);
    CHECK(classify_line("Server refused our key") == Signal::AuthFailed);
    // Wörtlich vom GPU-PC (2026-10-28), Pageant ohne dev01-Schlüssel:
    CHECK(classify_line("FATAL ERROR: Cannot answer interactive prompts in batch mode") == Signal::AuthFailed);
    CHECK(classify_line("The host key is not cached for this server:") == Signal::HostKeyUnknown);
    CHECK(classify_line("FATAL ERROR: Cannot confirm a host key in batch mode") == Signal::HostKeyUnknown);
    CHECK(classify_line("FATAL ERROR: Network error: Connection timed out") == Signal::Unreachable);
    CHECK(classify_line("Pageant is running. Requesting keys.") == Signal::None);
    CHECK(classify_line("Pageant has 1 SSH-2 keys") == Signal::None);
    CHECK_FALSE(signal_text(Signal::PageantNoKeys).empty());
}

TEST_CASE("Tunnel: Wartezeit 10, 20, 40, 60, 60 s") {
    CHECK(backoff(1).count() == 10);
    CHECK(backoff(2).count() == 20);
    CHECK(backoff(3).count() == 40);
    CHECK(backoff(4).count() == 60);
    CHECK(backoff(50).count() == 60);
}

TEST_CASE("Tunnel: unbekannte plink-Meldung steht in der Statuszeile statt nur „Exit 1“") {
    // Vorher ging plinks Grund verloren (GPU-PC 2026-10-28: „plink beendet (Exit 1)“ ohne Text). Messprobe ohne Netz:
    // where.exe als plink.exe – endet mit Exit 2 und einer Meldung, die classify_line nicht kennt.
    swbh::test::TempDir dir("tunnel-fake");
    std::filesystem::path sys(L"C:\\Windows\\System32\\where.exe");
    const auto fake = dir / "plink.exe";
    std::filesystem::copy_file(sys, fake);
    std::mutex m;
    std::condition_variable cv;
    std::vector<Tunnel::Event> events;
    Tunnel t([&](const Tunnel::Event& e) {
        std::lock_guard<std::mutex> lock(m);
        events.push_back(e);
        cv.notify_all();
    });
    std::string error;
    REQUIRE(t.start(config_with(fake), error));
    {
        std::unique_lock<std::mutex> lock(m);
        REQUIRE(cv.wait_for(lock, std::chrono::seconds(20), [&] {
            return !events.empty() && events.back().state == State::Waiting;
        }));
    }
    t.stop();
    std::string waiting;
    for (const auto& e : events) {
        if (e.state == State::Waiting) {
            waiting = e.text;
        }
    }
    MESSAGE(waiting);
    CHECK(waiting.find("Exit 2): ") != std::string::npos);
    CHECK(waiting.size() > std::string("plink beendet (Exit 2): – neuer Versuch in 10 s").size());
}

TEST_CASE("Tunnel: Passwortdatei ist nach dem plink-Versuch gelöscht") {
    // Messprobe ohne Netz wie oben: where.exe als plink.exe liest -pwfile nicht, schreibt eine Zeile und endet.
    swbh::test::TempDir dir("tunnel-pw");
    const auto fake = dir / "plink.exe";
    std::filesystem::copy_file(std::filesystem::path(L"C:\\Windows\\System32\\where.exe"), fake);
    const auto secrets = dir / "geheim";
    std::mutex m;
    std::condition_variable cv;
    bool waiting = false;
    Tunnel t([&](const Tunnel::Event& e) {
        std::lock_guard<std::mutex> lock(m);
        waiting = waiting || e.state == State::Waiting;
        cv.notify_all();
    });
    TunnelConfig c = config_with(fake);
    c.password = "geheim-123";
    c.secret_dir = secrets;
    std::string error;
    REQUIRE(t.start(c, error));
    {
        std::unique_lock<std::mutex> lock(m);
        REQUIRE(cv.wait_for(lock, std::chrono::seconds(20), [&] { return waiting; }));
    }
    // Prüfen, solange der Tunnel noch auf den nächsten Versuch wartet – nach stop() räumte ohnehin der Destruktor auf,
    // der Test sähe dann auch ohne das frühe Löschen einen leeren Ordner.
    std::error_code ec;
    REQUIRE(std::filesystem::is_directory(secrets, ec));  // angelegt …
    CHECK(std::filesystem::is_empty(secrets, ec));          // … und schon wieder leer
    t.stop();
}

TEST_CASE("Tunnel: falsche Einstellungen starten nichts") {
    std::vector<Tunnel::Event> events;
    Tunnel t([&](const Tunnel::Event& e) { events.push_back(e); });
    std::string error;
    CHECK_FALSE(t.start(config_with(L"C:\\gibt\\es\\nicht\\plink.exe"), error));
    CHECK(error.find("nicht gefunden") != std::string::npos);
    CHECK(t.state() == State::Failed);
}

TEST_CASE("Tunnel: ohne eingetragenen Server startet nichts, und der Grund sagt, was zu tun ist") {
    swbh::test::TempDir dir("tunnel-leer");
    const auto plink = dir / "plink.exe";
    swbh::write_file_atomic(plink, "MZ");
    TunnelConfig c;
    c.plink = plink;
    CHECK(c.target.empty());  // keine fremde Adresse als Vorgabe
    const auto why = validate(c);
    REQUIRE(why.has_value());
    CHECK(why->find("Kein Minecraft-Server eingetragen") != std::string::npos);
}

TEST_CASE("Tunnel: Pageant über die benannte Pipe zählt als laufend, nicht nur das Fenster") {
    // App-Log 2026-11-05 17:27: „Pageant läuft nicht“, eine Zeile später „verbunden“ (Schlüssel über die Pipe).
    CHECK(swbh::tunnel::is_pageant_pipe(L"pageant.black.0123456789abcdef"));
    CHECK(swbh::tunnel::is_pageant_pipe(L"Pageant.BLACK.x"));
    CHECK_FALSE(swbh::tunnel::is_pageant_pipe(L"pageant."));
    CHECK_FALSE(swbh::tunnel::is_pageant_pipe(L"openssh-ssh-agent"));
    CHECK_FALSE(swbh::tunnel::is_pageant_pipe(L"xpageant.black"));
    // Der Hinweis befiehlt nichts mehr, er sagt, dass plink es trotzdem versucht.
    CHECK(swbh::tunnel::kPageantNotSeen.find("trotzdem") != std::string_view::npos);
}
