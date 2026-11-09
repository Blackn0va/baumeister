// Plan-Vorschau mit echtem Qt (Plattform „minimal“, ohne Bildschirm): Kopfzeilen, Draufsicht, Schnitt, Zeichnen.

#include "test_support.hpp"

#include "gui/plan_preview.hpp"

#include <QApplication>
#include <QColor>
#include <QImage>
#include <QPixmap>

using namespace swbh::gui;

namespace {

QApplication& app() {
    static int argc = 1;
    static char name[] = "swbh_test_gui_widgets";
    static char* argv[] = {name, nullptr};
    qputenv("QT_QPA_PLATFORM", "minimal");  // offscreen fehlt in den Qt-Abhängigkeiten (wie kraken)
    static QApplication a(argc, argv);
    return a;
}

QColor rgb(std::uint32_t v) {
    return QColor(static_cast<int>((v >> 16) & 0xff), static_cast<int>((v >> 8) & 0xff), static_cast<int>(v & 0xff));
}

// Der Turm-Beispielplan aus dem System-Prompt des Plugins (tests/plan/data/system-prompt.txt).
PlanRecord tower(std::uint64_t seq) {
    PlanRecord r;
    r.seq = seq;
    r.request = "ein Turm aus Stein";
    r.grammar = "bauplan";
    r.ok = true;
    r.tokens = 304;
    r.seconds = 54.1;
    r.tokens_per_second = 5.7;
    r.text =
        R"({"design":"round tower","reply":"Ein stolzer Steinturm kommt sofort!","mode":"new","steps":[)"
        R"({"op":"cylinder","block":"stone_bricks","pos":[4,0,4],"radius":4,"height":1},)"
        R"({"op":"cylinder","block":"stone_bricks","pos":[4,1,4],"radius":4,"height":12,"hollow":true},)"
        R"({"op":"door","block":"spruce_door","pos":[4,1,0],"facing":"front"},)"
        R"({"op":"set","block":"glass_pane","pos":[0,5,4]},{"op":"set","block":"glass_pane","pos":[8,5,4]},)"
        R"({"op":"set","block":"glass_pane","pos":[4,9,8]},)"
        R"({"op":"cylinder","block":"stone_bricks","pos":[4,13,4],"radius":4,"height":1},)"
        R"({"op":"cylinder","block":"cobblestone_wall","pos":[4,14,4],"radius":4,"height":1,"hollow":true},)"
        R"({"op":"set","block":"lantern","pos":[4,14,4]}]})";
    return r;
}

}  // namespace

TEST_CASE("Plan-Vorschau (Widget): ohne Auftrag ein Hinweis und kein Bild") {
    app();
    PlanPreview p;
    CHECK(p.header_plain().contains(QStringLiteral("Noch kein Bauplan")));
    CHECK(p.view_image().isNull());
}

TEST_CASE("Plan-Vorschau (Widget): Kopf mit Auftrag, Token, Dauer, Schritten und Blöcken") {
    app();
    PlanPreview p;
    REQUIRE(p.show_record(tower(1)));
    const QString h = p.header_plain();
    CHECK(h.contains(QStringLiteral("„ein Turm aus Stein“")));
    CHECK(h.contains(QStringLiteral("304 Token")));
    CHECK(h.contains(QStringLiteral("54,1 s")));
    CHECK(h.contains(QStringLiteral("5,7 Token/s")));
    CHECK(h.contains(QStringLiteral("9 Schritte")));
    CHECK(h.contains(QStringLiteral("9 × 9 × 15 (B × T × H)")));
    CHECK(h.contains(QStringLiteral("Ein stolzer Steinturm kommt sofort!")));
}

TEST_CASE("Plan-Vorschau (Widget): Draufsicht zeigt den obersten Block, Ecken bleiben leer") {
    app();
    PlanPreview p;
    p.show_record(tower(1));
    const QImage img = p.view_image();
    REQUIRE(img.width() == 9);
    REQUIRE(img.height() == 9);
    // Mitte (x 4, z 4): Laterne auf y 14 = höchster Block → volle Helligkeit.
    CHECK(img.pixelColor(4, 4) == rgb(block_rgb("lantern")));
    // Ecke außerhalb der Scheibe: Feldfarbe.
    CHECK(img.pixelColor(0, 0) != rgb(block_rgb("stone_bricks")));
    CHECK(img.pixelColor(0, 0) == img.pixelColor(8, 8));
}

TEST_CASE("Plan-Vorschau (Widget): Schnitt durch die Mitte zeigt die Fenster der Turmwand") {
    app();
    PlanPreview p;
    p.show_record(tower(1));
    p.set_mode(PlanPreview::Mode::Section);
    CHECK(p.section_z() == 4);  // Mitte von z 0 … 8
    const QImage img = p.view_image();
    REQUIRE(img.width() == 9);
    REQUIRE(img.height() == 15);
    // Fenster bei x 0, y 5 → Zeile 15 − 1 − 5 = 9 (y nach oben).
    CHECK(img.pixelColor(0, 9) == rgb(block_rgb("glass_pane")));
    CHECK(img.pixelColor(4, 9) != rgb(block_rgb("stone_bricks")));  // innen hohl
    p.set_section_z(500);
    CHECK(p.section_z() == 8);  // auf den Plan geklemmt
}

TEST_CASE("Plan-Vorschau (Widget): freie Antworten ersetzen den Plan nicht, Fehlschläge schon") {
    app();
    PlanPreview p;
    p.show_record(tower(1));
    PlanRecord free_answer;
    free_answer.seq = 2;
    free_answer.grammar = "frei";
    free_answer.ok = true;
    free_answer.text = "Hallo";
    CHECK_FALSE(p.show_record(free_answer));
    CHECK(p.record().seq == 1);
    PlanRecord failed;
    failed.seq = 3;
    failed.grammar = "bauplan";
    failed.request = "eine Brücke";
    failed.error = "Gegenseite hat die Verbindung geschlossen";
    CHECK(p.show_record(failed));
    CHECK(p.header_plain().contains(QStringLiteral("Fehlgeschlagen: Gegenseite hat die Verbindung geschlossen")));
    CHECK(p.view_image().isNull());
}

TEST_CASE("Plan-Vorschau (Widget): Zeichnen skaliert ohne Glättung – Blockfarben bleiben rein") {
    app();
    PlanPreview p;
    p.resize(420, 560);
    p.show_record(tower(1));
    p.show();
    QApplication::processEvents();
    const QImage shot = p.grab().toImage();
    REQUIRE_FALSE(shot.isNull());
    bool lantern = false;
    for (int y = 0; y < shot.height() && !lantern; ++y) {
        for (int x = 0; x < shot.width(); ++x) {
            if (shot.pixelColor(x, y) == rgb(block_rgb("lantern"))) {
                lantern = true;
                break;
            }
        }
    }
    CHECK(lantern);
}

TEST_CASE("Plan-Vorschau (Widget): Neuzeichnen baut das Bild nicht neu, Ansichtswechsel schon") {
    app();
    PlanPreview p;
    p.resize(420, 560);
    p.show_record(tower(1));
    p.show();
    QApplication::processEvents();
    const int builds = p.image_builds();
    for (int i = 0; i < 3; ++i) {
        REQUIRE_FALSE(p.grab().isNull());  // grab() ruft paintEvent der Zeichenfläche
    }
    CHECK(p.image_builds() == builds);
    p.set_mode(PlanPreview::Mode::Section);
    CHECK(p.image_builds() == builds + 1);
}
