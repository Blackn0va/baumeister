// Plan-Vorschau ohne Qt: Umrechnung Plan → Blöcke, Draufsicht, Schnitt, Farben, Auftragstext.

#include "test_support.hpp"

#include "base/fileio.hpp"
#include "base/json.hpp"
#include "gui/plan_view.hpp"

#include <map>
#include <sstream>
#include <string>
#include <tuple>

using namespace swbh::gui;

namespace {

std::string plan_with(const std::string& steps) {
    return R"({"design":"d","reply":"Klar!","mode":"new","steps":[)" + steps + "]}";
}

}  // namespace

TEST_CASE("Plan-Vorschau: Auftragstext ohne Vorspann und ohne Umgebungsbeschreibung") {
    CHECK(order_title("Bauauftrag: ein Turm aus Stein  EXISTING SURROUNDINGS: nothing") == "ein Turm aus Stein");
    CHECK(order_title("  Bauauftrag:   kleines Haus  ") == "kleines Haus");
    CHECK(order_title("freie Frage ohne Vorspann") == "freie Frage ohne Vorspann");
}

TEST_CASE("Plan-Vorschau: Blockname ohne Namensraum und Zustände") {
    CHECK(block_base_name("minecraft:Oak_Stairs[facing=left,half=top]") == "oak_stairs");
    CHECK(block_base_name(" glass_pane ") == "glass_pane");
}

TEST_CASE("Plan-Vorschau: Farben – bekannte Materialien, Farbstoffe, feste Ersatzfarbe") {
    CHECK(block_rgb("oak_planks") == 0xa2834f);
    CHECK(block_rgb("dark_oak_planks") == 0x4a3218);
    CHECK(block_rgb("stone_bricks") == 0x7a7979);
    CHECK(block_rgb("mossy_stone_bricks") == 0x6e7b5a);
    CHECK(block_rgb("bricks") == 0x96503f);
    CHECK(block_rgb("light_blue_stained_glass") == 0x3aafd9);
    CHECK(block_rgb("blue_concrete") == 0x35399d);
    CHECK(block_rgb("red_sandstone") == 0xd8cb9b);  // „red_“ ohne gefärbte Art → Material
    CHECK(block_rgb("lantern") == 0xf6d365);
    CHECK(block_rgb(kModuleMarker) == 0xa78bfa);
    // Unbekannt: gleiche Farbe bei jedem Aufruf, gedeckt (jeder Kanal 0x50 … 0xaf).
    const std::uint32_t a = block_rgb("ladder");
    CHECK(a == block_rgb("minecraft:ladder[facing=front]"));
    for (int shift : {0, 8, 16}) {
        const std::uint32_t c = (a >> shift) & 0xff;
        CHECK(c >= 0x50);
        CHECK(c <= 0xaf);
    }
}

TEST_CASE("Plan-Vorschau: unlesbare Antwort und fehlende steps sind kein Absturz") {
    CHECK_FALSE(rasterize_plan("").ok);
    CHECK_FALSE(rasterize_plan("{kaputt").ok);
    const PlanBlocks no_steps = rasterize_plan(R"({"design":"x"})");
    CHECK_FALSE(no_steps.ok);
    CHECK(no_steps.error == "kein Bauplan (steps fehlt)");
}

TEST_CASE("Plan-Vorschau: fill, walls und hollow wie PlanCompiler.box") {
    const PlanBlocks fill = rasterize_plan(plan_with(R"({"op":"fill","block":"stone","from":[0,0,0],"to":[2,2,2]})"));
    REQUIRE(fill.ok);
    CHECK(fill.cells.size() == 27);
    const PlanBlocks walls = rasterize_plan(plan_with(R"({"op":"walls","block":"stone","from":[0,0,0],"to":[2,2,2]})"));
    CHECK(walls.cells.size() == 24);  // je Schicht 8 Randblöcke, kein Boden, keine Decke
    CHECK_FALSE(walls.at(1, 0, 1).has_value());
    const PlanBlocks hollow =
        rasterize_plan(plan_with(R"({"op":"hollow","block":"stone","from":[0,0,0],"to":[2,2,2]})"));
    CHECK(hollow.cells.size() == 26);  // nur die Mitte fehlt
    CHECK_FALSE(hollow.at(1, 1, 1).has_value());
    CHECK(hollow.at(1, 0, 1) == std::optional<std::string_view>("stone"));
}

TEST_CASE("Plan-Vorschau: Luft schneidet aus, spätere Schritte überschreiben frühere") {
    const PlanBlocks p = rasterize_plan(plan_with(
        R"({"op":"fill","block":"stone","from":[0,0,0],"to":[4,0,0]},)"
        R"({"op":"fill","block":"air","from":[1,0,0],"to":[2,0,0]},)"
        R"({"op":"set","block":"oak_planks","pos":[4,0,0]})"));
    REQUIRE(p.ok);
    CHECK(p.cells.size() == 3);
    CHECK_FALSE(p.at(1, 0, 0).has_value());
    CHECK(p.at(4, 0, 0) == std::optional<std::string_view>("oak_planks"));
    CHECK(p.steps == 3);
}

TEST_CASE("Plan-Vorschau: Zylinder und Kugel mit denselben Scheiben wie das Plugin") {
    // Radius 4: inDisc (Abstand < 4,5) ergibt 69 Felder; hohl bleiben die Randfelder (24). Nachgerechnet mit demselben
    // Kriterium in Python (2026-10-29), unabhängig von dieser Umsetzung.
    const PlanBlocks solid = rasterize_plan(
        plan_with(R"({"op":"cylinder","block":"stone","pos":[0,0,0],"radius":4,"height":1})"));
    CHECK(solid.cells.size() == 69);
    const PlanBlocks tube = rasterize_plan(
        plan_with(R"({"op":"cylinder","block":"stone","pos":[0,0,0],"radius":4,"height":3,"hollow":true})"));
    CHECK(tube.cells.size() == 3 * 24);
    CHECK_FALSE(tube.at(0, 1, 0).has_value());
    CHECK(tube.at(4, 1, 0).has_value());
    const PlanBlocks dome = rasterize_plan(
        plan_with(R"({"op":"sphere","block":"glass","pos":[0,10,0],"radius":3,"dome":true})"));
    REQUIRE_FALSE(dome.empty());
    CHECK(dome.min_y == 10);
    CHECK(dome.max_y == 13);
}

TEST_CASE("Plan-Vorschau: Satteldach mit Überstand, First entlang der längeren Seite") {
    // Wände x0–6, z0–4 → mit Überstand 1 x-1–7, z-1–5; First entlang x (8 ≥ 6), Schichten von z-1/z5 nach innen.
    const PlanBlocks p = rasterize_plan(
        plan_with(R"({"op":"roof","block":"oak_stairs","from":[0,5,0],"to":[6,5,4],"shape":"gable",)"
                  R"("gable_block":"oak_planks"})"));
    REQUIRE(p.ok);
    CHECK(p.min_x == -1);
    CHECK(p.max_x == 7);
    CHECK(p.min_z == -1);
    CHECK(p.max_z == 5);
    CHECK(p.max_y == 8);                                                    // 4 Schichten (z -1…5 → Mitte z 2)
    // First als Platte wie PlanCompiler.slabFor – bis T-017 stand hier oak_stairs, das Plugin baut dort aber oak_slab.
    CHECK(p.at(3, 8, 2) == std::optional<std::string_view>("oak_slab"));
    CHECK(p.at(3, 7, 1) == std::optional<std::string_view>("oak_stairs"));  // Schicht darunter bleibt Treppe
    CHECK(p.at(0, 6, 2) == std::optional<std::string_view>("oak_planks"));  // Giebelfüllung in der Wandebene
}

TEST_CASE("Plan-Vorschau: Haus mit Decken, Tür vorn in der Mitte und Dach") {
    const PlanBlocks p = rasterize_plan(
        plan_with(R"({"op":"house","block":"spruce_planks","from":[0,0,0],"to":[6,0,6],"floors":2})"));
    REQUIRE(p.ok);
    CHECK(p.at(3, 1, 0) == std::optional<std::string_view>("spruce_door"));
    CHECK(p.at(3, 2, 0) == std::optional<std::string_view>("spruce_door"));
    CHECK(p.at(0, 1, 0) == std::optional<std::string_view>("spruce_log"));    // Eckstamm
    CHECK(p.at(3, 4, 3) == std::optional<std::string_view>("spruce_planks"));  // Zwischendecke
    CHECK_FALSE(p.at(3, 2, 3).has_value());                                    // Innenraum
    CHECK(p.max_y > 8);                                                        // Dach über der obersten Decke (y 8)
}

TEST_CASE("Plan-Vorschau: Hochhaus mit Geschossen, Brüstung und Eingang") {
    const PlanBlocks p = rasterize_plan(plan_with(
        R"({"op":"skyscraper","block":"white_concrete","from":[0,0,0],"to":[15,0,15],"floors":30,)"
        R"("window_block":"glass"})"));
    REQUIRE(p.ok);
    CHECK(p.max_y == 121);  // 30 × 4 + Brüstung
    CHECK_FALSE(p.at(7, 1, 0).has_value());  // Eingang
    CHECK(p.at(0, 2, 0) == std::optional<std::string_view>("white_concrete"));  // Eckpfeiler
    CHECK(p.at(1, 2, 0) == std::optional<std::string_view>("glass"));           // Fensterband
}

TEST_CASE("Plan-Vorschau: Modul und Logik als Markierung mit Hinweis") {
    const PlanBlocks p = rasterize_plan(plan_with(
        R"({"op":"module","name":"takt","pos":[2,0,3],"facing":"front"},)"
        R"({"op":"logic","expr":"Q = A und B","pos":[8,0,0],"facing":"front"})"));
    REQUIRE(p.ok);
    CHECK(p.at(2, 0, 3) == std::optional<std::string_view>(kModuleMarker));
    CHECK(p.at(8, 0, 0) == std::optional<std::string_view>(kLogicMarker));
    REQUIRE(p.notes.size() == 2);
    CHECK(p.notes[0].find("Modul „takt“") != std::string::npos);
    CHECK(p.notes[1].find("Q = A und B") != std::string::npos);
}

TEST_CASE("Plan-Vorschau: zu große Schritte werden ausgelassen statt Sekunden zu rechnen") {
    // Grammatik-Grenzen -64 … 200 je Achse: 265³ = 18,6 Mio. Blöcke.
    const PlanBlocks p = rasterize_plan(plan_with(
        R"({"op":"fill","block":"stone","from":[-64,-64,-64],"to":[200,200,200]},)"
        R"({"op":"set","block":"stone","pos":[0,0,0]})"));
    REQUIRE(p.ok);
    CHECK(p.cells.size() == 1);
    REQUIRE(p.notes.size() == 1);
    CHECK(p.notes[0].find("zu groß für die Vorschau") != std::string::npos);
}

TEST_CASE("Plan-Vorschau: viele überlappende Großschritte brechen nach dem Gesamtbudget ab") {
    // Prüfbefund 2026-10-29: 70 × fill 84³ ergeben nur 593.000 verschiedene Blöcke, kosteten aber 6,9 s im GUI-Thread.
    // 84³ = 592.704 liegt unter kMaxStepVolume, drei davon unter kMaxPreviewWork, der vierte nicht mehr.
    std::string steps;
    for (int i = 0; i < 10; ++i) {
        steps += std::string(i == 0 ? "" : ",") + R"({"op":"fill","block":"stone","from":[0,0,0],"to":[83,83,83]})";
    }
    const PlanBlocks p = rasterize_plan(plan_with(steps));
    REQUIRE(p.ok);
    CHECK(p.cells.size() == 592'704);
    REQUIRE(p.notes.size() == 1);
    CHECK(p.notes[0] == "Vorschau bei 2000000 Blockoperationen abgebrochen (Schritt 4 von 10).");
}

TEST_CASE("Plan-Vorschau: fehlende Felder und unbekannte op als Hinweis") {
    const PlanBlocks p = rasterize_plan(plan_with(
        R"({"op":"fill","from":[0,0,0],"to":[1,1,1]},{"op":"zauber","block":"stone"},)"
        R"({"op":"set","block":"stone","pos":[1,2]})"));
    REQUIRE(p.ok);
    CHECK(p.empty());
    CHECK(p.notes.size() == 3);
}

TEST_CASE("Plan-Vorschau: Draufsicht nimmt je Spalte den obersten Block, Schnitt nur die Ebene") {
    const PlanBlocks p = rasterize_plan(plan_with(
        R"({"op":"fill","block":"stone","from":[0,0,0],"to":[2,0,1]},)"
        R"({"op":"set","block":"glass","pos":[1,3,1]},)"
        R"({"op":"set","block":"oak_planks","pos":[0,1,0]})"));
    REQUIRE(p.ok);
    const PlanView top = top_view(p);
    CHECK(top.width == 3);
    CHECK(top.height == 2);
    CHECK(top.origin_u == 0);
    CHECK(top.origin_v == 0);
    CHECK(p.palette[static_cast<std::size_t>(top.at(1, 1).block)] == "glass");
    CHECK(top.at(1, 1).level == 3);
    CHECK(p.palette[static_cast<std::size_t>(top.at(0, 0).block)] == "oak_planks");
    CHECK(p.palette[static_cast<std::size_t>(top.at(2, 0).block)] == "stone");

    const PlanView cut = section_view(p, 1);  // z = 1: Steinreihe y0 und Glas y3
    CHECK(cut.width == 3);
    CHECK(cut.height == 4);
    CHECK(p.palette[static_cast<std::size_t>(cut.at(1, 3).block)] == "glass");
    CHECK(cut.at(0, 1).block == -1);  // Bretter liegen bei z = 0
    CHECK(top_view(rasterize_plan(plan_with(""))).cells.empty());
}

namespace {

using CellMap = std::map<std::tuple<int, int, int>, std::string>;

// Erwartete Zellen aus tests/plan/data/<name>.cells.txt (Ausgabe des PlanCompilers, erzeugt von vorschau_erzeugen.py).
CellMap expected_cells(const std::string& name) {
    std::istringstream in(swbh::read_file(swbh::test::source_path("tests/plan/data/" + name + ".cells.txt")));
    CellMap out;
    std::string line;
    while (std::getline(in, line)) {
        if (line.empty() || line.front() == '#') {
            continue;
        }
        std::istringstream row(line);
        int x = 0;
        int y = 0;
        int z = 0;
        std::string block;
        row >> x >> y >> z >> block;
        out[{x, y, z}] = block;
    }
    return out;
}

PlanBlocks preview_of(const std::string& name) {
    const swbh::Json record =
        swbh::parse_utf8(swbh::read_file(swbh::test::source_path("tests/plan/data/" + name + ".json")));
    return rasterize_plan(swbh::json_find(record, "antwort")->get<std::string>());
}

// Zählt Abweichungen und nennt die ersten, damit ein roter Lauf sagt, WO die Vorschau vom Bau abweicht.
int count_differences(const PlanBlocks& p, const CellMap& want) {
    int diffs = 0;
    for (const auto& [pos, block] : want) {
        const auto [x, y, z] = pos;
        const auto got = p.at(x, y, z);
        if (!got || *got != block) {
            if (++diffs <= 5) {
                MESSAGE(x << " " << y << " " << z << ": Plugin " << block << ", Vorschau "
                          << (got ? std::string(*got) : std::string("nichts")));
            }
        }
    }
    std::size_t extra = 0;
    for (int x = p.min_x; x <= p.max_x; ++x) {
        for (int y = p.min_y; y <= p.max_y; ++y) {
            for (int z = p.min_z; z <= p.max_z; ++z) {
                if (p.at(x, y, z) && !want.contains({x, y, z})) {
                    if (++extra <= 5) {
                        MESSAGE(x << " " << y << " " << z << ": nur in der Vorschau " << std::string(*p.at(x, y, z)));
                    }
                }
            }
        }
    }
    return diffs + static_cast<int>(extra);
}

}  // namespace

// AK-19 (T-017): Die Erwartung kommt aus dem echten PlanCompiler, nicht von Hand. Gemessen an 50 echten Plänen von
// dev01 waren vorher 16 von 41 vergleichbaren Plänen zellgenau, nachher 35 von 46 (Rest: Module, Burg, Ersatzblöcke
// des Server-Katalogs – bewusst, siehe plan_view.hpp).
TEST_CASE("Plan-Vorschau: ein nachgebauter Hausplan zeigt die Zellen, die das Plugin baut") {
    const PlanBlocks p = preview_of("vorschau-haus");
    REQUIRE(p.ok);
    const CellMap want = expected_cells("vorschau-haus");
    REQUIRE(want.size() > 800);
    CHECK(count_differences(p, want) == 0);
    CHECK(p.cells.size() == want.size());
}

TEST_CASE("Plan-Vorschau: Hochhaus, Dachplatten, hohles fill und Aliasnamen wie im Plugin") {
    const PlanBlocks p = preview_of("vorschau-hochhaus");
    REQUIRE(p.ok);
    const CellMap want = expected_cells("vorschau-hochhaus");
    REQUIRE(want.size() > 800);
    CHECK(count_differences(p, want) == 0);
    CHECK(p.cells.size() == want.size());
}

TEST_CASE("Plan-Vorschau: abgeschnittene Antwort zeigt die vollständigen Schritte wie das Plugin") {
    const PlanBlocks p = preview_of("vorschau-abgeschnitten");
    REQUIRE(p.ok);
    CHECK(p.steps == 2);
    const CellMap want = expected_cells("vorschau-abgeschnitten");
    CHECK(count_differences(p, want) == 0);
    CHECK(p.cells.size() == want.size());
    REQUIRE_FALSE(p.notes.empty());
    CHECK(p.notes.front().find("abgeschnitten") != std::string::npos);
}

TEST_CASE("Plan-Vorschau: Kappen abgeschnittener Pläne – nur am Ende eines ganzen Schritts") {
    // Abbruch direkt nach einem Komma in der Schrittliste: alles Vollständige bleibt.
    CHECK(cut_truncated_plan(R"({"steps":[{"op":"set","pos":[1,2,3]},)") ==
          std::optional<std::string>(R"({"steps":[{"op":"set","pos":[1,2,3]}]})"));
    // Abbruch in einer Koordinatenliste: Der halbe Schritt fällt ganz weg.
    CHECK(cut_truncated_plan(R"({"steps":[{"op":"set","pos":[1,2,3]},{"op":"set","pos":[4,)") ==
          std::optional<std::string>(R"({"steps":[{"op":"set","pos":[1,2,3]}]})"));
    // Abbruch in einem Text mit Klammer darin: Zeichen in Anführungszeichen zählen nicht.
    CHECK(cut_truncated_plan(R"({"steps":[{"op":"set","block":"a[b]","pos":[0,0,0]},{"op":"x}]")") ==
          std::optional<std::string>(R"({"steps":[{"op":"set","block":"a[b]","pos":[0,0,0]}]})"));
    CHECK_FALSE(cut_truncated_plan(R"({"steps":[]})").has_value());  // vollständig
    CHECK_FALSE(cut_truncated_plan("kein json").has_value());
}
