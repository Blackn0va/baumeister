// Recherche-Endpunkt /baumeister/v1/recherche: Schalter (GET) und Meldung (POST) wie ReferenceLookup.java im Plugin.

#include "test_support.hpp"

#include "server/research_api.hpp"

#include <optional>
#include <string>

using namespace swbh;
using namespace swbh::server;

namespace {

HttpRequest req(std::string method, std::string body = {}) {
    HttpRequest r;
    r.method = std::move(method);
    r.target = std::string(kResearchPath);
    r.version = "HTTP/1.1";
    r.body = std::move(body);
    return r;
}

}  // namespace

TEST_CASE("Recherche: der Schalter in den Einstellungen geht als erlaubt ans Plugin") {
    bool allowed = true;
    std::optional<ResearchNote> seen;
    const auto ask = [&] { return allowed; };
    const auto note = [&](const ResearchNote& n) { seen = n; };
    CHECK(handle_research(req("GET"), ask, note).body == R"({"erlaubt":true})");
    allowed = false;
    CHECK(handle_research(req("GET"), ask, note).body == R"({"erlaubt":false})");
    CHECK_FALSE(seen.has_value());
}

TEST_CASE("Recherche: Meldung des Plugins kommt in der App an, wie ReferenceLookup.report sie baut") {
    std::optional<ResearchNote> seen;
    const ApiResponse r = handle_research(
        req("POST", R"({"auftrag":"bau den Kölner Dom","titel":"Kölner Dom","hoehe_m":157.0,"zielhoehe":157,)"
                    R"("massstab":1.0})"),
        [] { return true; }, [&](const ResearchNote& n) { seen = n; });
    CHECK(r.status == 200);
    REQUIRE(seen.has_value());
    CHECK(seen->title == "Kölner Dom");
    CHECK(seen->target_blocks == 157);
    CHECK(research_line_de(*seen) == "Kölner Dom – 157 m, 1:1");
}

TEST_CASE("Recherche: verkleinert wird mit Prozent und Zielhöhe angezeigt") {
    ResearchNote n;
    n.title = "Eiffelturm";
    n.height_m = 330.0;
    n.target_blocks = 184;
    n.scale = 0.5576;
    CHECK(research_line_de(n) == "Eiffelturm – 330 m, verkleinert auf 56 % (184 Blöcke)");
}

TEST_CASE("Recherche: kaputte oder unsinnige Meldungen werden abgelehnt, nicht angezeigt") {
    bool called = false;
    const auto ask = [] { return true; };
    const auto note = [&](const ResearchNote&) { called = true; };
    CHECK(handle_research(req("POST", "{kaputt"), ask, note).status == 400);
    CHECK(handle_research(req("POST", R"({"hoehe_m":1,"zielhoehe":1,"massstab":1})"), ask, note).status == 400);
    CHECK(handle_research(req("POST", R"({"titel":"x","hoehe_m":-5,"zielhoehe":1,"massstab":1})"), ask, note).status ==
          400);
    CHECK(handle_research(req("POST", R"({"titel":"x","hoehe_m":5,"zielhoehe":1,"massstab":7})"), ask, note).status ==
          400);
    CHECK(handle_research(req("DELETE"), ask, note).status == 405);
    CHECK_FALSE(called);
}

TEST_CASE("Recherche: überlange Titel werden UTF-8-sicher gekürzt") {
    std::optional<ResearchNote> seen;
    std::string longTitle;
    for (int i = 0; i < 100; ++i) {
        longTitle += "ä";  // 2 Byte je Zeichen
    }
    handle_research(req("POST", R"({"titel":")" + longTitle + R"(","hoehe_m":1,"zielhoehe":1,"massstab":1})"),
                    [] { return true; }, [&](const ResearchNote& n) { seen = n; });
    REQUIRE(seen.has_value());
    CHECK(seen->title.size() <= 120 + 3);
    CHECK(seen->title.substr(0, 4) == "ää");
}
