// Stadtansicht ohne Qt: Farben je Kategorie, Bildpunkte einer Etappe, Kopfzeile mit Restzeit.

#include "test_support.hpp"

#include "gui/city_paint.hpp"

#include <vector>

using namespace swbh::gui;

TEST_CASE("Stadtkarte: leer ist durchsichtig, jede Kategorie hat ihre eigene deckende Farbe") {
    CHECK(city_color(0) == 0u);
    std::vector<std::uint32_t> seen;
    for (std::uint8_t k = 1; k < 10; ++k) {
        CAPTURE(int(k));
        CHECK((city_color(k) >> 24) == 0xffu);
        for (std::uint32_t c : seen) {
            CHECK(c != city_color(k));
        }
        seen.push_back(city_color(k));
    }
    CHECK(city_color(200) == city_color(9));
    CHECK(std::string(city_category_name(4)) == "Wasser");
}

TEST_CASE("Stadtkarte: ein Bildpunkt je Zelle, falsche Länge ergibt nichts") {
    const std::vector<std::uint8_t> cells = {1, 2, 4, 0};
    const std::vector<std::uint32_t> px = city_pixels(2, 2, cells);
    REQUIRE(px.size() == 4);
    CHECK(px[0] == city_color(1));
    CHECK(px[2] == city_color(4));
    CHECK(px[3] == 0u);
    CHECK(city_pixels(3, 2, cells).empty());
}

TEST_CASE("Stadtkarte: Kopfzeile mit Etappe, Tempo, Restzeit erst nach 30 s, Ende") {
    CityStatus s;
    s.kind = "stadt";
    s.place = "Bad Kreuznach";
    s.stages = 5;
    CHECK(city_status_text(s) == "Stadt Bad Kreuznach · 5 Etappen · startet …");
    s.has_progress = true;
    s.stages_done = 2;
    s.blocks = 62912;
    s.planned = 120000;
    s.rate = 30000.0;
    s.seconds = 10.0;
    CHECK(city_status_text(s) == "Stadt Bad Kreuznach · Etappe 2 von 5 · 62.912 Blöcke · 30.000 Blöcke/s");
    s.seconds = 40.0;
    // offen: 3 Etappen × 60 000 + (120 000 − 62 912) = 237 088 Blöcke / 30 000 je s ≈ 7 s
    CHECK(city_status_text(s).find("noch ca. 7 s") != std::string::npos);
    s.paused = true;
    CHECK(city_status_text(s).ends_with("· PAUSE"));
    s.ended = true;
    s.end_reason = "fertig";
    s.end_blocks = 151141;
    CHECK(city_status_text(s) == "Stadt Bad Kreuznach · fertig · 151.141 Blöcke");
    s.kind = "weltbau";
    s.end_reason = "abgebrochen";
    CHECK(city_status_text(s).starts_with("Weltbau Bad Kreuznach · abgebrochen"));
}

TEST_CASE("Weltbau-Zeile: Kacheln, laufende Kachel, Automatik, Spieler") {
    WorldStatus w;
    w.done = 3;
    w.queued = 2;
    w.players = 1;
    w.auto_on = true;
    w.has_current = true;
    w.current_tx = 366;
    w.current_tz = 6584;
    w.phase = "bauen";
    CHECK(world_status_text(w) ==
          "Weltbau: 3 Kacheln fertig · 2 wartend · Kachel 366/6584 baut · Automatik an · 1 Spieler in der Erdwelt");
    w.done = 1;
    w.failed = 1;
    w.has_current = false;
    w.auto_on = false;
    CHECK(world_status_text(w) ==
          "Weltbau: 1 Kachel fertig · 1 gescheitert · 2 wartend · Automatik aus · 1 Spieler in der Erdwelt");
}
