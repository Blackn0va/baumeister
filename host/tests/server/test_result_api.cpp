// Ergebnis-Endpunkt /baumeister/v1/ergebnis: Meldung des Plugins (ResultReporter.java) nach jedem Auftrag.

#include "test_support.hpp"

#include "server/result_api.hpp"

#include <optional>
#include <string>

using namespace swbh;
using namespace swbh::server;

namespace {

HttpRequest req(std::string method, std::string body = {}) {
    HttpRequest r;
    r.method = std::move(method);
    r.target = std::string(kResultPath);
    r.version = "HTTP/1.1";
    r.body = std::move(body);
    return r;
}

}  // namespace

TEST_CASE("Ergebnis: Bau auf dem Plot kommt in der App an, wie ResultReporter ihn meldet") {
    std::optional<ResultNote> seen;
    const ApiResponse r = handle_result(
        req("POST", R"({"auftrag":"bau ein hochhaus mit 2 stockwerken","ergebnis":"gebaut","grund":"",)"
                    R"("bloecke":8652,"plot":"1;1"})"),
        [&](const ResultNote& n) { seen = n; });
    CHECK(r.status == 200);
    REQUIRE(seen.has_value());
    CHECK(result_line_de(*seen) == "gebaut: 8 652 Blöcke auf Plot 1;1");
    CHECK_FALSE(result_is_failure(*seen));
}

TEST_CASE("Ergebnis: verworfen und abgesagt zeigen den Grund und gelten als Fehlschlag") {
    std::optional<ResultNote> seen;
    handle_result(req("POST", R"({"auftrag":"bau mir fort knox","ergebnis":"abgesagt",)"
                              R"("grund":"Das passt nicht auf Plot 1;2 (42 × 42)."})"),
                  [&](const ResultNote& n) { seen = n; });
    REQUIRE(seen.has_value());
    CHECK(result_line_de(*seen) == "abgesagt: Das passt nicht auf Plot 1;2 (42 × 42).");
    CHECK(result_is_failure(*seen));
}

TEST_CASE("Ergebnis: Bauten der Client-Mod sind als Client gekennzeichnet") {
    std::optional<ResultNote> seen;
    handle_result(req("POST", R"({"auftrag":"bau einen Bunker","ergebnis":"gebaut","bloecke":4843,)"  // umlaut-ok
                              R"("quelle":"client"})"),
                  [&](const ResultNote& n) { seen = n; });
    REQUIRE(seen.has_value());
    CHECK(result_line_de(*seen) == "gebaut (Client): 4 843 Blöcke");
}

TEST_CASE("Ergebnis: Nachbesserungen ohne LLM und Runden kommen an und stehen in der Kurzzeile") {
    std::optional<ResultNote> seen;
    handle_result(req("POST", R"({"auftrag":"bau ein haus","ergebnis":"gebaut","bloecke":412,)"  // umlaut-ok
                              R"("korrekturen":"Schritt 2: house-Grundriss 32×1 auf 24×5 angepasst","runden":2})"),
                  [&](const ResultNote& n) { seen = n; });
    REQUIRE(seen.has_value());
    CHECK(seen->fixes == "Schritt 2: house-Grundriss 32×1 auf 24×5 angepasst");
    CHECK(seen->rounds == 2);
    CHECK(result_line_de(*seen) == "gebaut, selbst nachgebessert: 412 Blöcke");
    // Ohne die Felder (älteres Plugin) wie bisher.
    seen.reset();
    handle_result(req("POST", R"({"auftrag":"x","ergebnis":"gebaut","bloecke":1})"),  // umlaut-ok
                  [&](const ResultNote& n) { seen = n; });
    REQUIRE(seen.has_value());
    CHECK(seen->fixes.empty());
    CHECK(seen->rounds == 0);
}

TEST_CASE("Ergebnis: Unbekanntes, Unsinniges und andere Methoden werden abgelehnt") {
    bool called = false;
    const auto note = [&](const ResultNote&) { called = true; };
    CHECK(handle_result(req("POST", "{kaputt"), note).status == 400);
    CHECK(handle_result(req("POST", R"({"ergebnis":"explodiert"})"), note).status == 400);
    CHECK(handle_result(req("POST", R"({"ergebnis":"gebaut","bloecke":-1})"), note).status == 400);  // umlaut-ok
    CHECK(handle_result(req("POST", R"({"ergebnis":"gebaut","bloecke":"viele"})"), note).status == 400);  // umlaut-ok
    CHECK(handle_result(req("POST", R"({"ergebnis":"gebaut","runden":99})"), note).status == 400);
    CHECK(handle_result(req("POST", R"({"ergebnis":"gebaut","runden":"zwei"})"), note).status == 400);
    CHECK(handle_result(req("GET"), note).status == 405);
    CHECK_FALSE(called);
}

TEST_CASE("Ergebnis: langer Auftrag, viele Umlaute und Tab ergeben denselben Schlüssel wie order_text") {
    // Vorher kürzte handle_result den Auftrag auf 200 Byte ohne log::safe, order_text auf 160 Codepunkte mit
    // log::safe – keiner dieser Texte fand im Verlauf seine LLM-Zeile wieder.
    std::string umlauts;
    for (int i = 0; i < 120; ++i) {
        umlauts += "ä";
    }
    std::string huge;
    while (huge.size() < 3000) {
        huge += "Turm ";
    }
    const std::string texts[] = {std::string(170, 'a'), umlauts, "ein Turm\tmit Zinnen", "  kleines Haus  ", huge};
    for (const std::string& t : texts) {
        CAPTURE(t.size());
        Json body;
        body["auftrag"] = t;
        body["ergebnis"] = "gebaut";
        body["bloecke"] = 1;  // umlaut-ok: Schlüssel ASCII
        std::optional<ResultNote> seen;
        REQUIRE(handle_result(req("POST", dump_compact(body)), [&](const ResultNote& n) { seen = n; }).status == 200);
        REQUIRE(seen.has_value());
        CHECK(seen->order == order_text({{"user", "Bauauftrag: " + t + "\n\nEXISTING SURROUNDINGS: nothing"}}));
    }
}
