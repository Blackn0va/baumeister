// Stadtansicht-Endpunkt /baumeister/v1/stadt: Schnittstelle v1 wie HostReporter.java im Plugin, Ablehnungen, Grenzen.

#include "test_support.hpp"

#include "base/encoding.hpp"
#include "server/city_api.hpp"

#include <string>
#include <vector>

using namespace swbh;
using namespace swbh::server;

namespace {

HttpRequest post(std::string body) {
    HttpRequest r;
    r.method = "POST";
    r.target = std::string(kCityPath);
    r.version = "HTTP/1.1";
    r.body = std::move(body);
    return r;
}

// Genau die Form, die HostReporter.start/stage/progress/end im Plugin baut (Gson, Feldreihenfolge egal).
std::string start(const std::string& job) {
    return R"({"v":1,"event":"start","job":")" + job +
           R"(","kind":"stadt","place":"Bad Kreuznach","world":"stadtwelt","stages":5,"origin":[-3000,-3000]})";
}

std::string stage(const std::string& job, int index, int w, int h, const std::vector<std::uint8_t>& cells) {
    return R"({"v":1,"event":"stage","job":")" + job + R"(","index":)" + std::to_string(index) +
           R"(,"x0":-3100,"z0":-3100,"cell":2,"w":)" + std::to_string(w) + R"(,"h":)" + std::to_string(h) +
           R"(,"cells":")" + base64_encode(cells) + R"("})";
}

std::string code_of(const ApiResponse& r) {
    return r.body;
}

}  // namespace

TEST_CASE("Stadtansicht: start, Etappe, Fortschritt und Ende wie vom Plugin") {
    CityState st;
    CHECK(st.snapshot() == nullptr);
    CHECK(handle_city(post(start("a1")), st).status == 200);
    CHECK(handle_city(post(stage("a1", 0, 3, 2, {1, 2, 3, 4, 5, 6})), st).status == 200);
    CHECK(handle_city(post(R"({"v":1,"event":"progress","job":"a1","stagesDone":1,"currentStage":0,"blocks":62912,)"
                           R"("planned":233118,"rate":30430.5,"paused":false,"npc":[-3050,-2990]})"),
                      st)
              .status == 200);
    const auto v = st.snapshot();
    REQUIRE(v != nullptr);
    CHECK(v->place == "Bad Kreuznach");
    CHECK(v->stages == 5);
    REQUIRE(v->stage_list.size() == 1);
    CHECK(v->stage_list[0]->cells == std::vector<std::uint8_t>{1, 2, 3, 4, 5, 6});
    CHECK(v->stage_list[0]->cell == 2);
    CHECK(v->progress.blocks == 62912);
    CHECK(v->progress.has_npc);
    CHECK(v->progress.npc_x == -3050);
    CHECK(handle_city(post(R"({"v":1,"event":"end","job":"a1","reason":"fertig","blocks":151141})"), st).status == 200);
    CHECK(st.snapshot()->ended);
    CHECK(st.snapshot()->end_blocks == 151141);
    // Der alte Schnappschuss bleibt unverändert lesbar (die GUI hält ihn, während geschrieben wird).
    CHECK_FALSE(v->ended);
}

TEST_CASE("Stadtansicht: ungültige Nachrichten werden mit 400 und Code abgelehnt") {
    CityState st;
    REQUIRE(handle_city(post(start("a1")), st).status == 200);
    struct Bad {
        std::string body;
        std::string code;
    };
    const std::vector<Bad> bad = {
        {"kein json", "body_kein_json"},
        {R"({"v":2,"event":"start","job":"x"})", "version"},
        {R"({"v":1,"event":"start","job":"x y"})", "job"},
        {R"({"v":1,"event":"start","job":"b","kind":"burg","place":"p","world":"w","stages":1,"origin":[0,0]})", "kind"},
        {R"({"v":1,"event":"start","job":"b","kind":"stadt","place":"p","world":"w","stages":0,"origin":[0,0]})",
         "stages"},
        {R"({"v":1,"event":"start","job":"b","kind":"stadt","place":"p","world":"w","stages":1,"origin":[0]})", "origin"},
        {stage("a1", 0, 3, 2, {1, 2, 3}), "cells_laenge"},
        {stage("a1", 0, 2, 1, {1, 10}), "cells_wert"},
        {stage("a1", 0, 361, 1, std::vector<std::uint8_t>(361, 1)), "stage_felder"},
        {R"({"v":1,"event":"progress","job":"a1","stagesDone":1,"currentStage":0,"blocks":1,"planned":1,"rate":-1,)"
         R"("paused":false})",
         "rate"},
        {R"({"v":1,"event":"end","job":"a1","reason":"weg","blocks":1})", "reason"},
        {R"({"v":1,"event":"tanzen","job":"a1"})", "event"},
    };
    for (const Bad& b : bad) {
        CAPTURE(b.body);
        const ApiResponse r = handle_city(post(b.body), st);
        CHECK(r.status == 400);
        CHECK(code_of(r).find(b.code) != std::string::npos);
    }
    HttpRequest get = post("");
    get.method = "GET";
    CHECK(handle_city(get, st).status == 405);
}

TEST_CASE("Stadtansicht: fremder Auftrag verworfen, neuer ersetzt, Etappe ersetzt statt doppelt") {
    CityState st;
    REQUIRE(handle_city(post(start("alt")), st).status == 200);
    REQUIRE(handle_city(post(start("neu")), st).status == 200);
    CHECK(handle_city(post(stage("alt", 0, 1, 1, {1})), st).status == 200);  // Rest des alten: angenommen, verworfen
    CHECK(st.snapshot()->stage_list.empty());
    CHECK(handle_city(post(stage("neu", 4, 1, 1, {1})), st).status == 200);
    CHECK(handle_city(post(stage("neu", 4, 2, 1, {2, 2})), st).status == 200);
    REQUIRE(st.snapshot()->stage_list.size() == 1);
    CHECK(st.snapshot()->cells == 2);
    const std::uint64_t before = st.seq();
    CHECK(handle_city(post(stage("alt", 1, 1, 1, {1})), st).status == 200);
    CHECK(st.seq() == before);  // nichts übernommen, die GUI muss nicht neu zeichnen
}

TEST_CASE("Stadtansicht: höchstens 16 Mio. Zellen je Auftrag") {
    CityState st;
    REQUIRE(handle_city(post(start("g")), st).status == 200);
    const std::vector<std::uint8_t> full(360 * 360, 5);
    int accepted = 0;
    for (int i = 0; i < 130; ++i) {
        const ApiResponse r = handle_city(post(stage("g", i, 360, 360, full)), st);
        if (r.status == 200) {
            ++accepted;
        } else {
            CHECK(code_of(r).find("zu_viele_zellen") != std::string::npos);
        }
    }
    CHECK(accepted == static_cast<int>(kCityMaxCells / (360 * 360)));
    CHECK(st.snapshot()->cells <= kCityMaxCells);
}

TEST_CASE("Stadtansicht: Pfad mit und ohne Abfrage erkannt, andere nicht") {
    CHECK(is_city_path("/baumeister/v1/stadt"));
    CHECK(is_city_path("/baumeister/v1/stadt?x=1"));
    CHECK_FALSE(is_city_path("/baumeister/v1/stadtx"));
    CHECK_FALSE(is_city_path("/v1/chat/completions"));
}

TEST_CASE("Weltbau: Übersicht ohne Auftrag, Kacheln bleiben über den nächsten Kachel-Auftrag stehen") {
    CityState st;
    const std::string welt = R"({"v":1,"event":"welt","world":"erde","tile":1024,"done":[[366,6584],[365,6584]],)"
                             R"("queue":[[367,6584]],"current":[367,6584],"phase":"bauen","auto":true,)"
                             R"("players":[[375000,6742000]]})";
    REQUIRE(handle_city(post(welt), st).status == 200);
    auto v = st.snapshot();
    REQUIRE(v != nullptr);
    CHECK(v->job.empty());
    CHECK(v->kind == "weltbau");
    REQUIRE(v->overview != nullptr);
    CHECK(v->overview->done.size() == 2);
    CHECK(v->overview->has_current);
    CHECK(v->overview->players.size() == 1);
    // Erste Kachel als Auftrag, eine Etappe; dann die zweite Kachel – die erste bleibt als Hintergrund.
    const auto kachel = [](const std::string& job) {
        return R"({"v":1,"event":"start","job":")" + job +
               R"(","kind":"weltbau","place":"Erdkachel","world":"erde","stages":16,"origin":[0,0]})";
    };
    REQUIRE(handle_city(post(kachel("k1")), st).status == 200);
    CHECK(st.snapshot()->overview != nullptr);  // Übersicht bleibt beim Auftragswechsel
    REQUIRE(handle_city(post(stage("k1", 0, 2, 1, {1, 2})), st).status == 200);
    REQUIRE(handle_city(post(kachel("k2")), st).status == 200);
    REQUIRE(handle_city(post(stage("k2", 0, 1, 1, {5})), st).status == 200);
    v = st.snapshot();
    CHECK(v->background.size() == 1);
    CHECK(v->stage_list.size() == 1);
    CHECK(v->cells == 3);
    // Eine Stadt in einer anderen Welt nimmt den Hintergrund nicht mit.
    REQUIRE(handle_city(post(start("s1")), st).status == 200);
    CHECK(st.snapshot()->background.empty());
}

TEST_CASE("Weltbau: Hintergrund opfert die ältesten Kacheln, bevor eine neue Etappe abgelehnt wird") {
    CityState st;
    const std::vector<std::uint8_t> full(360 * 360, 5);
    const int per_job = 60;  // 60 × 129 600 = 7,8 Mio. Zellen je Kachel
    for (int k = 0; k < 4; ++k) {
        const std::string job = "k" + std::to_string(k);
        REQUIRE(handle_city(post(R"({"v":1,"event":"start","job":")" + job +
                                 R"(","kind":"weltbau","place":"x","world":"erde","stages":100,"origin":[0,0]})"),
                            st)
                    .status == 200);
        for (int i = 0; i < per_job; ++i) {
            CAPTURE(k);
            CAPTURE(i);
            REQUIRE(handle_city(post(stage(job, i, 360, 360, full)), st).status == 200);
        }
    }
    CHECK(st.snapshot()->cells <= kCityMaxCells);
    CHECK(st.snapshot()->stage_list.size() == per_job);
}

TEST_CASE("Weltbau: ungültige Übersicht wird abgelehnt") {
    CityState st;
    for (const std::string& body :
         {std::string(R"({"v":1,"event":"welt","world":"","tile":1024,"phase":"bauen","auto":true})"),
          std::string(R"({"v":1,"event":"welt","world":"e","tile":1024,"phase":"tanzen","auto":true})"),
          std::string(R"({"v":1,"event":"welt","world":"e","tile":1024,"done":[[1]],"phase":"bauen","auto":true})"),
          std::string(R"({"v":1,"event":"welt","world":"e","tile":0,"phase":"bauen","auto":true})"),
          std::string(R"({"v":1,"event":"welt","world":"e","tile":1024,"phase":"bauen"})")}) {
        CAPTURE(body);
        CHECK(handle_city(post(body), st).status == 400);
    }
}
