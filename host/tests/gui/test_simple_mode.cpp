// Einfache Bedienung: Server-Ort und Start-Modus ↔ Konfiguration, Satz zum aktuellen Stand.

#include "test_support.hpp"

#include "gui/simple_mode.hpp"

#include <string>
#include <vector>

using namespace swbh::gui;

TEST_CASE("Server-Ort: dieser PC heißt kein Tunnel, aber lauschen auf dem Plugin-Port") {
    AppConfig c;
    c.local_server = false;  // alte Datei mit abgeschaltetem lokalem Lauscher
    apply_server_place(c, ServerPlace::ThisPc);
    CHECK_FALSE(c.tunnel_enabled);
    CHECK(c.local_server);  // sonst erreichte der Server auf diesem PC die App nie
    CHECK(server_place(c) == ServerPlace::ThisPc);
    apply_server_place(c, ServerPlace::Remote);
    CHECK(c.tunnel_enabled);
    CHECK(server_place(c) == ServerPlace::Remote);
}

TEST_CASE("Start-Modus: drei Wahlmöglichkeiten statt zweier abhängiger Häkchen, Hin- und Rückweg") {
    AppConfig c;
    CHECK(start_mode(c) == StartMode::ConnectWithAi);  // Vorgabe wie bisher
    apply_start_mode(c, StartMode::ConnectOnly);
    CHECK(c.autostart);
    CHECK_FALSE(c.load_model_on_start);
    CHECK(start_mode(c) == StartMode::ConnectOnly);
    apply_start_mode(c, StartMode::Nothing);
    CHECK_FALSE(c.autostart);
    CHECK(start_mode(c) == StartMode::Nothing);
    CHECK_FALSE(c.load_model_on_start);  // alte Wahl bleibt für später
    apply_start_mode(c, StartMode::ConnectWithAi);
    CHECK(start_mode(c) == StartMode::ConnectWithAi);
}

TEST_CASE("Statuszeile: sagt, was ohne und was mit KI geht") {
    ModeInput in;
    CHECK(mode_line_de(in).rfind("Nicht verbunden", 0) == 0);
    in.connected = true;
    const std::string off = mode_line_de(in);
    CHECK(off.rfind("Verbunden ohne KI: Stadt- und Weltbau laufen", 0) == 0);
    CHECK(off.find("KI einschalten") != std::string::npos);
    in.ai = AiState::On;
    CHECK(mode_line_de(in).rfind("Verbunden mit KI", 0) == 0);
}

TEST_CASE("Statuszeile: anderer Rechner – erst eingetragen, dann Tunnel, dann KI") {
    ModeInput in;
    in.connected = true;
    in.tunnel_needed = true;
    in.server_set = false;
    in.ai = AiState::On;
    CHECK(mode_line_de(in).rfind("Kein Server eingetragen", 0) == 0);
    in.server_set = true;
    CHECK(mode_line_de(in).rfind("Verbindung zum Server wird aufgebaut", 0) == 0);
    in.tunnel_up = true;
    CHECK(mode_line_de(in).rfind("Verbunden mit KI", 0) == 0);
}

TEST_CASE("Server-Ort: im Internet über eine Adresse heißt Fernverbindung an, Tunnel aus – nie beide") {
    AppConfig c;
    c.local_server = false;
    apply_server_place(c, ServerPlace::Internet);
    CHECK(c.relay_enabled);
    CHECK_FALSE(c.tunnel_enabled);
    CHECK(c.local_server);
    CHECK(server_place(c) == ServerPlace::Internet);
    apply_server_place(c, ServerPlace::Remote);
    CHECK(c.tunnel_enabled);
    CHECK_FALSE(c.relay_enabled);
    CHECK(server_place(c) == ServerPlace::Remote);
    apply_server_place(c, ServerPlace::ThisPc);
    CHECK_FALSE(c.tunnel_enabled);
    CHECK_FALSE(c.relay_enabled);
    CHECK(server_place(c) == ServerPlace::ThisPc);
}

TEST_CASE("Statuszeile: über die Adresse – erst Fingerabdruck, dann Aufbau, dann KI") {
    ModeInput in;
    in.connected = true;
    in.tunnel_needed = true;
    in.via_address = true;
    in.server_set = false;
    in.ai = AiState::On;
    CHECK(mode_line_de(in).rfind("Kein Fingerabdruck des Servers eingetragen", 0) == 0);
    in.server_set = true;
    CHECK(mode_line_de(in).rfind("Verbindung über die Adresse (HTTPS) wird aufgebaut", 0) == 0);
    in.tunnel_up = true;
    CHECK(mode_line_de(in).rfind("Verbunden mit KI", 0) == 0);
}

TEST_CASE("Einstellungen der Fernverbindung: Adresse, erster Pin Pflicht, zweiter optional und verschieden") {
    const std::string a = "sha256:" + std::string(64, 'a');
    const std::string b = "sha256:" + std::string(64, 'b');
    const std::string url = "wss://dein-server.example.net:25580/kanal/v1";
    CHECK_FALSE(relay_settings_problem(url, a, "").has_value());
    CHECK_FALSE(relay_settings_problem(url, " " + a + " ", "  ").has_value());
    CHECK_FALSE(relay_settings_problem(url, a, b).has_value());
    CHECK(relay_settings_problem("https://dein-server.example.net/kanal/v1", a, "").has_value());
    CHECK(relay_settings_problem(url, "", "").has_value());  // ohne Pin keine Verbindung – nicht speicherbar
    CHECK(relay_settings_problem(url, "sha256:abc", "").has_value());
    CHECK(relay_settings_problem(url, a, "sha256:abc").has_value());
    CHECK(relay_settings_problem(url, a, a).has_value());
    CHECK(relay_pins_from(" SHA256:" + std::string(64, 'A'), b) == std::vector<std::string>{a, b});
    CHECK(relay_pins_from(a, a) == std::vector<std::string>{a});
    CHECK(relay_pins_from("", "kaputt").empty());
}