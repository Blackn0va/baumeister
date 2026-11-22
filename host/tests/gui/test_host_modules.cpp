// Eigene Module der Host-App (Seite „Module“, Vertrag v012 AK-38): Jeder Schalter schreibt seinen BESTEHENDEN
// Konfigschlüssel und überlebt Speichern und Laden; die Verbindung ist reine Anzeige; Zustand und Grund folgen dem
// Live-Zustand statt dem Häkchen.

#include "test_support.hpp"

#include "gui/app_config.hpp"
#include "gui/host_modules.hpp"

#include <string>

using namespace swbh::gui;

namespace {

const HostModule& find(const std::vector<HostModule>& list, const std::string& id) {
    for (const HostModule& m : list) {
        if (m.id == id) {
            return m;
        }
    }
    FAIL("Modul fehlt: " << id);
    static HostModule none;
    return none;
}

}  // namespace

TEST_CASE("Host-Module: alle eigenen Funktionen mit Name, Beschreibung, Schlüssel und Hinweis") {
    const std::vector<HostModule> list = host_modules(AppConfig{}, HostModuleFacts{});
    const char* ids[] = {"plan-ki", "recherche", "stimme", "mikrofon", "mitspieler", "stadt-vorschau", "meldungen",
                         "verbindung"};
    CHECK(list.size() == std::size(ids));
    for (const char* id : ids) {
        const HostModule& m = find(list, id);
        CHECK_FALSE(m.name.empty());
        CHECK_FALSE(m.description.empty());
        CHECK_FALSE(m.effect.empty());
    }
    CHECK(find(list, "recherche").key == "research");
    CHECK(find(list, "mikrofon").key == "sprechen");
    CHECK(find(list, "stadt-vorschau").key == "stadt_vorschau");
    // Verbindung: festgelegt über „Verbinden“ (2026-11-01) – kein zweiter Schalter.
    const HostModule& link = find(list, "verbindung");
    CHECK_FALSE(link.switchable);
    CHECK(link.key.empty());
    AppConfig c;
    const AppConfig before = c;
    CHECK_FALSE(set_host_module(c, "verbindung", true));
    CHECK_FALSE(set_host_module(c, "gibt-es-nicht", true));
    CHECK(c.tunnel_enabled == before.tunnel_enabled);
    CHECK(c.relay_enabled == before.relay_enabled);
}

TEST_CASE("Host-Module: Schalter schreibt den bestehenden Schlüssel und bleibt nach Speichern und Laden") {
    swbh::test::TempDir dir("module");
    const auto file = dir / "config.local.json";
    AppConfig c;
    REQUIRE(c.research);
    REQUIRE(c.city_preview);
    REQUIRE(c.load_model_on_start);
    REQUIRE(c.notifications);
    REQUIRE(c.speech_out);
    REQUIRE(c.hear_others);
    REQUIRE_FALSE(c.speech_in);
    for (const char* id : {"plan-ki", "recherche", "stimme", "mitspieler", "stadt-vorschau", "meldungen"}) {
        CHECK(set_host_module(c, id, false));
    }
    CHECK(set_host_module(c, "mikrofon", true));
    save_config(file, c);
    const ConfigLoad l = load_config(file);
    REQUIRE_FALSE(l.error.has_value());
    CHECK_FALSE(l.config.load_model_on_start);
    CHECK_FALSE(l.config.research);
    CHECK_FALSE(l.config.speech_out);
    CHECK_FALSE(l.config.hear_others);
    CHECK_FALSE(l.config.city_preview);
    CHECK_FALSE(l.config.notifications);
    CHECK(l.config.speech_in);
    const std::vector<HostModule> list = host_modules(l.config, HostModuleFacts{});
    CHECK_FALSE(find(list, "recherche").on);
    CHECK_FALSE(find(list, "stadt-vorschau").on);
    CHECK(find(list, "mikrofon").on);

    // Zurück auf an – auch das überlebt die Datei.
    AppConfig again = l.config;
    CHECK(set_host_module(again, "stadt-vorschau", true));
    save_config(file, again);
    CHECK(load_config(file).config.city_preview);
}

TEST_CASE("Host-Module: ältere Datei ohne „stadt_vorschau“ zeigt die Stadt weiter") {
    swbh::test::TempDir dir("module");
    const auto file = dir / "config.local.json";
    swbh::write_file_atomic(file, R"({"version":3,"verbindung":"lokal","research":false})");
    const ConfigLoad l = load_config(file);
    CHECK(l.config.city_preview);
    CHECK_FALSE(l.config.research);
}

TEST_CASE("Host-Module: Zustand und Grund folgen dem echten Zustand") {
    AppConfig c;
    HostModuleFacts f;
    // Plan-KI: an, aber nicht geladen → wartet; geladen → aktiv, auch wenn „beim Start“ aus ist.
    CHECK(find(host_modules(c, f), "plan-ki").state == HostModuleState::Waiting);
    f.model = LoadState::Ready;
    c.load_model_on_start = false;
    const HostModule ai = find(host_modules(c, f), "plan-ki");
    CHECK(ai.state == HostModuleState::Active);
    CHECK(ai.reason.find("beim Start: aus") != std::string::npos);
    f.model = LoadState::Failed;
    CHECK(find(host_modules(c, f), "plan-ki").state == HostModuleState::Failed);
    f.model = LoadState::Off;
    CHECK(find(host_modules(c, f), "plan-ki").state == HostModuleState::Off);

    // Stimme ohne Minecraft-Namen liest nichts vor (server::speak_for) → wartet mit Grund.
    CHECK(find(host_modules(c, f), "stimme").state == HostModuleState::Waiting);
    c.minecraft_name = "Steve";
    CHECK(find(host_modules(c, f), "stimme").state == HostModuleState::Active);

    // Mikrofon: Fehler der Erkennung kommt als Grund.
    c.speech_in = true;
    f.speech = LoadState::Failed;
    f.speech_error = "Mikrofon nicht gefunden";
    const HostModule mic = find(host_modules(c, f), "mikrofon");
    CHECK(mic.state == HostModuleState::Failed);
    CHECK(mic.reason == "Mikrofon nicht gefunden");
    CHECK(find(host_modules(c, f), "mitspieler").state == HostModuleState::Waiting);
    f.speech = LoadState::Ready;
    CHECK(find(host_modules(c, f), "mikrofon").state == HostModuleState::Active);
    CHECK(find(host_modules(c, f), "mitspieler").state == HostModuleState::Active);

    // Stadt-Vorschau wartet, bis das Plugin etwas gemeldet hat.
    CHECK(find(host_modules(c, f), "stadt-vorschau").state == HostModuleState::Waiting);
    f.city_reported = true;
    CHECK(find(host_modules(c, f), "stadt-vorschau").state == HostModuleState::Active);

    // Verbindung: getrennt → aus; Tunnel verbunden → aktiv; Tunnel gescheitert → fehler mit Text.
    CHECK(find(host_modules(c, f), "verbindung").state == HostModuleState::Off);
    f.server_running = true;
    c.tunnel_enabled = true;
    f.link = swbh::tunnel::State::Connected;
    const HostModule up = find(host_modules(c, f), "verbindung");
    CHECK(up.state == HostModuleState::Active);
    CHECK(up.on);
    CHECK(up.reason == "SSH-Tunnel verbunden");
    f.link = swbh::tunnel::State::Failed;
    f.link_text = "Fingerabdruck fehlt";
    CHECK(find(host_modules(c, f), "verbindung").reason == "SSH-Tunnel – Fingerabdruck fehlt");
    CHECK(host_module_state_de(HostModuleState::Waiting) == "wartet");
}
