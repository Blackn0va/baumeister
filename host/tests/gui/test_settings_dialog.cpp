// Einstellungen-Dialog mit echtem Qt (Plattform „minimal“): Die dritte Wahl „im Internet über eine Adresse (HTTPS)“
// zeigt Adresse, Fingerabdrücke und den eigenen Pin und gibt sie unverändert zurück; ein Wechsel des Ortes ändert nur
// die Verbindungsart. Eigenes Programm: Zwei QApplication in einem Prozess stürzen ab (siehe tests/gui/CMakeLists.txt).

#include "test_support.hpp"

#include "gui/settings_dialog.hpp"

#include <QApplication>
#include <QComboBox>
#include <QLineEdit>
#include <QPushButton>

#include <string>

using namespace swbh::gui;

namespace {

QApplication& app() {
    static int argc = 1;
    static char name[] = "swbh_test_settings_dialog";
    static char* argv[] = {name, nullptr};
    qputenv("QT_QPA_PLATFORM", "minimal");
    static QApplication a(argc, argv);
    return a;
}

// Die Auswahl „Der Server läuft“ – erkennbar an ihren drei Einträgen.
QComboBox* place_box(SettingsDialog& dlg) {
    for (QComboBox* box : dlg.findChildren<QComboBox*>()) {
        if (box->count() == 3 && box->itemText(2).contains(QStringLiteral("im Internet"))) {
            return box;
        }
    }
    return nullptr;
}

QLineEdit* field_with_text(SettingsDialog& dlg, const std::string& text) {
    for (QLineEdit* e : dlg.findChildren<QLineEdit*>()) {
        if (e->text().toStdString() == text) {
            return e;
        }
    }
    return nullptr;
}

}  // namespace

TEST_CASE("Einstellungen-Dialog: Fernverbindung hin und zurück, eigener Pin nur lesbar, Ortswechsel") {
    app();
    const std::string a = "sha256:" + std::string(64, 'a');
    const std::string b = "sha256:" + std::string(64, 'b');
    const std::string own = "sha256:" + std::string(64, 'c');
    AppConfig c;
    c.relay_enabled = true;
    c.tunnel_enabled = false;
    c.relay_url = "wss://relay.example:25580/kanal/v1";
    c.relay_pins = {a, b};
    c.relay_client_pin = own;
    int created = 0;
    SettingsDialog dlg(c, false, false, own, [&created](std::string&) {
        ++created;
        return std::optional<std::string>();
    });
    QComboBox* place = place_box(dlg);
    REQUIRE(place != nullptr);
    CHECK(place->currentIndex() == 2);
    QLineEdit* own_field = field_with_text(dlg, own);
    REQUIRE(own_field != nullptr);
    CHECK(own_field->isReadOnly());

    AppConfig r = dlg.result_config();
    CHECK(r.relay_enabled);
    CHECK_FALSE(r.tunnel_enabled);
    CHECK(r.relay_url == c.relay_url);
    CHECK(r.relay_pins == c.relay_pins);
    CHECK(r.relay_client_pin == own);

    // Zweiten Pin leeren: nur noch einer.
    QLineEdit* second = field_with_text(dlg, b);
    REQUIRE(second != nullptr);
    second->clear();
    CHECK(dlg.result_config().relay_pins == std::vector<std::string>{a});

    // „auf diesem PC“: Fernverbindung aus, Adresse und Pins bleiben für später stehen.
    place->setCurrentIndex(1);
    r = dlg.result_config();
    CHECK_FALSE(r.relay_enabled);
    CHECK_FALSE(r.tunnel_enabled);
    CHECK(r.relay_url == c.relay_url);
    // „anderer Rechner“: SSH-Tunnel, nie beide.
    place->setCurrentIndex(0);
    r = dlg.result_config();
    CHECK(r.tunnel_enabled);
    CHECK_FALSE(r.relay_enabled);
    CHECK(created == 0);  // ein vorhandener Pin legt keinen Schlüssel an
}

TEST_CASE("Einstellungen-Dialog: ohne eigenen Schlüssel gibt es „Schlüssel erzeugen“, das den Rückruf nutzt") {
    app();
    AppConfig c;
    const std::string made = "sha256:" + std::string(64, 'd');
    int created = 0;
    SettingsDialog dlg(c, false, false, std::string(), [&](std::string&) {
        ++created;
        return std::optional<std::string>(made);
    });
    QPushButton* make = nullptr;
    for (QPushButton* p : dlg.findChildren<QPushButton*>()) {
        if (p->text() == QStringLiteral("Schlüssel erzeugen")) {
            make = p;
        }
    }
    REQUIRE(make != nullptr);
    make->click();
    CHECK(created == 1);
    CHECK(field_with_text(dlg, made) != nullptr);
}
