// Einstellungen: Vorgaben (Autostart an), Klemmen, unlesbare Datei, Hin- und Rückweg.

#include "test_support.hpp"

#include "gui/app_config.hpp"

#include <algorithm>

using namespace swbh::gui;

TEST_CASE("Einstellungen: ohne Datei gelten die Vorgaben, Autostart an") {
    swbh::test::TempDir dir("cfg");
    const ConfigLoad l = load_config(dir / "config.local.json");
    CHECK_FALSE(l.error.has_value());
    CHECK(l.config.autostart);
    CHECK(l.config.server_port == 8080);
    CHECK(l.config.remote_port == 18080);
    CHECK(effective_model_key(l.config) == "qwen25-14b");
    CHECK(effective_plink(l.config) == "C:\\Program Files\\PuTTY\\plink.exe");
    CHECK(effective_target(l.config).empty());  // neue Installation: kein Server, kein fremdes Ziel
    CHECK(l.config.load_model_on_start);
    CHECK(l.notes.empty());
}

TEST_CASE("Einstellungen: ein unbekanntes Modell fällt auf die Vorgabe zurück") {
    // Ein Schlüssel, den der Katalog nicht (mehr) kennt – etwa nach einem Update –, darf die App nicht ohne Modell
    // starten lassen.
    swbh::test::TempDir dir("cfg");
    AppConfig c;
    c.model_key = "qwen25-gibt-es-nicht";
    save_config(dir / "config.local.json", c);
    const ConfigLoad l = load_config(dir / "config.local.json");
    CHECK(l.config.model_key.empty());
    CHECK(effective_model_key(l.config) == "qwen25-14b");
    CHECK(std::find(l.adjusted.begin(), l.adjusted.end(), "model") != l.adjusted.end());
}

TEST_CASE("Einstellungen: speichern und wieder lesen") {
    swbh::test::TempDir dir("cfg");
    AppConfig c;
    c.model_key = "qwen25-14b";
    c.server_port = 9000;
    c.compute = "cuda";
    c.autostart = false;
    c.hostkey = "SHA256:abc";
    save_config(dir / "config.local.json", c);
    const ConfigLoad l = load_config(dir / "config.local.json");
    CHECK(l.config.model_key == "qwen25-14b");
    CHECK(l.config.server_port == 9000);
    CHECK(l.config.compute == "cuda");
    CHECK_FALSE(l.config.autostart);
    CHECK(l.config.hostkey == "SHA256:abc");
}

TEST_CASE("Einstellungen: Werte außerhalb werden geklemmt, Kaputtes gemeldet") {
    swbh::test::TempDir dir("cfg");
    swbh::write_file_atomic(dir / "a.json", R"({"server_port":80,"compute":"opencl","model":"gibtsnicht"})");
    const ConfigLoad l = load_config(dir / "a.json");
    CHECK(l.config.server_port == 1024);
    CHECK(l.config.compute == "auto");
    CHECK(l.config.model_key.empty());
    CHECK(l.adjusted.size() == 3);
    swbh::write_file_atomic(dir / "b.json", "{kaputt");
    CHECK(load_config(dir / "b.json").error.has_value());
}

TEST_CASE("Einstellungen: Meldungen im Infobereich – Vorgabe an, abschaltbar, ältere Datei ohne Schlüssel bleibt an") {
    swbh::test::TempDir dir("cfg");
    CHECK(load_config(dir / "fehlt.json").config.notifications);
    AppConfig c;
    c.notifications = false;
    save_config(dir / "aus.json", c);
    CHECK_FALSE(load_config(dir / "aus.json").config.notifications);
    // config.local.json aus Runde 4 (ohne Schlüssel) – auf dem GPU-PC wird sie nicht ersetzt.
    swbh::write_file_atomic(dir / "alt.json", R"({"server_port":8080,"autostart":true})");
    CHECK(load_config(dir / "alt.json").config.notifications);
}

TEST_CASE("Einstellungen: Kontext – Vorgabe automatisch, alte Vorgabe 8192 wird automatisch, eigene Werte bleiben") {
    // Bis 2026-10-29 stand „n_ctx“: 8192 (die Vorgabe) in jeder gespeicherten Datei – mit ihr bliebe der GPU-PC des
    // Nutzers trotz neuem Katalog bei 8192.
    swbh::test::TempDir dir("cfg");
    CHECK(load_config(dir / "fehlt.json").config.n_ctx == 0);
    swbh::write_file_atomic(dir / "alt8192.json", R"({"n_ctx":8192,"autostart":true})");
    CHECK(load_config(dir / "alt8192.json").config.n_ctx == 0);
    swbh::write_file_atomic(dir / "alt16k.json", R"({"n_ctx":16384})");
    CHECK(load_config(dir / "alt16k.json").config.n_ctx == 16384);
    swbh::write_file_atomic(dir / "neu8192.json", R"({"kontext":8192,"n_ctx":4096})");
    CHECK(load_config(dir / "neu8192.json").config.n_ctx == 8192);
    swbh::write_file_atomic(dir / "klein.json", R"({"kontext":1000})");
    const ConfigLoad klein = load_config(dir / "klein.json");
    CHECK(klein.config.n_ctx == 2048);
    CHECK(klein.adjusted.size() == 1);
    AppConfig c;
    c.n_ctx = 8192;  // bewusst 8192 (Turbo erzwingen) – muss als 8192 zurückkommen, nicht als automatisch
    save_config(dir / "rund.json", c);
    CHECK(load_config(dir / "rund.json").config.n_ctx == 8192);
}

TEST_CASE("Einstellungen: lokaler Minecraft-Server – Vorgabe an, auch in älteren Dateien, abschaltbar") {
    swbh::test::TempDir dir("cfg");
    CHECK(load_config(dir / "fehlt.json").config.local_server);
    swbh::write_file_atomic(dir / "alt.json", R"({"server_port":8080,"remote_port":18080})");
    CHECK(load_config(dir / "alt.json").config.local_server);
    AppConfig c;
    c.local_server = false;
    save_config(dir / "aus.json", c);
    CHECK_FALSE(load_config(dir / "aus.json").config.local_server);
}

TEST_CASE("Einstellungen: Internet-Recherche – Vorgabe an, auch in älteren Dateien, abschaltbar") {
    swbh::test::TempDir dir("cfg");
    CHECK(load_config(dir / "fehlt.json").config.research);
    swbh::write_file_atomic(dir / "alt.json", R"({"server_port":8080,"remote_port":18080})");
    CHECK(load_config(dir / "alt.json").config.research);
    AppConfig c;
    c.research = false;
    save_config(dir / "aus.json", c);
    CHECK_FALSE(load_config(dir / "aus.json").config.research);
}

TEST_CASE("Einstellungen: alte Datei mit leerem Ziel behält dev01, damit der Tunnel nach dem Update weiterläuft") {
    swbh::test::TempDir dir("cfg-alt");
    swbh::write_file_atomic(dir / "config.local.json", R"({"target":"","tunnel_enabled":true,"autostart":true})");
    const ConfigLoad l = load_config(dir / "config.local.json");
    CHECK(l.config.target == "root@192.168.178.47");
    REQUIRE(l.notes.size() == 1);
    CHECK(l.notes.front().find("root@192.168.178.47") != std::string::npos);
    CHECK(l.adjusted.empty());  // keine Warnung „außerhalb des Bereichs“
}

TEST_CASE("Einstellungen: neue Datei mit leerem Ziel bleibt ohne Server, auch nach Speichern und Laden") {
    swbh::test::TempDir dir("cfg-neu");
    AppConfig c;
    c.load_model_on_start = false;
    save_config(dir / "config.local.json", c);
    const ConfigLoad l = load_config(dir / "config.local.json");
    CHECK(l.config.target.empty());
    CHECK(l.notes.empty());
    CHECK_FALSE(l.config.load_model_on_start);
}

TEST_CASE("Einstellungen: eigenes Ziel einer alten Datei bleibt unverändert") {
    swbh::test::TempDir dir("cfg-eigen");
    swbh::write_file_atomic(dir / "config.local.json", R"({"target":"mc@server.example.org"})");
    const ConfigLoad l = load_config(dir / "config.local.json");
    CHECK(l.config.target == "mc@server.example.org");
    CHECK(l.notes.empty());
}

TEST_CASE("Einstellungen: Server-Adresse und Benutzer werden getrennt bearbeitet") {
    CHECK(target_user("mc@server.example.org") == "mc");
    CHECK(target_host("mc@server.example.org") == "server.example.org");
    CHECK(target_user("192.168.1.20").empty());
    CHECK(target_host("192.168.1.20") == "192.168.1.20");
    CHECK(target_host("").empty());
    CHECK(make_target("mc", "server.example.org") == "mc@server.example.org");
    CHECK(make_target("", "192.168.1.20") == "192.168.1.20");  // fällt bei der Tunnelprüfung mit Grund auf
    CHECK(make_target("mc", "").empty());                     // keine Adresse = kein Server
}

TEST_CASE("Einstellungen: Tonlage ist Cartoon, auch für alte Dateien; Unbekanntes wird geklemmt") {
    swbh::test::TempDir dir("cfg");
    CHECK(load_config(dir / "neu.json").config.voice_style == "cartoon");
    AppConfig c;
    c.voice_style = "extra";
    save_config(dir / "a.json", c);
    CHECK(load_config(dir / "a.json").config.voice_style == "extra");
    c.voice_style = "quietsch";
    save_config(dir / "b.json", c);
    const ConfigLoad l = load_config(dir / "b.json");
    CHECK(l.config.voice_style == "cartoon");
    CHECK(std::find(l.adjusted.begin(), l.adjusted.end(), "tonlage") != l.adjusted.end());
}

TEST_CASE("Einstellungen v3: Datei aus Version 2 behält ihre Verbindungsart, die Fernverbindung ist neu und aus") {
    swbh::test::TempDir dir("cfg-v2");
    swbh::write_file_atomic(dir / "ssh.json", R"({"version":2,"target":"mc@server.example.org","tunnel_enabled":true})");
    const ConfigLoad ssh = load_config(dir / "ssh.json");
    CHECK(ssh.config.tunnel_enabled);
    CHECK_FALSE(ssh.config.relay_enabled);
    CHECK(ssh.config.relay_url == swbh::relay::kDefaultRelayUrl);
    CHECK(ssh.config.relay_pins.empty());
    CHECK(ssh.adjusted.empty());
    REQUIRE(ssh.notes.size() == 1);
    CHECK(ssh.notes.front().find("SSH-Tunnel") != std::string::npos);
    swbh::write_file_atomic(dir / "lokal.json", R"({"version":2,"tunnel_enabled":false})");
    const ConfigLoad local = load_config(dir / "lokal.json");
    CHECK_FALSE(local.config.tunnel_enabled);
    CHECK_FALSE(local.config.relay_enabled);
    REQUIRE(local.notes.size() == 1);
    CHECK(local.notes.front().find("Server auf diesem PC") != std::string::npos);
    // Gespeichert wird Version 3 mit „verbindung“ – danach keine Übernahme-Notiz mehr.
    save_config(dir / "neu.json", ssh.config);
    const std::string text = swbh::read_file(dir / "neu.json");
    CHECK(text.find(R"("version": 3)") != std::string::npos);
    CHECK(text.find(R"("verbindung": "ssh")") != std::string::npos);
    CHECK(load_config(dir / "neu.json").notes.empty());
}

TEST_CASE("Einstellungen v3: Fernverbindung hin und zurück, tunnel_enabled für alte App-Fassungen aus") {
    swbh::test::TempDir dir("cfg-v3");
    AppConfig c;
    c.relay_enabled = true;
    c.tunnel_enabled = false;
    c.relay_url = "wss://relay.example:25580/kanal/v1";
    c.relay_pins = {"sha256:" + std::string(64, 'a'), "sha256:" + std::string(64, 'b')};
    c.relay_client_pin = "sha256:" + std::string(64, 'c');
    save_config(dir / "c.json", c);
    const std::string text = swbh::read_file(dir / "c.json");
    CHECK(text.find(R"("verbindung": "https")") != std::string::npos);
    CHECK(text.find(R"("tunnel_enabled": false)") != std::string::npos);  // eine alte App startet keinen Tunnel
    const ConfigLoad l = load_config(dir / "c.json");
    CHECK(l.config.relay_enabled);
    CHECK_FALSE(l.config.tunnel_enabled);
    CHECK(l.config.relay_url == c.relay_url);
    CHECK(l.config.relay_pins == c.relay_pins);
    CHECK(l.config.relay_client_pin == c.relay_client_pin);
    CHECK(l.adjusted.empty());
    CHECK(l.notes.empty());
}

TEST_CASE("Einstellungen v3: kaputte Fernverbindungs-Felder werden verworfen statt geraten") {
    swbh::test::TempDir dir("cfg-v3-kaputt");
    const std::string a = "sha256:" + std::string(64, 'a');
    swbh::write_file_atomic(dir / "k.json",
                            R"({"version":3,"verbindung":"quantentunnel","tunnel_enabled":true,)"
                            R"("fern_adresse":"http://evil.example/kanal/v1",)"
                            R"("fern_server_pins":[")" + a + R"(","sha256:zz",")" + a + R"(","SHA256:)" +
                                std::string(64, 'B') + R"(","sha256:)" + std::string(64, 'c') + R"("],)"
                            R"("fern_eigener_pin":"kein-pin"})");
    const ConfigLoad l = load_config(dir / "k.json");
    CHECK_FALSE(l.config.relay_enabled);  // unbekannte Verbindungsart: nie still die Fernverbindung
    CHECK(l.config.tunnel_enabled);
    CHECK(l.config.relay_url == swbh::relay::kDefaultRelayUrl);
    // Gültige Pins klein, ohne Doppelte, höchstens zwei – kaputte und überzählige fallen weg.
    REQUIRE(l.config.relay_pins.size() == 2);
    CHECK(l.config.relay_pins[0] == a);
    CHECK(l.config.relay_pins[1] == "sha256:" + std::string(64, 'b'));
    CHECK(l.config.relay_client_pin.empty());
    for (const char* key : {"verbindung", "fern_adresse", "fern_server_pins", "fern_eigener_pin"}) {
        CAPTURE(key);
        CHECK(std::find(l.adjusted.begin(), l.adjusted.end(), key) != l.adjusted.end());
    }
}

TEST_CASE("Einstellungen v3: „verbindung“ entscheidet, auch wenn tunnel_enabled von Hand widerspricht") {
    swbh::test::TempDir dir("cfg-v3-wider");
    swbh::write_file_atomic(dir / "w.json", R"({"version":3,"verbindung":"https","tunnel_enabled":true})");
    const ConfigLoad l = load_config(dir / "w.json");
    CHECK(l.config.relay_enabled);
    CHECK_FALSE(l.config.tunnel_enabled);  // nie beide an
}