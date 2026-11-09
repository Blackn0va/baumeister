// Reiter „Stadt“ mit echtem Qt (Plattform „minimal“): Etappen werden einmal gemalt, Fortschritt malt nicht neu,
// ein neuer Auftrag ersetzt die Karte.

#include "test_support.hpp"

#include "base/encoding.hpp"
#include "gui/city_paint.hpp"
#include "gui/city_view.hpp"

#include <QApplication>

using namespace swbh;
using namespace swbh::gui;

namespace {

QApplication& city_app() {
    static int argc = 1;
    static char name[] = "swbh_test_gui_widgets";
    static char* argv[] = {name, nullptr};
    qputenv("QT_QPA_PLATFORM", "minimal");
    static QApplication a(argc, argv);
    return a;
}

std::string start(const std::string& job) {
    return R"({"v":1,"event":"start","job":")" + job +
           R"(","kind":"stadt","place":"Testdorf","world":"w","stages":3,"origin":[0,0]})";
}

std::string stage(const std::string& job, int index, int x0, std::vector<std::uint8_t> cells) {
    return R"({"v":1,"event":"stage","job":")" + job + R"(","index":)" + std::to_string(index) + R"(,"x0":)" +
           std::to_string(x0) + R"(,"z0":0,"cell":2,"w":2,"h":1,"cells":")" + base64_encode(cells) + R"("})";
}

void send(server::CityState& st, const std::string& json) {
    REQUIRE(st.apply(parse_utf8(json)).empty());
}

}  // namespace

TEST_CASE("Stadtansicht: Etappen erscheinen an ihrer Weltstelle und werden nur einmal gemalt") {
    city_app();
    server::CityState st;
    CityView view;
    view.resize(400, 300);
    send(st, start("j"));
    send(st, stage("j", 0, 100, {1, 4}));
    view.show_city(st.snapshot());
    CHECK(view.stage_images() == 1);
    // cell 2: Zelle 0 deckt x 100..101, Zelle 1 x 102..103.
    CHECK(view.color_at(101, 1) == city_color(1));
    CHECK(view.color_at(103, 0) == city_color(4));
    CHECK(view.color_at(99, 0) == 0u);
    send(st, stage("j", 1, 104, {2, 2}));
    send(st, R"({"v":1,"event":"progress","job":"j","stagesDone":2,"currentStage":1,"blocks":10,"planned":20,)"
             R"("rate":5,"paused":false})");
    view.show_city(st.snapshot());
    CHECK(view.stage_images() == 2);
    CHECK(view.image_builds() == 2);
    // Nur Fortschritt: kein neues Bild.
    send(st, R"({"v":1,"event":"progress","job":"j","stagesDone":2,"currentStage":1,"blocks":15,"planned":20,)"
             R"("rate":5,"paused":true})");
    view.show_city(st.snapshot());
    CHECK(view.image_builds() == 2);
    CHECK(view.header_plain().contains(QStringLiteral("PAUSE")));
    view.grab();  // Zeichnen darf nicht werfen
}

TEST_CASE("Stadtansicht: neuer Auftrag ersetzt die Karte") {
    city_app();
    server::CityState st;
    CityView view;
    send(st, start("a"));
    send(st, stage("a", 0, 0, {1, 1}));
    view.show_city(st.snapshot());
    CHECK(view.stage_images() == 1);
    send(st, start("b"));
    view.show_city(st.snapshot());
    CHECK(view.stage_images() == 0);
    CHECK(view.color_at(0, 0) == 0u);
}

TEST_CASE("Weltbau: frühere Kachel bleibt auf der Karte, Bild wird nicht neu gemalt") {
    city_app();
    server::CityState st;
    CityView view;
    const auto kachel = [](const std::string& job) {
        return R"({"v":1,"event":"start","job":")" + job +
               R"(","kind":"weltbau","place":"Erdkachel","world":"erde","stages":16,"origin":[0,0]})";
    };
    send(st, R"({"v":1,"event":"welt","world":"erde","tile":1024,"done":[],"queue":[[0,0],[1,0]],"phase":"karte",)"
             R"("auto":true,"players":[[10,10]]})");
    view.show_city(st.snapshot());
    CHECK(view.header_plain().contains(QStringLiteral("Weltbau: 0 Kacheln fertig")));
    send(st, kachel("k1"));
    send(st, stage("k1", 0, 100, {1, 1}));
    view.show_city(st.snapshot());
    CHECK(view.image_builds() == 1);
    send(st, kachel("k2"));
    send(st, stage("k2", 0, 1124, {4, 4}));
    view.show_city(st.snapshot());
    CHECK(view.stage_images() == 2);
    CHECK(view.image_builds() == 2);  // die erste Kachel nicht neu gemalt
    CHECK(view.color_at(101, 0) == city_color(1));
    CHECK(view.color_at(1125, 0) == city_color(4));
    view.grab();
}
