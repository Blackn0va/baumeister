// Reiter „Module“ mit echtem Qt (Plattform „minimal“): Ein Klick auf einen eigenen Schalter landet über den Rückruf in
// config.local.json; das Nachführen im 250-ms-Takt schaltet nichts; Server-Module zeigen Zustand, Grund und Zeitpunkt,
// HTML aus dem Plugin bleibt Text; ohne Meldung der Hinweis „Server meldet keine Module“.
// Eigenes Programm: Zwei QApplication in einem Prozess stürzen ab (siehe tests/gui/CMakeLists.txt).

#include "test_support.hpp"

#include "gui/app_config.hpp"
#include "gui/host_modules.hpp"
#include "gui/modules_page.hpp"

#include <QApplication>
#include <QCheckBox>

#include <memory>
#include <string>
#include <vector>

using namespace swbh::gui;

namespace {

QApplication& app() {
    static int argc = 1;
    static char name[] = "swbh_test_modules_page";
    static char* argv[] = {name, nullptr};
    qputenv("QT_QPA_PLATFORM", "minimal");
    static QApplication a(argc, argv);
    return a;
}

}  // namespace

TEST_CASE("Module-Seite: Klick auf einen eigenen Schalter wird gespeichert, Nachführen schaltet nicht") {
    app();
    swbh::test::TempDir dir("modules-page");
    const auto file = dir / "config.local.json";
    AppConfig cfg;
    save_config(file, cfg);
    std::vector<std::string> calls;
    // Rückruf wie im Hauptfenster (MainWindow::toggle_module): Schlüssel setzen und speichern.
    ModulesPage page([&](const std::string& id, bool on) {
        calls.push_back(id + (on ? "=an" : "=aus"));
        AppConfig c = load_config(file).config;
        REQUIRE(set_host_module(c, id, on));
        save_config(file, c);
    });
    page.show_host(host_modules(cfg, HostModuleFacts{}));
    QCheckBox* research = page.host_switch("recherche");
    REQUIRE(research != nullptr);
    CHECK(research->isChecked());
    CHECK(research->isEnabled());

    research->click();
    REQUIRE(calls.size() == 1);
    CHECK(calls[0] == "recherche=aus");
    CHECK_FALSE(load_config(file).config.research);

    QCheckBox* city = page.host_switch("stadt-vorschau");
    REQUIRE(city != nullptr);
    city->click();
    CHECK_FALSE(load_config(file).config.city_preview);

    // Nachführen mit dem gespeicherten Stand: Häkchen folgen, Rückruf bleibt stumm.
    const AppConfig saved = load_config(file).config;
    page.show_host(host_modules(saved, HostModuleFacts{}));
    page.show_host(host_modules(saved, HostModuleFacts{}));
    CHECK(calls.size() == 2);
    CHECK_FALSE(page.host_switch("recherche")->isChecked());
    CHECK(page.host_switch("recherche") == research);  // dieselbe Zeile, nicht neu gebaut

    // Verbindung ist nur Anzeige.
    QCheckBox* link = page.host_switch("verbindung");
    REQUIRE(link != nullptr);
    CHECK_FALSE(link->isEnabled());
    CHECK(page.host_text("verbindung").contains(QStringLiteral("Verbinden")));
    CHECK(page.host_text("plan-ki").contains(QStringLiteral("wartet")));
}

TEST_CASE("Module-Seite: Server-Module mit Zustand, Grund und Zeitpunkt; HTML bleibt Text") {
    app();
    ModulesPage page({});
    CHECK(page.server_text().contains(QStringLiteral("Server meldet keine Module")));

    swbh::server::ModuleState state;
    const std::string body =
        R"({"version":1,"modules":[{"id":"stadtleben","name":"Stadtleben","description":"KI-Bewohner","on":true,)"
        R"("state":"wartet","reason":"KI nicht erreichbar","needs":["ki"]},)"
        R"({"id":"karte","name":"<b>Karte</b>","on":false,"state":"aus"}]})";
    REQUIRE(state.apply(body, 1'800'000'000, "14:03:22").empty());
    page.show_server(state.snapshot());
    const QString text = page.server_text();
    CHECK(text.contains(QStringLiteral("14:03:22")));
    CHECK(text.contains(QStringLiteral("Stadtleben – wartet")));
    CHECK(text.contains(QStringLiteral("KI nicht erreichbar")));
    CHECK(text.contains(QStringLiteral("braucht: ki")));
    CHECK(text.contains(QStringLiteral("<b>Karte</b> – aus")));  // als Text sichtbar, nicht fett gesetzt
    CHECK(text.contains(QStringLiteral("(ausgeschaltet)")));
    CHECK_FALSE(text.contains(QStringLiteral("Server meldet keine Module")));

    // Leere Liste: eigener Hinweis statt alter Einträge.
    REQUIRE(state.apply(R"({"version":1,"modules":[]})", 1'800'000'060, "14:04:22").empty());
    page.show_server(state.snapshot());
    CHECK(page.server_text().contains(QStringLiteral("Das Plugin meldet keine Module")));
    CHECK_FALSE(page.server_text().contains(QStringLiteral("Stadtleben")));
}
