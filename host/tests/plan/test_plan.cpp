// Bauplan-Grammatik: Beispiele aus dem System-Prompt des Plugins gehen durch, Fehler nicht, und die Grammatik passt
// zu plan-schema.json (Feldnamen, Aufzählungen, Grenzen) – das Plugin ändert das Schema in einer anderen Sitzung, die
// Gleichheit muss deshalb ein Test halten, nicht das Gedächtnis.

#include "test_support.hpp"

#include "base/json.hpp"
#include "llm/engine.hpp"
#include "plan/bauplan.hpp"

#include <string>
#include <vector>

namespace {

using swbh::Json;

std::string grammar() { return std::string(swbh::plan::bauplan_grammar()); }

// Plugin-Quellen liegen eine Ebene über host/ (minecraft/src/main/resources). Wird host/ allein kopiert (Bau auf dem
// GPU-PC, gemeldet 2026-10-28), fehlen sie – dann gilt die Abschrift in tests/plan/data. Mit Plugin-Ordner prüft
// „Abschrift des Schemas ist aktuell“, dass sie nicht veraltet.
std::filesystem::path plugin_path(const char* name) {
    return swbh::test::source_path(std::string("../src/main/resources/") + name);
}

bool plugin_present() {
    std::error_code ec;
    return std::filesystem::is_regular_file(plugin_path("plan-schema.json"), ec);
}

std::string plugin_resource(const char* name) {
    if (plugin_present()) {
        return swbh::test::read_bytes(plugin_path(name));
    }
    return swbh::test::read_bytes(swbh::test::source_path(std::string("tests/plan/data/") + name));
}

// Die JSON-Beispiele aus system-prompt.txt: jede Stelle ab {"design" bis zur Zeile, die mit ]} endet.
std::vector<std::string> prompt_examples() {
    const std::string text = plugin_resource("system-prompt.txt");
    std::vector<std::string> out;
    std::size_t pos = 0;
    while ((pos = text.find("{\"design\"", pos)) != std::string::npos) {
        const std::size_t end = text.find("]}\n", pos);
        REQUIRE(end != std::string::npos);
        out.push_back(text.substr(pos, end + 2 - pos));
        pos = end;
    }
    return out;
}

bool accepts(const std::string& text) {
    std::string error;
    const bool ok = swbh::llm::grammar_accepts(grammar(), text, error);
    if (!ok) {
        MESSAGE(error);
    }
    return ok;
}

const std::string kMinimal = R"({"design":"x","reply":"Hallo","mode":"new","steps":[{"op":"set","block":"stone","pos":[0,0,0]}]})";

}  // namespace

TEST_CASE("Bauplan: die Grammatik ist für llama.cpp gültig") {
    std::string error;
    CHECK(swbh::llm::grammar_accepts(grammar(), kMinimal, error));
    CHECK(error.empty());
}

TEST_CASE("Bauplan: alle Beispiele aus system-prompt.txt werden angenommen") {
    const auto examples = prompt_examples();
    REQUIRE(examples.size() == 4);
    for (const std::string& e : examples) {
        CHECK(swbh::try_parse_utf8(e).has_value());  // wirklich JSON
        CHECK(accepts(e));
    }
}

TEST_CASE("Bauplan: Umlaute, Escapes und alle Felder in Schema-Reihenfolge") {
    CHECK(accepts(R"({"design":"Türme \"hoch\" ä","reply":"Ein gemütliches Haus!","mode":"new","steps":[)"
                  R"({"op":"module","name":"tor","pos":[-48,0,96],"facing":"up"},)"
                  R"({"op":"sphere","block":"glass","pos":[4,4,4],"radius":24,"hollow":true,"dome":false},)"
                  R"({"op":"roof","block":"oak_stairs","from":[0,4,0],"to":[6,4,6],"shape":"hip","overhang":2,)"
                  R"("gable_block":"oak_planks"},{"op":"cylinder","block":"stone","pos":[1,1,1],"radius":1,"height":48}]})"));
    CHECK(accepts(R"({"design":"x","reply":"y","mode":"new","steps":[)"
                  R"({"op":"skyscraper","block":"stone","from":[-64,0,0],"to":[15,0,15],"floors":30,)"
                  R"("window_block":"glass"},)"
                  R"({"op":"house","block":"spruce_planks","from":[0,0,0],"to":[8,0,10],"floors":2,)"
                  R"("window_block":"glass_pane","roof_block":"spruce_stairs"},)"
                  R"({"op":"line","block":"iron_bars","from":[8,121,8],"to":[8,200,8]}]})"));
}

// Blöcke mit Daten (Plan B5, 2026-11-08): block ist id[zustände]{snbt} wie bei /setblock. Vorher passte block nur
// 80 Zeichen – ein Befehlsblock mit Befehl ging nicht. Anführungszeichen im SNBT schreibt das Modell als \" (JSON).
TEST_CASE("Bauplan: block mit Daten bis 1000 Zeichen, window_block bleibt bei 80") {
    CHECK(accepts(R"({"design":"x","reply":"y","mode":"new","steps":[)"
                  R"({"op":"set","block":"command_block[facing=up]{Command:\"say Hallo\",auto:0b}","pos":[0,1,0]},)"
                  R"({"op":"set","block":"oak_sign[rotation=0]{front_text:{messages:[\"Zeile 1\",\"\",\"\",\"\"]}}",)"
                  R"("pos":[1,1,0]},)"
                  R"({"op":"set","block":"chest[facing=front]{Items:[{Slot:0b,id:\"minecraft:bread\",count:5}]}",)"
                  R"("pos":[2,1,0]}]})"));
    std::string error;
    const auto rejects = [&](const std::string& t) {
        error.clear();
        return !swbh::llm::grammar_accepts(grammar(), t, error) && !error.empty();
    };
    const auto field = [](const std::string& name, std::size_t n) {
        return R"({"design":"x","reply":"y","mode":"new","steps":[{"op":"house",")" + name + R"(":")"
               + std::string(n, 'a') + R"("}]})";
    };
    CHECK(accepts(field("block", 1000)));
    CHECK(rejects(field("block", 1001)));
    CHECK(accepts(field("window_block", 80)));
    CHECK(rejects(field("window_block", 81)));
    // Ein nacktes Anführungszeichen beendet den Text – ohne \" kein SNBT mit Texten.
    CHECK(rejects(R"({"design":"x","reply":"y","mode":"new","steps":[{"op":"set","block":"x{Command:"say"}"}]})"));
}

// Bauformen (2026-10-29): Geometrie im Plugin (Structures), das Modell liefert nur Maße, Material, width und style.
TEST_CASE("Bauplan: Bauformen castle, wall, bridge, garden und tree mit width und style") {
    CHECK(accepts(R"({"design":"Burg mit Brücke","reply":"Eine Burg!","mode":"new","steps":[)"
                  R"({"op":"castle","block":"stone_bricks","from":[0,0,0],"to":[24,0,24],"height":6,)"
                  R"("facing":"front","width":3,"style":"round"},)"
                  R"({"op":"wall","block":"cobblestone","from":[0,0,30],"to":[20,0,30],"height":5,"facing":"back",)"
                  R"("width":2},)"
                  R"({"op":"bridge","block":"stone_bricks","from":[0,0,40],"to":[0,0,61],"width":9,"style":"arch"},)"
                  R"({"op":"garden","block":"oak_fence","from":[30,0,0],"to":[40,0,8],"facing":"left"},)"
                  R"({"op":"tree","block":"spruce_log","pos":[50,0,5],"height":16,"style":"cone"}]})"));
    std::string error;
    const auto rejects = [&](const std::string& t) {
        error.clear();
        return !swbh::llm::grammar_accepts(grammar(), t, error) && !error.empty();
    };
    CHECK(rejects(R"({"design":"x","reply":"y","mode":"new","steps":[{"op":"bridge","width":10}]})"));     // > 9
    CHECK(rejects(R"({"design":"x","reply":"y","mode":"new","steps":[{"op":"wall","width":0}]})"));        // < 1
    CHECK(rejects(R"({"design":"x","reply":"y","mode":"new","steps":[{"op":"tree","style":"gothic"}]})")); // Wert
    CHECK(rejects(R"({"design":"x","reply":"y","mode":"new","steps":[{"op":"castle","style":"round",)"
                  R"("facing":"front"}]})"));                                                               // Folge
    CHECK(rejects(R"({"design":"x","reply":"y","mode":"new","steps":[{"op":"tower"}]})"));                 // keine op
}

TEST_CASE("Bauplan: falsche op, fremde Felder und Grenzen werden abgelehnt") {
    std::string error;
    const auto rejects = [&](const std::string& t) {
        error.clear();
        return !swbh::llm::grammar_accepts(grammar(), t, error) && !error.empty();
    };
    CHECK(rejects(R"({"design":"x","reply":"y","mode":"new","steps":[{"op":"explode","pos":[0,0,0]}]})"));
    CHECK(rejects(R"({"design":"x","mode":"new","steps":[{"op":"set","pos":[0,0,0]}]})"));      // reply fehlt
    CHECK(rejects(R"({"design":"x","reply":"y","mode":"new","steps":[]})"));                                  // minItems 1
    CHECK(rejects(R"({"design":"x","reply":"y","mode":"new","steps":[{"op":"set","color":"red"}]})"));       // fremdes Feld
    CHECK(rejects(R"({"design":"x","reply":"y","mode":"new","steps":[{"op":"set","pos":[0,201,0]}]})"));     // Koordinate > 200
    CHECK(rejects(R"({"design":"x","reply":"y","mode":"new","steps":[{"op":"set","pos":[0,-65,0]}]})"));     // < -64
    CHECK(rejects(R"({"design":"x","reply":"y","mode":"new","steps":[{"op":"skyscraper","floors":46}]})"));  // floors > 45
    CHECK(rejects(R"({"design":"x","reply":"y","mode":"new","steps":[{"op":"house","roof_block":"a","floors":2}]})"));  // Folge
    CHECK(rejects(R"({"design":"x","reply":"y","mode":"new","steps":[{"op":"set","pos":[0,0]}]})"));         // nur zwei Werte
    CHECK(rejects(R"({"design":"x","reply":"y","mode":"new","steps":[{"op":"cylinder","radius":25}]})"));    // radius > 24
    CHECK(rejects(R"({"design":"x","reply":"y","mode":"new","steps":[{"op":"door","facing":"north"}]})"));   // Richtung
    CHECK(rejects(R"({"design":"x","reply":"y","mode":"new","steps":[{"op":"set"}]}  trailing)"));
    CHECK(rejects(R"({"design":"x","reply":"y","mode":"new","steps":[{"op":"set"})"));                       // unvollständig
    // mode ist Pflicht (2026-10-28): Ohne das Feld wüsste das Plugin nicht, ob der Plan den vorigen Bau ersetzt.
    CHECK(rejects(R"({"design":"x","reply":"y","steps":[{"op":"set"}]})"));                      // mode fehlt
    CHECK(rejects(R"({"design":"x","reply":"y","mode":"edit","steps":[{"op":"set"}]})"));        // unbekannt
    CHECK(accepts(R"({"design":"x","reply":"y","mode":"update","steps":[{"op":"set"}]})"));
    CHECK(accepts(R"({"design":"x","reply":"y","mode":"new","steps":[{"op":"logic","expr":"A und nicht B",)"
                  R"("pos":[0,0,2],"facing":"front"}]})"));
    // 61 Schritte (maxItems 60)
    std::string many = R"({"design":"x","reply":"y","mode":"new","steps":[)";
    for (int i = 0; i < 61; ++i) {
        many += std::string(i > 0 ? "," : "") + R"({"op":"set"})";
    }
    CHECK(rejects(many + "]}"));
    // reply mit 201 Zeichen (maxLength 200)
    CHECK(rejects(R"({"design":"x","reply":")" + std::string(201, 'a') + R"(","mode":"new","steps":[{"op":"set"}]})"));
    CHECK(accepts(R"({"design":"x","reply":")" + std::string(200, 'a') + R"(","mode":"new","steps":[{"op":"set"}]})"));
    // Formel bis 480 Zeichen (Logik in Stockwerken, 2026-11-07; vorher 160)
    const auto logic = [](std::size_t n) {
        return R"({"design":"x","reply":"y","mode":"new","steps":[{"op":"logic","expr":")" + std::string(n, 'A')
               + R"("}]})";
    };
    CHECK(accepts(logic(480)));
    CHECK(rejects(logic(481)));
}

TEST_CASE("Bauplan: Grammatik und plan-schema.json laufen nicht auseinander") {
    const Json schema = swbh::parse_utf8(plugin_resource("plan-schema.json"));
    const std::string g = grammar();
    const auto quoted = [](const std::string& s) { return "\"\\\"" + s + "\\\"\""; };  // "\"name\"" in GBNF

    CHECK(schema["required"] == Json::array({"design", "reply", "mode", "steps"}));
    CHECK(schema["additionalProperties"] == false);
    CHECK(schema["properties"]["mode"]["enum"] == Json::array({"new", "update"}));
    CHECK(g.find("mode ::= \"\\\"new\\\"\" | \"\\\"update\\\"\"") != std::string::npos);
    CHECK(schema["properties"]["design"]["maxLength"] == 700);
    CHECK(g.find("char{0,700}") != std::string::npos);
    CHECK(schema["properties"]["reply"]["maxLength"] == 200);
    CHECK(g.find("char{0,200}") != std::string::npos);
    const Json& steps = schema["properties"]["steps"];
    CHECK(steps["minItems"] == 1);
    CHECK(steps["maxItems"] == 60);
    CHECK(g.find("){0,59}") != std::string::npos);

    const Json& item = steps["items"];
    CHECK(item["required"] == Json::array({"op"}));
    CHECK(item["additionalProperties"] == false);
    // Jedes Feld des Schemas steht in der Grammatik, in derselben Reihenfolge.
    std::size_t last = 0;
    for (const auto& [name, prop] : item["properties"].items()) {
        const std::size_t at = g.find(quoted(name));
        CHECK_MESSAGE(at != std::string::npos, "Feld fehlt in bauplan.gbnf: " << name);
        CHECK_MESSAGE(at >= last, "Feld außer der Reihe: " << name);
        last = at;
        if (prop.contains("enum")) {
            for (const Json& v : prop["enum"]) {
                CHECK_MESSAGE(g.find(quoted(v.get<std::string>())) != std::string::npos,
                              "Wert fehlt in bauplan.gbnf: " << name << "=" << v.get<std::string>());
            }
        }
    }
    // Aufzählungen nicht größer als im Schema: Jede op der Grammatik muss im Schema stehen.
    const std::size_t op_rule = g.find("\nop ::=");
    REQUIRE(op_rule != std::string::npos);
    const std::string op_line = g.substr(op_rule, g.find('\n', op_rule + 1) - op_rule);
    std::size_t count = 0;
    for (std::size_t p = op_line.find("\\\""); p != std::string::npos; p = op_line.find("\\\"", p + 2)) {
        ++count;
    }
    CHECK(count / 2 == item["properties"]["op"]["enum"].size());
    // Grenzen der Zahlen
    CHECK(item["properties"]["radius"]["maximum"] == 24);
    CHECK(item["properties"]["height"]["maximum"] == 48);
    CHECK(item["properties"]["overhang"]["maximum"] == 2);
    // block bis 1000 Zeichen (Daten wie bei /setblock, 2026-11-08), window_block und roof_block bleiben bei 80.
    CHECK(item["properties"]["block"]["maxLength"] == 1000);
    CHECK(g.find("char{0,1000}") != std::string::npos);
    CHECK(item["properties"]["name"]["maxLength"] == 32);
    CHECK(item["properties"]["expr"]["maxLength"] == 480);
    CHECK(g.find("char{0,480}") != std::string::npos);
    CHECK(item["properties"]["floors"]["maximum"] == 45);
    CHECK(item["properties"]["window_block"]["maxLength"] == 80);
    CHECK(item["properties"]["roof_block"]["maxLength"] == 80);
    CHECK(item["properties"]["width"]["minimum"] == 1);
    CHECK(item["properties"]["width"]["maximum"] == 9);
    CHECK(item["properties"]["style"]["enum"] == Json::array({"arch", "beam", "round", "cone", "square"}));
    CHECK(g.find("width ::= [1-9]") != std::string::npos);
    CHECK(schema["$defs"]["vec"]["items"]["minimum"] == -64);
    CHECK(schema["$defs"]["vec"]["items"]["maximum"] == 200);
    CHECK(schema["$defs"]["vec"]["minItems"] == 3);
}

TEST_CASE("Bauplan: Abschrift des Schemas in tests/plan/data ist aktuell") {
    if (!plugin_present()) {
        MESSAGE("Plugin-Ordner fehlt (host/ allein kopiert) – geprüft wird gegen die Abschrift in tests/plan/data");
        return;
    }
    // Inhaltlich vergleichen (nicht Bytes): Zeilenenden dürfen nach dem Kopieren abweichen.
    const Json live = swbh::parse_utf8(swbh::test::read_bytes(plugin_path("plan-schema.json")));
    const Json copy = swbh::parse_utf8(swbh::test::read_bytes(swbh::test::source_path("tests/plan/data/plan-schema.json")));
    CHECK_MESSAGE(live == copy, "src/main/resources/plan-schema.json geändert – bauplan.gbnf prüfen und die Abschrift "
                                "nach host/tests/plan/data kopieren");
}

TEST_CASE("Bauplan: response_format wählt die Grammatik, Fremdes wird abgelehnt") {
    std::string code;
    auto choice = swbh::plan::choose_grammar(Json::parse(R"({"messages":[]})"), code);
    REQUIRE(choice);
    CHECK(choice->grammar.empty());
    choice = swbh::plan::choose_grammar(
        Json::parse(R"({"response_format":{"type":"json_schema","json_schema":{"name":"bauplan","schema":{}}}})"), code);
    REQUIRE(choice);
    CHECK(choice->grammar == grammar());
    CHECK(choice->label == "bauplan");
    choice = swbh::plan::choose_grammar(Json::parse(R"({"response_format":{"type":"text"}})"), code);
    REQUIRE(choice);
    CHECK(choice->grammar.empty());
    CHECK_FALSE(swbh::plan::choose_grammar(
        Json::parse(R"({"response_format":{"type":"json_schema","json_schema":{"name":"anderes"}}})"), code));
    CHECK(code == "response_format_abgelehnt");
    CHECK_FALSE(swbh::plan::choose_grammar(Json::parse(R"({"response_format":{"type":"json_object"}})"), code));
}
